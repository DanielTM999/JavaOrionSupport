package dtm.ide.lsp;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenameEditVerifierTest {

    private static final Path COMPANY = Path.of("Company.java").toAbsolutePath();
    private static final Path FUNCIONARIO = Path.of("Funcionario.java").toAbsolutePath();
    private static final Path COLABORADOR = Path.of("Colaborador.java").toAbsolutePath();

    private static final String COMPANY_DISK = String.join("\r\n",
            "package demo;",
            "",
            "import java.util.List;",
            "",
            "public class Company {",
            "    private List<Funcionario> funcionarios;",
            "    private String descricao = \"Gestão de funcionários — ação\";",
            "",
            "    public Company(List<Funcionario> funcionarios) {",
            "        this.funcionarios = funcionarios;",
            "    }",
            "",
            "    public Funcionario primeiro() {",
            "        return funcionarios.get(0);",
            "    }",
            "}",
            "");

    private static final TextEdit JDTLS_COMPANY_EDIT = new TextEdit(Range.of(5, 17, 12, 22),
            "Colaborador> funcionarios;\r\n"
                    + "    private String descricao = \"Gestão de funcionários — ação\";\r\n"
                    + "\r\n"
                    + "    public Company(List<Colaborador> funcionarios) {\r\n"
                    + "        this.funcionarios = funcionarios;\r\n"
                    + "    }\r\n"
                    + "\r\n"
                    + "    public Colaborador");

    private static IdeWorkspaceEdit workspace(IdeWorkspaceEdit.Operation... operations) {
        return new IdeWorkspaceEdit(List.of(operations));
    }

    @Test
    void theWideEditFromTheServerBecomesOneEditPerName() {
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY, List.of(JDTLS_COMPANY_EDIT)));

        RenameEditVerifier.Result result = RenameEditVerifier.verify(edit, "Funcionario", "Colaborador",
                Map.of(COMPANY, COMPANY_DISK)::get);

        assertFalse(result.rejected(), result.problem());
        List<TextEdit> edits = result.edit().editsFor(COMPANY);
        assertEquals(List.of(
                new TextEdit(Range.of(5, 17, 5, 28), "Colaborador"),
                new TextEdit(Range.of(8, 24, 8, 35), "Colaborador"),
                new TextEdit(Range.of(12, 11, 12, 22), "Colaborador")), edits);
        assertEquals(COMPANY_DISK.replace("Funcionario>", "Colaborador>").replace("Funcionario primeiro", "Colaborador primeiro"),
                TextEditApplier.apply(COMPANY_DISK, edits));
    }

    @Test
    void aServerViewOlderThanTheDiskCancelsTheRename() {
        String changedOnDisk = COMPANY_DISK.replace("Gestão de funcionários — ação", "texto novo salvo agora");
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY, List.of(JDTLS_COMPANY_EDIT)));

        RenameEditVerifier.Result result = RenameEditVerifier.verify(edit, "Funcionario", "Colaborador",
                Map.of(COMPANY, changedOnDisk)::get);

        assertTrue(result.rejected());
        assertEquals(COMPANY, result.rejectedFile());
        assertNull(result.edit());
    }

    @Test
    void anEditThatAddsSomethingBesidesTheNameIsCancelled() {
        String disk = "Funcionario f;\n";
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY,
                List.of(new TextEdit(Range.of(0, 0, 0, 14), "Colaborador f; int extra;"))));

        assertTrue(RenameEditVerifier.verify(edit, "Funcionario", "Colaborador", Map.of(COMPANY, disk)::get).rejected());
    }

    @Test
    void longerNamesThatStartWithTheOldNameAreLeftAlone() {
        String disk = "FuncionarioRepository r; Funcionario f;";
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY,
                List.of(new TextEdit(Range.of(0, 0, 0, 36), "FuncionarioRepository r; Colaborador"))));

        RenameEditVerifier.Result result = RenameEditVerifier.verify(edit, "Funcionario", "Colaborador",
                Map.of(COMPANY, disk)::get);

        assertFalse(result.rejected(), result.problem());
        assertEquals(List.of(new TextEdit(Range.of(0, 25, 0, 36), "Colaborador")), result.edit().editsFor(COMPANY));
    }

    @Test
    void aServerThatRenamesPartOfALongerNameIsCancelled() {
        String disk = "FuncionarioRepository r;";
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY,
                List.of(new TextEdit(Range.of(0, 0, 0, 11), "Colaborador"))));

        assertTrue(RenameEditVerifier.verify(edit, "Funcionario", "Colaborador", Map.of(COMPANY, disk)::get).rejected());
    }

    @Test
    void aNewNameThatExtendsTheOldOneStillFindsTheRightOccurrences() {
        String disk = "FooBar a; Foo b; Foo c;";
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY,
                List.of(new TextEdit(Range.of(0, 0, 0, 23), "FooBar a; FooBar b; FooBar c;"))));

        RenameEditVerifier.Result result = RenameEditVerifier.verify(edit, "Foo", "FooBar", Map.of(COMPANY, disk)::get);

        assertFalse(result.rejected(), result.problem());
        assertEquals(List.of(
                new TextEdit(Range.of(0, 10, 0, 13), "FooBar"),
                new TextEdit(Range.of(0, 17, 0, 20), "FooBar")), result.edit().editsFor(COMPANY));
    }

    @Test
    void aFieldWithTheSameNameAsTheRenamedParameterKeepsItsName() {
        String disk = "    this.nome = nome;";
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY,
                List.of(new TextEdit(Range.of(0, 9, 0, 20), "nome = novo"))));

        RenameEditVerifier.Result result = RenameEditVerifier.verify(edit, "nome", "novo", Map.of(COMPANY, disk)::get);

        assertFalse(result.rejected(), result.problem());
        assertEquals(List.of(new TextEdit(Range.of(0, 16, 0, 20), "novo")), result.edit().editsFor(COMPANY));
    }

    @Test
    void renamingAFieldMayAlsoRenameItsAccessorsLikeEclipseDoes() {
        String disk = "return getNome() + isAtivo() + nome; setNome(x);";
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY,
                List.of(new TextEdit(Range.of(0, 7, 0, 44),
                        "getNomeCompleto() + isAtivo() + nomeCompleto; setNomeCompleto"))));

        RenameEditVerifier.Result result = RenameEditVerifier.verify(edit, "nome", "nomeCompleto",
                Map.of(COMPANY, disk)::get);

        assertFalse(result.rejected(), result.problem());
        assertEquals("return getNomeCompleto() + isAtivo() + nomeCompleto; setNomeCompleto(x);",
                TextEditApplier.apply(disk, result.edit().editsFor(COMPANY)));
    }

    @Test
    void anAccessorOfAnotherFieldIsNotAcceptedAsPartOfTheRename() {
        String disk = "getIdade();";
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY,
                List.of(new TextEdit(Range.of(0, 0, 0, 8), "getNomeCompleto"))));

        assertTrue(RenameEditVerifier.verify(edit, "nome", "nomeCompleto", Map.of(COMPANY, disk)::get).rejected());
    }

    @Test
    void positionsFollowTheLanguageServerLinesEvenWithDoubledCarriageReturns() {
        String disk = "a\r\r\nFuncionario f;\r\r\n";
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY,
                List.of(new TextEdit(Range.of(2, 0, 2, 11), "Colaborador"))));

        RenameEditVerifier.Result result = RenameEditVerifier.verify(edit, "Funcionario", "Colaborador",
                Map.of(COMPANY, disk)::get);

        assertFalse(result.rejected(), result.problem());
        assertEquals(List.of(new TextEdit(Range.of(2, 0, 2, 11), "Colaborador")), result.edit().editsFor(COMPANY));
    }

    @Test
    void aFileWhoseContentCannotBeReadCancelsTheRename() {
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY,
                List.of(new TextEdit(Range.of(0, 0, 0, 11), "Colaborador"))));

        assertTrue(RenameEditVerifier.verify(edit, "Funcionario", "Colaborador", path -> null).rejected());
    }

    @Test
    void anEditBeyondTheCurrentContentCancelsTheRename() {
        IdeWorkspaceEdit edit = workspace(new IdeWorkspaceEdit.TextEdits(COMPANY,
                List.of(new TextEdit(Range.of(30, 0, 30, 11), "Colaborador"))));

        assertTrue(RenameEditVerifier.verify(edit, "Funcionario", "Colaborador",
                Map.of(COMPANY, "Funcionario f;")::get).rejected());
    }

    @Test
    void theFileMoveIsKeptInItsOriginalOrder() {
        String current = "public class Funcionario {\n    public Funcionario() {}\n}\n";
        IdeWorkspaceEdit edit = workspace(
                new IdeWorkspaceEdit.TextEdits(FUNCIONARIO, List.of(new TextEdit(Range.of(0, 13, 1, 22),
                        "Colaborador {\n    public Colaborador"))),
                new IdeWorkspaceEdit.RenameFile(FUNCIONARIO, COLABORADOR));

        RenameEditVerifier.Result result = RenameEditVerifier.verify(edit, "Funcionario", "Colaborador",
                Map.of(FUNCIONARIO, current)::get);

        assertFalse(result.rejected(), result.problem());
        assertEquals(2, result.edit().operations().size());
        assertInstanceOf(IdeWorkspaceEdit.TextEdits.class, result.edit().operations().get(0));
        IdeWorkspaceEdit.RenameFile move = assertInstanceOf(IdeWorkspaceEdit.RenameFile.class,
                result.edit().operations().get(1));
        assertEquals(COLABORADOR, move.newPath());
        assertEquals("public class Colaborador {\n    public Colaborador() {}\n}\n",
                TextEditApplier.apply(current, result.edit().editsFor(FUNCIONARIO)));
    }

    @Test
    void readsTheOriginalNameFromThePreparedRangeOrFromTheCaret() {
        String text = "package demo.sub;\r\npublic class Funcionario {}";

        assertEquals("demo.sub", JdtLsService.textIn(text, Range.of(0, 8, 0, 16)));
        assertEquals("Funcionario", JdtLsService.identifierAt(text, 1, 17));
        assertEquals("Funcionario", JdtLsService.identifierAt(text, 1, 13));
        assertNull(JdtLsService.identifierAt(text, 9, 0));
    }
}
