package dtm.ide.build;

import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticAnalysisReportParserTest {

    @TempDir
    Path root;

    @Test
    void readsCheckstyleReport() throws Exception {
        Path source = source("src/main/java/demo/App.java");
        Path report = report("checkstyle.xml", """
                <?xml version="1.0"?>
                <checkstyle version="10">
                  <file name="src/main/java/demo/App.java">
                    <error line="7" column="5" severity="warning" message="Missing Javadoc"
                           source="com.puppycrawl.tools.checkstyle.checks.JavadocTypeCheck"/>
                  </file>
                </checkstyle>
                """);

        BuildDiagnostic diagnostic = only(report);

        assertEquals(source, diagnostic.file());
        assertEquals(7, diagnostic.line());
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity());
        assertEquals("checkstyle", diagnostic.source());
        assertTrue(diagnostic.message().contains("JavadocTypeCheck"));
    }

    @Test
    void readsNamespacedPmdReport() throws Exception {
        Path source = source("src/main/java/demo/App.java");
        Path report = report("pmd.xml", """
                <pmd xmlns="http://pmd.sourceforge.net/report/2.0.0">
                  <file name="src/main/java/demo/App.java">
                    <violation beginline="12" begincolumn="3" priority="2" rule="AvoidDuplicateLiterals">
                      Duplicate literal
                    </violation>
                  </file>
                </pmd>
                """);

        BuildDiagnostic diagnostic = only(report);

        assertEquals(source, diagnostic.file());
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals("pmd", diagnostic.source());
    }

    @Test
    void readsSpotBugsReportAndResolvesSourcePath() throws Exception {
        Path source = source("src/main/java/demo/App.java");
        Path report = report("spotbugs.xml", """
                <BugCollection>
                  <BugInstance type="NP_NULL_ON_SOME_PATH" priority="1">
                    <LongMessage>Possible null pointer dereference</LongMessage>
                    <SourceLine sourcepath="demo/App.java" start="21" end="21"/>
                  </BugInstance>
                </BugCollection>
                """);

        BuildDiagnostic diagnostic = only(report);

        assertEquals(source, diagnostic.file());
        assertEquals(21, diagnostic.line());
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity());
        assertEquals("spotbugs", diagnostic.source());
    }

    @Test
    void malformedOrUnknownReportsAreIgnored() throws Exception {
        assertTrue(StaticAnalysisReportParser.parse(report("bad.xml", "<pmd>"), root).isEmpty());
        assertTrue(StaticAnalysisReportParser.parse(
                report("unknown.xml", "<unknown/>"), root).isEmpty());
    }

    private BuildDiagnostic only(Path report) {
        List<BuildDiagnostic> diagnostics = StaticAnalysisReportParser.parse(report, root);
        assertEquals(1, diagnostics.size());
        return diagnostics.getFirst();
    }

    private Path source(String relative) throws Exception {
        Path source = root.resolve(relative);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}\n");
        return source.toAbsolutePath().normalize();
    }

    private Path report(String name, String xml) throws Exception {
        Path report = root.resolve(name);
        Files.writeString(report, xml);
        return report;
    }
}
