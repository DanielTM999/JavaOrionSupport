package dtm.ide.swingdesigner.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class HostJar {

    public static final String MAIN_CLASS = "dtm.ide.swingdesigner.host.DesignerHost";
    static final String PACKAGE_PATH = "dtm/ide/swingdesigner/host/";

    private HostJar() {
    }

    public static Path ensure(Path cacheDirectory) throws IOException {
        Path codeSource = codeSource();
        Map<String, byte[]> classes = hostClasses(codeSource);
        if (classes.isEmpty()) {
            throw new IOException("Classes do host do Swing Designer nao encontradas em " + codeSource);
        }
        Path target = cacheDirectory.resolve("swing-designer-host-" + hash(classes) + ".jar");
        if (Files.isRegularFile(target)) {
            return target;
        }
        Files.createDirectories(cacheDirectory);
        Path temp = Files.createTempFile(cacheDirectory, "host", ".tmp");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, MAIN_CLASS);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(temp), manifest)) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    static Map<String, byte[]> hostClasses(Path codeSource) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        if (Files.isDirectory(codeSource)) {
            Path packageDir = codeSource.resolve(PACKAGE_PATH);
            if (!Files.isDirectory(packageDir)) {
                return classes;
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(packageDir)) {
                files = new ArrayList<>(walk.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".class"))
                        .sorted(Comparator.comparing(Path::toString)).toList());
            }
            for (Path file : files) {
                classes.put(codeSource.relativize(file).toString().replace('\\', '/'),
                        Files.readAllBytes(file));
            }
            return classes;
        }
        try (ZipFile zip = new ZipFile(codeSource.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> entry.getName().startsWith(PACKAGE_PATH)
                            && entry.getName().endsWith(".class"))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : entries) {
                try (InputStream in = zip.getInputStream(entry)) {
                    classes.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return classes;
    }

    private static Path codeSource() throws IOException {
        try {
            return Path.of(HostJar.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            throw new IOException("Local do plugin desconhecido", e);
        }
    }

    private static String hash(Map<String, byte[]> classes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                digest.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(entry.getValue());
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(classes.hashCode());
        }
    }
}
