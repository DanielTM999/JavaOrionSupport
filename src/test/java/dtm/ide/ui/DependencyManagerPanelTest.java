package dtm.ide.ui;

import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.DependencyHealthSnapshot;
import dtm.ide.deps.DependencyInventorySnapshot;
import dtm.ide.deps.DependencySearchResult;
import dtm.ide.deps.DependencyVersionChoice;
import dtm.ide.deps.DependencyVersionOrigin;
import dtm.ide.deps.ManagedDependency;
import dtm.ide.project.JavaModule;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.inputfields.segmentedfield.SegmentedField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class DependencyManagerPanelTest {

    private static final JavaModule MODULE = new JavaModule(Path.of("demo"), "demo", "com.demo",
            "demo", "jar", List.of(), List.of(), Path.of("demo/target/classes"));

    @BeforeEach
    void requireGraphicsEnvironment() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Swing panels require a graphics environment");
    }

    @Test
    void locallyInstalledArtifactsShowBothBadgesAndTheirRepositoryPath() throws Exception {
        DependencyCoordinate widget = DependencyCoordinate.of("com.acme", "widget", "3.0.0");
        FakeHost host = new FakeHost();
        host.declared = List.of(widget);
        host.results = List.of(new DependencySearchResult(widget, 4, 100, true, true,
                Path.of("D:/MavenRepository"), List.of("3.0.0")));

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        search(panel, "widget");
        await(() -> rowCount(panel) == 1);

        PackageRowRenderer.Row row = firstRow(panel);
        assertEquals("local", row.badge());
        assertEquals("instalada", row.secondaryBadge());
        assertTrue(host.lastQuery.get().equals("widget"));
    }

    @Test
    void remoteOnlyArtifactsKeepTheInstalledBadgeAlone() throws Exception {
        DependencyCoordinate widget = DependencyCoordinate.of("com.acme", "widget", "3.0.0");
        FakeHost host = new FakeHost();
        host.declared = List.of(widget);
        host.results = List.of(new DependencySearchResult(widget, 4, 100, false, true,
                null, List.of()));

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        search(panel, "widget");
        await(() -> rowCount(panel) == 1);

        assertEquals("instalada", firstRow(panel).badge());
        assertEquals("", firstRow(panel).secondaryBadge());
    }

    @Test
    void addingALocalVersionWritesOnlyTheVersionNumber() throws Exception {
        DependencyCoordinate widget = DependencyCoordinate.of("com.acme", "widget", "3.0.0");
        FakeHost host = new FakeHost();
        host.results = List.of(new DependencySearchResult(widget, 2, 100, true, false,
                Path.of("D:/MavenRepository"), List.of("3.0.0-SNAPSHOT")));
        host.versions = List.of(new DependencyVersionChoice("3.0.0-SNAPSHOT", true, false));

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        search(panel, "widget");
        await(() -> rowCount(panel) == 1);
        onEdtRun(() -> list(panel).setSelectedIndex(0));
        await(() -> versionSelector(panel).getItemCount() == 1);

        onEdtRun(() -> button(panel, "Adicionar").doClick());

        await(() -> host.added.get() != null);
        assertEquals("com.acme:widget:3.0.0-SNAPSHOT", host.added.get().notation());
        assertFalse(host.added.get().version().contains("local"));
    }

    @Test
    void localOnlyVersionsAreBlockedWhileGradleHasNoMavenLocal() throws Exception {
        DependencyCoordinate widget = DependencyCoordinate.of("com.acme", "widget", "3.0.0");
        FakeHost host = new FakeHost();
        host.canInstallLocal = false;
        host.results = List.of(new DependencySearchResult(widget, 2, 100, true, false,
                Path.of("D:/MavenRepository"), List.of("3.0.0")));
        host.versions = List.of(new DependencyVersionChoice("3.0.0", true, false));

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        search(panel, "widget");
        await(() -> rowCount(panel) == 1);
        onEdtRun(() -> list(panel).setSelectedIndex(0));

        await(() -> !button(panel, "Adicionar").isEnabled());
        assertTrue(button(panel, "Adicionar").getToolTipText().contains("mavenLocal()"));

        onEdtRun(() -> button(panel, "Adicionar").doClick());
        assertNull(host.added.get());
    }

    @Test
    void localOnlyVersionsInstallWhenTheBuildResolvesMavenLocal() throws Exception {
        DependencyCoordinate widget = DependencyCoordinate.of("com.acme", "widget", "3.0.0");
        FakeHost host = new FakeHost();
        host.results = List.of(new DependencySearchResult(widget, 2, 100, true, false,
                Path.of("D:/MavenRepository"), List.of("3.0.0")));
        host.versions = List.of(new DependencyVersionChoice("3.0.0", true, false));

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        search(panel, "widget");
        await(() -> rowCount(panel) == 1);
        onEdtRun(() -> list(panel).setSelectedIndex(0));

        await(() -> button(panel, "Adicionar").isEnabled());
        assertNull(button(panel, "Adicionar").getToolTipText());
    }

    @Test
    void thePreReleaseFilterReachesBothOrigins() throws Exception {
        FakeHost host = new FakeHost();
        host.results = List.of(new DependencySearchResult(
                DependencyCoordinate.of("com.acme", "widget", "3.0.0"), 2, 100, true, true,
                Path.of("D:/MavenRepository"), List.of("3.0.0")));

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        search(panel, "widget");
        await(() -> rowCount(panel) == 1 && Boolean.FALSE.equals(host.lastPreRelease.get()));

        onEdtRun(() -> checkBox(panel, "Incluir pre-releases").doClick());

        await(() -> Boolean.TRUE.equals(host.lastPreRelease.get()));
    }

    @Test
    void repositoryEventsRefreshTheOpenSearchWithoutANewWebCall() throws Exception {
        FakeHost host = new FakeHost();
        host.results = List.of(new DependencySearchResult(
                DependencyCoordinate.of("com.acme", "widget", "3.0.0"), 2, 100, true, true,
                Path.of("D:/MavenRepository"), List.of("3.0.0")));

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        search(panel, "widget");
        await(() -> rowCount(panel) == 1);
        int searches = host.searches.size();

        host.results = List.of(new DependencySearchResult(
                DependencyCoordinate.of("com.acme", "widget", "4.0.0"), 3, 200, true, true,
                Path.of("D:/MavenRepository"), List.of("4.0.0", "3.0.0")));
        onEdtRun(() -> host.localChange.get().run());

        await(() -> firstRow(panel).meta().contains("4.0.0"));
        assertEquals(searches + 1, host.searches.size());
        assertEquals(List.of("widget", "widget"), host.searches);
    }

    @Test
    void aPartialWebFailureStillShowsTheLocalIndex() throws Exception {
        FakeHost host = new FakeHost();
        host.remoteStatus = DependencyManagerPanel.RemoteStatus.FAILED;
        host.results = List.of(new DependencySearchResult(
                DependencyCoordinate.of("com.acme", "widget", "3.0.0"), 1, 100, true, false,
                Path.of("D:/MavenRepository"), List.of("3.0.0")));

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        search(panel, "widget");

        await(() -> rowCount(panel) == 1);
        assertEquals("local", firstRow(panel).badge());
    }

    @Test
    void aSkippedWebSearchShowsANeutralLocalStatusInsteadOfAFailureBadge() throws Exception {
        FakeHost host = new FakeHost();
        host.remoteStatus = DependencyManagerPanel.RemoteStatus.SKIPPED;
        host.results = List.of(new DependencySearchResult(
                DependencyCoordinate.of("com.acme", "widget", "3.0.0"), 1, 100, true, false,
                Path.of("D:/MavenRepository"), List.of("3.0.0")));

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        search(panel, "wi");

        await(() -> rowCount(panel) == 1);
        BadgeLabel status = find(panel, BadgeLabel.class);
        assertEquals(BadgeLabel.Tone.NEUTRAL, status.getTone());
        assertTrue(status.getText().contains("repositorio Maven local"));
        assertFalse(status.getText().contains("indisponivel"));
    }

    @Test
    void theUpdatesTabNeverOffersADowngrade() throws Exception {
        DependencyCoordinate lombok = DependencyCoordinate.of(
                "org.projectlombok", "lombok", "1.18.46");
        FakeHost host = new FakeHost();
        host.declared = List.of(lombok);
        host.inventory = new DependencyInventorySnapshot(List.of(new ManagedDependency(
                lombok, lombok, DependencyVersionOrigin.DIRECT,
                Path.of("demo/pom.xml"), "")), List.of(), false);
        host.latest = Map.of(lombok.key(), "1.18.38");

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        onEdtRun(() -> find(panel, SegmentedField.class).setSelectedIndex(2));

        await(() -> host.latestRequests.get() == 1);
        assertEquals(0, rowCount(panel));
        assertFalse(button(panel, "Atualizar todas").isEnabled());
    }

    @Test
    void staleResponsesNeverReplaceTheLatestQuery() throws Exception {
        DependencyCoordinate stale = DependencyCoordinate.of("com.acme", "stale", "1.0.0");
        DependencyCoordinate fresh = DependencyCoordinate.of("com.acme", "fresh", "1.0.0");
        List<Runnable> deferred = new ArrayList<>();
        FakeHost host = new FakeHost() {
            @Override
            public void search(String query, boolean includePreReleases,
                               Consumer<DependencyManagerPanel.SearchOutcome> onResult) {
                searches.add(query);
                DependencyCoordinate coordinate = query.equals("stale") ? stale : fresh;
                deferred.add(() -> onResult.accept(new DependencyManagerPanel.SearchOutcome(
                        List.of(new DependencySearchResult(coordinate, 1, 100, true, true,
                                Path.of("D:/MavenRepository"), List.of("1.0.0"))),
                        DependencyManagerPanel.RemoteStatus.OK, false)));
            }
        };

        DependencyManagerPanel panel = onEdt(() -> new DependencyManagerPanel(host));
        search(panel, "stale");
        await(() -> host.searches.size() == 1);
        search(panel, "fresh");
        await(() -> host.searches.size() == 2);

        onEdtRun(() -> deferred.get(1).run());
        onEdtRun(() -> deferred.get(0).run());

        assertEquals(1, rowCount(panel));
        assertEquals("fresh", firstRow(panel).coordinate().artifactId());
    }

    private static class FakeHost implements DependencyManagerPanel.Host {
        private List<DependencySearchResult> results = List.of();
        private List<DependencyVersionChoice> versions = List.of();
        private List<DependencyCoordinate> declared = List.of();
        private DependencyInventorySnapshot inventory = DependencyInventorySnapshot.empty();
        private Map<String, String> latest = Map.of();
        private boolean canInstallLocal = true;
        private DependencyManagerPanel.RemoteStatus remoteStatus =
                DependencyManagerPanel.RemoteStatus.OK;
        final List<String> searches = new ArrayList<>();
        private final AtomicReference<String> lastQuery = new AtomicReference<>();
        private final AtomicReference<Boolean> lastPreRelease = new AtomicReference<>();
        private final AtomicReference<DependencyCoordinate> added = new AtomicReference<>();
        private final AtomicReference<Runnable> localChange = new AtomicReference<>(() -> { });
        private final java.util.concurrent.atomic.AtomicInteger latestRequests =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public List<JavaModule> modules() {
            return List.of(MODULE);
        }

        @Override
        public List<DependencyCoordinate> declaredDependencies(JavaModule module) {
            return declared;
        }

        @Override
        public void search(String query, boolean includePreReleases,
                           Consumer<DependencyManagerPanel.SearchOutcome> onResult) {
            searches.add(query);
            lastQuery.set(query);
            lastPreRelease.set(includePreReleases);
            onResult.accept(new DependencyManagerPanel.SearchOutcome(results, remoteStatus, false));
        }

        @Override
        public void versions(DependencyCoordinate coordinate, boolean includePreReleases,
                             Consumer<List<DependencyVersionChoice>> onResult) {
            onResult.accept(versions);
        }

        @Override
        public boolean canInstallLocal(JavaModule module) {
            return canInstallLocal;
        }

        @Override
        public void onLocalRepositoryChanged(Runnable listener) {
            localChange.set(listener);
        }

        @Override
        public void latestVersions(List<DependencyCoordinate> coordinates,
                                   Consumer<Map<String, String>> onResult) {
            latestRequests.incrementAndGet();
            onResult.accept(latest);
        }

        @Override
        public void inventory(JavaModule module, List<DependencyCoordinate> declaredCoordinates,
                              Consumer<DependencyInventorySnapshot> onResult) {
            onResult.accept(inventory);
        }

        @Override
        public void health(JavaModule module, DependencyInventorySnapshot inventory,
                           Consumer<DependencyHealthSnapshot> onResult) {
            onResult.accept(DependencyHealthSnapshot.empty());
        }

        @Override
        public void add(JavaModule module, DependencyCoordinate coordinate,
                        Consumer<Boolean> onDone) {
            added.set(coordinate);
            onDone.accept(true);
        }

        @Override
        public void remove(JavaModule module, DependencyCoordinate coordinate,
                           Consumer<Boolean> onDone) {
            onDone.accept(true);
        }

        @Override
        public void updateVersion(JavaModule module, ManagedDependency dependency, String version,
                                  Consumer<Boolean> onDone) {
            onDone.accept(true);
        }
    }

    private static void search(DependencyManagerPanel panel, String query) throws Exception {
        onEdtRun(() -> {
            MaskedTextField field = find(panel, MaskedTextField.class);
            field.setText(query);
            field.postActionEvent();
        });
    }

    @SuppressWarnings("unchecked")
    private static JList<PackageRowRenderer.Row> list(DependencyManagerPanel panel) {
        return (JList<PackageRowRenderer.Row>) find(panel, JList.class);
    }

    private static int rowCount(DependencyManagerPanel panel) {
        return onEdtUnchecked(() -> list(panel).getModel().getSize());
    }

    private static PackageRowRenderer.Row firstRow(DependencyManagerPanel panel) {
        return onEdtUnchecked(() -> list(panel).getModel().getElementAt(0));
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<DependencyVersionChoice> versionSelector(
            DependencyManagerPanel panel) {
        List<JComboBox<?>> boxes = new ArrayList<>();
        collect(panel, JComboBox.class, boxes);
        return (JComboBox<DependencyVersionChoice>) boxes.stream()
                .filter(box -> box.getItemCount() == 0
                        || box.getItemAt(0) instanceof DependencyVersionChoice)
                .findFirst().orElseThrow(() -> new AssertionError("version selector not found"));
    }

    private static AbstractButton button(DependencyManagerPanel panel, String label) {
        List<AbstractButton> buttons = new ArrayList<>();
        collect(panel, AbstractButton.class, buttons);
        return buttons.stream().filter(button -> label.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("button not found: " + label));
    }

    private static JCheckBox checkBox(DependencyManagerPanel panel, String label) {
        List<JCheckBox> boxes = new ArrayList<>();
        collect(panel, JCheckBox.class, boxes);
        return boxes.stream().filter(box -> label.equals(box.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("checkbox not found: " + label));
    }

    @SuppressWarnings("unchecked")
    private static <T> void collect(Container root, Class<? super T> type, List<T> found) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                found.add((T) component);
            }
            if (component instanceof Container child) {
                collect(child, type, found);
            }
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        if (found.isEmpty()) {
            throw new AssertionError(type.getSimpleName() + " not found");
        }
        return found.getFirst();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            flushEdt();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static void flushEdt() throws Exception {
        onEdt(() -> null);
    }

    private static void onEdtRun(Runnable action) throws Exception {
        onEdt(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T onEdtUnchecked(ThrowingSupplier<T> action) {
        try {
            return onEdt(action);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T onEdt(ThrowingSupplier<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.get());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
