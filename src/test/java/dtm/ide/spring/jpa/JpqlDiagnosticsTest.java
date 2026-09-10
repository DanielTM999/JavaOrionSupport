package dtm.ide.spring.jpa;

import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringSourceParser;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpqlDiagnosticsTest {

    private static final Path ROOT = Path.of("/projeto");
    private static final Path ENTITY = ROOT.resolve("Cliente.java");
    private static final Path CIDADE = ROOT.resolve("Cidade.java");
    private static final Path REPOSITORY = ROOT.resolve("ClienteRepository.java");

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

    private static final String CIDADE_SOURCE = """
            package com.example;

            @Entity
            public class Cidade {
                @Id
                private Long id;
                private String nome;
            }
            """;

    @Test
    void acceptsAValidQuery() {
        assertTrue(analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select c from Cliente c where c.nome = :nome")
                    List<Cliente> buscar(@Param("nome") String nome);
                }
        """).isEmpty());
    }

    @Test
    void acceptsAValidMultilineQueryWithAFunctionAndNamedParameter() {
        String repository = String.join("\n",
                "package com.example;",
                "public interface ClienteRepository extends JpaRepository<Cliente, Long> {",
                "    @Query(\"\"\"",
                "        select count(c)",
                "        from Cliente c",
                "        where c.nome = :nome",
                "        \"\"\")",
                "    long contar(@Param(\"nome\") String nome);",
                "}");

        assertTrue(analyze(repository).isEmpty());
    }

    @Test
    void reportsAnUnknownEntity() {
        List<Diagnostic> diagnostics = analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select f from Fornecedor f")
                    List<Cliente> todos();
                }
                """);

        assertTrue(diagnostics.stream()
                .anyMatch(d -> d.message().contains("Entidade desconhecida")));
    }

    @Test
    void reportsAnUnknownProperty() {
        List<Diagnostic> diagnostics = analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select c from Cliente c where c.nomeCompleto = :nome")
                    List<Cliente> buscar(@Param("nome") String nome);
                }
                """);

        assertTrue(diagnostics.stream()
                .anyMatch(d -> d.message().contains("nomeCompleto")));
    }

    @Test
    void resolvesAPathThroughARelation() {
        assertTrue(analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select c from Cliente c where c.cidade.nome = :cidade")
                    List<Cliente> porCidade(@Param("cidade") String cidade);
                }
                """).isEmpty());
    }

    @Test
    void resolvesAnAliasIntroducedByAJoin() {
        assertTrue(analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select c from Cliente c join c.cidade cid where cid.nome = :nome")
                    List<Cliente> porCidade(@Param("nome") String nome);
                }
                """).isEmpty());
    }

    @Test
    void reportsANamedParameterWithoutArgument() {
        List<Diagnostic> diagnostics = analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select c from Cliente c where c.nome = :nome")
                    List<Cliente> buscar(@Param("outro") String outro);
                }
                """);

        assertTrue(diagnostics.stream()
                .anyMatch(d -> d.message().contains(":nome")));
    }

    @Test
    void reportsAPositionalParameterOutOfRange() {
        List<Diagnostic> diagnostics = analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select c from Cliente c where c.nome = ?2")
                    List<Cliente> buscar(String nome);
                }
                """);

        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("?2")));
    }

    @Test
    void requiresModifyingForAnUpdate() {
        List<Diagnostic> diagnostics = analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("update Cliente c set c.nome = :nome")
                    int renomear(@Param("nome") String nome);
                }
                """);

        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("@Modifying")));
    }

    @Test
    void acceptsAnUpdateAnnotatedWithModifying() {
        List<Diagnostic> diagnostics = analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Modifying
                    @Query("update Cliente c set c.nome = :nome")
                    int renomear(@Param("nome") String nome);
                }
                """);

        assertTrue(diagnostics.stream().noneMatch(d -> d.message().contains("@Modifying")));
    }

    @Test
    void skipsNativeQueries() {
        assertTrue(analyze("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query(value = "select * from tabela_estranha", nativeQuery = true)
                    List<Cliente> nativa();
                }
                """).isEmpty());
    }

    @Test
    void parsesAliasesAndParameters() {
        JpqlQuery query = JpqlQuery.parse(
                "select c from Cliente c join c.cidade cid where c.nome = :nome and cid.nome = ?1");

        assertEquals("Cliente", query.entityOf("c"));
        assertTrue(query.namedParameters().contains("nome"));
        assertTrue(query.positionalParameters().contains(1));
        assertEquals(JpqlQuery.Statement.SELECT, query.statement());
    }

    private static List<Diagnostic> analyze(String repositorySource) {
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(ENTITY, SpringSourceParser.parse(ENTITY, CLIENTE))
                .replacingFile(CIDADE, SpringSourceParser.parse(CIDADE, CIDADE_SOURCE))
                .replacingFile(REPOSITORY, SpringSourceParser.parse(REPOSITORY, repositorySource));
        return JpqlDiagnostics.analyze(snapshot, REPOSITORY);
    }
}
