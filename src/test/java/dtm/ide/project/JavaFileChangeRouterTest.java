package dtm.ide.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFileChangeRouterTest {

    @Test
    void anObsoleteDispatchCannotConsumeTheReplacementEvent(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("A.java"), "class A {}");
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        JavaFileChangeRouter router = router(events, latch, path -> false);
        try {
            router.acceptCreated(file);
            router.accept(file, StandardWatchEventKinds.ENTRY_MODIFY);
            router.dispatch(file, 1); // The first timer already left its queue before cancellation.
            assertTrue(events.isEmpty());
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, events.size());
            assertEquals(JavaFileChangeRouter.Change.CREATED, events.getFirst().change());
        } finally { router.shutdown(); }
    }

    @Test
    void shutdownRejectsPendingAndFutureEvents(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("A.java"), "class A {}");
        List<Event> events = new CopyOnWriteArrayList<>();
        JavaFileChangeRouter router = router(events, new CountDownLatch(1), path -> false);
        router.acceptCreated(file);
        router.shutdown();
        router.dispatch(file, 1);
        router.acceptCreated(file);
        router.accept(file, StandardWatchEventKinds.ENTRY_MODIFY);
        assertTrue(events.isEmpty());
    }

    private record Event(Path file, JavaFileChangeRouter.FileRole role,
                         JavaFileChangeRouter.Change change, boolean editorManaged) {
    }

    @Test
    void ignoresBuildOutputDirectories() {
        assertTrue(JavaFileChangeRouter.isIgnored(
                Path.of("/projeto/target/classes/com/example/Cliente.class")));
        assertTrue(JavaFileChangeRouter.isIgnored(Path.of("/projeto/build/tmp/Cliente.java")));
        assertTrue(JavaFileChangeRouter.isIgnored(Path.of("/projeto/.git/HEAD")));
        assertFalse(JavaFileChangeRouter.isIgnored(
                Path.of("/projeto/src/main/java/com/example/Cliente.java")));
    }

    @Test
    void classifiesTheFilesItCaresAbout(@TempDir Path dir) throws Exception {
        Path java = Files.writeString(dir.resolve("Cliente.java"), "class Cliente {}");
        Path yaml = Files.writeString(dir.resolve("application.yml"), "server:\n  port: 8080\n");
        Path pom = Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path other = Files.writeString(dir.resolve("notas.txt"), "nada");

        assertEquals(JavaFileChangeRouter.FileRole.JAVA, JavaFileChangeRouter.roleOf(java));
        assertEquals(JavaFileChangeRouter.FileRole.SPRING_CONFIG, JavaFileChangeRouter.roleOf(yaml));
        assertEquals(JavaFileChangeRouter.FileRole.BUILD, JavaFileChangeRouter.roleOf(pom));
        assertNull(JavaFileChangeRouter.roleOf(other));
        assertNull(JavaFileChangeRouter.roleOf(dir));
    }

    @Test
    void reportsACreatedJavaFile(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Cliente.java"), "class Cliente {}");
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        JavaFileChangeRouter router = router(events, latch, path -> false);

        router.accept(file, StandardWatchEventKinds.ENTRY_CREATE);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, events.size());
        assertEquals(JavaFileChangeRouter.Change.CREATED, events.getFirst().change());
        assertEquals(JavaFileChangeRouter.FileRole.JAVA, events.getFirst().role());
        router.shutdown();
    }

    @Test
    void coalescesAStormOfEventsIntoASingleCreate(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Cliente.java"), "class Cliente {}");
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        JavaFileChangeRouter router = router(events, latch, path -> false);

        router.accept(file, StandardWatchEventKinds.ENTRY_CREATE);
        router.accept(file, StandardWatchEventKinds.ENTRY_MODIFY);
        router.accept(file, StandardWatchEventKinds.ENTRY_MODIFY);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(400);
        assertEquals(1, events.size());
        assertEquals(JavaFileChangeRouter.Change.CREATED, events.getFirst().change());
        router.shutdown();
    }

    @Test
    void reportsDeletionWhenTheFileIsGoneAtDispatch(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Cliente.java"), "class Cliente {}");
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        JavaFileChangeRouter router = router(events, latch, path -> false);

        Files.delete(file);
        router.accept(file, StandardWatchEventKinds.ENTRY_DELETE);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(JavaFileChangeRouter.Change.DELETED, events.getFirst().change());
        router.shutdown();
    }

    @Test
    void reportsModificationsOfFilesOwnedByAnOpenEditorAsManaged(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Cliente.java"), "class Cliente {}");
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        JavaFileChangeRouter router = router(events, latch, path -> true);

        router.accept(file, StandardWatchEventKinds.ENTRY_MODIFY);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(JavaFileChangeRouter.Change.MODIFIED, events.getFirst().change());
        assertTrue(events.getFirst().editorManaged());
        router.shutdown();
    }

    @Test
    void anOverflowScansTheDirectoryItReportsAbout(@TempDir Path dir) throws Exception {
        Path sources = Files.createDirectories(dir.resolve("src").resolve("com"));
        Files.writeString(sources.resolve("A.java"), "class A {}");
        Files.writeString(Files.createDirectories(sources.resolve("sub")).resolve("B.java"),
                "class B {}");
        Files.writeString(Files.createDirectories(dir.resolve("target")).resolve("C.java"),
                "class C {}");
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        JavaFileChangeRouter router = router(events, latch, path -> false);

        router.accept(dir, StandardWatchEventKinds.OVERFLOW);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(400);
        assertEquals(2, events.size());
        assertTrue(events.stream().noneMatch(event -> event.file().toString().contains("target")));
        router.shutdown();
    }

    @Test
    void aCreatedDirectoryReportsTheFilesAlreadyInsideIt(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("com").resolve("example"));
        Files.writeString(pkg.resolve("Novo.java"), "class Novo {}");
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        JavaFileChangeRouter router = router(events, latch, path -> false);

        router.accept(dir.resolve("com"), StandardWatchEventKinds.ENTRY_CREATE);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, events.size());
        assertEquals(JavaFileChangeRouter.Change.CREATED, events.getFirst().change());
        router.shutdown();
    }

    @Test
    void anExplicitDirectoryScanReportsTheJavaFilesUnderIt(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("src"));
        Files.writeString(pkg.resolve("A.java"), "class A {}");
        Files.writeString(pkg.resolve("notas.txt"), "nada");
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        JavaFileChangeRouter router = router(events, latch, path -> false);

        router.acceptDirectory(dir);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(400);
        assertEquals(1, events.size());
        assertEquals(JavaFileChangeRouter.Change.MODIFIED, events.getFirst().change());
        router.shutdown();
    }

    @Test
    void stillReportsCreationOfAFileOwnedByAnOpenEditor(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("Cliente.java"), "class Cliente {}");
        List<Event> events = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        JavaFileChangeRouter router = router(events, latch, path -> true);

        router.accept(file, StandardWatchEventKinds.ENTRY_CREATE);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(JavaFileChangeRouter.Change.CREATED, events.getFirst().change());
        router.shutdown();
    }

    @Test
    void ignoresEventsFromBuildOutput(@TempDir Path dir) throws Exception {
        Path target = Files.createDirectories(dir.resolve("target").resolve("classes"));
        Path file = Files.writeString(target.resolve("Cliente.java"), "class Cliente {}");
        List<Event> events = new CopyOnWriteArrayList<>();
        JavaFileChangeRouter router = router(events, new CountDownLatch(1), path -> false);

        router.accept(file, StandardWatchEventKinds.ENTRY_CREATE);
        Thread.sleep(500);

        assertTrue(events.isEmpty());
        router.shutdown();
    }

    private static JavaFileChangeRouter router(List<Event> events, CountDownLatch latch,
                                               java.util.function.Predicate<Path> editorManaged) {
        return new JavaFileChangeRouter((file, role, change, managed) -> {
            events.add(new Event(file, role, change, managed));
            latch.countDown();
        }, editorManaged);
    }
}
