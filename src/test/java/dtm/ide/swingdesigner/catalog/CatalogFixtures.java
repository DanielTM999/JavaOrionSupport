package dtm.ide.swingdesigner.catalog;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

final class CatalogFixtures {

    static final Map<String, String> WORKSPACE_SOURCES = Map.of(
            "fx/FluentBox.java", """
                    package fx;
                    public class FluentBox extends javax.swing.JPanel {
                        public enum Mode { COMPACT, WIDE }
                        public FluentBox setTitle(String title) { return this; }
                        public String getTitle() { return ""; }
                        public void setAnimated(boolean animated) { }
                        public FluentBox setRange(int min, int max) { return this; }
                        public void setMode(Mode mode) { }
                        public Mode getMode() { return Mode.COMPACT; }
                        @Deprecated public void setLegacy(String legacy) { }
                        public void addThingListener(ThingListener listener) { }
                        public void removeThingListener(ThingListener listener) { }
                    }
                    """,
            "fx/ThingListener.java", """
                    package fx;
                    public interface ThingListener extends java.util.EventListener {
                        void thingHappened(java.awt.event.ActionEvent event);
                        void thingCleared(java.awt.event.ActionEvent event);
                    }
                    """,
            "fx/Labeled.java", """
                    package fx;
                    public class Labeled extends javax.swing.JLabel {
                        public Labeled(long size, String caption) { super(caption); }
                    }
                    """,
            "fx/Deep.java", """
                    package fx;
                    public class Deep extends FluentBox { }
                    """,
            "fx/Base.java", """
                    package fx;
                    public abstract class Base extends javax.swing.JComponent { }
                    """,
            "fx/NotUi.java", """
                    package fx;
                    public class NotUi { public void setName(String name) { } }
                    """,
            "fx/Hidden.java", """
                    package fx;
                    class Hidden extends javax.swing.JPanel { }
                    """,
            "fx/Factory.java", """
                    package fx;
                    public class Factory extends javax.swing.JPanel {
                        private Factory() { }
                        public static Factory create(String caption) { return new Factory(); }
                    }
                    """,
            "fx/MyFrame.java", """
                    package fx;
                    public class MyFrame extends javax.swing.JFrame { }
                    """);

    static final Map<String, String> LIBRARY_SOURCES = Map.of(
            "lib/ui/Switch.java", """
                    package lib.ui;
                    public class Switch extends javax.swing.JComponent {
                        public Switch setOn(boolean on) { return this; }
                    }
                    """,
            "lib/ui/internal/Gutter.java", """
                    package lib.ui.internal;
                    public class Gutter extends javax.swing.JComponent { }
                    """,
            "lib/ui/Card.java", """
                    package lib.ui;
                    public class Card extends javax.swing.JPanel {
                        public void addContent(java.awt.Component content) { }
                    }
                    """);

    private CatalogFixtures() {
    }

    static Path compile(Path root, Map<String, String> sources, List<Path> classpath)
            throws IOException {
        Path src = root.resolve("src");
        Path out = root.resolve("classes");
        Files.createDirectories(out);
        List<String> arguments = new ArrayList<>(List.of("-g", "-d", out.toString(),
                "--release", "8", "-Xlint:-options"));
        if (!classpath.isEmpty()) {
            arguments.add("-cp");
            arguments.add(String.join(java.io.File.pathSeparator,
                    classpath.stream().map(Path::toString).toList()));
        }
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = compiler.run(null, OutputStream.nullOutputStream(), errors,
                arguments.toArray(String[]::new));
        if (exit != 0) {
            throw new IllegalStateException(errors.toString(StandardCharsets.UTF_8));
        }
        return out;
    }

    static Path jar(Path classes, Path jar, Map<String, String> resources) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
            for (Map.Entry<String, String> resource : resources.entrySet()) {
                out.putNextEntry(new JarEntry(resource.getKey()));
                out.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
