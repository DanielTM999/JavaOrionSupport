package dtm.ide.spring.config;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringConfigSupportTest {

    private static final Path PROPERTIES = Path.of("/projeto/application.properties");
    private static final Path YAML = Path.of("/projeto/application.yml");

    private final SpringConfigMetadata metadata = SpringConfigMetadata.builtIn();

    @TempDir
    static Path classesDir;

    private static SpringConfigMetadata classpathMetadata;

    @BeforeAll
    static void indexClasspathMetadata() throws Exception {
        Path descriptor = classesDir.resolve("META-INF")
                .resolve("spring-configuration-metadata.json");
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, """
                {"properties": [
                  {"name": "server.port", "type": "java.lang.Integer",
                   "defaultValue": "8080", "description": "Porta HTTP."},
                  {"name": "spring.datasource.url", "type": "java.lang.String",
                   "description": "URL do banco."},
                  {"name": "logging.level",
                   "type": "java.util.Map<java.lang.String,java.lang.String>",
                   "description": "Niveis de log."}
                ]}
                """);
        classpathMetadata = SpringConfigMetadata.fromClasspath(List.of(classesDir));
    }

    @Test
    void readsMetadataFromClasspathDirectories() {
        assertTrue(classpathMetadata.fromClasspath());
        assertTrue(classpathMetadata.contains("spring.datasource.url"));
        assertFalse(SpringConfigMetadata.builtIn().fromClasspath());
    }

    @Test
    void recognizesSpringConfigFiles() {
        assertTrue(SpringConfigSupport.isConfigFile(PROPERTIES));
        assertTrue(SpringConfigSupport.isConfigFile(YAML));
        assertTrue(SpringConfigSupport.isConfigFile(Path.of("/p/application-dev.yaml")));
        assertTrue(SpringConfigSupport.isConfigFile(Path.of("/p/bootstrap.properties")));
    }

    @Test
    void ignoresOtherFiles() {
        assertFalse(SpringConfigSupport.isConfigFile(Path.of("/p/messages.properties")));
        assertFalse(SpringConfigSupport.isConfigFile(Path.of("/p/App.java")));
        assertFalse(SpringConfigSupport.isConfigFile(null));
    }

    @Test
    void builtInCatalogCoversCommonKeys() {
        assertTrue(metadata.contains("server.port"));
        assertTrue(metadata.contains("spring.datasource.url"));
        assertTrue(metadata.contains("spring.jpa.hibernate.ddl-auto"));
        assertTrue(metadata.size() > 30);
    }

    @Test
    void prefixSearchIgnoresCaseAndSeparators() {
        assertFalse(metadata.startingWith("spring.jpa.show").isEmpty());
        assertFalse(metadata.startingWith("spring.jpa.showSql").isEmpty(),
                "showSql e show-sql sao a mesma chave para o Spring");
        assertFalse(metadata.startingWith("SERVER.").isEmpty());
    }

    @Test
    void mapPropertiesAcceptAnySuffix() {
        assertTrue(metadata.isKnown("logging.level.com.exemplo"),
                "logging.level e um mapa: qualquer pacote abaixo dele e valido");
        assertTrue(metadata.isKnown("logging.level"));
        assertFalse(metadata.isKnown("logging.inventado.demais"));
    }

    @Test
    void emptyKeyIsNeverKnown() {
        assertFalse(metadata.isKnown(""));
        assertFalse(metadata.isKnown(null));
    }

    @Test
    void propertyDocumentationCarriesTypeAndDefault() {
        SpringConfigProperty port = metadata.find("server.port").orElseThrow();

        assertEquals("Integer", port.simpleType());
        assertTrue(port.hasDefault());
        assertTrue(port.documentation().contains("server.port"));
        assertTrue(port.documentation().contains("8080"));
    }

    @Test
    void completesFullKeysInProperties() {
        List<AutoCompleteItem> items = SpringConfigSupport.complete(
                metadata, PROPERTIES, "server.po", 0, 9);

        AutoCompleteItem port = items.stream()
                .filter(item -> item.label().equals("server.port"))
                .findFirst()
                .orElseThrow();

        assertEquals("server.port=", port.insertText(), "em properties entra a chave inteira");
        assertEquals(AutoCompleteItem.Kind.PROPERTY, port.kind());
    }

    @Test
    void completesOnlyTheRemainingSegmentInYaml() {
        String content = """
                spring:
                  datasource:
                    ur
                """;

        List<AutoCompleteItem> items = SpringConfigSupport.complete(metadata, YAML, content, 2, 6);

        AutoCompleteItem url = items.stream()
                .filter(item -> item.label().equals("spring.datasource.url"))
                .findFirst()
                .orElseThrow();

        assertEquals("url: ", url.insertText(),
                "o caminho ja escrito acima nao pode ser repetido");
    }

    @Test
    void suggestsNothingInsideAValue() {
        assertTrue(SpringConfigSupport.complete(metadata, PROPERTIES, "server.port=80", 0, 14)
                .isEmpty());
    }

    @Test
    void suggestsNothingForFilesThatAreNotConfig() {
        assertTrue(SpringConfigSupport.complete(
                metadata, Path.of("/p/App.java"), "server.po", 0, 9).isEmpty());
    }

    @Test
    void limitsTheNumberOfSuggestions() {
        List<AutoCompleteItem> items = SpringConfigSupport.complete(metadata, PROPERTIES, "", 0, 0);

        assertTrue(items.size() <= 60);
    }

    @Test
    void hoversOverAKnownKey() {
        var hover = SpringConfigSupport.hover(metadata, PROPERTIES, "server.port=8080", 0);

        assertNotNull(hover);
        assertTrue(hover.content().contains("server.port"));
    }

    @Test
    void hoversOverAYamlKeyUsingItsFullPath() {
        String content = """
                spring:
                  datasource:
                    url: jdbc:h2:mem:test
                """;

        var hover = SpringConfigSupport.hover(metadata, YAML, content, 2);

        assertNotNull(hover);
        assertTrue(hover.content().contains("spring.datasource.url"));
    }

    @Test
    void unknownKeysHaveNoHover() {
        assertNull(SpringConfigSupport.hover(classpathMetadata, PROPERTIES, "chave.inventada=1", 0));
    }

    @Test
    void warnsAboutUnknownProperties() {
        List<Diagnostic> diagnostics = SpringConfigSupport.validate(classpathMetadata, PROPERTIES, """
                server.port=8080
                spring.datasourse.url=jdbc:h2:mem:test
                """);

        assertEquals(1, diagnostics.size());
        Diagnostic warning = diagnostics.getFirst();
        assertEquals(DiagnosticSeverity.WARNING, warning.severity(),
                "chave desconhecida e aviso: pode ser uma propriedade do proprio projeto");
        assertTrue(warning.message().contains("spring.datasourse.url"));
        assertEquals(1, warning.startLine());
    }

    @Test
    void validatesYamlUsingTheFullPath() {
        List<Diagnostic> diagnostics = SpringConfigSupport.validate(classpathMetadata, YAML, """
                spring:
                  datasource:
                    url: jdbc:h2:mem:test
                    urll: erro
                """);

        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.getFirst().message().contains("spring.datasource.urll"));
    }

    @Test
    void intermediateYamlKeysAreNotValidated() {
        List<Diagnostic> diagnostics = SpringConfigSupport.validate(classpathMetadata, YAML, """
                spring:
                  datasource:
                    url: jdbc:h2:mem:test
                """);

        assertTrue(diagnostics.isEmpty(),
                "spring e spring.datasource so abrem niveis; nao sao chaves declaradas");
    }

    @Test
    void mapKeysUnderAKnownPrefixAreAccepted() {
        assertTrue(SpringConfigSupport.validate(classpathMetadata, PROPERTIES,
                "logging.level.com.exemplo=DEBUG\n").isEmpty());
    }

    @Test
    void commentsAreNotValidated() {
        assertTrue(SpringConfigSupport.validate(classpathMetadata, PROPERTIES,
                "# chave.inventada=1\n").isEmpty());
    }

    @Test
    void withoutClasspathMetadataUnknownKeysAreNotReported() {
        assertTrue(SpringConfigSupport.validate(metadata, PROPERTIES,
                        "spring.datasourse.url=jdbc:h2:mem:test\n").isEmpty(),
                "o catalogo embutido e pequeno demais para afirmar que a chave nao existe");
    }

    @Test
    void nonConfigFilesAreNotValidated() {
        assertTrue(SpringConfigSupport.validate(
                metadata, Path.of("/p/messages.properties"), "qualquer.coisa=1").isEmpty());
    }
}
