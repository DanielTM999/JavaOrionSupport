package dtm.ide.editor;

import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.deps.DependencyCoordinate;
import dtm.ide.deps.MavenCentralClient;
import dtm.ide.project.JavaProjectConventions;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BuildFileCompletionProvider {

    private static final int MIN_PREFIX = 3;

    private enum Slot {
        GROUP,
        ARTIFACT,
        VERSION,
        NOTATION,
        NONE
    }

    private static final Pattern XML_TAG_AT_CARET = Pattern.compile(
            "<(groupId|artifactId|version)\\s*>([^<]*)$");
    private static final Pattern XML_ENCLOSING = Pattern.compile(
            "(?s)<(dependency|plugin|parent)\\b[^>]*>(?:(?!</\\1>).)*$");
    private static final Pattern XML_GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern XML_ARTIFACT =
            Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");

    private static final Pattern GRADLE_NOTATION = Pattern.compile(
            "[\"']([^\"']*)$");

    public interface Catalog {

        List<MavenCentralClient.SearchResult> search(String query);

        List<String> versions(String groupId, String artifactId);
    }

    private final Catalog catalog;

    public BuildFileCompletionProvider(MavenCentralClient client) {
        this(new Catalog() {
            @Override
            public List<MavenCentralClient.SearchResult> search(String query) {
                return client.search(query);
            }

            @Override
            public List<String> versions(String groupId, String artifactId) {
                return client.versions(groupId, artifactId);
            }
        });
    }

    public BuildFileCompletionProvider(Catalog catalog) {
        this.catalog = catalog;
    }

    public static boolean handles(Path filePath) {
        return JavaProjectConventions.isMavenPom(filePath)
                || JavaProjectConventions.isGradleBuildFile(filePath);
    }

    public List<AutoCompleteItem> suggestions(IdeCompletionContext context) {
        if (context == null || !handles(context.filePath())) {
            return List.of();
        }
        String before = textBeforeCaret(context);
        return JavaProjectConventions.isMavenPom(context.filePath())
                ? mavenSuggestions(before)
                : gradleSuggestions(before);
    }

    private List<AutoCompleteItem> mavenSuggestions(String before) {
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
            case GROUP -> coordinates(typed, DependencyCoordinate::groupId);
            case ARTIFACT -> artifactsOf(group, typed);
            case VERSION -> versions(group, artifact, typed);
            default -> List.of();
        };
    }

    private static boolean insideDependencyBlock(String before) {
        return XML_ENCLOSING.matcher(before).find();
    }

    private List<AutoCompleteItem> gradleSuggestions(String before) {
        Matcher quoted = GRADLE_NOTATION.matcher(before);
        if (!quoted.find()) {
            return List.of();
        }
        String typed = quoted.group(1);
        String[] parts = typed.split(":", -1);
        return switch (parts.length) {
            case 1 -> coordinates(parts[0], DependencyCoordinate::groupId);
            case 2 -> artifactsOf(parts[0], parts[1]);
            case 3 -> versions(parts[0], parts[1], parts[2]);
            default -> List.of();
        };
    }

    private List<AutoCompleteItem> coordinates(String typed,
                                               java.util.function.Function<DependencyCoordinate, String> field) {
        if (typed == null || typed.length() < MIN_PREFIX) {
            return List.of();
        }
        List<AutoCompleteItem> items = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (MavenCentralClient.SearchResult result : catalog.search(typed)) {
            String value = field.apply(result.coordinate());
            if (value.isBlank() || seen.contains(value)) {
                continue;
            }
            seen.add(value);
            items.add(item(value, result.coordinate().key()));
        }
        return List.copyOf(items);
    }

    private List<AutoCompleteItem> artifactsOf(String group, String typed) {
        String query = group == null || group.isBlank() ? typed : group + ":" + typed + "*";
        if (query == null || query.replace("*", "").length() < MIN_PREFIX) {
            return List.of();
        }
        List<AutoCompleteItem> items = new ArrayList<>();
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        for (MavenCentralClient.SearchResult result : catalog.search(query)) {
            String artifact = result.coordinate().artifactId();
            if (artifact.isBlank() || !artifact.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            items.add(item(artifact, result.coordinate().key()));
        }
        return List.copyOf(items);
    }

    private List<AutoCompleteItem> versions(String group, String artifact, String typed) {
        if (group == null || group.isBlank() || artifact == null || artifact.isBlank()) {
            return List.of();
        }
        String prefix = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        List<String> all = catalog.versions(group, artifact);
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String version : all) {
            if (MavenCentralClient.isStable(version)
                    && version.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                items.add(item(version, group + ":" + artifact));
            }
        }
        for (String version : all) {
            if (!MavenCentralClient.isStable(version)
                    && version.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                items.add(item(version, group + ":" + artifact));
            }
        }
        return List.copyOf(items);
    }

    private static AutoCompleteItem item(String value, String detail) {
        return new AutoCompleteItem(value, value, detail, null, null,
                AutoCompleteItem.Kind.VALUE, List.of());
    }

    private static String lastMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        String last = "";
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private static String textBeforeCaret(IdeCompletionContext context) {
        String text = context.text() == null ? "" : context.text();
        int caret = Math.max(0, Math.min(context.caretOffset(), text.length()));
        int from = Math.max(0, caret - 4000);
        return text.substring(from, caret);
    }
}
