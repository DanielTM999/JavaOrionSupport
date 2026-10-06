package dtm.ide.swingdesigner.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public final class JarHeaderCache {

    private static final int FORMAT = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Key, List<ClassHeader>> MEMORY = new ConcurrentHashMap<>();

    private final Path directory;

    public JarHeaderCache(Path directory) {
        this.directory = directory;
    }

    public static JarHeaderCache memoryOnly() {
        return new JarHeaderCache(null);
    }

    Optional<List<ClassHeader>> load(Path jar) {
        Optional<Key> key = Key.of(jar);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        List<ClassHeader> cached = MEMORY.get(key.get());
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<List<ClassHeader>> stored = readDisk(key.get());
        stored.ifPresent(headers -> MEMORY.put(key.get(), headers));
        return stored;
    }

    void store(Path jar, List<ClassHeader> headers) {
        Optional<Key> key = Key.of(jar);
        if (key.isEmpty()) {
            return;
        }
        List<ClassHeader> copy = List.copyOf(headers);
        MEMORY.put(key.get(), copy);
        writeDisk(key.get(), copy);
    }

    static void clearMemory() {
        MEMORY.clear();
    }

    private Optional<List<ClassHeader>> readDisk(Key key) {
        if (directory == null) {
            return Optional.empty();
        }
        Path file = directory.resolve(key.fileName());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(file.toFile());
            if (root.path("format").asInt() != FORMAT
                    || !root.path("path").asText().equals(key.path())
                    || root.path("size").asLong() != key.size()
                    || root.path("modified").asLong() != key.modified()) {
                return Optional.empty();
            }
            List<ClassHeader> headers = new ArrayList<>();
            for (JsonNode row : root.path("classes")) {
                List<String> interfaces = new ArrayList<>();
                for (JsonNode type : row.path(4)) {
                    interfaces.add(type.asText());
                }
                String superName = row.path(1).isNull() ? null : row.path(1).asText();
                headers.add(new ClassHeader(row.path(0).asText(), superName, interfaces,
                        row.path(2).asInt(), row.path(3).asBoolean(), 0));
            }
            return Optional.of(List.copyOf(headers));
        } catch (IOException | RuntimeException e) {
            log.debug("Ignoring unreadable class header cache {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    private void writeDisk(Key key, List<ClassHeader> headers) {
        if (directory == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            ObjectNode root = JSON.createObjectNode();
            root.put("format", FORMAT);
            root.put("path", key.path());
            root.put("size", key.size());
            root.put("modified", key.modified());
            ArrayNode classes = root.putArray("classes");
            for (ClassHeader header : headers) {
                ArrayNode row = classes.addArray();
                row.add(header.name());
                if (header.superName() == null) {
                    row.addNull();
                } else {
                    row.add(header.superName());
                }
                row.add(header.access());
                row.add(header.accessible());
                ArrayNode interfaces = row.addArray();
                header.interfaces().forEach(interfaces::add);
            }
            Path target = directory.resolve(key.fileName());
            Path temp = Files.createTempFile(directory, "headers", ".tmp");
            JSON.writeValue(temp.toFile(), root);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not persist class header cache for {}: {}", key.path(), e.toString());
        }
    }

    private record Key(String path, long size, long modified) {

        static Optional<Key> of(Path jar) {
            try {
                Path absolute = jar.toAbsolutePath().normalize();
                return Optional.of(new Key(absolute.toString(), Files.size(absolute),
                        Files.getLastModifiedTime(absolute).toMillis()));
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
        }

        String fileName() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                byte[] hash = digest.digest(path.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash) + ".json";
            } catch (NoSuchAlgorithmException e) {
                return Integer.toHexString(path.hashCode()) + ".json";
            }
        }
    }
}
