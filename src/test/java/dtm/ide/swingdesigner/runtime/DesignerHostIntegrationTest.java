package dtm.ide.swingdesigner.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dtm.ide.swingdesigner.catalog.InjectionRule;
import dtm.ide.swingdesigner.catalog.PropertyDescriptor;
import dtm.ide.swingdesigner.recovery.LifecycleRecovery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class DesignerHostIntegrationTest {

    private static final Map<String, String> SOURCES = Map.of(
            "demo/Painel.java", """
                    package demo;
                    import javax.swing.*;
                    import java.awt.*;
                    public class Painel extends JPanel {
                        private final JLabel titulo;
                        private final JButton salvar = new JButton("Salvar");
                        public Painel(String texto, int linhas) {
                            super(new BorderLayout());
                            System.out.println("construindo painel");
                            titulo = new JLabel("Titulo: " + texto);
                            add(titulo, BorderLayout.NORTH);
                            add(new JScrollPane(new JTextArea(linhas, 20)), BorderLayout.CENTER);
                            add(salvar, BorderLayout.SOUTH);
                            setPreferredSize(new Dimension(320, 200));
                        }
                    }
                    """,
            "demo/Quebra.java", """
                    package demo;
                    public class Quebra extends javax.swing.JPanel {
                        public Quebra() { throw new IllegalStateException("sem banco"); }
                    }
                    """,
            "demo/BaseActivity.java", """
                    package demo;
                    public abstract class BaseActivity extends javax.swing.JFrame {
                        private boolean drawn;
                        protected final void dispatchDrawing() {
                            if (!drawn) {
                                drawn = true;
                                onDrawing();
                            }
                        }
                        protected void onDrawing() { }
                    }
                    """,
            "demo/Principal.java", """
                    package demo;
                    import javax.swing.*;
                    import java.awt.BorderLayout;
                    public class Principal extends BaseActivity {
                        private final Controlador controller;
                        public Principal(Controlador controller) { this.controller = controller; }
                        @Override
                        protected void onDrawing() {
                            setTitle("Principal");
                            getContentPane().add(new JLabel("cabecalho"), BorderLayout.NORTH);
                            controller.iniciar();
                            getContentPane().add(new JButton("nunca"), BorderLayout.CENTER);
                        }
                    }
                    """,
            "demo/Assincrona.java", """
                    package demo;
                    import javax.swing.*;
                    public class Assincrona extends JPanel {
                        public Assincrona() {
                            SwingUtilities.invokeLater(() -> add(new JLabel("tarde")));
                        }
                    }
                    """,
            "demo/Tela.java", """
                    package demo;
                    import javax.swing.*;
                    public class Tela extends JFrame {
                        public Tela() {
                            super("Minha tela");
                            setDefaultCloseOperation(EXIT_ON_CLOSE);
                            JMenuBar barra = new JMenuBar();
                            barra.add(new JMenu("Arquivo"));
                            setJMenuBar(barra);
                            getContentPane().add(new Painel("x", 3));
                            pack();
                        }
                    }
                    """);

    private static final Map<String, String> EXTRA_SOURCES = Map.of(
            "demo/Controlador.java", """
                    package demo;
                    public class Controlador {
                        public Controlador(String nome) { }
                        public void iniciar() { }
                        public javax.swing.JPanel criarBarra() { return new javax.swing.JPanel(); }
                    }
                    """,
            "demo/Valor.java", """
                    package demo;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.FIELD)
                    public @interface Valor {
                        String chave();
                        String padrao() default "";
                    }
                    """,
            "demo/ComValor.java", """
                    package demo;
                    import javax.swing.*;
                    public class ComValor extends JPanel {
                        @Valor(chave = "titulo", padrao = "Ola")
                        private String titulo;
                        @Valor(chave = "colunas", padrao = "7")
                        private int colunas;
                        protected void montar() {
                            add(new JLabel(titulo.toUpperCase() + colunas));
                        }
                    }
                    """,
            "demo/Servico.java", """
                    package demo;
                    public interface Servico {
                        String nome();
                        java.util.List<String> itens();
                        Servico filho();
                    }
                    """,
            "demo/ComServico.java", """
                    package demo;
                    import javax.swing.*;
                    public class ComServico extends JPanel {
                        private Servico servico;
                        protected void montar() {
                            add(new JLabel("[" + servico.nome() + servico.itens().size() + servico.filho().nome() + "]"));
                        }
                    }
                    """,
            "demo/Recuperavel.java", """
                    package demo;
                    import javax.swing.*;
                    import java.awt.BorderLayout;
                    public class Recuperavel extends BaseActivity {
                        private Controlador controller;
                        @Override
                        protected void onDrawing() {
                            getContentPane().add(new JLabel("topo"), BorderLayout.NORTH);
                            controller.iniciar();
                            String local = "x";
                            getContentPane().add(new JLabel(local));
                            JPanel barra = controller.criarBarra();
                            barra.add(new JLabel("menu"));
                            getContentPane().add(barra, BorderLayout.WEST);
                            montarCorpo();
                            this.montarRodape("fim", 3);
                        }
                        private void montarCorpo() {
                            getContentPane().add(new JButton("corpo"), BorderLayout.CENTER);
                        }
                        private void montarRodape(String texto, int altura) {
                            getContentPane().add(new JLabel(texto + altura), BorderLayout.SOUTH);
                        }
                    }
                    """);

    @TempDir
    static Path root;

    private static DesignerHostProcess host;
    private static SwingViewClient client;
    private static final List<String> logs = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startHost() throws IOException {
        assumeFalse(GraphicsEnvironment.isHeadless(), "precisa de display");
        Path classes = compile();
        Path hostJar = HostJar.ensure(root.resolve("cache"));
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        host = DesignerHostProcess.start(java, hostJar, logs::add, Duration.ofSeconds(30));
        client = new SwingViewClient(host);
        client.init(List.of(), List.of(classes), null);
    }

    @AfterAll
    static void stopHost() {
        if (host != null) {
            host.close();
        }
    }

    @Test
    void rendersAHandWrittenPanelWhoseConstructorNeedsArguments() {
        ViewResult result = client.view("demo.Painel", null, -1, -1);

        assertFalse(result.failed(), () -> result.error() + " " + result.attempts());
        assertNotNull(result.image());
        assertEquals(320, result.width());
        assertEquals(200, result.height());
        assertEquals(List.of("java.lang.String", "int"), result.constructor().types());
        assertEquals("demo.Painel", result.root().className());
        List<String> fields = new ArrayList<>();
        result.root().forEach(node -> {
            if (node.field() != null) {
                fields.add(node.field());
            }
        });
        assertTrue(fields.containsAll(List.of("titulo", "salvar")), fields::toString);
        assertTrue(result.root().children().stream()
                .anyMatch(child -> child.className().equals("javax.swing.JScrollPane")
                        && child.children().getFirst().className().equals("javax.swing.JTextArea")));
        assertTrue(logs.contains("construindo painel"), logs::toString);
    }

    @Test
    void constructorArgumentsCanBeEdited() {
        ViewResult first = client.view("demo.Painel", null, -1, -1);
        ConstructorUse edited = first.constructor()
                .withValue(0, JsonNodeFactory.instance.textNode("Clientes"));

        ViewResult second = client.view("demo.Painel", edited, -1, -1);
        SnapshotNode titulo = second.root().children().stream()
                .filter(node -> "titulo".equals(node.field())).findFirst().orElseThrow();
        SwingViewClient.Inspection inspection = client.inspect(titulo.id(), List.of(
                new PropertyDescriptor("text", "java.lang.String", "setText", null, null, "getText",
                        null, null, null, null, null, null, null, null, null)));

        assertEquals("Titulo: Clientes", inspection.values().get("text").asText());
    }

    @Test
    void propertiesAreAppliedLive() {
        ViewResult view = client.view("demo.Painel", null, -1, -1);
        SnapshotNode salvar = view.root().children().stream()
                .filter(node -> "salvar".equals(node.field())).findFirst().orElseThrow();

        client.setProperty(salvar.id(), "setText", List.of("java.lang.String"),
                List.of(JsonNodeFactory.instance.textNode("Gravar")));
        client.setProperty(salvar.id(), "setBackground", List.of("java.awt.Color"),
                List.of(JsonNodeFactory.instance.textNode("#ff0000")));
        SwingViewClient.Inspection inspection = client.inspect(salvar.id(), List.of(
                property("text", "getText"), property("background", "getBackground")));

        assertEquals("Gravar", inspection.values().get("text").asText());
        assertEquals("#ff0000", inspection.values().get("background").asText());
    }

    @Test
    void designerStatementsRunLiveAgainstBoundNodes() {
        ViewResult view = client.view("demo.Painel", null, -1, -1);
        assertEquals("java.awt.BorderLayout", view.root().layoutClass());
        SnapshotNode salvar = view.root().children().stream()
                .filter(node -> "salvar".equals(node.field())).findFirst().orElseThrow();
        assertTrue(salvar.ownField());
        assertEquals("South", salvar.constraints().asText());

        String header = "package demo;\nimport javax.swing.*;\nimport java.awt.*;\n";
        List<com.fasterxml.jackson.databind.node.ObjectNode> statements =
                dtm.ide.swingdesigner.recovery.RecoveryPlanner.translate(header, "demo.Painel", List.of(
                        "extra = new JCheckBox(\"Ativo\");", "extra.setSelected(true);",
                        "add(extra, BorderLayout.WEST);", "botao.setText(\"Gravar\");"), java.util.Set.of());
        ViewResult live = client.interpret(statements, List.of(), Map.of("botao", salvar.id()), false).view();

        SnapshotNode added = live.root().children().stream()
                .filter(node -> node.className().equals("javax.swing.JCheckBox")).findFirst().orElseThrow();
        assertEquals("West", added.constraints().asText());
        SwingViewClient.Inspection text = client.inspect(salvar.id(), List.of(property("text", "getText")));
        assertEquals("Gravar", text.values().get("text").asText());

        ViewResult removed = client.interpret(dtm.ide.swingdesigner.recovery.RecoveryPlanner.translate(header,
                        "demo.Painel", List.of("designerParent.remove(designerChild);"), java.util.Set.of()),
                List.of(), Map.of("designerParent", live.root().id(), "designerChild", added.id()), false).view();
        assertTrue(removed.root().children().stream()
                .noneMatch(node -> node.className().equals("javax.swing.JCheckBox")));
    }

    @Test
    void framesRenderTheirRootPaneWithMenuBarButAreInspectedAsTheWindow() {
        ViewResult result = client.view("demo.Tela", null, -1, -1);

        assertFalse(result.failed(), () -> result.error() + " " + result.attempts());
        assertTrue(result.window());
        assertEquals("Minha tela", result.title());
        assertEquals("demo.Tela", result.root().className());
        assertEquals("javax.swing.JMenuBar", result.root().children().getFirst().className());
        assertTrue(result.width() > 100 && result.height() > 100);
        SwingViewClient.Inspection inspection = client.inspect(result.root().id(), List.of(
                property("title", "getTitle"), property("defaultCloseOperation", "getDefaultCloseOperation")));
        assertEquals("Minha tela", inspection.values().get("title").asText());
        assertEquals(3, inspection.values().get("defaultCloseOperation").asInt());
    }

    @Test
    void constructionFailuresBecomeErrorsInsteadOfCrashingTheHost() {
        ViewResult broken = client.view("demo.Quebra", null, -1, -1);

        assertTrue(broken.failed());
        assertNull(broken.image());
        assertTrue(broken.error().contains("sem banco"), broken.error());
        assertTrue(broken.attempts().stream().anyMatch(attempt -> attempt.contains("Quebra()")));
        assertFalse(client.view("demo.Painel", null, -1, -1).failed());
    }

    @Test
    void workspaceReloadPicksUpRecompiledClasses() throws IOException {
        Path classes = root.resolve("classes");
        client.reload(List.of(classes));

        assertFalse(client.view("demo.Painel", null, 200, 120).failed());
        assertEquals(200, client.render().width());
    }

    @Test
    void lifecycleHooksBuildTheScreenAndFailuresKeepThePartialTree() {
        ViewResult lazy = client.view("demo.Principal", null, List.of(), -1, -1);

        assertFalse(lazy.failed());
        assertTrue(lazy.root().children().stream().allMatch(child -> child.children().isEmpty()));
        assertTrue(lazy.warnings().stream().anyMatch(warning -> warning.text().contains("designInit")),
                lazy.warnings()::toString);

        ViewResult drawn = client.view("demo.Principal", null, List.of("dispatchDrawing"), -1, -1);
        List<String> classes = new ArrayList<>();
        drawn.root().forEach(node -> classes.add(node.className()));

        assertFalse(drawn.failed());
        assertEquals("Principal", drawn.title());
        assertTrue(classes.contains("javax.swing.JLabel"), classes::toString);
        assertFalse(classes.contains("javax.swing.JButton"), classes::toString);
        assertEquals(1, drawn.errors().size(), drawn.warnings()::toString);
        ViewWarning failure = drawn.errors().getFirst();
        assertTrue(failure.message().contains("NullPointerException"), failure::toString);
        assertTrue(failure.text().contains("Principal.onDrawing(Principal.java:"), failure::text);
        assertTrue(failure.hint().contains("controller"), failure::toString);
        assertTrue(failure.stack().contains("NullPointerException"));
    }

    @Test
    void unknownLifecycleHooksAreReported() {
        ViewResult result = client.view("demo.Painel", null, List.of("naoExiste"), -1, -1);

        assertFalse(result.failed());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.text().contains("naoExiste")),
                result.warnings()::toString);
    }

    @Test
    void componentsQueuedOnTheEventThreadAreRendered() {
        ViewResult result = client.view("demo.Assincrona", null, 200, 100);

        assertFalse(result.failed());
        assertEquals("javax.swing.JLabel", result.root().children().getFirst().className());
    }

    @Test
    void annotatedDefaultsAreInjectedBeforeTheLifecycleRuns() {
        ViewOptions options = new ViewOptions(List.of("montar"),
                List.of(new InjectionRule("demo.Valor", "padrao", null)), Map.of(), true);

        ViewResult result = client.view("demo.ComValor", null, options, 200, 60);

        assertTrue(result.errors().isEmpty(), result.warnings()::toString);
        assertEquals("javax.swing.JLabel", result.root().children().getFirst().className());
        SwingViewClient.Inspection label = client.inspect(result.root().children().getFirst().id(),
                List.of(property("text", "getText")));
        assertEquals("OLA7", label.values().get("text").asText());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.text().contains("titulo=Ola")),
                result.warnings()::toString);
    }

    @Test
    void explicitDesignValuesWinOverAnnotationDefaults() {
        ViewOptions options = new ViewOptions(List.of("montar"),
                List.of(new InjectionRule("demo.Valor", "padrao", null)),
                Map.of("titulo", JsonNodeFactory.instance.textNode("Clientes")), true);

        ViewResult result = client.view("demo.ComValor", null, options, 200, 60);
        SwingViewClient.Inspection label = client.inspect(result.root().children().getFirst().id(),
                List.of(property("text", "getText")));

        assertEquals("CLIENTES7", label.values().get("text").asText());
    }

    @Test
    void interfaceDependenciesReceiveDesignStubs() {
        ViewResult stubbed = client.view("demo.ComServico", null,
                new ViewOptions(List.of("montar"), List.of(), Map.of(), true), 200, 60);
        SwingViewClient.Inspection label = client.inspect(stubbed.root().children().getFirst().id(),
                List.of(property("text", "getText")));
        ViewResult plain = client.view("demo.ComServico", null,
                new ViewOptions(List.of("montar"), List.of(), Map.of(), false), 200, 60);

        assertTrue(stubbed.errors().isEmpty(), stubbed.warnings()::toString);
        assertEquals("[0]", label.values().get("text").asText());
        assertTrue(stubbed.warnings().stream().anyMatch(warning -> warning.text().contains("servico (Servico)")),
                stubbed.warnings()::toString);
        assertEquals(1, plain.errors().size(), plain.warnings()::toString);
    }

    @Test
    void recoveryRunsTheSelfCallsAfterTheFailingLine() {
        ViewResult initial = client.view("demo.Recuperavel", null,
                new ViewOptions(List.of("dispatchDrawing"), List.of(), Map.of(), false), -1, -1);
        Path sources = root.resolve("src");

        ViewResult recovered = LifecycleRecovery.using(
                owner -> owner.startsWith("demo."),
                className -> {
                    Path file = sources.resolve(className.replace('.', '/') + ".java");
                    return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
                },
                client, false).run(initial);

        List<String> classes = new ArrayList<>();
        recovered.root().forEach(node -> classes.add(node.className()));
        assertTrue(classes.contains("javax.swing.JButton"), classes::toString);
        assertEquals(4, classes.stream().filter("javax.swing.JLabel"::equals).count(), classes::toString);
        assertTrue(recovered.warnings().stream().anyMatch(warning -> warning.text().contains(
                "Valores de design para: barra (JPanel)")), recovered.warnings()::toString);
        assertTrue(recovered.warnings().stream().anyMatch(warning -> warning.isError()
                && warning.text().contains("controller.criarBarra()")), recovered.warnings()::toString);
    }

    private static PropertyDescriptor property(String name, String getter) {
        return new PropertyDescriptor(name, null, null, null, null, getter, null, null, null,
                null, null, null, null, null, null);
    }

    private static Path compile() throws IOException {
        Path src = root.resolve("src");
        Path out = root.resolve("classes");
        Files.createDirectories(out);
        List<String> arguments = new ArrayList<>(List.of("-g", "-d", out.toString(), "--release", "8",
                "-Xlint:-options"));
        Map<String, String> all = new java.util.LinkedHashMap<>(SOURCES);
        all.putAll(EXTRA_SOURCES);
        for (Map.Entry<String, String> source : all.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = compiler.run(null, OutputStream.nullOutputStream(), errors, arguments.toArray(String[]::new));
        if (exit != 0) {
            throw new IllegalStateException(errors.toString(StandardCharsets.UTF_8));
        }
        return out;
    }
}
