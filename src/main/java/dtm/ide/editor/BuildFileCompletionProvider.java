package dtm.ide.editor;

import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.DependencySearchMerger;
import dtm.ide.deps.DependencySearchResult;
import dtm.ide.deps.DependencyVersionChoice;
import dtm.ide.deps.MavenCentralClient;
import dtm.ide.deps.PomProperties;
import dtm.ide.project.JavaProjectConventions;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuildFileCompletionProvider {

    private static final int MIN_REMOTE_PREFIX = 3;
    private static final int MAX_PROPERTIES = 80;

    private enum Slot {
        GROUP,
        ARTIFACT,
        VERSION,
        NONE
    }

    private static final Pattern XML_TAG_AT_CARET = Pattern.compile(
            "<(groupId|artifactId|version)\\s*>([^<]*)$");
    private static final Pattern XML_ENCLOSING = Pattern.compile(
            "(?s)<(dependency|plugin|parent)\\b[^>]*>(?:(?!</\\1>).)*$");
    private static final Pattern XML_GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern XML_ARTIFACT =
            Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern PROPERTY_AT_CARET = Pattern.compile("\\$\\{([\\w.\\-]*)$");

    private static final Pattern GRADLE_NOTATION = Pattern.compile(
            "[\"']([^\"']*)$");

    public interface Catalog {

        List<DependencySearchResult> search(String query);

        List<DependencyVersionChoice> versions(String groupId, String artifactId);
    }

    private final Catalog catalog;
    private final PomProperties properties;

    public BuildFileCompletionProvider(MavenCentralClient client) {
        this(remoteOnly(client), new PomProperties(() -> null));
    }

    public BuildFileCompletionProvider(Catalog catalog) {
        this(catalog, new PomProperties(() -> null));
    }

    public BuildFileCompletionProvider(Catalog catalog, PomProperties properties) {
        this.catalog = catalog;
        this.properties = properties;
    }

    public static Catalog remoteOnly(MavenCentralClient client) {
        return new Catalog() {
            @Override
            public List<DependencySearchResult> search(String query) {
                String bare = query == null ? "" : query.replace("*", "");
                if (bare.length() < MIN_REMOTE_PREFIX) {
                    return List.of();
                }
                return DependencySearchMerger.merge(bare, List.of(), client.search(query), true);
            }

            @Override
            public List<DependencyVersionChoice> versions(String groupId, String artifactId) {
                return DependencySearchMerger.mergeVersions(List.of(),
                        client.versions(groupId, artifactId));
            }
        };
    }

    public static boolean handles(Path filePath) {
        return JavaProjectConventions.isMavenPom(filePath)
                || JavaProjectConventions.isGradleBuildFile(filePath);
    }

    public List<AutoCompleteItem> suggestions(IdeCompletionContext context) {
        if (context == null || !handles(context.filePath())) {
            return List.of();
        }
        Insertion insertion = Insertion.of(context);
        String before = insertion.before();
        if (!JavaProjectConventions.isMavenPom(context.filePath())) {
            return gradleSuggestions(before, insertion);
        }
        Matcher property = PROPERTY_AT_CARET.matcher(before);
        if (property.find()) {
            return propertySuggestions(context, property.group(1), insertion);
        }
        return mavenSuggestions(before, insertion);
    }

    private List<AutoCompleteItem> propertySuggestions(IdeCompletionContext context, String typed,
                                                       Insertion insertion) {
        List<PomProperties.Declaration> declarations =
                properties.declarations(context.filePath(), context.text());
        String needle = typed.toLowerCase(Locale.ROOT);
        List<PomProperties.Declaration> starting = new ArrayList<>();
        List<PomProperties.Declaration> containing = new ArrayList<>();
        for (PomProperties.Declaration declaration : declarations) {
            String name = declaration.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(needle)) {
                starting.add(declaration);
            } else if (!needle.isEmpty() && name.contains(needle)) {
                containing.add(declaration);
            }
        }
        starting.addAll(containing);
        String suffix = insertion.nextChar() == '}' ? "" : "}";
        Path own = context.filePath() == null ? null : context.filePath().toAbsolutePath().normalize();
        List<AutoCompleteItem> items = new ArrayList<>();
        for (PomProperties.Declaration declaration : starting) {
            if (items.size() >= MAX_PROPERTIES) {
                break;
            }
            String value = properties.resolve(declaration.value(), declarations);
            String origin = declaration.file() == null ? "built-in"
                    : declaration.file().equals(own) ? "pom.xml"
                    : declaration.file().getFileName().toString();
            items.add(insertion.item(declaration.name(), typed, suffix,
                    value.isBlank() ? origin : value, origin, AutoCompleteItem.Kind.PROPERTY));
        }
        return List.copyOf(items);
    }

    private List<AutoCompleteItem> mavenSuggestions(String before, Insertion insertion) {
        Matcher tag = XML_TAG_AT_CARET.matcher(before);
        if (!tag.find() || !insideDependencyBlock(before)) {
            return List.of();
        }
        Slot slot = switch (tag.group(1)) {
            case "groupId" -> Slot.GROUP;
            case "artifactId" -> Slot.ARTIFACT;
            case "version" -> Slot.VERSION;
            default -> Slot.NONE;
        };
        String typed = tag.group(2).trim();
        String group = lastMatch(XML_GROUP, before);
        String artifact = lastMatch(XML_ARTIFACT, before);

        return switch (slot) {
            case GROUP -> coordinates(typed, DependencyCoordinate::groupId, insertion);
            case ARTIFACT -> artifactsOf(group, typed, insertion);
            case VERSION -> versions(group, artifact, typed, insertion);
            default -> List.of();
        };
    }

    private static boolean insideDependencyBlock(String before) {
        return XML_ENCLOSING.matcher(before).find();
    }

    private List<AutoCompleteItem> gradleSuggestions(String before, Insertion insertion) {
        Matcher quoted = GRADLE_NOTATION.matcher(before);
        if (!quoted.find()) {
            return List.of();
        }
        String typed = quoted.group(1);
        String[] parts = typed.split(":", -1);
        return switch (parts.length) {
            case 1 -> coordinates(parts[0], DependencyCoordinate::groupId, insertion);
            case 2 -> artifactsOf(parts[0], parts[1], insertion);
            case 3 -> versions(parts[0], parts[1], parts[2], insertion);
            default -> List.of();
        };
    }

    private List<AutoCompleteItem> coordinates(String typed, Function<DependencyCoordinate, String> field,
                                               Insertion insertion) {
        if (typed == null || typed.isBlank()) {
            return List.of();
        }
        List<AutoCompleteItem> items = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (DependencySearchResult result : catalog.search(typed)) {
            String value = field.apply(result.coordinate());
            if (value.isBlank() || seen.contains(value)) {
                continue;
            }
            seen.add(value);
            items.add(insertion.item(value, typed, "", detail(result.coordinate().key(), result.local()),
                    null, AutoCompleteItem.Kind.VALUE));
        }
        return List.copyOf(items);
    }

    private List<AutoCompleteItem> artifactsOf(String group, String typed, Insertion insertion) {
        String query = group == null || group.isBlank() ? typed : group + ":" + typed + "*";
        if (query == null || query.replace("*", "").isBlank()) {
            return List.of();
        }
        List<AutoCompleteItem> items = new ArrayList<>();
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        for (DependencySearchResult result : catalog.search(query)) {
            String artifact = result.coordinate().artifactId();
            if (artifact.isBlank() || !artifact.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            items.add(insertion.item(artifact, typed, "", detail(result.coordinate().key(), result.local()),
                    null, AutoCompleteItem.Kind.VALUE));
        }
        return List.copyOf(items);
    }

    private List<AutoCompleteItem> versions(String group, String artifact, String typed,
                                            Insertion insertion) {
        if (group == null || group.isBlank() || artifact == null || artifact.isBlank()) {
            return List.of();
        }
        String prefix = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        List<DependencyVersionChoice> all = catalog.versions(group, artifact);
        List<AutoCompleteItem> items = new ArrayList<>();
        for (boolean stable : new boolean[]{true, false}) {
            for (DependencyVersionChoice choice : all) {
                String version = choice.version();
                if (MavenCentralClient.isStable(version) == stable
                        && version.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    items.add(insertion.item(version, typed, "",
                            detail(group + ":" + artifact, choice.local()), null,
                            AutoCompleteItem.Kind.VALUE));
                }
            }
        }
        return List.copyOf(items);
    }

    private static String detail(String key, boolean local) {
        return local ? key + " (local)" : key;
    }

    private static String lastMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        String last = "";
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private record Insertion(String text, int caret, int wordStart, String before) {

        static Insertion of(IdeCompletionContext context) {
            String text = context.text() == null ? "" : context.text();
            int caret = Math.max(0, Math.min(context.caretOffset(), text.length()));
            int wordStart = caret;
            while (wordStart > 0 && (Character.isLetterOrDigit(text.charAt(wordStart - 1))
                    || text.charAt(wordStart - 1) == '_')) {
                wordStart--;
            }
            int from = Math.max(0, caret - 4000);
            return new Insertion(text, caret, wordStart, text.substring(from, caret));
        }

        char nextChar() {
            return caret < text.length() ? text.charAt(caret) : '\0';
        }

        AutoCompleteItem item(String value, String typed, String suffix, String detail,
                              String description, AutoCompleteItem.Kind kind) {
            String written = typed == null ? "" : typed;
            int word = caret - wordStart;
            String kept = written.length() >= word ? written.substring(0, written.length() - word) : "";
            if (value.startsWith(kept)) {
                return new AutoCompleteItem(value.substring(kept.length()) + suffix, value, detail,
                        description, null, kind, List.of());
            }
            int keptStart = wordStart - kept.length();
            TextEdit removeKept = TextEdit.delete(new Range(position(keptStart), position(wordStart)));
            return new AutoCompleteItem(value + suffix, value, detail, description, null, kind,
                    List.of(removeKept));
        }

        private Position position(int offset) {
            int line = 0;
            int lineStart = 0;
            for (int index = 0; index < offset && index < text.length(); index++) {
                if (text.charAt(index) == '\n') {
                    line++;
                    lineStart = index + 1;
                }
            }
            return new Position(line, offset - lineStart);
        }
    }
}
