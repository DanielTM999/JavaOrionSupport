package dtm.ide.editor;

import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.MavenCentralClient;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildFileCompletionProviderTest {

    private static final class FakeClient implements BuildFileCompletionProvider.Catalog {

        private String lastQuery;
        private String lastVersionsKey;

        @Override
        public List<MavenCentralClient.SearchResult> search(String query) {
            lastQuery = query;
            return List.of(
                    new MavenCentralClient.SearchResult(DependencyCoordinate.of(
                            "org.projectlombok", "lombok", "1.18.42"), 40, 0L),
                    new MavenCentralClient.SearchResult(DependencyCoordinate.of(
                            "org.projectlombok", "lombok-mapstruct-binding", "0.2.0"), 3, 0L));
        }

        @Override
        public List<String> versions(String groupId, String artifactId) {
            lastVersionsKey = groupId + ":" + artifactId;
            return List.of("1.18.42", "1.19.0-SNAPSHOT", "1.18.30");
        }
    }

    private final FakeClient client = new FakeClient();
    private final BuildFileCompletionProvider provider = new BuildFileCompletionProvider(client);

    @Test
    void recognisesTheBuildFilesItHandles() {
        assertTrue(BuildFileCompletionProvider.handles(Path.of("pom.xml")));
        assertTrue(BuildFileCompletionProvider.handles(Path.of("build.gradle")));
        assertTrue(BuildFileCompletionProvider.handles(Path.of("build.gradle.kts")));
        assertFalse(BuildFileCompletionProvider.handles(Path.of("outro.xml")));
        assertFalse(BuildFileCompletionProvider.handles(Path.of("Main.java")));
    }

    @Test
    void suggestsGroupsInsideADependencyBlock() {
        List<AutoCompleteItem> items = complete("pom.xml", """
                <project><dependencies><dependency>
                    <groupId>org.pro""");

        assertFalse(items.isEmpty());
        assertEquals("org.projectlombok", items.getFirst().label());
    }

    @Test
    void suggestsArtifactsOfTheGroupAlreadyTyped() {
        List<AutoCompleteItem> items = complete("pom.xml", """
                <project><dependencies><dependency>
                    <groupId>org.projectlombok</groupId>
                    <artifactId>lomb""");

        assertEquals("org.projectlombok:lomb*", client.lastQuery);
        assertTrue(items.stream().anyMatch(item -> item.label().equals("lombok")));
    }

    @Test
    void suggestsStableVersionsFirst() {
        List<AutoCompleteItem> items = complete("pom.xml", """
                <project><dependencies><dependency>
                    <groupId>org.projectlombok</groupId>
                    <artifactId>lombok</artifactId>
                    <version>""");

        assertEquals("org.projectlombok:lombok", client.lastVersionsKey);
        assertEquals("1.18.42", items.getFirst().label());
        assertEquals("1.19.0-SNAPSHOT", items.getLast().label());
    }

    @Test
    void aVersionOutsideADependencyBlockGetsNothing() {
        assertTrue(complete("pom.xml", """
                <project>
                    <version>1.0""").isEmpty());
    }

    @Test
    void understandsTheGradleShortNotation() {
        List<AutoCompleteItem> items = complete("build.gradle",
                "dependencies {\n    implementation 'org.projectlombok:lombok:1.18");

        assertEquals("org.projectlombok:lombok", client.lastVersionsKey);
        assertEquals("1.18.42", items.getFirst().label());
    }

    @Test
    void aShortPrefixDoesNotReachTheNetwork() {
        assertTrue(complete("build.gradle", "dependencies {\n    implementation 'or").isEmpty());
        assertEquals(null, client.lastQuery);
    }

    private List<AutoCompleteItem> complete(String fileName, String text) {
        String[] lines = text.split("\n", -1);
        String currentLine = lines[lines.length - 1];
        IdeCompletionContext context = new IdeCompletionContext(
                text, Path.of(fileName), text.length(), lines.length - 1,
                currentLine.length(), currentLine, "", text.length(),
                IdeCompletionTriggerKind.TYPING);
        return provider.suggestions(context);
    }
}
