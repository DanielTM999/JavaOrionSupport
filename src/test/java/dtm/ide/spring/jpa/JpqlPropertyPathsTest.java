package dtm.ide.spring.jpa;

import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringSourceParser;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpqlPropertyPathsTest {

    private static final Path ROOT = Path.of("/projeto");
    private static final Path REPOSITORY = ROOT.resolve("LaudoRepository.java");
    private static final Map<String, String> ENTITIES = Map.of(
            "Laudo", """
                    @Entity
                    public class Laudo extends BaseLaudo {
                        @Id
                        private Long id;
                        @OneToOne
                        private Cliente cliente;
                        @ManyToOne
                        private Company company;
                        private LocalDate dataAgendamento;
                    }
                    """,
            "BaseLaudo", """
                    @MappedSuperclass
                    public class BaseLaudo {
                        @ManyToOne
                        private Cliente cliente_principal;
                    }
                    """,
            "Cliente", """
                    @Entity
                    public class Cliente extends Documento {
                        @Id
                        private Long id;
                        private String nome;
                        @Column(name = "documento_fiscal")
                        private String cpf_cnpj;
                        @Transient
                        private String ignorado;
                    }
                    """,
            "Documento", """
                    @MappedSuperclass
                    public class Documento {
                        private String codigo_externo;
                    }
                    """,
            "Company", """
                    @Entity
                    public class Company {
                        @Id
                        private Long id;
                    }
                    """,
            "LaudoCautelar", """
                    @Entity
                    public class LaudoCautelar {
                        @Id
                        private Long id;
                        @OneToOne
                        private Laudo laudo;
                    }
                    """);

    @Test
    void acceptsTheReportedMultilineQueryWithAllFourParameters() {
        String query = """
                SELECT
                    l
                FROM
                    Laudo l
                WHERE
                    l.cliente.cpf_cnpj = :cnpj
                AND
                    l.company = :company
                AND
                    l.dataAgendamento
                BETWEEN
                    :startDate AND :endDate
                """;
        String source = repository(query,
                "List<Laudo> findByClienteCNPJAndCompanyDateAgendamento("
                        + "@Param(\"cnpj\") String cnpj, @Param(\"company\") Company company, "
                        + "@Param(\"startDate\") LocalDate startDate, "
                        + "@Param(\"endDate\") LocalDate endDate);");

        assertTrue(analyze(source).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select c from Cliente c where c.cpf_cnpj = :cnpj",
            "select l from Laudo l where l.cliente.cpf_cnpj = :cnpj",
            "select lc from LaudoCautelar lc where lc.laudo.cliente.cpf_cnpj = :cnpj",
            "select l from Laudo l where l.cliente_principal.cpf_cnpj = :cnpj",
            "select l from Laudo l where l.cliente.codigo_externo = :cnpj",
            "select l from Laudo l join l.cliente c where c.cpf_cnpj = :cnpj",
            "select l from Laudo l join l.cliente_principal c where c.cpf_cnpj = :cnpj",
            "select lc from LaudoCautelar lc join lc.laudo.cliente c where c.cpf_cnpj = :cnpj"
    })
    void acceptsLiteralUnderscoresInDirectInheritedAndJoinedPaths(String query) {
        assertTrue(analyze(repository(query)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "cliente.cpf_cnpjj", "cliente.cpf.cnpj", "cliente_nome", "clienteNome",
            "cliente.Cpf_cnpj", "cliente.nome.valor", "cliente.ignorado",
            "cliente.documento_fiscal"
    })
    void stillReportsInvalidPropertiesWithoutSuggestingTheExistingRoot(String path) {
        List<Diagnostic> diagnostics = analyze(repository(
                "select l from Laudo l where l." + path + " = :cnpj"));

        assertEquals(1, diagnostics.size());
        String message = diagnostics.getFirst().message();
        assertTrue(message.contains("Propriedade inexistente em Laudo: " + path), message);
        if (path.startsWith("cliente.")) {
            assertFalse(message.endsWith("cliente?"), message);
        }
    }

    @Test
    void validatesPropertiesOfAnAliasForAMultisegmentJoin() {
        List<Diagnostic> diagnostics = analyze(repository(
                "select lc from LaudoCautelar lc join lc.laudo.cliente c "
                        + "where c.cpf_cnpjj = :cnpj"));

        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.getFirst().message().contains(
                "Propriedade inexistente em Cliente: cpf_cnpjj"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select l from Laudo l where l.cliente.<caret>",
            "select l from Laudo l where l.cliente_principal.<caret>",
            "select l from Laudo l join l.cliente_principal c where c.cpf_<caret>",
            "select lc from LaudoCautelar lc join lc.laudo.cliente c where c.<caret>"
    })
    void completesLiteralAndInheritedPropertiesThroughRelationsAndJoins(String query) {
        List<AutoCompleteItem> items = complete(query);

        assertTrue(items.stream().anyMatch(item -> "cpf_cnpj".equals(item.insertText())));
        assertTrue(items.stream().noneMatch(item -> "documento_fiscal".equals(item.insertText())));
    }

    @Test
    void doesNotCompleteAMisspelledRelationAsADerivedPath() {
        assertTrue(complete("select l from Laudo l where l.clientePrincipal.<caret>").isEmpty());
    }

    @Test
    void preservesCamelCaseAndUnderscoreTraversalForDerivedQueries() {
        SpringIndexSnapshot snapshot = snapshot("");
        JpaEntity laudo = snapshot.entityNamed("Laudo").orElseThrow();
        for (String condition : List.of("ClienteNome", "Cliente_Nome")) {
            List<JpaField> fields = JpaPropertyResolver.resolveCondition(
                    laudo, condition, snapshot.entityLookup()).orElseThrow();
            assertEquals(List.of("cliente", "nome"), fields.stream().map(JpaField::name).toList());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".cliente", "cliente.", "cliente..cpf_cnpj"})
    void rejectsEmptyJpqlPathSegments(String path) {
        SpringIndexSnapshot snapshot = snapshot("");
        assertTrue(JpaPropertyResolver.resolveJpqlPath(
                snapshot.entityNamed("Laudo").orElseThrow(), path, snapshot.entityLookup()).isEmpty());
    }

    private static List<AutoCompleteItem> complete(String markedQuery) {
        String markedSource = repository(markedQuery);
        int caret = markedSource.indexOf("<caret>");
        String source = markedSource.replace("<caret>", "");
        List<AutoCompleteItem> items = JpaQueryCompletionProvider.suggestions(
                snapshot(source), REPOSITORY, source, caret);
        assertNotNull(items);
        return items;
    }

    private static List<Diagnostic> analyze(String source) {
        SpringIndexSnapshot snapshot = snapshot(source);
        List<JpaRepositoryInfo> repositories = snapshot.repositoriesIn(REPOSITORY);
        assertEquals(1, repositories.size());
        assertEquals(1, repositories.getFirst().methods().size());
        assertTrue(repositories.getFirst().methods().getFirst().validatableQuery());
        return JpqlDiagnostics.analyze(snapshot, REPOSITORY);
    }

    private static String repository(String query) {
        return repository(query, "List<Laudo> buscar(@Param(\"cnpj\") String cnpj);");
    }

    private static String repository(String query, String method) {
        return String.join("\n",
                "package com.example;",
                "public interface LaudoRepository extends JpaRepository<Laudo, Long> {",
                "    @Query(\"\"\"",
                query,
                "    \"\"\")",
                "    " + method,
                "}");
    }

    private static SpringIndexSnapshot snapshot(String source) {
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT);
        for (Map.Entry<String, String> entry : ENTITIES.entrySet()) {
            Path file = ROOT.resolve(entry.getKey() + ".java");
            snapshot = snapshot.replacingFile(file, SpringSourceParser.parse(
                    file, "package com.example;\n" + entry.getValue()));
        }
        return snapshot.replacingFile(REPOSITORY, SpringSourceParser.parse(REPOSITORY, source));
    }
}
