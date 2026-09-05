package dtm.ide.todo;

import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoScannerTest {

    @TempDir
    Path root;

    private final TodoScanner scanner = new TodoScanner();

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>demo</artifactId></project>");
    }

    @Test
    void findsMarkersInLineAndBlockComments() throws IOException {
        source("com/example/A.java", """
                package com.example;

                public class A {
                    // TODO: trocar por uma fila
                    void run() { }

                    /* FIXME quebra com lista vazia */
                    void other() { }
                }
                """);

        List<TodoItem> found = scanner.scan(descriptor());

        assertEquals(2, found.size());
        assertEquals("TODO", found.get(0).marker());
        assertEquals("trocar por uma fila", found.get(0).message());
        assertEquals("FIXME", found.get(1).marker());
        assertEquals("quebra com lista vazia", found.get(1).message());
    }

    @Test
    void aMarkerInsideAStringIsNotAPendingItem() throws IOException {
        source("com/example/B.java", """
                package com.example;

                public class B {
                    String label = "TODO";
                    String other = "FIXME me";
                }
                """);

        assertTrue(scanner.scan(descriptor()).isEmpty());
    }

    @Test
    void aWordThatMerelyContainsTheMarkerDoesNotCount() throws IOException {
        source("com/example/C.java", """
                package com.example;

                public class C {
                    // TODOS os itens ja foram tratados
                    void run() { }
                }
                """);

        assertTrue(scanner.scan(descriptor()).isEmpty());
    }

    @Test
    void reportsTheEnclosingMethod() throws IOException {
        source("com/example/D.java", """
                package com.example;

                public class D {
                    public void process() {
                        // TODO validar a entrada
                    }
                }
                """);

        List<TodoItem> found = scanner.scan(descriptor());

        assertEquals(1, found.size());
        assertEquals("process", found.getFirst().context());
        assertEquals(4, found.getFirst().line());
    }

    @Test
    void refreshingOneFileLeavesTheOthersAlone() throws IOException {
        source("com/example/E.java", "package com.example;\n// TODO um\npublic class E { }\n");
        source("com/example/F.java", "package com.example;\n// TODO dois\npublic class F { }\n");
        assertEquals(2, scanner.scan(descriptor()).size());

        Path e = root.resolve("src/main/java/com/example/E.java");
        boolean changed = scanner.refreshFile(e, "package com.example;\npublic class E { }\n");

        assertTrue(changed);
        assertEquals(1, scanner.items().size());
        assertEquals("dois", scanner.items().getFirst().message());
    }

    @Test
    void refreshingWithoutChangesReportsNoChange() throws IOException {
        source("com/example/G.java", "package com.example;\n// TODO um\npublic class G { }\n");
        scanner.scan(descriptor());

        Path g = root.resolve("src/main/java/com/example/G.java");
        assertFalse(scanner.refreshFile(g, "package com.example;\n// TODO um\npublic class G { }\n"));
    }

    @Test
    void customMarkersReplaceTheDefaults() throws IOException {
        source("com/example/H.java", "package com.example;\n// NOTE olhar isto\n// TODO nao\n");
        scanner.setMarkers(List.of("NOTE"));

        List<TodoItem> found = scanner.scan(descriptor());

        assertEquals(1, found.size());
        assertEquals("NOTE", found.getFirst().marker());
    }

    @Test
    void alsoScansTheTestFolder() throws IOException {
        Path file = root.resolve("src/test/java/com/example/ITest.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package com.example;\n// TODO cobrir o caso vazio\n");

        assertEquals(1, scanner.scan(descriptor()).size());
    }

    private JavaProjectDescriptor descriptor() {
        return JavaProjectConventions.describe(root);
    }

    private void source(String relativePath, String content) throws IOException {
        Path file = root.resolve("src/main/java").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
