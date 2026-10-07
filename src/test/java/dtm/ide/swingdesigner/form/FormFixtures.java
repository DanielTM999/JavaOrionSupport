package dtm.ide.swingdesigner.form;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.awt.Component;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class FormFixtures {

    static final String TELA = """
            package demo;

            import java.awt.BorderLayout;
            import java.awt.event.ActionEvent;
            import java.awt.event.MouseAdapter;
            import java.awt.event.MouseEvent;
            import javax.swing.*;

            public class Tela extends JFrame {

                private JPanel topo;
                private JButton salvar;
                private JLabel titulo = new JLabel("Titulo");
                private JTextField campo;
                private Seletor seletor;

                public Tela() {
                    super("Cadastro");
                    initComponents();
                }

                private void initComponents() {
                    setTitle("Cadastro");
                    getContentPane().setLayout(new BorderLayout());
                    topo = new JPanel();
                    topo.setLayout(new java.awt.FlowLayout());
                    salvar = new JButton("Salvar");
                    salvar.setEnabled(true);
                    salvar.addActionListener(this::salvarActionPerformed);
                    salvar.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseClicked(MouseEvent e) {
                            salvarMouseClicked(e);
                        }
                    });
                    JLabel rotulo = new JLabel("Nome");
                    topo.add(rotulo);
                    topo.add(salvar);
                    getContentPane().add(topo, BorderLayout.NORTH);
                    add(titulo, BorderLayout.CENTER);
                    for (int i = 0; i < 2; i++) {
                        campo = new JTextField();
                    }
                    titulo.addPropertyChangeListener(e -> repaint());
                    seletor = new Seletor();
                    add(seletor, BorderLayout.SOUTH);
                }

                private void salvarActionPerformed(ActionEvent e) {
                }

                private void salvarMouseClicked(MouseEvent e) {
                }

                public interface EscolhaListener {
                    boolean escolheu(String valor, int indice);
                }

                public static class Seletor extends JPanel {
                    public void addEscolhaListener(EscolhaListener listener) {
                    }
                }
            }
            """;

    static final String VAZIO = """
            package demo;

            import javax.swing.JPanel;

            public class Vazio extends JPanel {
            }
            """;

    private static final Set<String> USER_COMPONENTS = Set.of("demo.Tela", "demo.Tela$Seletor", "demo.Vazio");

    private FormFixtures() {
    }

    static boolean drawable(String name) {
        if (name.startsWith("demo.")) {
            return USER_COMPONENTS.contains(name);
        }
        try {
            return Component.class.isAssignableFrom(Class.forName(name, false,
                    FormFixtures.class.getClassLoader()));
        } catch (Throwable error) {
            return false;
        }
    }

    static FormModel tela() {
        return tela(TELA);
    }

    static FormModel tela(String source) {
        return FormReader.read(source, FormReader.Options.of("demo.Tela", true, FormFixtures::drawable))
                .orElseThrow();
    }

    static FormModel vazio() {
        return FormReader.read(VAZIO, FormReader.Options.of("demo.Vazio", false, FormFixtures::drawable))
                .orElseThrow();
    }

    static void assertCompiles(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null);
        Path output;
        try {
            output = Files.createTempDirectory("form-planner");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///demo/" + className + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        boolean compiled = compiler.getTask(null, files, diagnostics,
                List.of("-d", output.toString(), "-proc:none"), null, List.of(file)).call();
        assertTrue(compiled, () -> diagnostics.getDiagnostics() + "\n" + source);
    }
}
