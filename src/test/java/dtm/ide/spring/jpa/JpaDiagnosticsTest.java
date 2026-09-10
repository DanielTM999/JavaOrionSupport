package dtm.ide.spring.jpa;

import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringSourceParser;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaDiagnosticsTest {

    private static final Path ROOT = Path.of("/projeto");
    private static final Path ENTITY_FILE = ROOT.resolve("Cliente.java");
    private static final Path REPOSITORY_FILE = ROOT.resolve("ClienteRepository.java");

    private static final String CLIENTE = """
            package com.example;

            @Entity
            public class Cliente {
                @Id
                private Long id;
                private String nome;
                private String email;
                @ManyToOne
                private Cidade cidade;
            }
            """;

    private static final String CIDADE = """
            package com.example;

            @Entity
            public class Cidade {
                @Id
                private Long id;
                private String nome;
            }
            """;

    @Test
    void reportsUnknownPropertyInDerivedQuery() {
        List<Diagnostic> diagnostics = analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    List<Cliente> findByNomeCompleto(String nome);
                }
                """);

        assertEquals(1, diagnostics.size());
        assertEquals(DiagnosticSeverity.ERROR, diagnostics.getFirst().severity());
        assertTrue(diagnostics.getFirst().message().contains("nomeCompleto"));
    }

    @Test
    void suggestsTheClosestPropertyName() {
        List<Diagnostic> diagnostics = analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    List<Cliente> findByNme(String nome);
                }
                """);

        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.getFirst().message().contains("nome"));
    }

    @Test
    void acceptsKnownPropertiesWithPredicateKeywords() {
        assertTrue(analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    List<Cliente> findByNomeContainingIgnoreCase(String nome);

                    List<Cliente> findByEmailAndNome(String email, String nome);

                    List<Cliente> findByNomeOrderByEmailDesc(String nome);
                }
                """).isEmpty());
    }

    @Test
    void traversesRelationsWhenResolvingProperties() {
        assertTrue(analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    List<Cliente> findByCidadeNome(String nome);

                    List<Cliente> findByCidade_Nome(String nome);
                }
                """).isEmpty());
    }

    @Test
    void skipsMethodsWithDeclaredQuery() {
        assertTrue(analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select c from Cliente c")
                    List<Cliente> findByQualquerCoisa();
                }
        """).isEmpty());
    }

    @Test
    void skipsDerivedValidationForAQueryInATextBlock() {
        String repository = String.join("\n",
                "package com.example;",
                "public interface ClienteRepository extends JpaRepository<Cliente, Long> {",
                "    @Query(\"\"\"",
                "        select c from Cliente c where c.nome = :nome",
                "        \"\"\")",
                "    List<Cliente> findByNomeCompleto(@Param(\"nome\") String nome);",
                "}");

        assertTrue(analyze(repository).isEmpty());
    }

    @Test
    void reportsEntityWithoutId() {
        List<Diagnostic> diagnostics = JpaDiagnostics.analyze(snapshotOf(
                entry(ENTITY_FILE, """
                        package com.example;

                        @Entity
                        public class Cliente {
                            private String nome;
                        }
                        """)), ENTITY_FILE);

        assertTrue(diagnostics.stream()
                .anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
    }

    @Test
    void doesNotReportMissingIdWhenInheritedFromMappedSuperclass() {
        Path baseFile = ROOT.resolve("Base.java");
        List<Diagnostic> diagnostics = JpaDiagnostics.analyze(snapshotOf(
                entry(baseFile, """
                        package com.example;

                        @MappedSuperclass
                        public class Base {
                            @Id
                            private Long id;
                        }
                        """),
                entry(ENTITY_FILE, """
                        package com.example;

                        @Entity
                        public class Cliente extends Base {
                            private String nome;
                        }
                        """)), ENTITY_FILE);

        assertFalse(diagnostics.stream()
                .anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR));
    }

    @Test
    void hintsAboutEagerSingularRelations() {
        List<Diagnostic> diagnostics = JpaDiagnostics.analyze(
                snapshotOf(entry(ENTITY_FILE, CLIENTE)), ENTITY_FILE);

        assertTrue(diagnostics.stream()
                .anyMatch(d -> d.severity() == DiagnosticSeverity.HINT));
    }

    private static List<Diagnostic> analyze(String repositorySource) {
        SpringIndexSnapshot snapshot = snapshotOf(
                entry(ENTITY_FILE, CLIENTE),
                entry(ROOT.resolve("Cidade.java"), CIDADE),
                entry(REPOSITORY_FILE, repositorySource));
        return JpaDiagnostics.analyze(snapshot, REPOSITORY_FILE);
    }

    private record Entry(Path file, String source) {
    }

    private static Entry entry(Path file, String source) {
        return new Entry(file, source);
    }

    private static SpringIndexSnapshot snapshotOf(Entry... entries) {
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT);
        List<Entry> all = new ArrayList<>(List.of(entries));
        for (Entry entry : all) {
            snapshot = snapshot.replacingFile(entry.file(),
                    SpringSourceParser.parse(entry.file(), entry.source()));
        }
        return snapshot;
    }
}
