package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JavaSnippetCompletionProvider {

    private static final List<AutoCompleteItem> JAVA = List.of(
            AutoCompleteItem.snippet("psvm",
                    "public static void main(String[] args) {\n    $0\n}", "metodo main"),
            AutoCompleteItem.snippet("sout",
                    "System.out.println($0);", "System.out.println"),
            AutoCompleteItem.snippet("souf",
                    "System.out.printf(\"$1%n\", $0);", "System.out.printf"),
            AutoCompleteItem.snippet("serr",
                    "System.err.println($0);", "System.err.println"),
            AutoCompleteItem.snippet("fori",
                    "for (int ${1:i} = 0; ${1:i} < ${2:limite}; ${1:i}++) {\n    $0\n}", "for indexado"),
            AutoCompleteItem.snippet("iter",
                    "for (${1:var} ${2:item} : ${3:colecao}) {\n    $0\n}", "for-each"),
            AutoCompleteItem.snippet("ifn",
                    "if (${1:valor} == null) {\n    $0\n}", "if nulo"),
            AutoCompleteItem.snippet("nn",
                    "if (${1:valor} != null) {\n    $0\n}", "if nao nulo"),
            AutoCompleteItem.snippet("try",
                    "try {\n    $0\n} catch (${1:Exception} ${2:e}) {\n    \n}", "try/catch"),
            AutoCompleteItem.snippet("tryr",
                    "try (${1:var recurso = null}) {\n    $0\n} catch (${2:Exception} ${3:e}) {\n    \n}",
                    "try-with-resources"),
            AutoCompleteItem.snippet("switche",
                    "switch (${1:valor}) {\n    case ${2:opcao} -> $0;\n    default -> throw new IllegalStateException();\n}",
                    "switch com seta"),
            AutoCompleteItem.snippet("record",
                    "public record ${1:Nome}(${2:String valor}) {\n}$0", "record"),
            AutoCompleteItem.snippet("ctor",
                    "public ${1:Classe}(${2}) {\n    $0\n}", "construtor"),
            AutoCompleteItem.snippet("getset",
                    "public ${1:String} get${2:Valor}() {\n    return ${3:valor};\n}\n\n"
                            + "public void set${2:Valor}(${1:String} ${3:valor}) {\n    this.${3:valor} = ${3:valor};\n}$0",
                    "getter e setter"),
            AutoCompleteItem.snippet("test",
                    "@Test\nvoid ${1:deveFazerAlgo}() {\n    $0\n}", "teste JUnit 5"),
            AutoCompleteItem.snippet("ptest",
                    "@ParameterizedTest\n@ValueSource(strings = {\"${1:a}\"})\nvoid ${2:deveFazerAlgo}(String ${3:valor}) {\n    $0\n}",
                    "teste parametrizado")
    );

    private static final List<AutoCompleteItem> SPRING = List.of(
            AutoCompleteItem.snippet("@service",
                    "@Service\npublic class ${1:Nome}Service {\n    $0\n}", "classe @Service"),
            AutoCompleteItem.snippet("@component",
                    "@Component\npublic class ${1:Nome} {\n    $0\n}", "classe @Component"),
            AutoCompleteItem.snippet("@repository",
                    "@Repository\npublic interface ${1:Nome}Repository extends JpaRepository<${2:Entidade}, ${3:Long}> {\n    $0\n}",
                    "interface @Repository"),
            AutoCompleteItem.snippet("@restcontroller",
                    "@RestController\n@RequestMapping(\"/${1:recurso}\")\npublic class ${2:Nome}Controller {\n    $0\n}",
                    "classe @RestController"),
            AutoCompleteItem.snippet("@configuration",
                    "@Configuration\npublic class ${1:Nome}Config {\n    $0\n}", "classe @Configuration"),
            AutoCompleteItem.snippet("@bean",
                    "@Bean\npublic ${1:Tipo} ${2:nome}() {\n    return $0;\n}", "metodo @Bean"),
            AutoCompleteItem.snippet("@get",
                    "@GetMapping(\"/${1:caminho}\")\npublic ${2:ResponseEntity<?>} ${3:buscar}() {\n    $0\n}",
                    "handler @GetMapping"),
            AutoCompleteItem.snippet("@post",
                    "@PostMapping(\"/${1:caminho}\")\npublic ${2:ResponseEntity<?>} ${3:criar}(@RequestBody ${4:Tipo} ${5:corpo}) {\n    $0\n}",
                    "handler @PostMapping"),
            AutoCompleteItem.snippet("@entity",
                    "@Entity\n@Table(name = \"${1:tabela}\")\npublic class ${2:Nome} {\n\n"
                            + "    @Id\n    @GeneratedValue(strategy = GenerationType.IDENTITY)\n"
                            + "    private Long id;\n    $0\n}",
                    "entidade JPA"),
            AutoCompleteItem.snippet("@value",
                    "@Value(\"${${1:propriedade}}\")\nprivate ${2:String} ${3:campo};$0", "campo @Value"),
            AutoCompleteItem.snippet("@configprops",
                    "@ConfigurationProperties(prefix = \"${1:prefixo}\")\npublic record ${2:Nome}Properties(${3:String valor}) {\n}$0",
                    "@ConfigurationProperties"),
            AutoCompleteItem.snippet("@springboottest",
                    "@SpringBootTest\nclass ${1:Nome}Test {\n\n    @Test\n    void ${2:contextoCarrega}() {\n        $0\n    }\n}",
                    "teste @SpringBootTest")
    );

    public List<AutoCompleteItem> suggestions(String prefix, boolean spring) {
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<AutoCompleteItem> matches = new ArrayList<>();
        collect(JAVA, needle, matches);
        if (spring) {
            collect(SPRING, needle, matches);
        }
        return List.copyOf(matches);
    }

    private static void collect(List<AutoCompleteItem> source, String needle,
                                List<AutoCompleteItem> target) {
        for (AutoCompleteItem item : source) {
            String label = item.label().toLowerCase(Locale.ROOT);
            if (needle.isBlank() || label.startsWith(needle)
                    || (label.startsWith("@") && label.substring(1).startsWith(needle))) {
                target.add(item);
            }
        }
    }
}
