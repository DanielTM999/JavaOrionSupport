package dtm.ide.build.incremental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleBuildStateTest {

    @TempDir
    Path root;

    @Test
    void everySourceIsNewOnTheFirstBuild() {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");

        ModuleBuildState state = ModuleBuildState.load(stateFile());
        ModuleBuildState.Changes changes = state.changes(root, List.of(lojista));

        assertEquals(List.of(lojista), changes.added());
        assertTrue(changes.modified().isEmpty());
        assertTrue(changes.deleted().isEmpty());
    }

    @Test
    void recordedSourcesStopBeingReportedAsChanged() {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");
        ModuleBuildState state = ModuleBuildState.load(stateFile());
        state.reset("fp");
        state.record(root, lojista);

        assertTrue(state.changes(root, List.of(lojista)).isEmpty());
    }

    @Test
    void editingASourceMarksItAsModified() throws Exception {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");
        ModuleBuildState state = ModuleBuildState.load(stateFile());
        state.reset("fp");
        state.record(root, lojista);

        Files.writeString(lojista, "package a; public class Lojista { void extra() {} }",
                StandardCharsets.UTF_8);

        assertEquals(List.of(lojista), state.changes(root, List.of(lojista)).modified());
    }

    @Test
    void removingASourceIsReportedAsDeleted() {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");
        ModuleBuildState state = ModuleBuildState.load(stateFile());
        state.reset("fp");
        state.record(root, lojista);

        ModuleBuildState.Changes changes = state.changes(root, List.of());

        assertEquals(List.of(lojista), changes.deleted());
    }

    @Test
    void dependentsAreFoundByTheTypesTheyMention() {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");
        Path service = source("LojistaService.java",
                "package a; public class LojistaService { Lojista lojista; }");
        Path unrelated = source("Outro.java", "package a; public class Outro {}");
        ModuleBuildState state = ModuleBuildState.load(stateFile());
        state.reset("fp");
        state.record(root, lojista);
        state.record(root, service);
        state.record(root, unrelated);

        Set<Path> dependents = state.dependentsOf(root, Set.of("Lojista"));

        assertTrue(dependents.contains(service));
        assertFalse(dependents.contains(unrelated));
    }

    @Test
    void textInsideStringsDoesNotCreateADependency() {
        Path holder = source("Holder.java",
                "package a; public class Holder { String name = \"Lojista\"; }");
        ModuleBuildState state = ModuleBuildState.load(stateFile());
        state.reset("fp");
        state.record(root, holder);

        assertTrue(state.dependentsOf(root, Set.of("Lojista")).isEmpty());
    }

    @Test
    void theStateSurvivesAReload() {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");
        ModuleBuildState saved = ModuleBuildState.load(stateFile());
        saved.reset("fp");
        saved.record(root, lojista);
        saved.save();

        ModuleBuildState reloaded = ModuleBuildState.load(stateFile());

        assertTrue(reloaded.isUsable("fp"));
        assertEquals(1, reloaded.size());
        assertTrue(reloaded.changes(root, List.of(lojista)).isEmpty());
        assertEquals(Set.of("Lojista"), reloaded.typesOf(root, lojista));
    }

    @Test
    void aDifferentFingerprintInvalidatesTheState() {
        ModuleBuildState saved = ModuleBuildState.load(stateFile());
        saved.reset("fp");
        saved.record(root, source("Lojista.java", "package a; public class Lojista {}"));
        saved.save();

        assertFalse(ModuleBuildState.load(stateFile()).isUsable("outro"));
    }

    @Test
    void discardRemovesTheWholeStateDirectory() {
        ModuleBuildState saved = ModuleBuildState.load(stateFile());
        saved.reset("fp");
        saved.save();

        ModuleBuildState.discard(stateFile().getParent());

        assertFalse(Files.exists(stateFile().getParent()));
    }

    private Path stateFile() {
        return root.resolve(".orion/incremental/demo.state");
    }

    private Path source(String name, String content) {
        try {
            Path file = root.resolve("src/main/java/a").resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
