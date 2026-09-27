package dtm.ide.lsp;

import dtm.ide.api.extension.Resource;
import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkService;
import dtm.ide.sdk.SdkDownloader;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
@EnabledIfSystemProperty(named = "orion.it.jdtls", matches = "true")
class JdtLsRenameIntegrationTest {

    private static final long READY_TIMEOUT_MS = 300_000;
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @TempDir
    Path workspace;

    private JdtLsService service;

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void renamingAClassTwiceKeepsEveryFileIntactAndCompilable() throws Exception {
        Path project = Files.createDirectories(workspace.resolve("rename"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>demo</groupId><artifactId>rename</artifactId><version>1</version>"
                + "<properties><maven.compiler.source>21</maven.compiler.source>"
                + "<maven.compiler.target>21</maven.compiler.target>"
                + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>");
        Path sources = Files.createDirectories(project.resolve("src/main/java/demo"));

        Path funcionario = sources.resolve("Funcionario.java");
        String funcionarioText = """
                package demo;

                public class Funcionario {
                    private final String nome;

                    public Funcionario(String nome) {
                        this.nome = nome;
                    }

                    public String nome() {
                        return nome;
                    }
                }
                """;
        Files.writeString(funcionario, crlf(funcionarioText));

        Path company = sources.resolve("Company.java");
        Files.writeString(company, crlf("""
                package demo;

                import java.util.List;

                public class Company {
                    private List<Funcionario> funcionarios;
                    private String descricao = "Gestão de funcionários — ação";

                    public Company(List<Funcionario> funcionarios) {
                        this.funcionarios = funcionarios;
                    }

                    public Funcionario primeiro() {
                        return funcionarios.get(0);
                    }
                }
                """), StandardCharsets.UTF_8);

        Path registro = sources.resolve("Registro.java");
        Files.write(registro, concat(BOM, """
                package demo;

                public class Registro {
                    Funcionario registrar(String nome) {
                        Funcionario novo = new Funcionario(nome);
                        return novo;
                    }
                }
                """.getBytes(StandardCharsets.UTF_8)));

        Path intocado = sources.resolve("Intocado.java");
        byte[] intocadoBytes = crlf("""
                package demo;

                public class Intocado {
                    String texto = "sem relação";
                }
                """).getBytes(StandardCharsets.UTF_8);
        Files.write(intocado, intocadoBytes);

        startServer(project);
        service.openDocument(funcionario, funcionarioText);

        Path colaborador = sources.resolve("Colaborador.java");
        String editorText = rename(funcionario, funcionarioText, 2, 13, "Colaborador", colaborador);

        assertTrue(editorText.contains("public class Colaborador {"), editorText);
        assertTrue(editorText.contains("public Colaborador(String nome)"), editorText);
        assertFalse(Files.exists(funcionario), "o arquivo antigo deveria ter sido movido");
        assertTrue(Files.exists(colaborador));
        assertEquals(crlf(funcionarioText), Files.readString(colaborador),
                "o move leva o conteudo do disco; as edicoes do arquivo atual ficam no editor ate salvar");

        Files.writeString(colaborador, editorText);
        service.saveDocument(colaborador, editorText);

        String companyAfter = Files.readString(company, StandardCharsets.UTF_8);
        assertTrue(companyAfter.contains("private List<Colaborador> funcionarios;"), companyAfter);
        assertTrue(companyAfter.contains("public Company(List<Colaborador> funcionarios)"), companyAfter);
        assertTrue(companyAfter.contains("public Colaborador primeiro()"), companyAfter);
        assertTrue(companyAfter.contains("\"Gestão de funcionários — ação\""), companyAfter);
        assertOnlyCarriageReturnLineFeeds(company);

        byte[] registroAfter = Files.readAllBytes(registro);
        assertArrayEquals(BOM, Arrays.copyOf(registroAfter, 3), "o BOM tem que continuar no lugar");
        String registroText = new String(registroAfter, 3, registroAfter.length - 3, StandardCharsets.UTF_8);
        assertTrue(registroText.contains("Colaborador novo = new Colaborador(nome);"), registroText);
        assertFalse(registroText.contains("\r"), "arquivo LF nao pode ganhar CR");

        assertArrayEquals(intocadoBytes, Files.readAllBytes(intocado), "arquivo sem referencia nao pode mudar");
        assertNoDoubledCarriageReturns(sources);
        assertCompiles(sources);

        Path pessoa = sources.resolve("Pessoa.java");
        String secondEditorText = rename(colaborador, editorText, 2, 13, "Pessoa", pessoa);
        Files.writeString(pessoa, secondEditorText);
        service.saveDocument(pessoa, secondEditorText);

        String companySecond = Files.readString(company, StandardCharsets.UTF_8);
        assertTrue(companySecond.contains("private List<Pessoa> funcionarios;"), companySecond);
        assertTrue(companySecond.contains("public Pessoa primeiro()"), companySecond);
        assertTrue(companySecond.contains("\"Gestão de funcionários — ação\""), companySecond);
        assertOnlyCarriageReturnLineFeeds(company);
        assertEquals(companyAfter.replace("Colaborador", "Pessoa"), companySecond,
                "o segundo rename so pode trocar o nome, nada mais");
        assertArrayEquals(intocadoBytes, Files.readAllBytes(intocado));
        assertNoDoubledCarriageReturns(sources);
        assertCompiles(sources);

        String changedBehindTheServer = companySecond.replace("Gestão de funcionários — ação", "alterado fora do servidor");
        Files.writeString(company, changedBehindTheServer, StandardCharsets.UTF_8);
        IdeWorkspaceEdit stale = service.renameWorkspace(pessoa, secondEditorText, 2, 13, "Gente");
        System.out.println("[rename-it] rename sobre disco alterado: vazio=" + stale.isEmpty()
                + " motivo=" + service.lastRenameProblem());
        describe(stale);
        if (stale.isEmpty()) {
            assertNotNull(service.lastRenameProblem(), "rename vazio sem motivo registrado");
        } else {
            List<TextEdit> companyEdits = stale.editsFor(company);
            String applied = TextEditApplier.apply(changedBehindTheServer, companyEdits);
            assertTrue(applied.contains("alterado fora do servidor"),
                    "o rename desfez uma alteracao feita no disco: " + applied);
            assertEquals(changedBehindTheServer.replace("Pessoa", "Gente"), applied);
        }
        assertEquals(changedBehindTheServer, Files.readString(company, StandardCharsets.UTF_8));
    }

    @Test
    void renamingMembersKeepsEveryFileIntactAndCompilable() throws Exception {
        Path project = Files.createDirectories(workspace.resolve("members"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>demo</groupId><artifactId>members</artifactId><version>1</version>"
                + "<properties><maven.compiler.source>21</maven.compiler.source>"
                + "<maven.compiler.target>21</maven.compiler.target>"
                + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>");
        Path sources = Files.createDirectories(project.resolve("src/main/java/demo"));

        Path identificavel = sources.resolve("Identificavel.java");
        Files.writeString(identificavel, crlf("""
                package demo;

                public interface Identificavel {
                    String identificador();
                }
                """));
        Path pessoa = sources.resolve("Pessoa.java");
        Files.writeString(pessoa, crlf("""
                package demo;

                public class Pessoa implements Identificavel {
                    private String nome;
                    private Status status = Status.ATIVO;

                    public Pessoa(String nome) {
                        this.nome = nome;
                    }

                    public String getNome() {
                        return nome;
                    }

                    @Override
                    public String identificador() {
                        return "p:" + nome;
                    }

                    public String saudacao(String prefixo) {
                        String resultado = prefixo + " " + nome + " (ação)";
                        return resultado;
                    }
                }
                """), StandardCharsets.UTF_8);
        Path cliente = sources.resolve("Cliente.java");
        Files.writeString(cliente, """
                package demo;

                public class Cliente extends Pessoa {
                    public Cliente(String nome) {
                        super(nome);
                    }

                    @Override
                    public String identificador() {
                        return "c:" + getNome();
                    }
                }
                """);
        Path statusFile = sources.resolve("Status.java");
        Files.writeString(statusFile, crlf("""
                package demo;

                public enum Status {
                    ATIVO,
                    INATIVO
                }
                """));
        Path uso = sources.resolve("Uso.java");
        Files.writeString(uso, crlf("""
                package demo;

                import java.util.List;

                public class Uso {
                    String rodar(List<Identificavel> itens) {
                        StringBuilder todos = new StringBuilder();
                        for (Identificavel item : itens) {
                            todos.append(item.identificador());
                        }
                        Pessoa p = new Cliente("Ana");
                        Status s = Status.ATIVO;
                        return todos + p.saudacao("Oi") + p.identificador() + s;
                    }
                }
                """));
        byte[] identificavelBefore = Files.readAllBytes(identificavel);

        startServer(project);

        renameInPlace(pessoa, "private String nome;", "nome", "nomeCompleto");
        String pessoaText = Files.readString(pessoa, StandardCharsets.UTF_8);
        assertTrue(pessoaText.contains("private String nomeCompleto;"), pessoaText);
        assertTrue(pessoaText.contains("this.nomeCompleto = nome;"), "o parametro do construtor nao e o field: " + pessoaText);
        assertTrue(pessoaText.contains("public Pessoa(String nome)"), pessoaText);
        assertTrue(pessoaText.contains("return nomeCompleto;"), pessoaText);
        String clienteText = Files.readString(cliente);
        boolean getterRenamed = pessoaText.contains("public String getNomeCompleto()");
        System.out.println("[rename-it] getter renomeado junto com o field: " + getterRenamed);
        assertEquals(getterRenamed, clienteText.contains("getNomeCompleto()"),
                "getter e chamadas precisam concordar: " + clienteText);
        assertTrue(getterRenamed || pessoaText.contains("public String getNome()"), pessoaText);
        assertTrue(clienteText.contains("super(nome);"), "o parametro do Cliente nao e o field");
        assertArrayEquals(identificavelBefore, Files.readAllBytes(identificavel));
        assertFilesHealthy(sources);

        renameInPlace(identificavel, "String identificador();", "identificador", "codigo");
        assertTrue(Files.readString(identificavel).contains("String codigo();"));
        assertTrue(Files.readString(pessoa, StandardCharsets.UTF_8).contains("public String codigo() {"));
        assertTrue(Files.readString(cliente).contains("public String codigo() {"), "override em outra classe");
        String usoText = Files.readString(uso);
        assertTrue(usoText.contains("todos.append(item.codigo());"), usoText);
        assertTrue(usoText.contains("p.codigo() + s;"), usoText);
        assertFalse(usoText.contains("identificador"), usoText);
        assertFilesHealthy(sources);

        renameInPlace(pessoa, "public String saudacao(String prefixo)", "prefixo", "inicio");
        pessoaText = Files.readString(pessoa, StandardCharsets.UTF_8);
        assertTrue(pessoaText.contains("public String saudacao(String inicio)"), pessoaText);
        assertTrue(pessoaText.contains("String resultado = inicio + \" \" + nomeCompleto + \" (ação)\";"), pessoaText);
        assertFilesHealthy(sources);

        renameInPlace(pessoa, "String resultado =", "resultado", "texto");
        pessoaText = Files.readString(pessoa, StandardCharsets.UTF_8);
        assertTrue(pessoaText.contains("String texto = inicio"), pessoaText);
        assertTrue(pessoaText.contains("return texto;"), pessoaText);
        assertFalse(pessoaText.contains("resultado"), pessoaText);
        assertFilesHealthy(sources);

        renameInPlace(statusFile, "    ATIVO,", "ATIVO", "HABILITADO");
        assertTrue(Files.readString(statusFile).contains("    HABILITADO,"));
        assertTrue(Files.readString(statusFile).contains("    INATIVO"), "a outra constante nao muda");
        assertTrue(Files.readString(pessoa, StandardCharsets.UTF_8).contains("Status.HABILITADO;"));
        assertTrue(Files.readString(uso).contains("Status s = Status.HABILITADO;"));
        assertFilesHealthy(sources);
    }

    private void renameInPlace(Path file, String context, String name, String newName) throws Exception {
        String text = LspConversions.normalizeLineBreaks(Files.readString(file, StandardCharsets.UTF_8));
        int contextAt = text.indexOf(context);
        assertTrue(contextAt >= 0, "contexto nao encontrado: " + context);
        int offset = text.indexOf(name, contextAt);
        int line = (int) text.substring(0, offset).chars().filter(c -> c == '\n').count();
        int col = offset - (text.lastIndexOf('\n', offset - 1) + 1);

        service.openDocument(file, text);
        String editor = rename(file, text, line, col, newName, null);
        assertTrue(editor.contains(newName), "o arquivo atual deveria ter o nome novo: " + editor);

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, raw.contains("\r\n") ? editor.replace("\n", "\r\n") : editor, StandardCharsets.UTF_8);
        service.saveDocument(file, editor);
        service.closeDocument(file);
        Thread.sleep(1_500);
    }

    private void assertFilesHealthy(Path sources) throws IOException {
        assertNoDoubledCarriageReturns(sources);
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains("\r\n")) {
                    assertOnlyCarriageReturnLineFeeds(file);
                } else {
                    assertFalse(text.contains("\r"), file + " ganhou CR");
                }
            }
        }
        assertCompiles(sources);
    }

    private String rename(Path file, String editorText, int line, int col, String newName, Path expectedTarget)
            throws Exception {
        IdeWorkspaceEdit edit = service.renameWorkspace(file, editorText, line, col, newName);
        assertNotNull(edit);
        assertFalse(edit.isEmpty(), "o JDT LS nao devolveu edicoes para o rename de " + file.getFileName()
                + ":" + line + ":" + col + " motivo=" + service.lastRenameProblem());
        describe(edit);

        Path current = file.toAbsolutePath().normalize();
        String editor = editorText;
        boolean currentMoved = false;
        Map<Path, Path> moves = new LinkedHashMap<>();
        for (IdeWorkspaceEdit.Operation operation : edit.operations()) {
            if (operation instanceof IdeWorkspaceEdit.TextEdits textEdits) {
                for (TextEdit textEdit : textEdits.edits()) {
                    assertFalse(textEdit.newText().contains("\r"),
                            "newText com CR chegando ao IDE: " + textEdit);
                }
                Path target = textEdits.file().toAbsolutePath().normalize();
                if (!currentMoved && target.equals(current)) {
                    editor = TextEditApplier.apply(editor, textEdits.edits());
                    continue;
                }
                if (currentMoved && target.equals(moves.get(current))) {
                    editor = TextEditApplier.apply(editor, textEdits.edits());
                    continue;
                }
                String disk = Files.readString(target, StandardCharsets.UTF_8);
                boolean bom = !disk.isEmpty() && disk.charAt(0) == '﻿';
                String body = bom ? disk.substring(1) : disk;
                String updated = TextEditApplier.apply(body, textEdits.edits());
                Files.writeString(target, bom ? '﻿' + updated : updated, StandardCharsets.UTF_8);
                service.pathChanged(target);
            } else if (operation instanceof IdeWorkspaceEdit.RenameFile renameFile) {
                Path from = renameFile.oldPath().toAbsolutePath().normalize();
                Path to = renameFile.newPath().toAbsolutePath().normalize();
                Files.move(from, to);
                moves.put(from, to);
                if (from.equals(current)) {
                    currentMoved = true;
                }
            }
        }
        if (expectedTarget == null) {
            assertFalse(currentMoved, "rename de membro nao pode mover o arquivo");
            assertTrue(moves.isEmpty(), "rename de membro nao pode mover arquivos: " + moves);
            return editor;
        }
        assertTrue(currentMoved, "o rename da classe publica deveria mover o arquivo");
        Path moved = moves.get(current);
        assertEquals(expectedTarget.toAbsolutePath().normalize(), moved);

        service.pathDeleted(current);
        service.closeDocument(current);
        service.pathCreated(moved);
        service.openDocument(moved, editor);
        Thread.sleep(2_000);
        return editor;
    }

    private static void describe(IdeWorkspaceEdit edit) {
        for (IdeWorkspaceEdit.Operation operation : edit.operations()) {
            if (operation instanceof IdeWorkspaceEdit.TextEdits textEdits) {
                System.out.println("[rename-it] TextEdits " + textEdits.file().getFileName());
                for (TextEdit textEdit : textEdits.edits()) {
                    System.out.println("[rename-it]   " + textEdit.range() + " -> "
                            + textEdit.newText().replace("\n", "\\n"));
                }
            } else if (operation instanceof IdeWorkspaceEdit.RenameFile renameFile) {
                System.out.println("[rename-it] RenameFile " + renameFile.oldPath().getFileName()
                        + " -> " + renameFile.newPath().getFileName());
            }
        }
    }

    private static void assertOnlyCarriageReturnLineFeeds(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        long lineFeeds = text.chars().filter(c -> c == '\n').count();
        long pairs = text.split("\r\n", -1).length - 1L;
        long carriageReturns = text.chars().filter(c -> c == '\r').count();
        assertEquals(lineFeeds, pairs, file + " perdeu CRLF em alguma linha");
        assertEquals(lineFeeds, carriageReturns, file + " ganhou CR extra");
    }

    private static void assertNoDoubledCarriageReturns(Path sources) throws IOException {
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.toList()) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(text.contains("\r\r"), file + " ficou com CR duplicado");
            }
        }
    }

    private void assertCompiles(Path sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "a suite precisa rodar sobre uma JDK");
        Path output = Files.createTempDirectory(workspace, "classes");
        Path copies = Files.createTempDirectory(workspace, "sources");
        List<String> arguments = new ArrayList<>(List.of("-encoding", "UTF-8", "-d", output.toString()));
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.toList()) {
                byte[] bytes = Files.readAllBytes(file);
                boolean bom = bytes.length >= 3 && Arrays.equals(Arrays.copyOf(bytes, 3), BOM);
                Path copy = copies.resolve(file.getFileName());
                Files.write(copy, bom ? Arrays.copyOfRange(bytes, 3, bytes.length) : bytes);
                arguments.add(copy.toString());
            }
        }
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = compiler.run(null, null, errors, arguments.toArray(String[]::new));
        assertEquals(0, status, errors.toString(StandardCharsets.UTF_8));
    }

    private void startServer(Path project) throws Exception {
        JdkService jdks = new JdkService(resourceAt(workspace.resolve("plugin")), null);
        var jdk = jdks.languageServerJdk();
        assumeTrue(jdk.isPresent(), "a JDK is required");
        Path sdk = integrationSdk(jdks);
        var provisioner = new JdtLsProvisioner(new SdkDownloader(null), sdk);
        assumeTrue(provisioner.find().isPresent(), "set -Dorion.it.sdk to an SDK with JDT LS installed");
        service = new JdtLsService(jdks, provisioner, null, null);
        service.start(project, jdk.get(), DownloadProgressListener.NOOP).join();
        assertTrue(service.awaitReady(READY_TIMEOUT_MS), service.getLastError());
        await(() -> !service.isWarmingUp(), 60_000);
    }

    private static String crlf(String text) {
        return text.replace("\n", "\r\n");
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static Path integrationSdk(JdkService jdks) {
        String configured = System.getProperty("orion.it.sdk", "");
        return configured.isBlank() ? jdks.sdkRoot() : Path.of(configured).toAbsolutePath().normalize();
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "condicao nao atingida a tempo");
    }

    private static Resource resourceAt(Path directory) throws IOException {
        Files.createDirectories(directory);
        return new Resource() {
            @Override
            public Path getResourcePath() {
                return directory;
            }

            @Override
            public Path getResourcePath(String path) {
                return directory.resolve(path);
            }

            @Override
            public Path getResourcePath(Path path) {
                return directory.resolve(path);
            }

            @Override
            public URL getResource(String name) {
                return null;
            }

            @Override
            public List<URL> getResources(Collection<String> name) {
                return List.of();
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return null;
            }

            @Override
            public List<InputStream> getResourcesAsStreams(Collection<String> name) {
                return List.of();
            }

            @Override
            public Path getSharedResourcePath() {
                return directory;
            }

            @Override
            public URL getSharedResource(String name) {
                return null;
            }

            @Override
            public List<URL> getSharedResources(Collection<String> name) {
                return List.of();
            }

            @Override
            public InputStream getSharedResourceAsStream(String name) {
                return null;
            }

            @Override
            public List<InputStream> getSharedResourcesAsStreams(Collection<String> name) {
                return List.of();
            }
        };
    }
}
