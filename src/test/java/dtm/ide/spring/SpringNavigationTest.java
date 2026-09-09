package dtm.ide.spring;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringNavigationTest {

    private static final Path ROOT = Path.of("/projeto");
    private static final Path RAPIDO = ROOT.resolve("EnvioRapido.java");
    private static final Path ECONOMICO = ROOT.resolve("EnvioEconomico.java");
    private static final Path CONSUMIDOR = ROOT.resolve("PedidoService.java");

    private static final String RAPIDO_SOURCE = """
            package com.example;

            @Service
            @Qualifier("rapido")
            public class EnvioRapido implements Envio {
            }
            """;

    private static final String ECONOMICO_SOURCE = """
            package com.example;

            @Service
            @Qualifier("economico")
            public class EnvioEconomico implements Envio {
            }
            """;

    private static final String CONSUMIDOR_SOURCE = """
            package com.example;

            @Service
            public class PedidoService {

                @Autowired
                @Qualifier("rapido")
                private Envio envio;
            }
            """;

    @Test
    void navigatesFromTheQualifierLiteralToTheBean() {
        SpringIndexSnapshot snapshot = snapshot();
        String lineText = "    @Qualifier(\"rapido\")";
        int col = lineText.indexOf("rapido") + 2;

        Optional<SpringNavigation.Target> target =
                SpringNavigation.insideAnnotationLiteral(snapshot, lineText, col);

        assertTrue(target.isPresent());
        assertEquals(SpringNavigation.Kind.BEAN, target.get().kind());
        assertEquals(1, target.get().anchors().size());
        assertEquals(RAPIDO, target.get().anchors().getFirst().file());
    }

    @Test
    void navigatesFromTheInjectionPointToTheResolvedBean() {
        SpringIndexSnapshot snapshot = snapshot();
        int injectionLine = snapshot.injectionsIn(CONSUMIDOR).getFirst().line();

        Optional<SpringNavigation.Target> target = SpringNavigation.definitions(
                snapshot, CONSUMIDOR, CONSUMIDOR_SOURCE, injectionLine - 1, 20);

        assertTrue(target.isPresent());
        assertEquals(SpringNavigation.Kind.INJECTION, target.get().kind());
        assertEquals(List.of(RAPIDO),
                target.get().anchors().stream().map(SpringNavigation.Anchor::file).toList());
    }

    @Test
    void listsEveryCandidateWhenTheInjectionIsAmbiguous() {
        String semQualifier = """
                package com.example;

                @Service
                public class PedidoService {

                    @Autowired
                    private Envio envio;
                }
                """;
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(RAPIDO, SpringSourceParser.parse(RAPIDO, RAPIDO_SOURCE))
                .replacingFile(ECONOMICO, SpringSourceParser.parse(ECONOMICO, ECONOMICO_SOURCE))
                .replacingFile(CONSUMIDOR, SpringSourceParser.parse(CONSUMIDOR, semQualifier));
        int injectionLine = snapshot.injectionsIn(CONSUMIDOR).getFirst().line();

        Optional<SpringNavigation.Target> target = SpringNavigation.definitions(
                snapshot, CONSUMIDOR, semQualifier, injectionLine - 1, 20);

        assertTrue(target.isPresent());
        assertEquals(2, target.get().anchors().size());
    }

    @Test
    void findsTheInjectionPointsOfABean() {
        SpringIndexSnapshot snapshot = snapshot();
        int beanLine = snapshot.beansIn(RAPIDO).getFirst().line();

        Optional<SpringNavigation.Target> target =
                SpringNavigation.references(snapshot, RAPIDO, beanLine - 1);

        assertTrue(target.isPresent());
        assertEquals(1, target.get().anchors().size());
        assertEquals(CONSUMIDOR, target.get().anchors().getFirst().file());
    }

    @Test
    void readsThePlaceholderKeyOfAValueAnnotation() {
        String lineText = "    @Value(\"${app.timeout:30}\")";
        int col = lineText.indexOf("timeout");

        Optional<SpringNavigation.Target> target = SpringNavigation.insideAnnotationLiteral(
                SpringIndexSnapshot.empty(ROOT), lineText, col);

        assertTrue(target.isPresent());
        assertEquals(SpringNavigation.Kind.CONFIG_KEY, target.get().kind());
        assertEquals("app.timeout", target.get().token());
    }

    @Test
    void ignoresLiteralsOutsideAnnotations() {
        assertTrue(SpringNavigation.insideAnnotationLiteral(
                SpringIndexSnapshot.empty(ROOT), "    String nome = \"rapido\";", 22).isEmpty());
    }

    @Test
    void navigatesFromARepositoryToItsEntity() {
        Path entityFile = ROOT.resolve("Cliente.java");
        Path repositoryFile = ROOT.resolve("ClienteRepository.java");
        String repositorySource = """
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                }
                """;
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(entityFile, SpringSourceParser.parse(entityFile, """
                        package com.example;

                        @Entity
                        public class Cliente {
                            @Id
                            private Long id;
                        }
                        """))
                .replacingFile(repositoryFile,
                        SpringSourceParser.parse(repositoryFile, repositorySource));

        int repositoryLine = snapshot.repositoriesIn(repositoryFile).getFirst().line();
        Optional<SpringNavigation.Target> target = SpringNavigation.definitions(
                snapshot, repositoryFile, repositorySource, repositoryLine - 1, 20);

        assertTrue(target.isPresent());
        assertEquals(SpringNavigation.Kind.ENTITY, target.get().kind());
        assertEquals(entityFile, target.get().anchors().getFirst().file());
    }

    @Test
    void readsTheLineUnderTheCaret() {
        assertEquals("segunda", SpringNavigation.lineAt("primeira\nsegunda\nterceira", 1));
        assertEquals("terceira", SpringNavigation.lineAt("primeira\nsegunda\nterceira", 2));
    }

    private static SpringIndexSnapshot snapshot() {
        return SpringIndexSnapshot.empty(ROOT)
                .replacingFile(RAPIDO, SpringSourceParser.parse(RAPIDO, RAPIDO_SOURCE))
                .replacingFile(ECONOMICO, SpringSourceParser.parse(ECONOMICO, ECONOMICO_SOURCE))
                .replacingFile(CONSUMIDOR, SpringSourceParser.parse(CONSUMIDOR, CONSUMIDOR_SOURCE));
    }
}
