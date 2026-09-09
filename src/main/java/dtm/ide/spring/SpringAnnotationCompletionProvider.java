package dtm.ide.spring;

import dtm.ide.spring.jpa.JpaEntity;
import dtm.ide.spring.jpa.JpaNaming;
import dtm.ide.spring.jpa.JpaPropertyResolver;
import dtm.ide.spring.jpa.JpaRepositoryInfo;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpringAnnotationCompletionProvider {

    private static final int MAX_SUGGESTIONS = 40;

    private static final int ANNOTATION_LOOKAHEAD = 4;

    private static final int ANNOTATION_LOOKBEHIND = 6;

    private static final Pattern OPEN_ANNOTATION =
            Pattern.compile("@([A-Za-z_][\\w.]*)\\s*\\([^()]*$");

    private static final Pattern QUERY_PREFIX =
            Pattern.compile("(find|read|get|query|count|exists|delete|remove)(By)?$");

    private SpringAnnotationCompletionProvider() {
    }

    public static List<AutoCompleteItem> suggestions(SpringIndexSnapshot snapshot, Path file,
                                                     String lineText, int caretCol) {
        return suggestions(snapshot, file, lineText, caretCol, -1);
    }

    public static List<AutoCompleteItem> suggestions(SpringIndexSnapshot snapshot, Path file,
                                                     String lineText, int caretCol, int caretLine) {
        if (snapshot == null || lineText == null) {
            return null;
        }
        List<AutoCompleteItem> literal = insideLiteral(snapshot, file, lineText, caretCol, caretLine);
        if (literal != null) {
            return literal;
        }
        return derivedQueries(snapshot, file, lineText, caretCol);
    }

    public static boolean opensAnnotationLiteral(String lineText, int caretCol) {
        if (lineText == null) {
            return false;
        }
        int caret = Math.max(0, Math.min(caretCol, lineText.length()));
        int quote = lineText.lastIndexOf('"', Math.max(0, caret - 1));
        if (quote < 0 || countQuotes(lineText, quote) % 2 != 0) {
            return false;
        }
        String annotation = annotationBefore(lineText, quote);
        return "qualifier".equals(annotation) || "resource".equals(annotation)
                || "profile".equals(annotation);
    }

    private static List<AutoCompleteItem> insideLiteral(SpringIndexSnapshot snapshot, Path file,
                                                        String lineText, int caretCol,
                                                        int caretLine) {
        int caret = Math.max(0, Math.min(caretCol, lineText.length()));
        int quote = lineText.lastIndexOf('"', Math.max(0, caret - 1));
        if (quote < 0 || countQuotes(lineText, quote) % 2 != 0) {
            return null;
        }
        String annotation = annotationBefore(lineText, quote);
        if (annotation.isBlank()) {
            return null;
        }
        String prefix = lineText.substring(quote + 1, caret);

        List<String> values = switch (annotation) {
            case "qualifier", "resource" -> qualifierValues(snapshot, file, caretLine);
            case "profile" -> snapshot.profileNames();
            default -> List.of();
        };
        if (values.isEmpty()) {
            return null;
        }
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String value : values) {
            if (items.size() >= MAX_SUGGESTIONS) {
                break;
            }
            if (!value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                continue;
            }
            items.add(new AutoCompleteItem(value, value, detailOf(snapshot, annotation, value),
                    documentationOf(snapshot, annotation, value), null,
                    AutoCompleteItem.Kind.PROPERTY, List.of()));
        }
        return items.isEmpty() ? null : items;
    }

    static List<String> qualifierValues(SpringIndexSnapshot snapshot, Path file, int caretLine) {
        SpringInjection injection = injectionNear(snapshot, file, caretLine);
        if (injection == null) {
            return snapshot.beanNames();
        }
        List<String> scoped = snapshot.candidatesFor(injection).stream()
                .map(bean -> bean.hasQualifier() ? bean.qualifier() : bean.name())
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted()
                .toList();
        return scoped.isEmpty() ? snapshot.beanNames() : scoped;
    }

    private static SpringInjection injectionNear(SpringIndexSnapshot snapshot, Path file,
                                                 int caretLine) {
        if (file == null || caretLine < 0) {
            return null;
        }
        SpringInjection nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (SpringInjection injection : snapshot.injectionsIn(file)) {
            int distance = injection.line() - (caretLine + 1);
            if (distance > ANNOTATION_LOOKAHEAD || distance < -ANNOTATION_LOOKBEHIND) {
                continue;
            }
            int absolute = Math.abs(distance);
            if (absolute < nearestDistance) {
                nearestDistance = absolute;
                nearest = injection;
            }
        }
        return nearest;
    }

    private static String detailOf(SpringIndexSnapshot snapshot, String annotation, String value) {
        if ("profile".equals(annotation)) {
            return "profile";
        }
        return snapshot.beansMatchingQualifier(value).stream()
                .findFirst()
                .map(bean -> bean.simpleName())
                .orElse("bean");
    }

    private static String documentationOf(SpringIndexSnapshot snapshot, String annotation,
                                          String value) {
        if ("profile".equals(annotation)) {
            return "";
        }
        return snapshot.beansMatchingQualifier(value).stream()
                .findFirst()
                .map(SpringBean::type)
                .orElse("");
    }

    private static List<AutoCompleteItem> derivedQueries(SpringIndexSnapshot snapshot, Path file,
                                                         String lineText, int caretCol) {
        if (file == null) {
            return null;
        }
        List<JpaRepositoryInfo> repositories = snapshot.repositoriesIn(file);
        if (repositories.size() != 1) {
            return null;
        }
        Optional<JpaEntity> entity = snapshot.entityNamed(repositories.getFirst().entityType());
        if (entity.isEmpty()) {
            return null;
        }
        int caret = Math.max(0, Math.min(caretCol, lineText.length()));
        String head = lineText.substring(0, caret);
        Matcher matcher = QUERY_PREFIX.matcher(head);
        if (!matcher.find()) {
            return null;
        }
        String verb = matcher.group(1);
        List<String> properties = JpaPropertyResolver.knownProperties(entity.get(),
                snapshot.entityLookup());

        List<AutoCompleteItem> items = new ArrayList<>();
        for (String property : properties) {
            if (items.size() >= MAX_SUGGESTIONS) {
                break;
            }
            String name = verb + "By" + JpaNaming.capitalize(property);
            items.add(new AutoCompleteItem(name, name, entity.get().simpleName() + "." + property,
                    "", null, AutoCompleteItem.Kind.METHOD, List.of()));
        }
        return items.isEmpty() ? null : items;
    }

    private static int countQuotes(String lineText, int limit) {
        int count = 0;
        for (int i = 0; i < limit; i++) {
            if (lineText.charAt(i) == '"') {
                count++;
            }
        }
        return count;
    }

    private static String annotationBefore(String lineText, int quoteIndex) {
        Matcher matcher = OPEN_ANNOTATION.matcher(lineText.substring(0, quoteIndex));
        if (!matcher.find()) {
            return "";
        }
        String name = matcher.group(1);
        int lastDot = name.lastIndexOf('.');
        return (lastDot >= 0 ? name.substring(lastDot + 1) : name).toLowerCase(Locale.ROOT);
    }
}
