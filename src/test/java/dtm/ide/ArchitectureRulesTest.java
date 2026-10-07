package dtm.ide;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchitectureRulesTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final String SERVER_PACKAGE = "dtm/ide/lsp/";
    private static final Pattern SERVER_SPECIFIC = Pattern.compile(
            "\\b(JdtLs\\w*|JavaClassFileNavigation|ImportCandidates)\\b|\"jdt:|\"vscode\\.java|\"java/[A-Za-z]+\"|\"java\\.project\\.");

    private static final Set<String> STILL_COUPLED_TO_JDT_LS = Set.of(
            "dtm/ide/debug/JavaDebugSession.java",
            "dtm/ide/ui/JavaDebugPanel.java");

    @Test
    void onlyTheLanguageServerPackageKnowsTheConcreteServer() throws IOException {
        assertEquals(new TreeSet<>(STILL_COUPLED_TO_JDT_LS), coupledOutsideServerPackage(),
                "codigo fora de " + SERVER_PACKAGE + " deve falar com o servidor de linguagem pelo contrato;"
                        + " ao desacoplar uma classe, remova-a de STILL_COUPLED_TO_JDT_LS");
    }

    private static Set<String> coupledOutsideServerPackage() throws IOException {
        Set<String> coupled = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = SOURCES.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith(SERVER_PACKAGE)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.stream().anyMatch(line -> SERVER_SPECIFIC.matcher(line).find())) {
                    coupled.add(relative);
                }
            }
        }
        return coupled;
    }
}
