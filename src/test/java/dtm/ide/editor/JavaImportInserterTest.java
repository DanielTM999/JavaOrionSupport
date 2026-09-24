package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaImportInserterTest {

    @Test
    void insertsSortedInsideTheMatchingImportGroup() {
        String source = """
                package demo;

                import java.io.File;
                import java.util.Map;

                import org.example.Service;

                class Demo {
                }
                """;

        JavaImportInserter.Result result = JavaImportInserter.insert(source,
                List.of("java.util.List", "org.example.Api"));

        assertEquals("""
                package demo;

                import java.io.File;
                import java.util.List;
                import java.util.Map;

                import org.example.Api;
                import org.example.Service;

                class Demo {
                }
                """, result.text());
        assertEquals(2, result.insertedLines());
        assertEquals(3, result.firstLine());
        assertEquals(List.of(
                TextEdit.insert(Position.of(3, 0), "import java.util.List;\n"),
                TextEdit.insert(Position.of(5, 0), "import org.example.Api;\n")), result.edits());
    }

    @Test
    void createsTheImportBlockAfterThePackage() {
        String source = """
                package demo;
                class Demo {
                }
                """;

        JavaImportInserter.Result result = JavaImportInserter.insert(source, List.of("java.util.List"));

        assertEquals("""
                package demo;

                import java.util.List;

                class Demo {
                }
                """, result.text());
        assertEquals(3, result.insertedLines());
        assertEquals(List.of(TextEdit.insert(Position.of(1, 0), "\nimport java.util.List;\n\n")),
                result.edits());
    }

    @Test
    void placesRegularImportsBeforeStaticOnes() {
        String source = """
                import static java.util.Objects.requireNonNull;

                class Demo {
                }
                """;

        JavaImportInserter.Result result = JavaImportInserter.insert(source, List.of("java.util.List"));

        assertEquals("""
                import java.util.List;

                import static java.util.Objects.requireNonNull;

                class Demo {
                }
                """, result.text());
    }

    @Test
    void skipsNamesAlreadyCoveredOrImplicit() {
        String source = """
                package demo;

                import java.util.*;
                import java.awt.List;

                class Demo {
                }
                """;

        JavaImportInserter.Result result = JavaImportInserter.insert(source, List.of(
                "java.util.Map", "java.util.List", "demo.Other", "java.lang.String"));

        assertEquals(source, result.text());
        assertEquals(0, result.insertedLines());
        assertEquals(List.of(), result.edits());
    }

    @Test
    void ignoresImportsInsideComments() {
        String source = """
                package demo;

                /* import java.util.List; */
                class Demo {
                }
                """;

        JavaImportInserter.Result result = JavaImportInserter.insert(source, List.of("java.util.List"));

        assertEquals("""
                package demo;

                import java.util.List;

                /* import java.util.List; */
                class Demo {
                }
                """, result.text());
    }
}
