package dtm.ide.swingdesigner.catalog;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public final class ClasspathIndex implements AutoCloseable {

    public static final String COMPONENT = "java.awt.Component";
    public static final String WINDOW = "java.awt.Window";
    public static final String OBJECT = "java.lang.Object";

    private static final Optional<ClassHeader> MISSING = Optional.empty();

    private final List<ClasspathEntry> entries;
    private final Map<String, ClassHeader> headers;
    private final Map<String, Optional<ClassHeader>> jdkHeaders = new ConcurrentHashMap<>();
    private final Map<String, Boolean> drawable = new ConcurrentHashMap<>();
    private final Map<Integer, ZipFile> openJars = new HashMap<>();

    private ClasspathIndex(List<ClasspathEntry> entries, Map<String, ClassHeader> headers) {
        this.entries = List.copyOf(entries);
        this.headers = headers;
    }

    public static ClasspathIndex build(List<ClasspathEntry> entries, JarHeaderCache cache) {
        JarHeaderCache headerCache = cache == null ? JarHeaderCache.memoryOnly() : cache;
        Map<String, ClassHeader> headers = new LinkedHashMap<>();
        List<ClasspathEntry> usable = new ArrayList<>();
        for (ClasspathEntry entry : entries) {
            if (entry == null || !Files.exists(entry.path())) {
                continue;
            }
            int index = usable.size();
            usable.add(entry);
            for (ClassHeader header : scanEntry(entry, headerCache)) {
                headers.putIfAbsent(header.name(), header.withEntry(index));
            }
        }
        return new ClasspathIndex(usable, headers);
    }

    public List<ClasspathEntry> entries() {
        return entries;
    }

    public Optional<ClasspathEntry> entryOf(ClassHeader header) {
        if (header == null || header.isJdk()) {
            return Optional.empty();
        }
        return Optional.of(entries.get(header.entryIndex()));
    }

    public Collection<ClassHeader> indexedClasses() {
        return Collections.unmodifiableCollection(headers.values());
    }

    public Optional<ClassHeader> header(String className) {
        if (className == null) {
            return MISSING;
        }
        ClassHeader header = headers.get(className);
        if (header != null) {
            return Optional.of(header);
        }
        return jdkHeaders.computeIfAbsent(className, this::readJdkHeader);
    }

    public boolean contains(String className) {
        return header(className).isPresent();
    }

    public boolean isDrawable(String className) {
        if (className == null) {
            return false;
        }
        Boolean known = drawable.get(className);
        if (known != null) {
            return known;
        }
        boolean result = resolveDrawable(className);
        drawable.put(className, result);
        return result;
    }

    public boolean isSubtypeOf(String className, String ancestor) {
        return superChain(className).contains(ancestor);
    }

    public List<String> superChain(String className) {
        List<String> chain = new ArrayList<>();
        String current = className;
        int guard = 0;
        while (current != null && guard++ < 256) {
            if (chain.contains(current)) {
                break;
            }
            chain.add(current);
            Optional<ClassHeader> header = header(current);
            if (header.isEmpty()) {
                break;
            }
            current = header.get().superName();
        }
        return chain;
    }

    public boolean isChainResolved(String className) {
        List<String> chain = superChain(className);
        return !chain.isEmpty() && chain.getLast().equals(OBJECT);
    }

    public Optional<byte[]> bytes(String className) {
        ClassHeader header = headers.get(className);
        String resource = ClassHeaders.internalName(className) + ".class";
        if (header == null) {
            return readSystemResource(resource);
        }
        ClasspathEntry entry = entries.get(header.entryIndex());
        try {
            if (entry.isJar()) {
                synchronized (openJars) {
                    ZipFile zip = openJars.get(header.entryIndex());
                    if (zip == null) {
                        zip = new ZipFile(entry.path().toFile());
                        openJars.put(header.entryIndex(), zip);
                    }
                    ZipEntry zipEntry = zip.getEntry(resource);
                    if (zipEntry == null) {
                        return Optional.empty();
                    }
                    try (InputStream in = zip.getInputStream(zipEntry)) {
                        return Optional.of(in.readAllBytes());
                    }
                }
            }
            Path file = entry.path().resolve(resource);
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            log.debug("Could not read {} from {}: {}", className, entry.path(), e.toString());
            return Optional.empty();
        }
    }

    public List<Resource> resources(String name) {
        List<Resource> found = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ClasspathEntry entry = entries.get(i);
            try {
                if (entry.isJar()) {
                    try (ZipFile zip = new ZipFile(entry.path().toFile())) {
                        ZipEntry zipEntry = zip.getEntry(name);
                        if (zipEntry != null) {
                            try (InputStream in = zip.getInputStream(zipEntry)) {
                                found.add(new Resource(entry, in.readAllBytes()));
                            }
                        }
                    }
                } else {
                    Path file = entry.path().resolve(name);
                    if (Files.isRegularFile(file)) {
                        found.add(new Resource(entry, Files.readAllBytes(file)));
                    }
                }
            } catch (IOException e) {
                log.debug("Could not read {} from {}: {}", name, entry.path(), e.toString());
            }
        }
        return found;
    }

    @Override
    public void close() {
        synchronized (openJars) {
            for (ZipFile zip : openJars.values()) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
            openJars.clear();
        }
    }

    private boolean resolveDrawable(String className) {
        List<String> chain = superChain(className);
        return chain.contains(COMPONENT);
    }

    private Optional<ClassHeader> readJdkHeader(String className) {
        return readSystemResource(ClassHeaders.internalName(className) + ".class")
                .flatMap(bytes -> ClassHeaders.parse(bytes, ClassHeader.JDK_ENTRY));
    }

    private static Optional<byte[]> readSystemResource(String resource) {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(resource)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static List<ClassHeader> scanEntry(ClasspathEntry entry, JarHeaderCache cache) {
        if (entry.isJar()) {
            Optional<List<ClassHeader>> cached = cache.load(entry.path());
            if (cached.isPresent()) {
                return cached.get();
            }
            List<ClassHeader> scanned = scanJar(entry.path());
            cache.store(entry.path(), scanned);
            return scanned;
        }
        return scanDirectory(entry.path());
    }

    private static List<ClassHeader> scanJar(Path jar) {
        List<ClassHeader> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry zipEntry = enumeration.nextElement();
                if (!isClassEntry(zipEntry.getName()) || zipEntry.isDirectory()) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(zipEntry)) {
                    ClassHeaders.parse(in.readAllBytes(), 0).ifPresent(result::add);
                }
            }
        } catch (IOException e) {
            log.debug("Could not scan jar {}: {}", jar, e.toString());
        }
        return result;
    }

    private static List<ClassHeader> scanDirectory(Path directory) {
        List<ClassHeader> result = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return result;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> isClassEntry(directory.relativize(file).toString()
                            .replace('\\', '/')))
                    .forEach(file -> {
                        try {
                            ClassHeaders.parse(Files.readAllBytes(file), 0).ifPresent(result::add);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            log.debug("Could not scan directory {}: {}", directory, e.toString());
        }
        return result;
    }

    private static boolean isClassEntry(String name) {
        return name.endsWith(".class")
                && !name.startsWith("META-INF/")
                && !name.endsWith("module-info.class")
                && !name.endsWith("package-info.class");
    }

    public record Resource(ClasspathEntry entry, byte[] content) {
    }
}
