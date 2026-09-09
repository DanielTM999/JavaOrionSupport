package dtm.ide.spring.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public record SpringConfigIndex(List<Entry> entries) {

    public record Entry(String key, String value, Path file, int line, String profile) {

        public Entry {
            key = key == null ? "" : key.trim();
            value = value == null ? "" : value;
            profile = profile == null ? "" : profile;
            line = Math.max(1, line);
        }

        public boolean isProfileSpecific() {
            return !profile.isBlank();
        }
    }

    public SpringConfigIndex {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static SpringConfigIndex empty() {
        return new SpringConfigIndex(List.of());
    }

    public static SpringConfigIndex scan(List<Path> resourceRoots) {
        if (resourceRoots == null || resourceRoots.isEmpty()) {
            return empty();
        }
        List<Entry> entries = new ArrayList<>();
        for (Path root : resourceRoots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.list(root)) {
                files.filter(Files::isRegularFile)
                        .filter(SpringConfigSupport::isConfigFile)
                        .sorted()
                        .forEach(file -> entries.addAll(entriesOf(file)));
            } catch (Exception e) {
                return new SpringConfigIndex(entries);
            }
        }
        return new SpringConfigIndex(entries);
    }

    static List<Entry> entriesOf(Path file) {
        String name = file.getFileName().toString();
        SpringConfigDocument.Format format = SpringConfigDocument.Format.of(name);
        String content;
        try {
            content = Files.readString(file);
        } catch (Exception e) {
            return List.of();
        }
        String profile = profileOf(name);
        List<Entry> entries = new ArrayList<>();
        for (SpringConfigDocument.ConfigKey key : SpringConfigDocument.keys(content, format)) {
            entries.add(new Entry(key.key(), key.value(), file, key.line() + 1, profile));
        }
        return entries;
    }

    static String profileOf(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        int dash = name.indexOf('-');
        return dash < 0 ? "" : name.substring(dash + 1);
    }

    public List<Entry> definitionsOf(String key) {
        if (key == null || key.isBlank()) {
            return List.of();
        }
        String canonical = SpringConfigMetadata.canonical(key);
        return entries.stream()
                .filter(entry -> SpringConfigMetadata.canonical(entry.key()).equals(canonical))
                .toList();
    }

    public List<Entry> startingWith(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        String canonical = SpringConfigMetadata.canonical(prefix);
        return entries.stream()
                .filter(entry -> SpringConfigMetadata.canonical(entry.key()).startsWith(canonical))
                .toList();
    }

    public boolean knows(String key) {
        return !definitionsOf(key).isEmpty();
    }

    public List<String> profiles() {
        LinkedHashSet<String> profiles = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry.isProfileSpecific()) {
                profiles.add(entry.profile());
            }
        }
        for (Entry entry : definitionsOf("spring.profiles.active")) {
            for (String declared : entry.value().split(",")) {
                String trimmed = declared.trim();
                if (!trimmed.isBlank()) {
                    profiles.add(trimmed);
                }
            }
        }
        return List.copyOf(profiles);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
