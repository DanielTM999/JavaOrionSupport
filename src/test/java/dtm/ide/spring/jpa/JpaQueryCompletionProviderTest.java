package dtm.ide.spring.jpa;

import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringSourceParser;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaQueryCompletionProviderTest {

    private static final Path ROOT = Path.of("/projeto");
    private static final Path REPOSITORY = ROOT.resolve("AuditoriaLaudoRepository.java");

    private static final String LAUDO = """
            package com.example;

            @Entity
            public class Laudo {
                @Id
                private long idLaudo;
                private Company company;
                private String titulo;
            }
            """;

    private static final String AUDITORIA = """
            package com.example;

            @Entity
            @Table(name = "auditoria_laudo")
            public class AuditoriaLaudo {
                @Id
                @Column(name = "id_auditoria")
                private long id;
                @ManyToOne
                @JoinColumn(name = "laudo_id")
                private Laudo laudo;
                private String acao;
            }
            """;

    @Test
    void returnsNullOutsideAQueryLiteral() {
        assertNull(JpaQueryCompletionProvider.suggestions(snapshot(""), REPOSITORY,
                "class Demo { String value = \"FROM\"; }", 30));
    }

    @Test
    void suggestsKeywordsFunctionsAndTemplatesAtTheStart() {
        Completion completion = complete(repositoryWithQuery("<caret>"));

        assertEquals(AutoCompleteItem.Kind.KEYWORD, completion.item("SELECT").kind());
        assertTrue(completion.item("COUNT").isSnippet());
        assertTrue(completion.item("SELECT … FROM …").isSnippet());
        assertTrue(completion.items().stream().noneMatch(item -> "INSERT INTO".equals(item.label())));
    }

    @Test
    void keepsSqlOnlyKeywordsInNativeQueries() {
        String marked = """
                package com.example;
                public interface AuditoriaLaudoRepository extends JpaRepository<AuditoriaLaudo, Long> {
                    @Query(value = "<caret>", nativeQuery = true)
                    List<AuditoriaLaudo> buscar();
                }
                """;

        Completion completion = complete(marked);

        assertEquals(AutoCompleteItem.Kind.KEYWORD, completion.item("INSERT INTO").kind());
        assertEquals(AutoCompleteItem.Kind.KEYWORD, completion.item("VALUES").kind());
    }

    @Test
    void suggestsJpaEntitiesAfterFromAndFiltersThePrefix() {
        Completion completion = complete(repositoryWithQuery("SELECT la FROM Audi<caret>"));

        AutoCompleteItem item = completion.item("AuditoriaLaudo");
        assertEquals("AuditoriaLaudo", item.insertText());
        assertEquals(AutoCompleteItem.Kind.CLASS, item.kind());
        assertTrue(completion.items().stream().noneMatch(candidate ->
                "Laudo".equals(candidate.label())));
    }

    @Test
    void suggestsPropertiesThroughARelationPath() {
        Completion completion = complete(repositoryWithQuery(String.join("\n",
                "SELECT la FROM AuditoriaLaudo la",
                "WHERE la.laudo.<caret>")));

        assertEquals(AutoCompleteItem.Kind.PROPERTY, completion.item("idLaudo").kind());
        assertNotNull(completion.item("company"));
        assertNotNull(completion.item("titulo"));
    }

    @Test
    void suggestsAliasesAndNamedMethodParameters() {
        Completion aliases = complete(repositoryWithQuery(
                "SELECT la FROM AuditoriaLaudo la WHERE <caret>"));
        Completion parameters = complete(repositoryWithQuery(
                "SELECT la FROM AuditoriaLaudo la WHERE la.id = :lau<caret>"));

        assertEquals(AutoCompleteItem.Kind.VARIABLE, aliases.item("la").kind());
        AutoCompleteItem parameter = parameters.item(":laudoId");
        assertEquals("laudoId", parameter.insertText());
        assertEquals(AutoCompleteItem.Kind.PARAMETER, parameter.kind());
    }

    @Test
    void seesAliasesDeclaredAfterTheCaretAndOffersThemForJpaJoins() {
        Completion select = complete(repositoryWithQuery(String.join("\n",
                "SELECT <caret>",
                "FROM AuditoriaLaudo la")));
        Completion join = complete(repositoryWithQuery(
                "SELECT la FROM AuditoriaLaudo la JOIN <caret>"));

        assertEquals(AutoCompleteItem.Kind.VARIABLE, select.item("la").kind());
        assertEquals(AutoCompleteItem.Kind.VARIABLE, join.item("la").kind());
    }

    @Test
    void suggestsNativeTablesAndColumnsUsingMappedNames() {
        String marked = String.join("\n",
                "package com.example;",
                "public interface AuditoriaLaudoRepository",
                "        extends JpaRepository<AuditoriaLaudo, Long> {",
                "    @Query(value = \"SELECT a.id_auditoria FROM auditoria_laudo a "
                        + "WHERE a.<caret>\", nativeQuery = true)",
                "    List<AuditoriaLaudo> buscar(@Param(\"laudoId\") long laudoId);",
                "}");

        Completion completion = complete(marked);

        assertNotNull(completion.item("id_auditoria"));
        assertNotNull(completion.item("laudo_id"));
        assertNotNull(completion.item("acao"));
    }

    @Test
    void resolvesAliasesDeclaredInAPreviousConcatenatedLiteral() {
        String marked = """
                package com.example;
                public interface AuditoriaLaudoRepository extends JpaRepository<AuditoriaLaudo, Long> {
                    @Query("SELECT la FROM AuditoriaLaudo la "
                            + "WHERE la.<caret>")
                    List<AuditoriaLaudo> buscar();
                }
                """;

        Completion completion = complete(marked);

        assertNotNull(completion.item("id"));
        assertNotNull(completion.item("laudo"));
    }

    @Test
    void suppressesCompletionInsideASpelExpression() {
        String marked = """
                package com.example;
                public interface AuditoriaLaudoRepository extends JpaRepository<AuditoriaLaudo, Long> {
                    @Query("SELECT e FROM #{#enti<caret>tyName} e")
                    List<AuditoriaLaudo> buscar();
                }
                """;

        assertTrue(complete(marked).items().isEmpty());
    }

    private static String repositoryWithQuery(String query) {
        return String.join("\n",
                "package com.example;",
                "public interface AuditoriaLaudoRepository",
                "        extends JpaRepository<AuditoriaLaudo, Long> {",
                "    @Query(\"\"\"",
                "        " + query.replace("\n", "\n        "),
                "        \"\"\")",
                "    List<AuditoriaLaudo> buscar(@Param(\"laudoId\") long laudoId,",
                "            @Param(\"company\") Company company);",
                "}");
    }

    private static Completion complete(String markedSource) {
        int caret = markedSource.indexOf("<caret>");
        if (caret < 0) {
            throw new AssertionError("marcador de caret ausente");
        }
        String source = markedSource.replace("<caret>", "");
        List<AutoCompleteItem> items = JpaQueryCompletionProvider.suggestions(
                snapshot(source), REPOSITORY, source, caret);
        assertNotNull(items);
        return new Completion(items);
    }

    private static SpringIndexSnapshot snapshot(String repositorySource) {
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT)
                .replacingFile(ROOT.resolve("Laudo.java"),
                        SpringSourceParser.parse(ROOT.resolve("Laudo.java"), LAUDO))
                .replacingFile(ROOT.resolve("AuditoriaLaudo.java"),
                        SpringSourceParser.parse(ROOT.resolve("AuditoriaLaudo.java"), AUDITORIA));
        return repositorySource.isBlank() ? snapshot : snapshot.replacingFile(REPOSITORY,
                SpringSourceParser.parse(REPOSITORY, repositorySource));
    }

    private record Completion(List<AutoCompleteItem> items) {

        AutoCompleteItem item(String label) {
            return items.stream().filter(item -> label.equals(item.label())).findFirst()
                    .orElseThrow(() -> new AssertionError("sugestão ausente: " + label
                            + " em " + items.stream().map(AutoCompleteItem::label).toList()));
        }
    }
}
