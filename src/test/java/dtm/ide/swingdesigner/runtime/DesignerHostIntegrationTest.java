package dtm.ide.swingdesigner.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dtm.ide.swingdesigner.catalog.PropertyDescriptor;
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
        for (Map.Entry<String, String> source : SOURCES.entrySet()) {
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
