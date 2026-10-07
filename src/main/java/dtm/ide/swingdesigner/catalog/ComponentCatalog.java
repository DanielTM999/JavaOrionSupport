package dtm.ide.swingdesigner.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ComponentCatalog {

    public static final String JDK_CATEGORY = "Swing";
    public static final String WORKSPACE_CATEGORY_PREFIX = "Projeto: ";

    private static final List<String> INTERNAL_MARKERS = List.of(".internal.", ".impl.",
            ".delegated.", ".sun.", ".plaf.");

    private final ClasspathIndex index;
    private final BytecodeComponentScanner scanner;
    private final List<DescriptorSet> layers;
    private final Map<String, Optional<ComponentDescriptor>> declared = new ConcurrentHashMap<>();
    private final Map<String, Optional<ComponentDescriptor>> effective = new ConcurrentHashMap<>();
    private volatile List<PaletteEntry> palette;

    private ComponentCatalog(ClasspathIndex index, List<DescriptorSet> layers) {
        this.index = index;
        this.scanner = new BytecodeComponentScanner(index);
        this.layers = List.copyOf(layers);
    }

    public static ComponentCatalog build(ClasspathIndex index, List<DescriptorSet> layers) {
        return new ComponentCatalog(index, layers);
    }

    public static ComponentCatalog standard(ClasspathIndex index, DescriptorSet project) {
        List<DescriptorSet> layers = new ArrayList<>();
        layers.add(DescriptorSetReader.jdk());
        layers.addAll(DescriptorSetReader.bundled());
        layers.addAll(DescriptorSetReader.libraries(index));
        if (project != null) {
            layers.add(project);
        }
        return build(index, layers);
    }

    public ClasspathIndex index() {
        return index;
    }

    public List<DescriptorSet> layers() {
        return layers;
    }

    public boolean isDrawable(String className) {
        return index.isDrawable(className);
    }

    public Optional<ComponentDescriptor> declared(String className) {
        return declared.computeIfAbsent(className, this::computeDeclared);
    }

    public Optional<ComponentDescriptor> descriptor(String className) {
        Optional<ComponentDescriptor> cached = effective.get(className);
        if (cached != null) {
            return cached;
        }
        Optional<ComponentDescriptor> computed = computeEffective(className);
        effective.put(className, computed);
        return computed;
    }

    public List<String> enumConstants(String className) {
        return scanner.enumConstants(className);
    }

    public List<InjectionRule> injectionRules() {
        Map<String, InjectionRule> rules = new LinkedHashMap<>();
        for (DescriptorSet layer : layers) {
            for (InjectionRule rule : layer.injections()) {
                rules.put(rule.annotation() + "#" + rule.attribute(), rule);
            }
        }
        return List.copyOf(rules.values());
    }

    public DesignerOptions options() {
        DesignerOptions options = DesignerOptions.DEFAULTS;
        for (DescriptorSet layer : layers) {
            options = options.overlay(layer.options());
        }
        return options;
    }

    public Map<String, LayoutDescriptor> layouts() {
        Map<String, LayoutDescriptor> merged = new LinkedHashMap<>();
        for (DescriptorSet layer : layers) {
            layer.layouts().forEach((key, value) -> merged.merge(key, value, LayoutDescriptor::overlay));
        }
        return merged;
    }

    public List<PaletteEntry> palette() {
        List<PaletteEntry> current = palette;
        if (current == null) {
            current = computePalette();
            palette = current;
        }
        return current;
    }

    public List<String> categories() {
        Set<String> categories = new LinkedHashSet<>();
        for (PaletteEntry entry : palette()) {
            categories.add(entry.category());
        }
        return new ArrayList<>(categories);
    }

    public void invalidate(Set<String> classNames) {
        if (classNames == null || classNames.isEmpty()) {
            declared.clear();
            effective.clear();
        } else {
            classNames.forEach(declared::remove);
            effective.clear();
        }
        palette = null;
    }

    private Optional<ComponentDescriptor> computeDeclared(String className) {
        Optional<ComponentDescriptor> scanned = scanner.scan(className);
        if (scanned.isEmpty()) {
            return Optional.empty();
        }
        ComponentDescriptor result = scanned.get();
        ComponentDescriptor layered = layerOverlay(className, result.origin(), result.source());
        return Optional.of(result.overlay(layered));
    }

    private Optional<ComponentDescriptor> computeEffective(String className) {
        Optional<ComponentDescriptor> own = declared(className);
        if (own.isEmpty()) {
            return Optional.empty();
        }
        String parent = own.get().superClass();
        if (parent == null || parent.equals(ClasspathIndex.OBJECT) || !index.isDrawable(parent)) {
            return own;
        }
        Optional<ComponentDescriptor> inherited = descriptor(parent);
        return Optional.of(own.get().inherit(inherited.orElse(null)));
    }

    private ComponentDescriptor layerOverlay(String className, ComponentOrigin origin,
                                             String source) {
        ComponentDescriptor result = ComponentDescriptor.builder(className)
                .category(defaultCategory(origin, source))
                .build();
        for (DescriptorSet layer : layers) {
            if (layer.hides(className)) {
                result = result.overlay(ComponentDescriptor.builder(className).hidden(Boolean.TRUE).build());
            }
            ComponentDescriptor described = layer.components().get(className);
            if (described != null) {
                result = result.overlay(described);
            }
        }
        return result;
    }

    private String defaultCategory(ComponentOrigin origin, String source) {
        if (origin == null || origin == ComponentOrigin.JDK) {
            return JDK_CATEGORY;
        }
        ClasspathEntry entry = entryFor(source);
        String label = entry == null ? "?" : entry.label();
        if (origin == ComponentOrigin.WORKSPACE) {
            return WORKSPACE_CATEGORY_PREFIX + label;
        }
        if (entry != null) {
            for (DescriptorSet layer : layers) {
                if (layer.entry() != null && layer.entry().equals(entry.path())
                        && layer.defaultCategory() != null) {
                    return layer.defaultCategory();
                }
            }
        }
        return label;
    }

    private ClasspathEntry entryFor(String source) {
        if (source == null) {
            return null;
        }
        for (ClasspathEntry entry : index.entries()) {
            if (entry.path().toString().equals(source)) {
                return entry;
            }
        }
        return null;
    }

    private List<PaletteEntry> computePalette() {
        Map<String, PaletteEntry> entries = new LinkedHashMap<>();
        for (ClassHeader header : index.indexedClasses()) {
            if (!header.accessible() || !header.isConcreteClass()
                    || !index.isDrawable(header.name())) {
                continue;
            }
            ClasspathEntry entry = index.entryOf(header).orElse(null);
            ComponentOrigin origin = entry == null ? ComponentOrigin.JDK : entry.origin();
            String source = entry == null ? null : entry.path().toString();
            paletteEntry(header.name(), origin, source).ifPresent(value -> entries.put(value.className(), value));
        }
        for (DescriptorSet layer : layers) {
            for (String className : layer.components().keySet()) {
                if (entries.containsKey(className)) {
                    continue;
                }
                Optional<ClassHeader> header = index.header(className);
                if (header.isEmpty() || !header.get().isJdk() || !header.get().isConcreteClass()
                        || !index.isDrawable(className)) {
                    continue;
                }
                paletteEntry(className, ComponentOrigin.JDK, null)
                        .ifPresent(value -> entries.put(value.className(), value));
            }
        }
        List<PaletteEntry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparingInt(PaletteEntry::rank)
                .thenComparing(PaletteEntry::category, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PaletteEntry::label, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(sorted);
    }

    private Optional<PaletteEntry> paletteEntry(String className, ComponentOrigin origin,
                                                String source) {
        ComponentDescriptor layered = layerOverlay(className, origin, source);
        if (layered.isHidden()) {
            return Optional.empty();
        }
        boolean window = index.isSubtypeOf(className, ClasspathIndex.WINDOW);
        return Optional.of(new PaletteEntry(className, layered.label(), layered.category(),
                layered.icon(), origin, rank(className, origin, layered.isDescribed()),
                layered.isDescribed(), window));
    }

    private static int rank(String className, ComponentOrigin origin, boolean described) {
        if (origin == ComponentOrigin.WORKSPACE) {
            return 0;
        }
        if (described) {
            return 1;
        }
        String lower = "." + className.toLowerCase(Locale.ROOT);
        for (String marker : INTERNAL_MARKERS) {
            if (lower.contains(marker)) {
                return 3;
            }
        }
        return className.indexOf('$') >= 0 ? 3 : 2;
    }

    public record PaletteEntry(String className,
                               String label,
                               String category,
                               String icon,
                               ComponentOrigin origin,
                               int rank,
                               boolean described,
                               boolean window) {
    }
}
