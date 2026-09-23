package dtm.ide.build.incremental;

import dtm.ide.index.JavaLexicalSource;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public final class ModuleBuildState {

    public static final String FORMAT_VERSION = "2";

    private static final String HEADER = "#orion-incremental";
    private static final String FIELD_SEPARATOR = "\t";
    private static final String VALUE_SEPARATOR = ",";

    public record Changes(List<Path> added, List<Path> modified, List<Path> deleted) {

        public boolean isEmpty() {
            return added.isEmpty() && modified.isEmpty() && deleted.isEmpty();
        }

        public List<Path> touched() {
            List<Path> all = new ArrayList<>(added);
            all.addAll(modified);
            return all;
        }
    }

    private record Entry(long modified, long size, String hash, List<String> types,
                         List<String> references) {
    }

    private final Path stateFile;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private String localFingerprint = "";
    private String classpath = "";
    private String classpathFingerprint = "";

    private String fingerprint = "";
    private boolean loaded;

    private ModuleBuildState(Path stateFile) {
        this.stateFile = stateFile;
    }

    public static ModuleBuildState load(Path stateFile) {
        ModuleBuildState state = new ModuleBuildState(stateFile);
        state.read();
        return state;
    }

    public boolean isLocallyUsable(String expectedLocalFingerprint) {
        return matchesLocally(expectedLocalFingerprint) && !entries.isEmpty();
    }

    public boolean matchesLocally(String expectedLocalFingerprint) {
        return loaded && !localFingerprint.isEmpty()
                && localFingerprint.equals(expectedLocalFingerprint);
    }

    public String classpath() {
        return classpath;
    }

    public String classpathFingerprint() {
        return classpathFingerprint;
    }

    public void reset(String newFingerprint, String newLocalFingerprint,
                      String newClasspath, String newClasspathFingerprint) {
        reset(newFingerprint);
        localFingerprint = newLocalFingerprint == null ? "" : newLocalFingerprint;
        classpath = newClasspath == null ? "" : newClasspath;
        classpathFingerprint = newClasspathFingerprint == null ? "" : newClasspathFingerprint;
    }

    public boolean isUsable(String expectedFingerprint) {
        return loaded && fingerprint.equals(expectedFingerprint) && !entries.isEmpty();
    }

    public void reset(String newFingerprint) {
        entries.clear();
        fingerprint = newFingerprint == null ? "" : newFingerprint;
        loaded = true;
    }

    public Changes changes(Path moduleRoot, List<Path> sources) {
        List<Path> added = new ArrayList<>();
        List<Path> modified = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path source : sources) {
            String key = key(moduleRoot, source);
            seen.add(key);
            Entry entry = entries.get(key);
            if (entry == null) {
                added.add(source);
            } else if (isStale(entry, source)) {
                modified.add(source);
            }
        }
        List<Path> deleted = new ArrayList<>();
        for (String key : entries.keySet()) {
            if (!seen.contains(key)) {
                deleted.add(moduleRoot.resolve(key));
            }
        }
        return new Changes(List.copyOf(added), List.copyOf(modified), List.copyOf(deleted));
    }

    private static boolean isStale(Entry entry, Path source) {
        try {
            long modified = Files.getLastModifiedTime(source).toMillis();
            long size = Files.size(source);
            if (entry.modified() == modified && entry.size() == size) {
                return false;
            }
            return !entry.hash().equals(hashOf(Files.readAllBytes(source)));
        } catch (Exception e) {
            return true;
        }
    }

    public Set<Path> dependentsOf(Path moduleRoot, Set<String> types) {
        if (types.isEmpty()) {
            return Set.of();
        }
        Set<Path> dependents = new LinkedHashSet<>();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            for (String reference : entry.getValue().references()) {
                if (types.contains(reference)) {
                    dependents.add(moduleRoot.resolve(entry.getKey()));
                    break;
                }
            }
        }
        return dependents;
    }

    public Set<String> typesOf(Path moduleRoot, Path source) {
        Entry entry = entries.get(key(moduleRoot, source));
        return entry == null ? Set.of() : new LinkedHashSet<>(entry.types());
    }

    public int size() {
        return entries.size();
    }

    public void record(Path moduleRoot, Path source) {
        try {
            byte[] bytes = Files.readAllBytes(source);
            String content = new String(bytes, StandardCharsets.UTF_8);
            entries.put(key(moduleRoot, source), new Entry(
                    Files.getLastModifiedTime(source).toMillis(),
                    bytes.length,
                    hashOf(bytes),
                    typesDeclaredIn(content),
                    typeReferencesIn(content)));
        } catch (Exception e) {
            log.debug("Nao foi possivel registrar {}: {}", source, e.getMessage());
        }
    }

    public void remove(Path moduleRoot, Path source) {
        entries.remove(key(moduleRoot, source));
    }

    public void save() {
        try {
            Files.createDirectories(stateFile.getParent());
            StringBuilder content = new StringBuilder();
            content.append(HEADER).append(' ').append(FORMAT_VERSION).append(System.lineSeparator());
            content.append(localFingerprint).append(System.lineSeparator());
            content.append(classpathFingerprint).append(System.lineSeparator());
            content.append(Base64.getEncoder().encodeToString(classpath.getBytes(StandardCharsets.UTF_8))).append(System.lineSeparator());
            content.append(fingerprint).append(System.lineSeparator());
            for (Map.Entry<String, Entry> entry : entries.entrySet()) {
                Entry value = entry.getValue();
                content.append(entry.getKey()).append(FIELD_SEPARATOR)
                        .append(value.modified()).append(FIELD_SEPARATOR)
                        .append(value.size()).append(FIELD_SEPARATOR)
                        .append(value.hash()).append(FIELD_SEPARATOR)
                        .append(String.join(VALUE_SEPARATOR, value.types())).append(FIELD_SEPARATOR)
                        .append(String.join(VALUE_SEPARATOR, value.references()))
                        .append(System.lineSeparator());
            }
            Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(temporary, content.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.debug("Nao foi possivel gravar {}: {}", stateFile, e.getMessage());
        }
    }

    public static void discard(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            log.debug("Nao foi possivel limpar {}: {}", directory, e.getMessage());
        }
    }

    private void read() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(stateFile, StandardCharsets.UTF_8);
            if (lines.size() < 5 || !lines.get(0).equals(HEADER + " " + FORMAT_VERSION)) {
                return;
            }
            localFingerprint = lines.get(1);
            classpathFingerprint = lines.get(2);
            classpath = new String(Base64.getDecoder().decode(lines.get(3)), StandardCharsets.UTF_8);
            fingerprint = lines.get(4);
            for (String line : lines.subList(5, lines.size())) {
                String[] fields = line.split(FIELD_SEPARATOR, -1);
                if (fields.length < 6) {
                    continue;
                }
                entries.put(fields[0], new Entry(Long.parseLong(fields[1]),
                        Long.parseLong(fields[2]), fields[3],
                        splitValues(fields[4]), splitValues(fields[5])));
            }
            loaded = true;
        } catch (Exception e) {
            log.debug("Estado incremental invalido em {}: {}", stateFile, e.getMessage());
            entries.clear();
            loaded = false;
        }
    }

    private static List<String> splitValues(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(VALUE_SEPARATOR));
    }

    private static String key(Path moduleRoot, Path source) {
        try {
            return moduleRoot.relativize(source).toString().replace('\\', '/');
        } catch (Exception e) {
            return source.toString().replace('\\', '/');
        }
    }

    static List<String> typesDeclaredIn(String content) {
        Set<String> names = new LinkedHashSet<>();
        for (JavaLexicalSource.Declared declared : JavaLexicalSource.declarations(content)) {
            if (JavaLexicalSource.isType(declared.kind())) {
                names.add(declared.name());
            }
        }
        return List.copyOf(names);
    }

    static List<String> topLevelTypesDeclaredIn(String content) {
        Set<String> names = new LinkedHashSet<>();
        for (JavaLexicalSource.Declared declared : JavaLexicalSource.declarations(content)) {
            if (declared.depth() == 0 && JavaLexicalSource.isType(declared.kind())) {
                names.add(declared.name());
            }
        }
        return List.copyOf(names);
    }

    static List<String> typeReferencesIn(String content) {
        Set<String> names = new LinkedHashSet<>();
        JavaLexicalSource.identifiers(JavaLexicalSource.mask(content), name -> {
            if (Character.isUpperCase(name.charAt(0))) {
                names.add(name);
            }
        });
        return List.copyOf(names);
    }

    static String hashOf(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest(bytes)) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.hashCode(bytes));
        }
    }

    public static String fingerprintOf(String... parts) {
        return hashOf(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
    }
}
