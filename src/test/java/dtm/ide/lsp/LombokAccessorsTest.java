package dtm.ide.lsp;

import dtm.stools.component.panels.editor.code.api.DocumentSymbol;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.SymbolKind;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LombokAccessorsTest {

    @Test
    void builderAndWitherFollowTheRenamedField() {
        String source = """
                package demo;

                @Data
                @Builder
                @With
                public class Funcionario {
                    private Long idFuncionario;
                    private boolean ativo;
                    private boolean isGerente;
                }
                """;

        assertEquals(List.of(new LombokAccessors.Accessor("idFuncionario", "codigo"),
                        new LombokAccessors.Accessor("withIdFuncionario", "withCodigo")),
                accessors(source, "idFuncionario", "codigo"));
        assertEquals(List.of(new LombokAccessors.Accessor("isGerente", "isChefe"),
                        new LombokAccessors.Accessor("withGerente", "withChefe")),
                accessors(source, "isGerente", "isChefe"));
    }

    @Test
    void builderSetterPrefixAndFieldLevelWither() {
        String source = """
                package demo;

                @lombok.Builder(setterPrefix = "com")
                public class Pedido {
                    @With
                    private String numero;
                    private String cliente;
                }
                """;

        assertEquals(List.of(new LombokAccessors.Accessor("comNumero", "comCodigo"),
                        new LombokAccessors.Accessor("withNumero", "withCodigo")),
                accessors(source, "numero", "codigo"));
        assertEquals(List.of(new LombokAccessors.Accessor("comCliente", "comComprador")),
                accessors(source, "cliente", "comprador"));
    }

    @Test
    void ignoresFieldsLombokDoesNotGenerateMethodsFor() {
        String source = """
                package demo;

                // @Builder
                @Getter
                @Setter
                @Builder.Default
                @With
                public class Config {
                    private static String padrao;
                    private final String fixo = "x";
                    @With(AccessLevel.NONE)
                    private String nome;
                }
                """;

        assertTrue(accessors(source, "padrao", "outro").isEmpty());
        assertTrue(accessors(source, "fixo", "outro").isEmpty());
        assertTrue(accessors(source, "nome", "outro").isEmpty());
    }

    @Test
    void accessorsPrefixDisablesTheWither() {
        String source = """
                package demo;

                @With
                @Accessors(prefix = "m")
                public class Legado {
                    private String mNome;
                }
                """;

        assertTrue(accessors(source, "mNome", "mApelido").isEmpty());
    }

    @Test
    void explicitMethodWinsOverTheGeneratedOne() {
        String source = """
                package demo;

                @Builder
                @With
                public class Conta {
                    private String saldo;
                    public Conta withSaldo(String saldo) { return this; }
                }
                """;

        assertEquals(List.of(new LombokAccessors.Accessor("saldo", "valor")), accessors(source, "saldo", "valor"));
    }

    @Test
    void combineKeepsServerEditsAndDropsOverlaps() {
        TextEdit server = new TextEdit(Range.of(2, 4, 2, 17), "codigo");
        TextEdit same = new TextEdit(Range.of(2, 4, 2, 17), "codigo");
        TextEdit other = new TextEdit(Range.of(5, 10, 5, 23), "codigo");

        assertEquals(List.of(server, other), LombokAccessorRename.combine(List.of(server), List.of(same, other)));
    }

    private static List<LombokAccessors.Accessor> accessors(String source, String field, String newName) {
        List<DocumentSymbol> symbols = symbols(source);
        Position position = positionOf(source, source.indexOf(" " + field + ";") + 1);
        return LombokAccessors.of(source, symbols, position, newName);
    }

    private static List<DocumentSymbol> symbols(String source) {
        List<String> lines = source.lines().toList();
        int classLine = 0;
        while (!lines.get(classLine).contains("class ")) {
            classLine++;
        }
        int headerStart = classLine;
        while (headerStart > 0 && lines.get(headerStart - 1).trim().startsWith("@")) {
            headerStart--;
        }
        List<DocumentSymbol> members = new ArrayList<>();
        for (int line = classLine + 1; line < lines.size(); line++) {
            String text = lines.get(line);
            if (text.trim().startsWith("@") || !text.contains(";") && !text.contains("{")) {
                continue;
            }
            int start = line;
            while (start > classLine + 1 && lines.get(start - 1).trim().startsWith("@")) {
                start--;
            }
            int indent = lines.get(start).indexOf(lines.get(start).trim());
            if (text.contains("(")) {
                String name = text.substring(0, text.indexOf('(')).trim();
                name = name.substring(name.lastIndexOf(' ') + 1);
                int col = text.indexOf(name + "(");
                members.add(new DocumentSymbol(name + "(String)", "", SymbolKind.METHOD,
                        Range.of(start, indent, line, text.length()), Range.of(line, col, line, col + name.length()), List.of()));
            } else {
                String declaration = text.substring(0, text.contains("=") ? text.indexOf('=') : text.indexOf(';')).trim();
                String name = declaration.substring(declaration.lastIndexOf(' ') + 1);
                int col = text.indexOf(" " + name) + 1;
                members.add(new DocumentSymbol(name, "", SymbolKind.FIELD,
                        Range.of(start, indent, line, text.length()), Range.of(line, col, line, col + name.length()), List.of()));
            }
        }
        String classText = lines.get(classLine);
        String className = classText.substring(classText.indexOf("class ") + 6).split("[ {]")[0];
        int nameCol = classText.indexOf(className);
        return List.of(new DocumentSymbol(className, "", SymbolKind.CLASS,
                Range.of(headerStart, 0, lines.size() - 1, 1),
                Range.of(classLine, nameCol, classLine, nameCol + className.length()), members));
    }

    private static Position positionOf(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, offset - lineStart);
    }
}
