package dtm.ide.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class PtyLauncherTest {

    @Test
    void conPtyPreservesSixteenAnd256ColorAnsiSequences() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        List<String> command = List.of(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                AnsiEmitter.class.getName());

        PtyLauncher.Result result = PtyLauncher.launch(command, null, Map.of());
        String output;
        try (var input = result.process().getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(result.process().waitFor(10, TimeUnit.SECONDS));
        assertTrue(result.pty(), "o teste deve exercitar o backend PTY");
        assertTrue(output.contains("\u001b[32mgreen\u001b[0m"));
        assertTrue(output.contains("\u001b[38;5;196mred256\u001b[0m"));
        assertFalse(output.contains("\u2190["), "ESC nao pode ser convertido em seta visivel");
        assertTrue(output.contains("TERM=xterm-256color"));
        assertTrue(output.contains("COLORTERM=truecolor"));
    }

    public static final class AnsiEmitter {

        private AnsiEmitter() {
        }

        public static void main(String[] args) {
            System.out.print("\u001b[32mgreen\u001b[0m ");
            System.out.println("\u001b[38;5;196mred256\u001b[0m");
            System.out.println("TERM=" + System.getenv("TERM"));
            System.out.println("COLORTERM=" + System.getenv("COLORTERM"));
            System.out.flush();
        }
    }
}
