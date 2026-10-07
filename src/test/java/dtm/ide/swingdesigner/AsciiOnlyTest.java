package dtm.ide.swingdesigner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AsciiOnlyTest {

    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u(?!0001)[0-9a-fA-F]{4}");

    @Test
    void designerSourcesAndResourcesUseOnlyAscii() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String folder : List.of("src/main/java/dtm/ide/swingdesigner", "src/host/java",
                "src/main/resources/swingdesigner")) {
            Path root = Path.of(folder);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    String[] lines = text.split("\\R", -1);
                    for (int i = 0; i < lines.length; i++) {
                        boolean nonAscii = lines[i].chars().anyMatch(ch -> ch > 127);
                        if (nonAscii || UNICODE_ESCAPE.matcher(lines[i]).find()) {
                            offenders.add(file + ":" + (i + 1));
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), () -> String.join("\n", offenders));
    }
}
