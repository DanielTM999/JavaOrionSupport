package dtm.ide.refactor;

import dtm.stools.component.panels.editor.code.api.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaSafeDeleteScannerTest {

    @org.junit.jupiter.api.Test
    void incompleteScanIsNotReportedAsNoUsages(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var target = java.nio.file.Files.writeString(dir.resolve("Target.java"), "class Target {}");
        java.nio.file.Files.writeString(dir.resolve("Use.java"), "class Use { Target field; }");
        var scan = JavaSafeDeleteScanner.scan(dir, java.util.List.of(target), java.util.Map.of(), 0);
        org.junit.jupiter.api.Assertions.assertFalse(scan.complete());
    }

    @org.junit.jupiter.api.Test
    void scannerUsesUnsavedBuffers(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var target = java.nio.file.Files.writeString(dir.resolve("Target.java"), "class Target {}");
        var use = java.nio.file.Files.writeString(dir.resolve("Use.java"), "class Use {}");
        var scan = JavaSafeDeleteScanner.scan(dir, java.util.List.of(target),
                java.util.Map.of(use, "class Use { Target field; }"));
        org.junit.jupiter.api.Assertions.assertTrue(scan.complete());
        org.junit.jupiter.api.Assertions.assertEquals(1, scan.locations().size());
    }

    @TempDir
    Path root;

    @Test
    void findsUsagesOfANewSourceEvenWithoutTheLanguageServerIndex() throws IOException {
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/java/example"));
        Path deleted = sourceRoot.resolve("TesteDelete.java");
        Files.writeString(deleted, "package example; public class TesteDelete {}");
        Path consumer = sourceRoot.resolve("App.java");
        Files.writeString(consumer, """
                package example;
                class App {
                    TesteDelete value = new TesteDelete();
                }
                """);

        List<Location> usages = JavaSafeDeleteScanner.findExternalUsages(root, List.of(deleted));

        assertEquals(2, usages.size());
        assertEquals(consumer.toUri().toString(), usages.getFirst().uri());
        assertEquals(2, usages.getFirst().range().start().line());
    }

    @Test
    void onlyFilesThatCanSeeTheDeletedTypeCount() throws IOException {
        Path sources = Files.createDirectories(root.resolve("src/main/java"));
        Path deleted = write(sources, "a/Status.java", "package a; public class Status {}");
        Path samePackage = write(sources, "a/Uso.java", "package a; class Uso { Status s; }");
        Path explicit = write(sources, "b/Explicito.java", "package b; import a.Status; class Explicito { Status s; }");
        Path wildcard = write(sources, "c/Curinga.java", "package c; import a.*; class Curinga { Status s; }");
        Path qualified = write(sources, "d/Qualificado.java", "package d; class Qualificado { a.Status s; }");
        Path staticImport = write(sources, "e/Estatico.java",
                "package e; import static a . Status.valueOf; class Estatico { Object v = valueOf(); }");
        write(sources, "x/Homonimo.java", "package x; import y.Status; class Homonimo { Status s; }");
        write(sources, "x/Local.java", "package x; class Local { Status s; }");
        write(sources, "a2/Sombra.java", "package a; import z.Status; class Sombra { Status s; }");

        List<String> files = JavaSafeDeleteScanner.findExternalUsages(root, List.of(deleted)).stream()
                .map(Location::uri).distinct().sorted().toList();

        assertEquals(java.util.stream.Stream.of(samePackage, explicit, wildcard, qualified, staticImport)
                .map(path -> path.toUri().toString()).sorted().toList(), files);
    }

    private static Path write(Path base, String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @Test
    void ignoresNamesInCommentsStringsBuildOutputAndTheDeletedSelection() throws IOException {
        Path sourceRoot = Files.createDirectories(root.resolve("src/main/java/example"));
        Path deleted = sourceRoot.resolve("TesteDelete.java");
        Files.writeString(deleted, "class TesteDelete { TesteDelete self; }");
        Files.writeString(sourceRoot.resolve("Other.java"), """
                class Other {
                    // TesteDelete comment
                    String name = "TesteDelete";
                }
                """);
        Path generated = Files.createDirectories(root.resolve("target/generated"))
                .resolve("Generated.java");
        Files.writeString(generated, "class Generated { TesteDelete value; }");

        assertEquals(List.of(),
                JavaSafeDeleteScanner.findExternalUsages(root, List.of(deleted)));
    }
}
