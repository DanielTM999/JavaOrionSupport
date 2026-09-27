package dtm.ide.index;

import dtm.stools.component.panels.editor.code.api.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLexicalIndexTest {

    private final JavaLexicalIndex index = new JavaLexicalIndex();

    @TempDir
    Path root;

    @Test
    void pointsToTheDeclarationOfAnIndexedType() throws Exception {
        Path service = write("OrderService.java", """
                package demo;

                public class OrderService {
                    public int total() {
                        return 0;
                    }
                }
                """);

        List<Location> definitions = index.definitions("OrderService");

        assertEquals(1, definitions.size());
        assertEquals(service.toUri().toString(), definitions.getFirst().uri());
        assertEquals(2, definitions.getFirst().range().start().line());
        assertEquals("public class ".length(), definitions.getFirst().range().start().col());
    }

    @Test
    void findsUsagesInOtherIndexedFiles() throws Exception {
        write("OrderService.java", "package demo;\npublic class OrderService {}\n");
        Path caller = write("Checkout.java", """
                package demo;

                class Checkout {
                    OrderService service;
                }
                """);

        List<Location> usages = index.usages("OrderService", 5_000);

        assertTrue(usages.stream().anyMatch(usage ->
                usage.uri().equals(caller.toUri().toString())
                        && usage.range().start().line() == 3), usages.toString());
    }

    @Test
    void skipsUsagesThatOnlyAppearInCommentsOrStrings() throws Exception {
        write("Owner.java", "package demo;\npublic class Owner {}\n");
        write("Noise.java", """
                package demo;

                class Noise {
                    // Owner
                    String label = "Owner";
                }
                """);

        List<Location> usages = index.usages("Owner", 5_000);

        assertTrue(usages.stream().noneMatch(usage -> usage.uri().contains("Noise")),
                usages.toString());
    }

    @Test
    void reindexingAFileReplacesItsPreviousDeclarations() throws Exception {
        write("Renamed.java", "package demo;\nclass OldName {}\n");
        assertFalse(index.definitions("OldName").isEmpty());

        write("Renamed.java", "package demo;\nclass NewName {}\n");

        assertTrue(index.definitions("OldName").isEmpty());
        assertEquals(1, index.definitions("NewName").size());
    }

    @Test
    void reportsUnknownNamesWithoutTouchingTheFileSystem() throws Exception {
        write("Only.java", "package demo;\nclass Only {}\n");

        assertTrue(index.definitions("Missing").isEmpty());
        assertTrue(index.usages("Missing", 5_000).isEmpty());
    }

    @Test
    void methodCalledFromAnotherFileIsUsed() throws Exception {
        Path service = write("OrderService.java", """
                class OrderService {
                    public int total() {
                        return 0;
                    }
                    public int neverCalled() {
                        return helper();
                    }
                    private int helper() {
                        return 1;
                    }
                }
                """);
        write("Checkout.java", """
                class Checkout {
                    int run(OrderService service) {
                        return service.total();
                    }
                }
                """);

        Set<String> unused = index.unusedMethods(List.of("total", "neverCalled", "helper"),
                service, Files.readString(service));

        assertEquals(Set.of("neverCalled"), unused);
    }

    @Test
    void annotatedMethodsAndMainAreEntryPoints() throws Exception {
        Path app = write("App.java", """
                class App {
                    public static void main(String[] args) {
                    }

                    @Override
                    public String toString() {
                        return "app";
                    }

                    @Bean
                    public Object clock() {
                        return null;
                    }

                    void orphan() {
                    }
                }
                """);

        assertEquals(Set.of("orphan"), index.unusedMethods(
                List.of("main", "toString", "clock", "orphan"), app, Files.readString(app)));
    }

    @Test
    void unsavedBufferWinsOverTheIndexedContent() throws Exception {
        Path service = write("Service.java", """
                class Service {
                    void first() {
                    }
                    void second() {
                    }
                }
                """);
        String edited = """
                class Service {
                    void first() {
                        second();
                    }
                    void second() {
                    }
                }
                """;

        assertEquals(Set.of("first", "second"), index.unusedMethods(
                List.of("first", "second"), service, Files.readString(service)));
        assertEquals(Set.of("first"), index.unusedMethods(List.of("first", "second"), service, edited));
    }

    @Test
    void callSitesAreNotMistakenForDeclarations() throws Exception {
        Path service = write("Service.java", """
                class Service {
                    int value() {
                        return compute();
                    }
                    int compute() {
                        return 1;
                    }
                }
                """);

        assertEquals(Set.of("value"), index.unusedMethods(List.of("value", "compute"),
                service, Files.readString(service)));
    }

    @Test
    void namesWithoutAProjectMethodDeclarationAreNeverUnused() throws Exception {
        Path service = write("Service.java", "class Service { void run() { run(); } }\n");

        assertEquals(Set.of(), index.unusedMethods(List.of("stream", "size"), service,
                Files.readString(service)));
    }

    @Test
    void emptyIndexReportsNothing() {
        assertEquals(Set.of(), new JavaLexicalIndex().unusedMethods(List.of("orphan"),
                root.resolve("A.java"), "class A { void orphan() {} }"));
    }

    private Path write(String name, String content) throws Exception {
        Path file = root.resolve(name);
        Files.writeString(file, content);
        index.refreshFile(file, content);
        assertTrue(index.awaitIdle(5_000));
        return file.toAbsolutePath().normalize();
    }
}
