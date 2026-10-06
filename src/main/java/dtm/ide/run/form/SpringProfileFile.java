package dtm.ide.run.form;

import dtm.ide.spring.config.SpringConfigDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class SpringProfileFile {

    private static final String KEY = "spring.profiles.active";
    private static final List<String> NAMES = List.of(
            "application.properties", "application.yml", "application.yaml");

    private SpringProfileFile() {
    }

    static List<String> existing(Path moduleRoot) {
        if (moduleRoot == null) {
            return List.of();
        }
        Path resources = resources(moduleRoot);
        return NAMES.stream().filter(name -> Files.isRegularFile(resources.resolve(name))).toList();
    }

    static Path path(Path moduleRoot, String name) {
        if (moduleRoot == null || !NAMES.contains(name)) {
            throw new IllegalArgumentException("Selecione um arquivo Spring valido.");
        }
        return resources(moduleRoot).resolve(name);
    }

    static String read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        String content = Files.readString(file);
        boolean properties = isProperties(file);
        String found = "";
        for (SpringConfigDocument.ConfigKey key : keys(file, content)) {
            if (KEY.equals(key.key())) {
                found = unquote(properties ? key.value() : withoutYamlComment(key.value()));
            }
        }
        return found;
    }

    static void write(Path file, String expected, String desired) throws IOException {
        String current = read(file);
        if (!current.equals(expected == null ? "" : expected)) {
            throw new IOException("O perfil em " + file.getFileName()
                    + " mudou fora da configuracao. Reabra a tela antes de aplicar.");
        }
        String value = desired == null ? "" : desired.trim();
        if (current.equals(value) || !Files.exists(file) && value.isEmpty()) {
            return;
        }
        String content = Files.exists(file) ? Files.readString(file) : "";
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(content.split("\r?\n", -1)));
        List<SpringConfigDocument.ConfigKey> definitions = keys(file, content).stream()
                .filter(key -> KEY.equals(key.key())).toList();
        if (!definitions.isEmpty()) {
            for (int i = definitions.size() - 1; i >= 0; i--) {
                int line = definitions.get(i).line();
                if (value.isEmpty() || i < definitions.size() - 1) {
                    lines.remove(line);
                } else {
                    String original = lines.get(line);
                    int separator = original.indexOf(isProperties(file) ? '=' : ':');
                    if (separator < 0) {
                        separator = original.indexOf(':');
                    }
                    String tail = original.substring(separator + 1);
                    String spacing = tail.substring(0, tail.length() - tail.stripLeading().length());
                    if (!isProperties(file) && spacing.isEmpty()) {
                        spacing = " ";
                    }
                    String comment = isProperties(file) ? "" : yamlComment(tail);
                    lines.set(line, original.substring(0, separator + 1) + spacing + value + comment);
                }
            }
        } else if (!value.isEmpty()) {
            if (lines.size() == 1 && lines.getFirst().isEmpty()) {
                lines.clear();
            } else if (lines.getLast().isEmpty()) {
                lines.removeLast();
            }
            lines.add(KEY + (isProperties(file) ? "=" : ": ") + value);
        }
        String updated = String.join(newline, lines);
        if (!updated.isEmpty() && !updated.endsWith(newline)) {
            updated += newline;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, updated);
    }

    private static Path resources(Path moduleRoot) {
        return moduleRoot.resolve("src/main/resources");
    }

    private static List<SpringConfigDocument.ConfigKey> keys(Path file, String content) {
        return SpringConfigDocument.keys(content,
                SpringConfigDocument.Format.of(file.getFileName().toString()));
    }

    private static boolean isProperties(Path file) {
        return file.getFileName().toString().endsWith(".properties");
    }

    private static String withoutYamlComment(String value) {
        int marker = value.indexOf(" #");
        return marker < 0 ? value : value.substring(0, marker);
    }

    private static String yamlComment(String value) {
        int marker = value.indexOf(" #");
        return marker < 0 ? "" : value.substring(marker);
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() > 1 && (trimmed.startsWith("\"") && trimmed.endsWith("\"")
                || trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
