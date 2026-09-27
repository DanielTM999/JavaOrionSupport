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
    void stateSavedByAnOlderFormatIsNotReusedSoClassesGetRecompiledWithDebugInfo() throws Exception {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");
        ModuleBuildState saved = ModuleBuildState.load(stateFile());
        saved.reset("fp");
        saved.record(root, lojista);
        saved.save();
        assertTrue(ModuleBuildState.load(stateFile()).isUsable("fp"));

        String content = Files.readString(stateFile(), StandardCharsets.UTF_8);
        Files.writeString(stateFile(), content.replaceFirst(
                "#orion-incremental " + ModuleBuildState.FORMAT_VERSION, "#orion-incremental 4"),
                StandardCharsets.UTF_8);

        assertFalse(ModuleBuildState.load(stateFile()).isUsable("fp"));
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

        Set<Path> dependents = state.dependentsOf(root, Set.of("a.Lojista"));

        assertTrue(dependents.contains(service));
        assertFalse(dependents.contains(unrelated));
    }

    @Test
    void dependentsAreResolvedByPackageAndImports() {
        Path explicit = source("b/Explicito.java",
                "package b; import a.Lojista; public class Explicito { Lojista l; }");
        Path other = source("b/Outro.java",
                "package b; import z.Lojista; public class Outro { Lojista l; }");
        Path qualified = source("c/Qualificado.java",
                "package c; public class Qualificado { a.Lojista l; }");
        Path unqualified = source("c/SemImport.java",
                "package c; public class SemImport { Lojista l; }");
        Path nested = source("d/Aninhado.java",
                "package d; import a.Lojista.Item; public class Aninhado { Item i; Lojista x; }");
        ModuleBuildState state = ModuleBuildState.load(stateFile());
        state.reset("fp");
        for (Path file : List.of(explicit, other, qualified, unqualified, nested)) {
            state.record(root, file);
        }

        Set<Path> dependents = state.dependentsOf(root, Set.of("a.Lojista"));

        assertTrue(dependents.contains(explicit));
        assertTrue(dependents.contains(qualified));
        assertTrue(dependents.contains(nested));
        assertFalse(dependents.contains(other));
        assertFalse(dependents.contains(unqualified));
    }

    @Test
    void aTouchedButUnchangedSourceRefreshesItsTimestamp() throws Exception {
        Path lojista = source("Lojista.java", "package a; public class Lojista {}");
        ModuleBuildState state = ModuleBuildState.load(stateFile());
        state.reset("fp");
        state.record(root, lojista);
        state.save();
        Files.setLastModifiedTime(lojista, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 60_000));

        ModuleBuildState reloaded = ModuleBuildState.load(stateFile());

        assertTrue(reloaded.changes(root, List.of(lojista)).isEmpty());
        assertTrue(reloaded.isDirty());
    }

    @Test
    void textInsideStringsDoesNotCreateADependency() {
        Path holder = source("Holder.java",
                "package a; public class Holder { String name = \"Lojista\"; }");
        ModuleBuildState state = ModuleBuildState.load(stateFile());
        state.reset("fp");
        state.record(root, holder);

        assertTrue(state.dependentsOf(root, Set.of("a.Lojista")).isEmpty());
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
            Path file = name.contains("/") ? root.resolve("src/main/java").resolve(name)
                    : root.resolve("src/main/java/a").resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
