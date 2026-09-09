package dtm.ide.spring.jpa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JpaDerivedQuery {

    private static final Pattern PREFIX = Pattern.compile(
            "^(find|read|get|query|search|stream|count|exists|delete|remove)");

    private static final List<String> KEYWORDS = List.of(
            "IsNotNull", "NotNull", "IsNull", "Null",
            "IsNotEmpty", "NotEmpty", "IsEmpty", "Empty",
            "IsNotIn", "NotIn", "IsIn", "In",
            "IsBetween", "Between",
            "IsLessThanEqual", "LessThanEqual", "IsLessThan", "LessThan",
            "IsGreaterThanEqual", "GreaterThanEqual", "IsGreaterThan", "GreaterThan",
            "IsNotBefore", "IsBefore", "Before", "IsNotAfter", "IsAfter", "After",
            "IsNotLike", "NotLike", "IsLike", "Like",
            "IsStartingWith", "StartingWith", "StartsWith",
            "IsEndingWith", "EndingWith", "EndsWith",
            "IsNotContaining", "NotContaining", "IsContaining", "Containing", "Contains",
            "IsTrue", "True", "IsFalse", "False",
            "IsNear", "Near", "IsWithin", "Within",
            "MatchesRegex", "Matches", "Regex",
            "IgnoringCase", "IgnoresCase", "IgnoreCase",
            "IsNot", "Not", "Equals", "Is");

    private static final List<String> SORTED_KEYWORDS = KEYWORDS.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    private static final Set<String> LIMITERS = Set.of("Distinct", "First", "Top", "All");

    private JpaDerivedQuery() {
    }

    public record Parsed(JpaQueryMethod.Subject subject, List<String> conditions,
                         String orderBy, boolean derived) {

        public Parsed {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
            orderBy = orderBy == null ? "" : orderBy;
        }

        static Parsed custom() {
            return new Parsed(JpaQueryMethod.Subject.CUSTOM, List.of(), "", false);
        }
    }

    public static Parsed parse(String methodName) {
        if (methodName == null || methodName.isBlank()) {
            return Parsed.custom();
        }
        Matcher prefix = PREFIX.matcher(methodName);
        if (!prefix.find()) {
            return Parsed.custom();
        }
        JpaQueryMethod.Subject subject = subjectOf(prefix.group(1));
        String rest = methodName.substring(prefix.end());
        int by = indexOfBy(rest);
        if (by < 0) {
            return new Parsed(subject, List.of(), "", isLimiterOnly(rest));
        }
        String criteria = rest.substring(by + 2);
        if (criteria.isBlank()) {
            return new Parsed(subject, List.of(), "", false);
        }

        String orderBy = "";
        int order = criteria.indexOf("OrderBy");
        if (order >= 0) {
            orderBy = criteria.substring(order + "OrderBy".length());
            criteria = criteria.substring(0, order);
        }
        if (criteria.isBlank()) {
            return new Parsed(subject, List.of(), orderBy, true);
        }
        return new Parsed(subject, splitConditions(criteria), orderBy, true);
    }

    public static List<String> orderProperties(String orderBy) {
        if (orderBy == null || orderBy.isBlank()) {
            return List.of();
        }
        List<String> properties = new ArrayList<>();
        for (String segment : splitConditions(orderBy)) {
            String cleaned = segment;
            if (cleaned.endsWith("Asc")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            } else if (cleaned.endsWith("Desc")) {
                cleaned = cleaned.substring(0, cleaned.length() - 4);
            }
            if (!cleaned.isBlank()) {
                properties.add(cleaned);
            }
        }
        return properties;
    }

    public static List<String> propertyCandidates(String condition) {
        if (condition == null || condition.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String stripped = stripKeywords(condition);
        if (!stripped.isBlank()) {
            candidates.add(stripped);
        }
        candidates.add(condition);
        return List.copyOf(candidates);
    }

    static String stripKeywords(String condition) {
        String value = condition;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String keyword : SORTED_KEYWORDS) {
                if (value.length() > keyword.length() && value.endsWith(keyword)) {
                    value = value.substring(0, value.length() - keyword.length());
                    changed = true;
                    break;
                }
            }
        }
        return value;
    }

    static List<String> splitConditions(String criteria) {
        List<String> conditions = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < criteria.length()) {
            int length = separatorAt(criteria, i);
            if (length > 0) {
                String segment = criteria.substring(start, i);
                if (!segment.isBlank()) {
                    conditions.add(segment);
                }
                i += length;
                start = i;
                continue;
            }
            i++;
        }
        String tail = criteria.substring(start);
        if (!tail.isBlank()) {
            conditions.add(tail);
        }
        return conditions;
    }

    private static int separatorAt(String criteria, int index) {
        if (index == 0) {
            return 0;
        }
        if (criteria.startsWith("And", index) && startsNewWord(criteria, index + 3)) {
            return 3;
        }
        if (criteria.startsWith("Or", index) && startsNewWord(criteria, index + 2)) {
            return 2;
        }
        return 0;
    }

    private static boolean startsNewWord(String criteria, int index) {
        return index < criteria.length() && Character.isUpperCase(criteria.charAt(index));
    }

    private static int indexOfBy(String rest) {
        for (int i = 0; i + 2 <= rest.length(); i++) {
            if (!rest.startsWith("By", i)) {
                continue;
            }
            if (i + 2 < rest.length() && !Character.isUpperCase(rest.charAt(i + 2))) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean isLimiterOnly(String rest) {
        if (rest.isBlank()) {
            return true;
        }
        for (String segment : JpaNaming.camelSegments(rest)) {
            String cleaned = segment.replaceAll("\\d", "");
            if (!LIMITERS.contains(cleaned)) {
                return false;
            }
        }
        return true;
    }

    private static JpaQueryMethod.Subject subjectOf(String prefix) {
        if (prefix.startsWith("count")) {
            return JpaQueryMethod.Subject.COUNT;
        }
        if (prefix.startsWith("exists")) {
            return JpaQueryMethod.Subject.EXISTS;
        }
        if (prefix.startsWith("delete") || prefix.startsWith("remove")) {
            return JpaQueryMethod.Subject.DELETE;
        }
        return JpaQueryMethod.Subject.FIND;
    }
}
