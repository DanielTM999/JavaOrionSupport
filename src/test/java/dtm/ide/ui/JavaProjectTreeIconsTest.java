package dtm.ide.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class JavaProjectTreeIconsTest {

    @TempDir
    Path directory;

    @Test void unsavedRecordAndIncompleteDeclarationKeepJavaIcon() throws IOException {
        Path file = directory.resolve("State.java");
        Files.writeString(file, "class State {}");
        JavaProjectTreeIcons.updateOpenSource(file, "record State() {}");
        assertSame(JavaIcons.javaRecord(16), JavaProjectTreeIcons.iconOf(file, 16));
        JavaProjectTreeIcons.updateOpenSource(file, "rec State() {}");
        assertSame(JavaIcons.javaRecord(16), JavaProjectTreeIcons.iconOf(file, 16));
        JavaProjectTreeIcons.closeSource(file);
        assertSame(JavaIcons.javaClass(16), JavaProjectTreeIcons.iconOf(file, 16));
    }

    @Test
    void identifiesMainJavaDeclarationAndIgnoresCommentsAndStrings() {
        assertEquals(JavaProjectTreeIcons.Kind.CLASS, JavaProjectTreeIcons.kindFor("Pedido.java",
                "// interface Pedido {}\nclass Pedido { String text = \"enum Pedido\"; }"));
        assertEquals(JavaProjectTreeIcons.Kind.INTERFACE,
                JavaProjectTreeIcons.kindFor("Servico.java", "public interface Servico {}"));
        assertEquals(JavaProjectTreeIcons.Kind.ENUM,
                JavaProjectTreeIcons.kindFor("Estado.java", "public enum Estado { ATIVO }"));
        assertEquals(JavaProjectTreeIcons.Kind.ABSTRACT,
                JavaProjectTreeIcons.kindFor("Base.java", "public abstract class Base {}"));
        assertEquals(JavaProjectTreeIcons.Kind.NONE,
                JavaProjectTreeIcons.kindFor("Outro.java", "public class Pedido {}"));
        assertEquals(JavaProjectTreeIcons.Kind.NONE,
                JavaProjectTreeIcons.kindFor("Marcador.java", "public @interface Marcador {}"));
    }

    @Test
    void resolvesIconsOnlyForJavaFilesAtTheRequestedSize() throws IOException {
        Path file = directory.resolve("Pedido.java");
        Files.writeString(file, "public class Pedido {}");
        Path other = directory.resolve("readme.txt");
        Files.writeString(other, "class Pedido {}");

        assertSame(JavaIcons.javaClass(20), JavaProjectTreeIcons.iconOf(file, 20));
        assertEquals(20, JavaProjectTreeIcons.iconOf(file, 20).getIconWidth());
        assertNull(JavaProjectTreeIcons.iconOf(other, 16));
        assertNull(JavaProjectTreeIcons.iconOf(directory, 16));
    }

    @Test
    void refreshesIconAfterSourceChanges() throws IOException {
        Path file = directory.resolve("Estado.java");
        Files.writeString(file, "class Estado {}");
        assertSame(JavaIcons.javaClass(16), JavaProjectTreeIcons.iconOf(file, 16));

        Files.writeString(file, "enum Estado { ATIVO }");
        JavaProjectTreeIcons.invalidate(file);
        assertSame(JavaIcons.javaEnum(16), JavaProjectTreeIcons.iconOf(file, 16));

        Files.writeString(file, "record Estado() {}");
        JavaProjectTreeIcons.invalidate(file);
        assertSame(JavaIcons.javaRecord(16), JavaProjectTreeIcons.iconOf(file, 16));

        Files.writeString(file, "@interface Estado {}");
        JavaProjectTreeIcons.invalidate(file);
        assertNull(JavaProjectTreeIcons.iconOf(file, 16));
    }

    @Test
    void identifiesRecordsAndExceptions() {
        assertEquals(JavaProjectTreeIcons.Kind.RECORD,
                JavaProjectTreeIcons.kindFor("Ponto.java", "public record Ponto(int x, int y) implements Serializable {}"));
        assertEquals(JavaProjectTreeIcons.Kind.EXCEPTION,
                JavaProjectTreeIcons.kindFor("Falha.java", "public class Falha extends RuntimeException {}"));
        assertEquals(JavaProjectTreeIcons.Kind.EXCEPTION,
                JavaProjectTreeIcons.kindFor("Pane.java", "class Pane extends java.lang.Error {}"));
        assertEquals(JavaProjectTreeIcons.Kind.EXCEPTION,
                JavaProjectTreeIcons.kindFor("Base.java", "public abstract class Base extends Throwable {}"));
        assertEquals(JavaProjectTreeIcons.Kind.EXCEPTION,
                JavaProjectTreeIcons.kindFor("Envelope.java",
                        "public class Envelope<T extends Number> extends NegocioException implements Serializable {}"));
        assertEquals(JavaProjectTreeIcons.Kind.CLASS,
                JavaProjectTreeIcons.kindFor("Caixa.java", "public class Caixa<T extends Exception> implements Supplier<T> {}"));
        assertEquals(JavaProjectTreeIcons.Kind.CLASS,
                JavaProjectTreeIcons.kindFor("Tratador.java", "public class Tratador extends ExceptionHandler {}"));
        assertEquals(JavaProjectTreeIcons.Kind.ABSTRACT,
                JavaProjectTreeIcons.kindFor("Modelo.java", "public abstract class Modelo extends Entidade {}"));
    }

    @Test
    void readsOnlyTheHeadOfLargeSources() throws IOException {
        Path file = directory.resolve("Grande.java");
        String body = "  int campo;\n".repeat(JavaProjectTreeIcons.MAX_SOURCE_BYTES / 8);
        Files.writeString(file, "public interface Grande {\n" + body + "}\n");

        assertEquals(JavaProjectTreeIcons.Kind.INTERFACE, JavaProjectTreeIcons.kindOf(file));
    }
}
