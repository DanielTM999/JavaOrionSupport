package dtm.ide.build;

import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildDiagnosticParserTest {

    private static final Path ROOT = Path.of("/projeto").toAbsolutePath();

    private BuildDiagnosticParser parser;

    @BeforeEach
    void setUp() {
        parser = new BuildDiagnosticParser(ROOT);
    }

    @Test
    void readsMavenCompilerErrors() {
        BuildDiagnostic diagnostic = parser.accept(
                "[ERROR] /projeto/src/main/java/App.java:[12,34] cannot find symbol");

        assertNotNull(diagnostic);
        assertEquals(12, diagnostic.line());
        assertEquals(34, diagnostic.column());
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals("cannot find symbol", diagnostic.message());
        assertEquals("maven", diagnostic.source());
        assertTrue(diagnostic.file().toString().endsWith("App.java"));
    }

    @Test
    void joinsCannotFindSymbolDetailsIntoTheLocatedProblem() {
        parser.accept("[ERROR] /projeto/src/main/java/App.java:[12,9] cannot find symbol");
        parser.accept("[ERROR]   symbol:   class TesteDelete");
        parser.accept("[ERROR]   location: class App");

        assertEquals(1, parser.diagnostics().size());
        assertEquals("cannot find symbol\nsymbol: class TesteDelete\nlocation: class App",
                parser.diagnostics().getFirst().message());
    }

    @Test
    void joinsPlainJavacSymbolDetailsIntoTheLocatedProblem() {
        parser.accept("/projeto/src/main/java/App.java:12: error: cannot find symbol");
        parser.accept("  symbol:   class TesteDelete");

        assertEquals(1, parser.diagnostics().size());
        assertTrue(parser.diagnostics().getFirst().message().contains("class TesteDelete"));
    }

    @Test
    void readsMavenWarnings() {
        BuildDiagnostic diagnostic = parser.accept(
                "[WARNING] /projeto/src/main/java/App.java:[3,1] deprecated API");

        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity());
        assertFalse(diagnostic.isError());
    }

    @Test
    void readsMavenErrorsWithoutAFile() {
        BuildDiagnostic diagnostic = parser.accept(
                "[ERROR] Failed to execute goal on project demo: Could not resolve dependencies");

        assertNotNull(diagnostic);
        assertNull(diagnostic.file());
        assertFalse(diagnostic.hasLocation());
        assertTrue(diagnostic.message().startsWith("Failed to execute goal"));
    }

    @Test
    void ignoresMavenTrailerNoise() {
        assertNull(parser.accept("[ERROR] -> [Help 1]"));
        assertNull(parser.accept("[ERROR] Re-run Maven using the -X switch to enable full debug logging."));
        assertNull(parser.accept("[ERROR] To see the full stack trace of the errors, re-run with -e"));
        assertNull(parser.accept("[ERROR] "));
        assertTrue(parser.diagnostics().isEmpty());
    }

    @Test
    void ignoresInfoLinesWithoutLocation() {
        assertNull(parser.accept("[INFO] BUILD FAILURE"));
        assertNull(parser.accept("[INFO] Compiling 12 source files"));
        assertTrue(parser.diagnostics().isEmpty());
    }

    @Test
    void readsPlainJavacErrors() {
        BuildDiagnostic diagnostic = parser.accept(
                "/projeto/src/App.java:7: error: ';' expected");

        assertNotNull(diagnostic);
        assertEquals(7, diagnostic.line());
        assertEquals(0, diagnostic.column());
        assertEquals("javac", diagnostic.source());
        assertEquals("';' expected", diagnostic.message());
    }

    @Test
    void readsJavacErrorsWithColumn() {
        BuildDiagnostic diagnostic = parser.accept(
                "/projeto/src/App.java:7:15: error: incompatible types");

        assertEquals(7, diagnostic.line());
        assertEquals(15, diagnostic.column());
    }

    @Test
    void readsJavacNotesAsInfo() {
        BuildDiagnostic diagnostic = parser.accept(
                "/projeto/src/App.java:1: note: recompile with -Xlint");

        assertEquals(DiagnosticSeverity.INFO, diagnostic.severity());
    }

    @Test
    void stripsGradleAnsiColorsBeforeMatching() {
        String colored = (char) 27 + "[31m/projeto/src/App.java:9: error: cannot find symbol"
                + (char) 27 + "[0m";

        BuildDiagnostic diagnostic = parser.accept(colored);

        assertNotNull(diagnostic);
        assertEquals(9, diagnostic.line());
        assertEquals("cannot find symbol", diagnostic.message());
    }

    @Test
    void resolvesRelativePathsAgainstTheProjectRoot() {
        BuildDiagnostic diagnostic = parser.accept("src/App.java:4: error: falhou");

        assertEquals(ROOT.resolve("src/App.java").normalize(), diagnostic.file());
    }

    @Test
    void reportsTheSameErrorOnlyOnce() {
        String line = "[ERROR] /projeto/src/App.java:[12,34] cannot find symbol";

        assertNotNull(parser.accept(line));
        assertNull(parser.accept(line), "a repeticao no resumo do build nao deve duplicar");
        assertEquals(1, parser.diagnostics().size());
    }

    @Test
    void distinguishesDifferentErrorsInTheSameFile() {
        parser.accept("[ERROR] /projeto/src/App.java:[12,34] cannot find symbol");
        parser.accept("[ERROR] /projeto/src/App.java:[20,4] incompatible types");

        assertEquals(2, parser.diagnostics().size());
    }

    @Test
    void separatesErrorsFromWarnings() {
        parser.accept("[ERROR] /projeto/src/App.java:[1,1] erro");
        parser.accept("[WARNING] /projeto/src/App.java:[2,1] aviso");

        assertEquals(2, parser.diagnostics().size());
        assertEquals(1, parser.errors().size());
        assertTrue(parser.hasErrors());
    }

    @Test
    void resetClearsEverything() {
        parser.accept("[ERROR] /projeto/src/App.java:[1,1] erro");
        parser.reset();

        assertTrue(parser.diagnostics().isEmpty());
        assertFalse(parser.hasErrors());
    }

    @Test
    void groupsDiagnosticsByFileSkippingLocationlessOnes() {
        parser.accept("[ERROR] /projeto/src/A.java:[1,1] um");
        parser.accept("[ERROR] /projeto/src/A.java:[2,1] dois");
        parser.accept("[ERROR] /projeto/src/B.java:[1,1] tres");
        parser.accept("[ERROR] Falha geral do build");

        var grouped = BuildDiagnosticParser.byFile(parser.diagnostics());

        assertEquals(2, grouped.size());
        assertEquals(2, grouped.values().stream().mapToInt(List::size).max().orElse(0));
    }

    @Test
    void blankAndUnrelatedLinesAreIgnored() {
        assertNull(parser.accept(null));
        assertNull(parser.accept(""));
        assertNull(parser.accept("Downloading from central: https://repo.maven.apache.org/..."));
        assertTrue(parser.diagnostics().isEmpty());
    }

    @Test
    void convertsToTheEditorZeroBasedCoordinates() {
        BuildDiagnostic diagnostic = parser.accept(
                "[ERROR] /projeto/src/App.java:[12,34] cannot find symbol");

        var editorDiagnostic = diagnostic.toEditorDiagnostic();

        assertEquals(11, editorDiagnostic.startLine(), "o editor conta linhas a partir de zero");
        assertEquals(33, editorDiagnostic.startCol());
        assertEquals("cannot find symbol", editorDiagnostic.message());
    }

    @Test
    void convertsSafelyWhenTheCompilerGivesNoColumn() {
        BuildDiagnostic diagnostic = parser.accept("/projeto/src/App.java:1: error: falhou");

        var editorDiagnostic = diagnostic.toEditorDiagnostic();

        assertEquals(0, editorDiagnostic.startLine());
        assertEquals(0, editorDiagnostic.startCol());
    }
}
