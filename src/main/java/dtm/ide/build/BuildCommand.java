package dtm.ide.build;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BuildCommand(
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment
) {

    public BuildCommand {
        command = command == null ? List.of() : List.copyOf(command);
        environment = environment == null ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(environment));
    }

    public String display() {
        return String.join(" ", command);
    }

    public static String joinArguments(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        List<String> quoted = new ArrayList<>();
        for (String argument : arguments) {
            if (argument == null || argument.isEmpty()) {
                continue;
            }
            boolean needsQuotes = argument.chars().anyMatch(Character::isWhitespace);
            if (!needsQuotes) {
                quoted.add(argument);
            } else {
                char quote = argument.indexOf('"') >= 0 ? '\'' : '"';
                quoted.add(quote + argument + quote);
            }
        }
        return String.join(" ", quoted);
    }

    public static List<String> parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    arguments.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            arguments.add(current.toString());
        }
        return List.copyOf(arguments);
    }

    public record Options(
            List<String> profiles,
            List<String> extraArguments,
            boolean offline,
            Map<String, String> environment
    ) {

        public Options {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            extraArguments = extraArguments == null ? List.of() : List.copyOf(extraArguments);
            environment = environment == null ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(environment));
        }

        public static Options none() {
            return new Options(List.of(), List.of(), false, Map.of());
        }

        public Options withExtraArguments(List<String> arguments) {
            return new Options(profiles, arguments, offline, environment);
        }
    }
}
