package dtm.ide.spring.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public final class SpringConfigMetadata {

    private static final String METADATA_ENTRY = "META-INF/spring-configuration-metadata.json";
    private static final String ADDITIONAL_METADATA_ENTRY =
            "META-INF/additional-spring-configuration-metadata.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, SpringConfigProperty> properties;
    private final boolean fromClasspath;

    private SpringConfigMetadata(Map<String, SpringConfigProperty> properties, boolean fromClasspath) {
        this.properties = properties;
        this.fromClasspath = fromClasspath;
    }

    public static SpringConfigMetadata builtIn() {
        return new SpringConfigMetadata(new LinkedHashMap<>(BuiltInProperties.INDEX), false);
    }

    public static SpringConfigMetadata fromClasspath(List<Path> classpathEntries) {
        Map<String, SpringConfigProperty> index = new LinkedHashMap<>(BuiltInProperties.INDEX);
        if (classpathEntries == null) {
            return new SpringConfigMetadata(index, false);
        }
        int scanned = 0;
        for (Path entry : classpathEntries) {
            if (entry == null) {
                continue;
            }
            if (Files.isDirectory(entry)) {
                scanned += readDirectory(entry, index);
            } else if (Files.isRegularFile(entry)
                    && entry.getFileName().toString().endsWith(".jar")) {
                scanned += readJar(entry, index);
            }
        }
        log.debug("Catalogo de configuracao do Spring: {} chave(s) de {} descritor(es)",
                index.size(), scanned);
        return new SpringConfigMetadata(index, scanned > 0);
    }

    public boolean fromClasspath() {
        return fromClasspath;
    }

    private static int readDirectory(Path directory, Map<String, SpringConfigProperty> index) {
        int scanned = 0;
        for (String name : List.of(METADATA_ENTRY, ADDITIONAL_METADATA_ENTRY)) {
            Path descriptor = directory.resolve(name);
            if (!Files.isRegularFile(descriptor)) {
                continue;
            }
            try {
                readMetadata(MAPPER.readTree(descriptor.toFile()), index);
                scanned++;
            } catch (Exception e) {
                log.debug("Metadados de configuracao ilegiveis em {}: {}", descriptor, e.getMessage());
            }
        }
        return scanned;
    }

    private static int readJar(Path jar, Map<String, SpringConfigProperty> index) {
        int scanned = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!METADATA_ENTRY.equals(name) && !ADDITIONAL_METADATA_ENTRY.equals(name)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    readMetadata(MAPPER.readTree(in), index);
                    scanned++;
                }
            }
        } catch (Exception e) {
            log.debug("Metadados de configuracao ilegiveis em {}: {}", jar, e.getMessage());
        }
        return scanned;
    }

    static void readMetadata(JsonNode root, Map<String, SpringConfigProperty> index) {
        if (root == null) {
            return;
        }
        Map<String, List<String>> hints = readHints(root.path("hints"));
        JsonNode properties = root.path("properties");
        if (!properties.isArray()) {
            return;
        }
        for (JsonNode property : properties) {
            String name = property.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            JsonNode deprecation = property.path("deprecation");
            index.put(name, new SpringConfigProperty(
                    name,
                    property.path("type").asText(""),
                    property.path("description").asText(""),
                    property.path("defaultValue").asText(""),
                    property.path("sourceType").asText(""),
                    !deprecation.isMissingNode() || property.path("deprecated").asBoolean(false),
                    deprecation.path("replacement").asText(""),
                    hints.getOrDefault(name, List.of())));
        }
    }

    private static Map<String, List<String>> readHints(JsonNode hints) {
        if (!hints.isArray()) {
            return Map.of();
        }
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (JsonNode hint : hints) {
            String name = hint.path("name").asText("");
            JsonNode values = hint.path("values");
            if (name.isBlank() || !values.isArray()) {
                continue;
            }
            List<String> collected = new ArrayList<>();
            for (JsonNode value : values) {
                String text = value.path("value").asText("");
                if (!text.isBlank()) {
                    collected.add(text);
                }
            }
            if (!collected.isEmpty()) {
                byName.put(name, collected);
            }
        }
        return byName;
    }

    public int size() {
        return properties.size();
    }

    public Optional<SpringConfigProperty> find(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public boolean contains(String key) {
        return properties.containsKey(key);
    }

    public List<SpringConfigProperty> startingWith(String prefix) {
        String needle = canonical(prefix);
        return properties.values().stream()
                .filter(property -> needle.isEmpty() || canonical(property.name()).startsWith(needle))
                .sorted(Comparator.comparing(SpringConfigProperty::name))
                .toList();
    }

    public boolean isKnown(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String canonical = canonical(key);
        if (properties.containsKey(key)) {
            return true;
        }
        return properties.values().stream().anyMatch(property -> {
            String candidate = canonical(property.name());
            if (candidate.equals(canonical)) {
                return true;
            }
            return isMapType(property) && canonical.startsWith(candidate + ".");
        });
    }

    private static boolean isMapType(SpringConfigProperty property) {
        String type = property.type().toLowerCase(Locale.ROOT);
        return type.startsWith("java.util.map") || type.contains("properties");
    }

    static String canonical(String key) {
        if (key == null) {
            return "";
        }
        return key.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
