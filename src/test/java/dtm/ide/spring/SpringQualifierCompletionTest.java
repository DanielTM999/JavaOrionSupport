package dtm.ide.spring;

import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringQualifierCompletionTest {

    private static final Path ROOT = Path.of("/projeto");
    private static final Path CONSUMIDOR = ROOT.resolve("PedidoService.java");

    private static final String ENVIO = """
            package com.example;

            public interface Envio {
            }
            """;

    private static final String RAPIDO = """
            package com.example;

            @Service
            @Qualifier("rapido")
            public class EnvioRapido implements Envio {
            }
            """;

    private static final String ECONOMICO = """
            package com.example;

            @Service
            @Qualifier("economico")
            public class EnvioEconomico implements Envio {
            }
            """;

    private static final String PAGAMENTO = """
            package com.example;

            @Service
            @Qualifier("boleto")
            public class PagamentoBoleto implements Pagamento {
            }
            """;

    @Test
    void offersOnlyTheBeansAssignableToTheInjectionPoint() {
        String source = """
                package com.example;

                @Service
                public class PedidoService {

                    @Autowired
                    @Qualifier("")
                    private Envio envio;
                }
                """;
        SpringIndexSnapshot snapshot = snapshotWith(source);
        int qualifierLine = lineOf(source, "@Qualifier") - 1;

        List<String> values = SpringAnnotationCompletionProvider.qualifierValues(
                snapshot, CONSUMIDOR, qualifierLine);

        assertEquals(List.of("economico", "rapido"), values);
        assertFalse(values.contains("boleto"));
    }

    @Test
    void fallsBackToEveryBeanWhenTheInjectionCannotBeFound() {
        SpringIndexSnapshot snapshot = snapshotWith("""
                package com.example;

                @Service
                public class PedidoService {
                }
                """);

        List<String> values = SpringAnnotationCompletionProvider.qualifierValues(
                snapshot, CONSUMIDOR, 400);

        assertTrue(values.contains("boleto"));
        assertTrue(values.contains("rapido"));
    }

    @Test
    void filtersByTypeThroughATransitiveHierarchy() {
        Path baseFile = ROOT.resolve("BaseEnvio.java");
        Path expressoFile = ROOT.resolve("EnvioExpresso.java");
        String source = """
                package com.example;

                @Service
                public class PedidoService {

                    @Autowired
                    @Qualifier("")
                    private Envio envio;
                }
                """;
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(ROOT.resolve("Envio.java"),
                        SpringSourceParser.parse(ROOT.resolve("Envio.java"), ENVIO))
                .replacingFile(baseFile, SpringSourceParser.parse(baseFile, """
                        package com.example;

                        public abstract class BaseEnvio implements Envio {
                        }
                        """))
                .replacingFile(expressoFile, SpringSourceParser.parse(expressoFile, """
                        package com.example;

                        @Service
                        @Qualifier("expresso")
                        public class EnvioExpresso extends BaseEnvio {
                        }
                        """))
                .replacingFile(CONSUMIDOR, SpringSourceParser.parse(CONSUMIDOR, source));

        List<String> values = SpringAnnotationCompletionProvider.qualifierValues(
                snapshot, CONSUMIDOR, lineOf(source, "@Qualifier") - 1);

        assertEquals(List.of("expresso"), values);
    }

    @Test
    void doesNotReportAmbiguityForACollectionInjection() {
        SpringIndexSnapshot snapshot = snapshotWith("""
                package com.example;

                @Service
                public class PedidoService {

                    @Autowired
                    private List<Envio> envios;
                }
                """);

        List<Diagnostic> diagnostics =
                SpringDiagnostics.analyze(snapshot, CONSUMIDOR, "");

        assertTrue(diagnostics.stream().noneMatch(d -> d.message().contains("Mais de um bean")));
    }

    @Test
    void stillReportsAmbiguityForASingularInjection() {
        SpringIndexSnapshot snapshot = snapshotWith("""
                package com.example;

                @Service
                public class PedidoService {

                    @Autowired
                    private Envio envio;
                }
                """);

        List<Diagnostic> diagnostics =
                SpringDiagnostics.analyze(snapshot, CONSUMIDOR, "");

        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("Mais de um bean")));
    }

    private static int lineOf(String source, String needle) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static SpringIndexSnapshot snapshotWith(String consumerSource) {
        return SpringIndexSnapshot.empty(ROOT)
                .replacingFile(ROOT.resolve("Envio.java"),
                        SpringSourceParser.parse(ROOT.resolve("Envio.java"), ENVIO))
                .replacingFile(ROOT.resolve("EnvioRapido.java"),
                        SpringSourceParser.parse(ROOT.resolve("EnvioRapido.java"), RAPIDO))
                .replacingFile(ROOT.resolve("EnvioEconomico.java"),
                        SpringSourceParser.parse(ROOT.resolve("EnvioEconomico.java"), ECONOMICO))
                .replacingFile(ROOT.resolve("PagamentoBoleto.java"),
                        SpringSourceParser.parse(ROOT.resolve("PagamentoBoleto.java"), PAGAMENTO))
                .replacingFile(CONSUMIDOR, SpringSourceParser.parse(CONSUMIDOR, consumerSource));
    }
}
