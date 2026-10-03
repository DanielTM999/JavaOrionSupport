package dtm.ide.project;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomDiagnosticsTest {

    private static final String HEADER = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0</version>
            """;

    @Test
    void reportsUnexpectedTextInsideDependenciesAndClearsWhenCorrected() {
        String invalid = HEADER + "  <dependencies>{}</dependencies>\n</project>";
        List<Diagnostic> diagnostics = PomDiagnostics.validate(invalid);

        assertFalse(diagnostics.isEmpty());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.getFirst().severity());
        assertEquals(5, diagnostics.getFirst().startLine());
        assertTrue(PomDiagnostics.validate(invalid.replace("{}", "")).isEmpty());
    }

    @Test
    void highlightsTheUnexpectedTextBeforeFirstDependency() {
        String invalid = HEADER + """
                  <dependencies>fefvfefvvef
                    <dependency>
                      <groupId>example</groupId>
                      <artifactId>library</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        Diagnostic error = PomDiagnostics.validate(invalid).getFirst();
        assertEquals(5, error.startLine());
        assertEquals(16, error.startCol());
        assertEquals(27, error.endCol());
    }

    @Test
    void acceptsValidPomWithoutNamespaceAndInheritedCoordinates() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """;
        assertTrue(PomDiagnostics.validate(pom).isEmpty());
    }

    @Test
    void reportsMalformedXmlAndInvalidMavenModel() {
        List<Diagnostic> malformed = PomDiagnostics.validate(HEADER + "  <dependencies>\n</project>");
        assertFalse(malformed.isEmpty());
        assertEquals(DiagnosticSeverity.ERROR, malformed.getFirst().severity());
        assertTrue(malformed.getFirst().endCol() > malformed.getFirst().startCol());

        String missingArtifact = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <version>1.0</version>
                </project>
                """;
        assertTrue(PomDiagnostics.validate(missingArtifact).stream()
                .anyMatch(d -> d.message().contains("artifactId")
                        && d.severity() == DiagnosticSeverity.ERROR));
    }

    @Test
    void rejectsDoctypeWithoutResolvingExternalEntities() {
        String pom = "<!DOCTYPE project SYSTEM \"https://example.invalid/evil.dtd\">\n"
                + HEADER + "</project>";
        assertFalse(PomDiagnostics.validate(pom).isEmpty());
    }

    @Test
    void validatesThePluginsOwnPomAndDetectsTheReportedEdit() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(PomDiagnostics.validate(pom).isEmpty());
        String invalid = pom.replace("<dependencies>", "<dependencies>{}");
        assertTrue(PomDiagnostics.validate(invalid).stream()
                .anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
    }
}
