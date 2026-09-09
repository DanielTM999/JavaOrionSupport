package dtm.ide.spring.jpa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record JpqlQuery(
        Map<String, String> aliases,
        List<PropertyPath> paths,
        Set<String> namedParameters,
        Set<Integer> positionalParameters,
        Statement statement
) {

    public enum Statement {
        SELECT,
        UPDATE,
        DELETE,
        UNKNOWN
    }

    public record PropertyPath(String alias, String expression) {
    }

    private static final Pattern FROM_CLAUSE = Pattern.compile(
            "(?i)\\b(?:from|update|delete\\s+from)\\s+([A-Za-z_][\\w.]*)\\s*(?:as\\s+)?([A-Za-z_]\\w*)?");

    private static final Pattern JOIN_CLAUSE = Pattern.compile(
            "(?i)\\bjoin\\s+(?:fetch\\s+)?([A-Za-z_]\\w*)\\.([A-Za-z_][\\w.]*)\\s*(?:as\\s+)?([A-Za-z_]\\w*)?");

    private static final Pattern PROPERTY_PATH =
            Pattern.compile("\\b([A-Za-z_]\\w*)\\.([A-Za-z_][\\w.]*)");

    private static final Pattern NAMED_PARAMETER = Pattern.compile(":([A-Za-z_]\\w*)");

    private static final Pattern POSITIONAL_PARAMETER = Pattern.compile("\\?(\\d+)");

    private static final Set<String> KEYWORDS = Set.of(
            "select", "from", "where", "join", "left", "right", "inner", "outer", "fetch",
            "group", "order", "by", "having", "and", "or", "not", "in", "like", "between",
            "is", "null", "asc", "desc", "distinct", "count", "sum", "avg", "min", "max",
            "new", "as", "on", "set", "update", "delete", "insert", "values", "exists");

    public JpqlQuery {
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        paths = paths == null ? List.of() : List.copyOf(paths);
        namedParameters = namedParameters == null ? Set.of() : Set.copyOf(namedParameters);
        positionalParameters = positionalParameters == null
                ? Set.of() : Set.copyOf(positionalParameters);
        statement = statement == null ? Statement.UNKNOWN : statement;
    }

    public static JpqlQuery parse(String jpql) {
        if (jpql == null || jpql.isBlank()) {
            return new JpqlQuery(Map.of(), List.of(), Set.of(), Set.of(), Statement.UNKNOWN);
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, String> joinPaths = new LinkedHashMap<>();

        Matcher from = FROM_CLAUSE.matcher(jpql);
        while (from.find()) {
            String entity = from.group(1);
            String alias = from.group(2);
            if (alias != null && !isKeyword(alias)) {
                aliases.put(alias, entity);
            } else {
                aliases.put(entity, entity);
            }
        }

        Matcher join = JOIN_CLAUSE.matcher(jpql);
        while (join.find()) {
            String owner = join.group(1);
            String relation = join.group(2);
            String alias = join.group(3);
            if (alias != null && !isKeyword(alias)) {
                joinPaths.put(alias, owner + "." + relation);
            }
        }

        List<PropertyPath> paths = new ArrayList<>();
        Matcher property = PROPERTY_PATH.matcher(jpql);
        while (property.find()) {
            String alias = property.group(1);
            String expression = property.group(2);
            if (isKeyword(alias) || aliases.containsKey(alias) || joinPaths.containsKey(alias)) {
                paths.add(new PropertyPath(alias, expression));
            }
        }

        Set<String> named = new LinkedHashSet<>();
        Matcher namedMatcher = NAMED_PARAMETER.matcher(jpql);
        while (namedMatcher.find()) {
            named.add(namedMatcher.group(1));
        }

        Set<Integer> positional = new LinkedHashSet<>();
        Matcher positionalMatcher = POSITIONAL_PARAMETER.matcher(jpql);
        while (positionalMatcher.find()) {
            positional.add(Integer.valueOf(positionalMatcher.group(1)));
        }

        Map<String, String> merged = new LinkedHashMap<>(aliases);
        joinPaths.forEach((alias, path) -> merged.putIfAbsent(alias, resolveJoin(path, aliases)));

        return new JpqlQuery(merged, paths, named, positional, statementOf(jpql));
    }

    private static String resolveJoin(String path, Map<String, String> aliases) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            return "";
        }
        String owner = path.substring(0, dot);
        String relation = path.substring(dot + 1);
        String entity = aliases.get(owner);
        return entity == null ? "" : entity + "#" + relation;
    }

    private static Statement statementOf(String jpql) {
        String head = jpql.trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("update")) {
            return Statement.UPDATE;
        }
        if (head.startsWith("delete")) {
            return Statement.DELETE;
        }
        if (head.startsWith("select") || head.startsWith("from")) {
            return Statement.SELECT;
        }
        return Statement.UNKNOWN;
    }

    static boolean isKeyword(String token) {
        return token != null && KEYWORDS.contains(token.toLowerCase(Locale.ROOT));
    }

    public boolean modifiesData() {
        return statement == Statement.UPDATE || statement == Statement.DELETE;
    }

    public String entityOf(String alias) {
        String value = aliases.get(alias);
        if (value == null) {
            return "";
        }
        int marker = value.indexOf('#');
        return marker < 0 ? value : value.substring(0, marker);
    }

    public String relationOf(String alias) {
        String value = aliases.get(alias);
        if (value == null) {
            return "";
        }
        int marker = value.indexOf('#');
        return marker < 0 ? "" : value.substring(marker + 1);
    }
}
