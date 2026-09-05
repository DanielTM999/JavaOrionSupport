package dtm.ide.run;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainClassScannerTest {

    @TempDir
    Path root;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve("pom.xml"),
                "<project><groupId>com.example</groupId><artifactId>demo</artifactId></project>");
    }

    @Test
    void findsAClassWithMain() throws IOException {
        source("com/example/Main.java", """
                package com.example;

                public class Main {
                    public static void main(String[] args) { }
                }
                """);

        List<MainClassScanner.MainClass> found = scan();

        assertEquals(1, found.size());
        assertEquals("com.example.Main", found.getFirst().qualifiedName());
        assertEquals("Main", found.getFirst().simpleName());
        assertFalse(found.getFirst().springBoot());
    }

    @Test
    void acceptsTheVariationsOfTheMainSignature() throws IOException {
        source("a/A.java", "package a;\npublic class A {\n    static public void main(String[] args) { }\n}");
        source("a/B.java", "package a;\npublic class B {\n    public static void main(String args[]) { }\n}");
        source("a/C.java", "package a;\npublic class C {\n    public static void main(final String[] args) { }\n}");

        assertEquals(3, scan().size());
    }

    @Test
    void validatesTheCurrentUnsavedBuffer() throws IOException {
        Path file = root.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package com.example; public class Main { }");
        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);

        var main = MainClassScanner.inspect(file, """
                package com.changed;
                public class Main {
                    public static void main(String... args) { }
                }
                """, descriptor.rootModule()).orElseThrow();

        assertEquals("com.changed.Main", main.qualifiedName());
        assertTrue(MainClassScanner.hasValidMain("public static void main(String[] args) {}"));
        assertFalse(MainClassScanner.hasValidMain("// public static void main(String[] args) {}"));
    }

    @Test
    void springBootApplicationsComeFirstAndAreFlagged() throws IOException {
        source("com/example/Utilitario.java", """
                package com.example;

                public class Utilitario {
                    public static void main(String[] args) { }
                }
                """);
        source("com/example/Aplicacao.java", """
                package com.example;

                @SpringBootApplication
                public class Aplicacao {
                    public static void main(String[] args) {
                        SpringApplication.run(Aplicacao.class, args);
                    }
                }
                """);

        List<MainClassScanner.MainClass> found = scan();

        assertEquals(2, found.size());
        assertEquals("com.example.Aplicacao", found.getFirst().qualifiedName());
        assertTrue(found.getFirst().springBoot());
        assertTrue(found.getFirst().display().contains("Spring Boot"));
    }

    @Test
    void springBootClassCountsEvenWithoutAConventionalMain() throws IOException {
        source("com/example/Aplicacao.java", """
                package com.example;

                @SpringBootApplication
                public class Aplicacao { }
                """);

        assertEquals(1, scan().size());
    }

    @Test
    void classesWithoutMainAreIgnored() throws IOException {
        source("com/example/Servico.java", """
                package com.example;

                public class Servico {
                    public void main() { }
                    private static void main(int outro) { }
                }
                """);

        assertTrue(scan().isEmpty());
    }

    @Test
    void mainInsideACommentDoesNotCount() throws IOException {
        source("com/example/Falso.java", """
                package com.example;

                public class Falso {
                    // public static void main(String[] args) { }
                }
                """);

        assertTrue(scan().isEmpty());
    }

    @Test
    void classInTheDefaultPackageHasNoQualifier() throws IOException {
        source("Solto.java", """
                public class Solto {
                    public static void main(String[] args) { }
                }
                """);

        assertEquals("Solto", scan().getFirst().qualifiedName());
    }

    @Test
    void sortsAlphabeticallyWithinTheSameCategory() throws IOException {
        source("a/Zeta.java", "package a;\npublic class Zeta {\n    public static void main(String[] args) { }\n}");
        source("a/Alfa.java", "package a;\npublic class Alfa {\n    public static void main(String[] args) { }\n}");

        List<MainClassScanner.MainClass> found = scan();

        assertEquals("a.Alfa", found.getFirst().qualifiedName());
        assertEquals("a.Zeta", found.get(1).qualifiedName());
    }

    @Test
    void nullDescriptorYieldsNothing() {
        assertTrue(MainClassScanner.scan(null).isEmpty());
    }

    @Test
    void inspectRejectsNonJavaFiles() {
        assertTrue(MainClassScanner.inspect(root.resolve("pom.xml"), null).isEmpty());
        assertTrue(MainClassScanner.inspect(null, null).isEmpty());
    }

    @Test
    void findsAMainUnderTheTestFolder() throws IOException {
        testSource("com/example/Playground.java", """
                package com.example;

                public class Playground {
                    public static void main(String[] args) { }
                }
                """);

        List<MainClassScanner.MainClass> found = scan();

        assertEquals(1, found.size());
        assertEquals("com.example.Playground", found.getFirst().qualifiedName());
        assertTrue(found.getFirst().test());
    }

    @Test
    void productionMainsComeBeforeTestMains() throws IOException {
        testSource("com/example/AaaPlayground.java", """
                package com.example;

                public class AaaPlayground {
                    public static void main(String[] args) { }
                }
                """);
        source("com/example/ZzzApp.java", """
                package com.example;

                public class ZzzApp {
                    public static void main(String[] args) { }
                }
                """);

        List<MainClassScanner.MainClass> found = scan();

        assertEquals(2, found.size());
        assertEquals("com.example.ZzzApp", found.getFirst().qualifiedName());
        assertFalse(found.getFirst().test());
    }

    @Test
    void mainLensAnchorSitsBesideTheClassDeclaration() {
        MainClassScanner.MainLensAnchor anchor = MainClassScanner.mainLensAnchor("""
                package com.example;

                public class Main {
                    public static void main(String[] args) { }
                }
                """);

        assertNotNull(anchor);
        assertEquals(2, anchor.line());
        assertEquals(0, anchor.col());
        assertTrue(anchor.inline());
    }

    @Test
    void mainLensAnchorStaysOnTheClassWhenMainHasAnnotations() {
        MainClassScanner.MainLensAnchor anchor = MainClassScanner.mainLensAnchor("""
                package com.example;

                public class Main {
                    @SafeVarargs
                    @SuppressWarnings("unchecked")
                    public static void main(String[] args) { }
                }
                """);

        assertNotNull(anchor);
        assertEquals(2, anchor.line());
        assertTrue(anchor.inline());
    }

    @Test
    void mainLensAnchorIgnoresMembersBeforeMain() {
        MainClassScanner.MainLensAnchor anchor = MainClassScanner.mainLensAnchor("""
                package com.example;

                public class Main {
                    @Deprecated
                    private static final int X = 1;
                    public static void main(String[] args) { }
                }
                """);

        assertNotNull(anchor);
        assertEquals(2, anchor.line());
        assertTrue(anchor.inline());
    }

    @Test
    void mainLensAnchorIsNullWithoutAMain() {
        assertNull(MainClassScanner.mainLensAnchor("""
                package com.example;

                public class Main {
                }
                """));
    }

    private List<MainClassScanner.MainClass> scan() {
        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);
        return MainClassScanner.scan(descriptor);
    }

    private void source(String relativePath, String content) throws IOException {
        write(root.resolve("src/main/java").resolve(relativePath), content);
    }

    private void testSource(String relativePath, String content) throws IOException {
        write(root.resolve("src/test/java").resolve(relativePath), content);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
