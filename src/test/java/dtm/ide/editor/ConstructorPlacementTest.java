package dtm.ide.editor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConstructorPlacementTest {
    @Test void ignoresSelectedFieldAndFindsLastField() {
        String source = "class A {\n int a;\n int b;\n void run() {}\n}";
        var position = ConstructorPlacement.afterFields(source, 1, 5);
        assertEquals(2, position.line()); assertEquals(7, position.col());
    }
    @Test void nestedClassUsesOwnFields() {
        String source = "class A { int outer; class B { int a; int b; void run() {} } }";
        var position = ConstructorPlacement.afterFields(source, 0, source.indexOf("int a"));
        assertEquals(source.indexOf("int b;") + 6, position.col());
    }
}
