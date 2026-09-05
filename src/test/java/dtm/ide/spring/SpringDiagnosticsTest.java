package dtm.ide.spring;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringDiagnosticsTest {

    private static final Path ROOT = Path.of("/projeto").toAbsolutePath();

    @Test
    void reportsAmbiguousInjection() {
        SpringIndexSnapshot snapshot = index(
                file("ClienteService.java", "package p;\npublic interface ClienteService { }"),
                file("A.java", "package p;\n@Service\npublic class A implements ClienteService { }"),
                file("B.java", "package p;\n@Service\npublic class B implements ClienteService { }"),
                file("PedidoService.java", """
                        package p;

                        @Service
                        public class PedidoService {
                            public PedidoService(ClienteService clientes) { }
                        }
                        """));

        List<Diagnostic> diagnostics = analyze(snapshot, "PedidoService.java", "");

        Diagnostic ambiguity = firstOfSeverity(diagnostics, DiagnosticSeverity.ERROR);
        assertTrue(ambiguity.message().contains("ClienteService"));
        assertTrue(ambiguity.message().contains("A"));
        assertTrue(ambiguity.message().contains("B"));
        assertEquals(4, ambiguity.startLine(), "linha do construtor, base zero");
    }

    @Test
    void qualifierSilencesTheAmbiguity() {
        SpringIndexSnapshot snapshot = index(
                file("ClienteService.java", "package p;\npublic interface ClienteService { }"),
                file("A.java", "package p;\n@Service(\"a\")\npublic class A implements ClienteService { }"),
                file("B.java", "package p;\n@Service(\"b\")\npublic class B implements ClienteService { }"),
                file("PedidoService.java", """
                        package p;

                        @Service
                        public class PedidoService {
                            public PedidoService(@Qualifier("b") ClienteService clientes) { }
                        }
                        """));

        assertTrue(errorsOf(analyze(snapshot, "PedidoService.java", "")).isEmpty());
    }

    @Test
    void primarySilencesTheAmbiguity() {
        SpringIndexSnapshot snapshot = index(
                file("ClienteService.java", "package p;\npublic interface ClienteService { }"),
                file("A.java", """
                        package p;

                        @Service
                        @Primary
                        public class A implements ClienteService { }
                        """),
                file("B.java", "package p;\n@Service\npublic class B implements ClienteService { }"),
                file("PedidoService.java", """
                        package p;

                        @Service
                        public class PedidoService {
                            public PedidoService(ClienteService clientes) { }
                        }
                        """));

        assertTrue(errorsOf(analyze(snapshot, "PedidoService.java", "")).isEmpty());
    }

    @Test
    void singleCandidateIsNeverAmbiguous() {
        SpringIndexSnapshot snapshot = index(
                file("ClienteService.java", "package p;\n@Service\npublic class ClienteService { }"),
                file("PedidoService.java", """
                        package p;

                        @Service
                        public class PedidoService {
                            public PedidoService(ClienteService clientes) { }
                        }
                        """));

        assertTrue(errorsOf(analyze(snapshot, "PedidoService.java", "")).isEmpty());
    }

    @Test
    void fieldInjectionIsAHintNotAnError() {
        SpringIndexSnapshot snapshot = index(
                file("ClienteService.java", "package p;\n@Service\npublic class ClienteService { }"),
                file("PedidoService.java", """
                        package p;

                        @Service
                        public class PedidoService {
                            @Autowired
                            private ClienteService clientes;
                        }
                        """));

        List<Diagnostic> diagnostics = analyze(snapshot, "PedidoService.java", "");

        Diagnostic hint = firstOfSeverity(diagnostics, DiagnosticSeverity.HINT);
        assertTrue(hint.message().contains("construtor"));
        assertTrue(errorsOf(diagnostics).isEmpty());
    }

    @Test
    void constructorInjectionRaisesNoHint() {
        SpringIndexSnapshot snapshot = index(
                file("ClienteService.java", "package p;\n@Service\npublic class ClienteService { }"),
                file("PedidoService.java", """
                        package p;

                        @Service
                        public class PedidoService {
                            public PedidoService(ClienteService clientes) { }
                        }
                        """));

        assertTrue(analyze(snapshot, "PedidoService.java", "").isEmpty());
    }

    @Test
    void reportsConstructorCycles() {
        SpringIndexSnapshot snapshot = index(
                file("A.java", """
                        package p;

                        @Service
                        public class A {
                            public A(B b) { }
                        }
                        """),
                file("B.java", """
                        package p;

                        @Service
                        public class B {
                            public B(A a) { }
                        }
                        """));

        Diagnostic cycle = firstOfSeverity(analyze(snapshot, "A.java", ""), DiagnosticSeverity.ERROR);

        assertTrue(cycle.message().contains("circular"));
        assertTrue(cycle.message().contains("A"));
        assertTrue(cycle.message().contains("B"));
    }

    @Test
    void reportsLongerCycles() {
        SpringIndexSnapshot snapshot = index(
                file("A.java", "package p;\n@Service\npublic class A {\n    public A(B b) { }\n}"),
                file("B.java", "package p;\n@Service\npublic class B {\n    public B(C c) { }\n}"),
                file("C.java", "package p;\n@Service\npublic class C {\n    public C(A a) { }\n}"));

        Diagnostic cycle = firstOfSeverity(analyze(snapshot, "A.java", ""), DiagnosticSeverity.ERROR);

        assertTrue(cycle.message().contains("A"));
        assertTrue(cycle.message().contains("C"));
    }

    @Test
    void acyclicGraphsRaiseNothing() {
        SpringIndexSnapshot snapshot = index(
                file("A.java", "package p;\n@Service\npublic class A {\n    public A(B b) { }\n}"),
                file("B.java", "package p;\n@Service\npublic class B {\n    public B(C c) { }\n}"),
                file("C.java", "package p;\n@Service\npublic class C { }"));

        assertTrue(errorsOf(analyze(snapshot, "A.java", "")).isEmpty());
    }

    @Test
    void fieldCyclesAreNotReported() {
        SpringIndexSnapshot snapshot = index(
                file("A.java", """
                        package p;

                        @Service
                        public class A {
                            @Autowired
                            private B b;
                        }
                        """),
                file("B.java", """
                        package p;

                        @Service
                        public class B {
                            @Autowired
                            private A a;
                        }
                        """));

        assertTrue(errorsOf(analyze(snapshot, "A.java", "")).isEmpty(),
                "o Spring resolve ciclo por campo com proxy; so o construtor derruba o contexto");
    }

    @Test
    void warnsAboutTransactionalOnNonPublicMethods() {
        SpringIndexSnapshot snapshot = index(
                file("A.java", "package p;\n@Service\npublic class A { }"));

        String source = """
                @Service
                public class PedidoService {

                    @Transactional
                    private void salvar() { }
                }
                """;

        Diagnostic warning = firstOfSeverity(
                analyze(snapshot, "A.java", source), DiagnosticSeverity.WARNING);

        assertTrue(warning.message().contains("@Transactional"));
        assertEquals(3, warning.startLine());
    }

    @Test
    void warnsAboutTransactionalOnFinalMethods() {
        SpringIndexSnapshot snapshot = index(file("A.java", "package p;\n@Service\npublic class A { }"));

        String source = """
                @Service
                public class PedidoService {
                    @Transactional
                    final void salvar() { }
                }
                """;

        assertFalse(warningsOf(analyze(snapshot, "A.java", source)).isEmpty());
    }

    @Test
    void publicTransactionalMethodsAreFine() {
        SpringIndexSnapshot snapshot = index(file("A.java", "package p;\n@Service\npublic class A { }"));

        String source = """
                @Service
                public class PedidoService {
                    @Transactional
                    public void salvar() { }
                }
                """;

        assertTrue(warningsOf(analyze(snapshot, "A.java", source)).isEmpty());
    }

    @Test
    void transactionalInsideACommentIsIgnored() {
        SpringIndexSnapshot snapshot = index(file("A.java", "package p;\n@Service\npublic class A { }"));

        String source = """
                public class PedidoService {
                    // @Transactional
                    // private void salvar() { }
                }
                """;

        assertTrue(warningsOf(analyze(snapshot, "A.java", source)).isEmpty());
    }

    @Test
    void emptyIndexProducesNothing() {
        assertTrue(SpringDiagnostics.analyze(null, ROOT.resolve("A.java"), "").isEmpty());
        assertTrue(SpringDiagnostics.analyze(
                SpringIndexSnapshot.empty(ROOT), ROOT.resolve("A.java"), "").isEmpty());
    }

    private record SourceFile(String name, String content) {
    }

    private static SourceFile file(String name, String content) {
        return new SourceFile(name, content);
    }

    private static SpringIndexSnapshot index(SourceFile... files) {
        List<SpringBean> beans = new ArrayList<>();
        List<SpringInjection> injections = new ArrayList<>();
        List<SpringEndpoint> endpoints = new ArrayList<>();
        for (SourceFile file : files) {
            SpringSourceParser.ParseResult parsed =
                    SpringSourceParser.parse(ROOT.resolve(file.name()), file.content());
            beans.addAll(parsed.beans());
            injections.addAll(parsed.injections());
            endpoints.addAll(parsed.endpoints());
        }
        return new SpringIndexSnapshot(ROOT, beans, injections, endpoints);
    }

    private static List<Diagnostic> analyze(SpringIndexSnapshot snapshot, String fileName,
                                            String source) {
        return SpringDiagnostics.analyze(snapshot, ROOT.resolve(fileName), source);
    }

    private static Diagnostic firstOfSeverity(List<Diagnostic> diagnostics,
                                              DiagnosticSeverity severity) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity() == severity)
                .findFirst()
                .orElseThrow(() -> new AssertionError("nenhum diagnostico " + severity
                        + " em " + diagnostics));
    }

    private static List<Diagnostic> errorsOf(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)
                .toList();
    }

    private static List<Diagnostic> warningsOf(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.WARNING)
                .toList();
    }
}
