package dtm.ide.swingdesigner;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingSourceDetectorTest {

    @Test
    void explicitImportOfASwingClassIsDrawable() {
        String source = """
                package demo;
                import javax.swing.JFrame;
                public class Tela extends JFrame { }
                """;

        assertTrue(SwingSourceDetector.isDrawableSource("demo.Tela", source, Optional.empty()));
    }

    @Test
    void wildcardImportsAndFullyQualifiedNamesAreResolved() {
        assertTrue(SwingSourceDetector.isDrawableSource("demo.Painel", """
                package demo;
                import java.awt.*;
                public class Painel extends Canvas { }
                """, Optional.empty()));
        assertTrue(SwingSourceDetector.isDrawableSource("demo.Painel", """
                package demo;
                public class Painel extends javax.swing.JPanel { }
                """, Optional.empty()));
    }

    @Test
    void nonUiClassesAreNotDrawable() {
        assertFalse(SwingSourceDetector.isDrawableSource("demo.Servico", """
                package demo;
                import java.util.ArrayList;
                public class Servico extends ArrayList<String> { }
                """, Optional.empty()));
        assertFalse(SwingSourceDetector.isDrawableSource("demo.Simples", """
                package demo;
                public class Simples { javax.swing.JLabel label; }
                """, Optional.empty()));
        assertFalse(SwingSourceDetector.isDrawableSource("demo.Erro", """
                package demo;
                public class Erro extends Exception { }
                """, Optional.empty()));
    }

    @Test
    void unknownSuperclassesFallBackToUiReferencesInTheFile() {
        assertTrue(SwingSourceDetector.isDrawableSource("demo.Tela", """
                package demo;
                import javax.swing.JButton;
                public class Tela extends BaseTela { JButton ok = new JButton(); }
                """, Optional.empty()));
        assertFalse(SwingSourceDetector.isDrawableSource("demo.Regra", """
                package demo;
                public class Regra extends BaseRegra { }
                """, Optional.empty()));
    }

    @Test
    void commentsAndStringsDoNotCount() {
        assertFalse(SwingSourceDetector.isDrawableSource("demo.Regra", """
                package demo;
                public class Regra extends BaseRegra {
                    String text = "javax.swing.JFrame";
                }
                """, Optional.empty()));
    }

    @Test
    void genericDeclarationsAndNestedSuperclassesAreParsed() {
        assertEquals(Optional.of("JComboBox"), SwingSourceDetector.superclassOf(
                "public class Combo<T extends Number> extends JComboBox<T> { }", "Combo"));
        assertTrue(SwingSourceDetector.candidates("import javax.swing.JPopupMenu;", "JPopupMenu.Separator")
                .contains("javax.swing.JPopupMenu$Separator"));
    }
}
