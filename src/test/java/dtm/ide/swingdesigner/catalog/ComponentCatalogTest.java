package dtm.ide.swingdesigner.catalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentCatalogTest {

    private static final String LIBRARY_JSON = """
            {
              "library": { "name": "LibUi", "category": "Lib UI" },
              "hide": ["lib.ui.internal.**"],
              "components": {
                "lib.ui.Switch": {
                  "displayName": "Switch",
                  "icon": "icons/switch.svg",
                  "properties": { "on": { "preferred": true, "editor": "boolean" } }
                },
                "lib.ui.Card": {
                  "container": { "kind": "LIST", "strategy": "addContent(${child})" }
                }
              }
            }
            """;

    @TempDir
    static Path fixtures;

    @TempDir
    Path root;

    private static Path workspace;
    private static Path libraryJar;

    private ClasspathIndex index;

    @BeforeAll
    static void compileFixtures() throws IOException {
        workspace = CatalogFixtures.compile(fixtures.resolve("ws"),
                CatalogFixtures.WORKSPACE_SOURCES, List.of());
        Path libraryClasses = CatalogFixtures.compile(fixtures.resolve("lib"),
                CatalogFixtures.LIBRARY_SOURCES, List.of());
        libraryJar = CatalogFixtures.jar(libraryClasses, fixtures.resolve("lib-ui-2.3.1.jar"),
                Map.of(DescriptorSetReader.LIBRARY_RESOURCE, LIBRARY_JSON));
    }

    @BeforeEach
    void setUp() {
        index = ClasspathIndex.build(List.of(ClasspathEntry.workspace(workspace, "demo"),
                ClasspathEntry.library(libraryJar)), JarHeaderCache.memoryOnly());
    }

    @AfterEach
    void tearDown() {
        index.close();
        JarHeaderCache.clearMemory();
    }

    @Test
    void anythingThatInheritsFromComponentIsDrawable() {
        assertTrue(index.isDrawable("fx.FluentBox"));
        assertTrue(index.isDrawable("fx.Deep"));
        assertTrue(index.isDrawable("fx.Labeled"));
        assertTrue(index.isDrawable("fx.MyFrame"));
        assertTrue(index.isDrawable("lib.ui.Switch"));
        assertFalse(index.isDrawable("fx.NotUi"));
        assertFalse(index.isDrawable("fx.ThingListener"));
    }

    @Test
    void fluentSettersAreWritableProperties() {
        ComponentDescriptor box = catalog().declared("fx.FluentBox").orElseThrow();

        PropertyDescriptor title = box.properties().get("title");
        assertEquals("java.lang.String", title.type());
        assertEquals(SetterStyle.FLUENT, title.setterStyle());
        assertEquals("getTitle", title.getter());
        assertFalse(title.isHidden());
    }

    @Test
    void writeOnlyPropertiesAreKept() {
        PropertyDescriptor animated = catalog().declared("fx.FluentBox").orElseThrow()
                .properties().get("animated");

        assertEquals("boolean", animated.type());
        assertEquals(SetterStyle.VOID, animated.setterStyle());
        assertNull(animated.getter());
        assertTrue(animated.isWritable());
    }

    @Test
    void multiArgumentSettersStayHiddenUntilMapped() {
        PropertyDescriptor range = catalog().declared("fx.FluentBox").orElseThrow()
                .properties().get("range");

        assertTrue(range.isHidden());
        assertEquals(2, range.arity());
        assertEquals(List.of("min", "max"),
                range.setterParameters().stream().map(ParameterInfo::name).toList());
    }

    @Test
    void enumPropertiesExposeTheirConstants() {
        PropertyDescriptor mode = catalog().declared("fx.FluentBox").orElseThrow()
                .properties().get("mode");

        assertEquals("fx.FluentBox$Mode", mode.type());
        assertEquals("enum", mode.editor());
        assertEquals(List.of("COMPACT", "WIDE"), mode.enumValues());
    }

    @Test
    void deprecatedSettersAreIgnored() {
        assertFalse(catalog().declared("fx.FluentBox").orElseThrow().properties()
                .containsKey("legacy"));
    }

    @Test
    void listenerRegistrationsBecomeEvents() {
        EventDescriptor event = catalog().declared("fx.FluentBox").orElseThrow().events()
                .stream().filter(e -> e.listenerType().equals("fx.ThingListener"))
                .findFirst().orElseThrow();

        assertEquals("addThingListener", event.addMethod());
        assertEquals("removeThingListener", event.removeMethod());
        assertEquals(List.of("thingHappened", "thingCleared"),
                event.methods().stream().map(EventDescriptor.EventMethod::name).toList());
        assertEquals("java.awt.event.ActionEvent", event.methods().getFirst().eventType());
    }

    @Test
    void constructorsWithArgumentsKeepTheirParameterNames() {
        ComponentDescriptor labeled = catalog().declared("fx.Labeled").orElseThrow();

        ConstructorInfo constructor = labeled.constructors().getFirst();
        assertEquals(List.of(new ParameterInfo("size", "long"),
                new ParameterInfo("caption", "java.lang.String")), constructor.parameters());
    }

    @Test
    void staticFactoriesAreConstructionOptions() {
        ComponentDescriptor factory = catalog().declared("fx.Factory").orElseThrow();

        assertEquals(1, factory.constructors().size());
        ConstructorInfo create = factory.constructors().getFirst();
        assertTrue(create.isFactory());
        assertEquals("create", create.factoryMethod());
        assertEquals(List.of(new ParameterInfo("caption", "java.lang.String")), create.parameters());
    }

    @Test
    void propertiesAndContainerAreInheritedThroughTheWholeChain() {
        ComponentDescriptor deep = catalog().descriptor("fx.Deep").orElseThrow();

        assertTrue(deep.properties().containsKey("title"));
        assertTrue(deep.properties().get("toolTipText").isPreferred());
        assertTrue(deep.properties().get("layout").isHidden());
        assertTrue(deep.properties().get("background").isPreferred());
        assertEquals(ContainerKind.LAYOUT, deep.container().kind());
        assertTrue(deep.eventsOrEmpty().stream()
                .anyMatch(e -> e.listenerType().equals("java.awt.event.MouseListener")));
    }

    @Test
    void subclassesOfWindowsAreMarkedAsWindows() {
        ComponentDescriptor frame = catalog().descriptor("fx.MyFrame").orElseThrow();

        assertTrue(frame.isWindow());
        assertEquals("getContentPane()", frame.container().layoutTarget());
        assertEquals("setJMenuBar(${child})", frame.container().slots().get("menuBar"));
    }

    @Test
    void paletteShowsEveryPublicConcreteDrawableWithoutMetadata() {
        List<String> classes = catalog().palette().stream()
                .map(ComponentCatalog.PaletteEntry::className).toList();

        assertTrue(classes.containsAll(List.of("fx.FluentBox", "fx.Deep", "fx.Labeled",
                "fx.Factory", "fx.MyFrame", "lib.ui.Switch", "lib.ui.Card")));
        assertFalse(classes.contains("fx.Base"));
        assertFalse(classes.contains("fx.Hidden"));
        assertFalse(classes.contains("fx.NotUi"));
        assertFalse(classes.contains("fx.FluentBox$Mode"));
    }

    @Test
    void workspaceComponentsComeFirstUnderTheirModuleCategory() {
        ComponentCatalog.PaletteEntry first = catalog().palette().getFirst();

        assertEquals(ComponentOrigin.WORKSPACE, first.origin());
        assertEquals("Projeto: demo", first.category());
    }

    @Test
    void jdkComponentsComeFromTheBuiltInDescriptor() {
        ComponentCatalog.PaletteEntry button = paletteEntry(catalog(), "javax.swing.JButton")
                .orElseThrow();

        assertEquals("Button", button.label());
        assertEquals("Swing · Controles", button.category());
        assertEquals(ComponentOrigin.JDK, button.origin());
        assertTrue(paletteEntry(catalog(), "javax.swing.JFrame").orElseThrow().window());
        assertTrue(paletteEntry(catalog(), "javax.swing.plaf.basic.BasicArrowButton").isEmpty());
    }

    @Test
    void libraryDescriptorDescribesAndHidesItsOwnComponents() {
        ComponentCatalog catalog = catalog();

        ComponentCatalog.PaletteEntry toggle = paletteEntry(catalog, "lib.ui.Switch").orElseThrow();
        assertEquals("Switch", toggle.label());
        assertEquals("Lib UI", toggle.category());
        assertEquals("icons/switch.svg", toggle.icon());
        assertTrue(toggle.described());
        assertEquals("Lib UI", paletteEntry(catalog, "lib.ui.Card").orElseThrow().category());
        assertTrue(paletteEntry(catalog, "lib.ui.internal.Gutter").isEmpty());

        ComponentDescriptor card = catalog.descriptor("lib.ui.Card").orElseThrow();
        assertEquals(ContainerKind.LIST, card.container().kind());
        assertEquals("addContent(${child})", card.container().childStrategy());
        assertTrue(catalog.descriptor("lib.ui.Switch").orElseThrow()
                .properties().get("on").isPreferred());
    }

    @Test
    void hiddenComponentsCanStillBeDescribedForCustomPicks() {
        assertTrue(catalog().descriptor("lib.ui.internal.Gutter").isPresent());
    }

    @Test
    void projectOverrideWinsOverLibraryDescriptor() throws IOException {
        Path projectRoot = root.resolve("project");
        Files.createDirectories(projectRoot.resolve(".orion"));
        Files.writeString(projectRoot.resolve(DescriptorSetReader.PROJECT_FILE), """
                {
                  // comentarios sao aceitos
                  "hide": ["fx.Factory"],
                  "components": {
                    "lib.ui.Switch": { "displayName": "Interruptor", "category": "Meus" },
                    "lib.ui.internal.Gutter": { "hidden": false },
                    "fx.FluentBox": {
                      "designInit": ["montar"],
                      "properties": { "range": { "displayName": "Intervalo" } }
                    }
                  }
                }
                """);
        ComponentCatalog catalog = ComponentCatalog.standard(index,
                DescriptorSetReader.project(projectRoot).orElseThrow());

        ComponentCatalog.PaletteEntry toggle = paletteEntry(catalog, "lib.ui.Switch").orElseThrow();
        assertEquals("Interruptor", toggle.label());
        assertEquals("Meus", toggle.category());
        assertTrue(paletteEntry(catalog, "fx.Factory").isEmpty());
        assertTrue(paletteEntry(catalog, "lib.ui.internal.Gutter").isPresent());
        PropertyDescriptor range = catalog.descriptor("fx.FluentBox").orElseThrow()
                .properties().get("range");
        assertEquals("Intervalo", range.label());
        assertEquals(2, range.arity());
        assertEquals(List.of("montar"), catalog.descriptor("fx.Deep").orElseThrow().designInitOrEmpty());
    }

    @Test
    void bundledLibraryDescriptorsDeclareLifecycleHooksForSubclasses() {
        ComponentDescriptor screen = catalog().descriptor("lib.ui.MainScreen").orElseThrow();

        assertEquals(List.of("dispatchDrawing"), screen.designInitOrEmpty());
        assertTrue(catalog().descriptor("fx.FluentBox").orElseThrow().designInitOrEmpty().isEmpty());
    }

    @Test
    void layoutsAreReadFromDescriptors() {
        Map<String, LayoutDescriptor> layouts = catalog().layouts();

        assertEquals("border", layouts.get("java.awt.BorderLayout").dropPolicy());
        assertEquals("java.awt.GridBagConstraints",
                layouts.get("java.awt.GridBagLayout").constraintsType());
    }

    @Test
    void headerCacheSurvivesOnDisk() {
        Path cacheDir = root.resolve("cache");
        JarHeaderCache cache = new JarHeaderCache(cacheDir);
        try (ClasspathIndex first = ClasspathIndex.build(List.of(ClasspathEntry.library(libraryJar)),
                cache)) {
            assertTrue(first.isDrawable("lib.ui.Switch"));
        }
        JarHeaderCache.clearMemory();

        try (ClasspathIndex second = ClasspathIndex.build(List.of(ClasspathEntry.library(libraryJar)),
                new JarHeaderCache(cacheDir))) {
            assertTrue(second.isDrawable("lib.ui.Switch"));
            assertTrue(second.header("lib.ui.Card").orElseThrow().accessible());
        }
    }

    @Test
    void entryLabelsDropJarVersions() {
        assertEquals("lib-ui", ClasspathEntry.library(libraryJar).label());
        assertEquals("SwingTools", ClasspathEntry.library(Path.of("SwingTools-1.4.0.jar")).label());
        assertEquals("app", ClasspathEntry.defaultLabel(Path.of("app", "target", "classes")));
    }

    @Test
    void hidePatternsSupportPackagesAndWildcards() {
        assertTrue(DescriptorSet.matches("a.b.**", "a.b.c.D"));
        assertTrue(DescriptorSet.matches("a.b.**", "a.b.D"));
        assertTrue(DescriptorSet.matches("a.b.*", "a.b.D"));
        assertFalse(DescriptorSet.matches("a.b.*", "a.b.c.D"));
        assertTrue(DescriptorSet.matches("a.b.*Renderer", "a.b.CellRenderer"));
        assertTrue(DescriptorSet.matches("*Renderer", "CellRenderer"));
        assertFalse(DescriptorSet.matches("a.b.*Renderer", "a.b.c.CellRenderer"));
        assertTrue(DescriptorSet.matches("a.b.C", "a.b.C"));
    }

    private ComponentCatalog catalog() {
        return ComponentCatalog.standard(index, null);
    }

    private static Optional<ComponentCatalog.PaletteEntry> paletteEntry(ComponentCatalog catalog,
                                                                       String className) {
        return catalog.palette().stream().filter(entry -> entry.className().equals(className))
                .findFirst();
    }
}
