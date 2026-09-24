package dtm.ide.editor;

import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.DependencySearchResult;
import dtm.ide.deps.DependencyVersionChoice;
import dtm.ide.deps.PomProperties;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
        public List<DependencySearchResult> search(String query) {
            lastQuery = query;
            return List.of(
                    result("org.projectlombok", "lombok", "1.18.42", true),
                    result("org.projectlombok", "lombok-mapstruct-binding", "0.2.0", false));
        }

        @Override
        public List<DependencyVersionChoice> versions(String groupId, String artifactId) {
            lastVersionsKey = groupId + ":" + artifactId;
            return List.of(new DependencyVersionChoice("1.19.0-SNAPSHOT", false, true),
                    new DependencyVersionChoice("1.18.42", true, true),
                    new DependencyVersionChoice("1.18.30", false, true));
        }

        private static DependencySearchResult result(String group, String artifact, String version,
                                                     boolean local) {
            return new DependencySearchResult(DependencyCoordinate.of(group, artifact, version), 1, 0L,
                    local, !local, null, local ? List.of(version) : List.of());
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
    void anEmptyPrefixDoesNotSearch() {
        assertTrue(complete("build.gradle", "dependencies {\n    implementation '").isEmpty());
        assertEquals(null, client.lastQuery);
    }

    @Test
    void theRemoteOnlyCatalogKeepsShortPrefixesOffTheNetwork() {
        BuildFileCompletionProvider.Catalog remote = BuildFileCompletionProvider.remoteOnly(null);
        assertTrue(remote.search("or").isEmpty());
    }

    @Test
    void aDottedGroupOnlyReplacesTheSegmentTheEditorReplaces() {
        AutoCompleteItem item = complete("pom.xml", """
                <project><dependencies><dependency>
                    <groupId>org.pro""").getFirst();

        assertEquals("org.projectlombok", item.label());
        assertEquals("projectlombok", item.insertText());
        assertTrue(item.additionalTextEdits().isEmpty());
    }

    @Test
    void aGroupThatDoesNotStartWithTheTypedTextRemovesTheTypedHead() {
        AutoCompleteItem item = complete("pom.xml", """
                <project><dependencies><dependency>
                    <groupId>project.lomb""").getFirst();

        assertEquals("org.projectlombok", item.insertText());
        assertEquals(1, item.additionalTextEdits().size());
        assertEquals(1, item.additionalTextEdits().getFirst().range().start().line());
        assertEquals(13, item.additionalTextEdits().getFirst().range().start().col());
        assertEquals(21, item.additionalTextEdits().getFirst().range().end().col());
    }

    @Test
    void localArtifactsAreMarkedInTheDetail() {
        List<AutoCompleteItem> items = complete("pom.xml", """
                <project><dependencies><dependency>
                    <groupId>org.projectlombok</groupId>
                    <artifactId>lomb""");

        assertEquals("org.projectlombok:lombok (local)", items.getFirst().detail());
    }

    @Test
    void aDottedVersionOnlyInsertsTheRemainder() {
        List<AutoCompleteItem> items = complete("pom.xml", """
                <project><dependencies><dependency>
                    <groupId>org.projectlombok</groupId>
                    <artifactId>lombok</artifactId>
                    <version>1.18.4""");

        assertEquals("1.18.42", items.getFirst().label());
        assertEquals("42", items.getFirst().insertText());
    }

    @Test
    void suggestsPropertiesOfThePomAndItsParent(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                    <groupId>com.acme</groupId>
                    <artifactId>parent</artifactId>
                    <version>2.0.0</version>
                    <properties>
                        <lombok.version>1.18.42</lombok.version>
                    </properties>
                </project>""");
        Path module = Files.createDirectories(root.resolve("app")).resolve("pom.xml");
        String text = """
                <project>
                    <parent>
                        <groupId>com.acme</groupId>
                        <artifactId>parent</artifactId>
                        <version>2.0.0</version>
                    </parent>
                    <artifactId>app</artifactId>
                    <properties>
                        <app.version>${project.version}</app.version>
                    </properties>
                    <dependencies><dependency>
                        <version>${lombok.""";
        Files.writeString(module, text);

        List<AutoCompleteItem> items = complete(module, text);

        AutoCompleteItem lombok = items.stream().filter(item -> item.label().equals("lombok.version"))
                .findFirst().orElseThrow();
        assertEquals("version}", lombok.insertText());
        assertEquals("1.18.42", lombok.detail());

        List<AutoCompleteItem> all = complete(module, text.replace("${lombok.", "${"));
        AutoCompleteItem app = all.stream().filter(item -> item.label().equals("app.version"))
                .findFirst().orElseThrow();
        assertEquals("2.0.0", app.detail());
        assertEquals("app.version}", app.insertText());
    }

    @Test
    void doesNotDuplicateAnExistingClosingBrace() {
        String text = "<project><properties><a.b>1</a.b></properties><x>${a.}</x></project>";
        int caret = text.indexOf("${a.") + 4;
        IdeCompletionContext context = new IdeCompletionContext(text, Path.of("pom.xml"), caret, 0,
                caret, text, "", caret, IdeCompletionTriggerKind.TYPING);

        AutoCompleteItem item = new BuildFileCompletionProvider(client, new PomProperties(() -> null))
                .suggestions(context).getFirst();

        assertEquals("a.b", item.label());
        assertEquals("b", item.insertText());
    }

    private List<AutoCompleteItem> complete(String fileName, String text) {
        return complete(Path.of(fileName), text);
    }

    private List<AutoCompleteItem> complete(Path file, String text) {
        String[] lines = text.split("\n", -1);
        String currentLine = lines[lines.length - 1];
        IdeCompletionContext context = new IdeCompletionContext(
                text, file, text.length(), lines.length - 1,
                currentLine.length(), currentLine, "", text.length(),
                IdeCompletionTriggerKind.TYPING);
        return provider.suggestions(context);
    }
}
