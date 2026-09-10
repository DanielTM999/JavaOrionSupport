package dtm.ide.spring.jpa;

import dtm.ide.editor.tokenizer.JpaQueryLiteralScanner;
import dtm.ide.spring.SpringIndexSnapshot;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class JpaQueryCompletionProvider {

    private static String text(String key, String fallback) {
        return dtm.stools.i18n.I18n.getText(JpaQueryCompletionProvider.class, key, fallback);
    }

    private static final int MAX_SUGGESTIONS = 100;

    private static final List<String> JPQL_KEYWORDS = List.of(
            "SELECT", "FROM", "WHERE", "JOIN", "LEFT JOIN", "INNER JOIN",
            "LEFT JOIN FETCH", "ON", "AS", "DISTINCT", "AND", "OR", "NOT", "IN", "LIKE",
            "BETWEEN", "IS NULL", "IS NOT NULL", "IS EMPTY", "MEMBER OF", "EXISTS",
            "GROUP BY", "HAVING", "ORDER BY", "ASC", "DESC", "UPDATE", "SET", "DELETE FROM",
            "CASE", "WHEN", "THEN", "ELSE", "END", "TRUE", "FALSE", "NULL", "NEW");

    private static final List<String> SQL_KEYWORDS = Stream.concat(JPQL_KEYWORDS.stream(),
            Stream.of("INSERT INTO", "VALUES", "RIGHT JOIN", "FULL JOIN", "CROSS JOIN",
                    "UNION", "LIMIT", "OFFSET", "RETURNING"))
            .distinct().toList();

    private static final List<String> FUNCTIONS = List.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "CONCAT", "SUBSTRING", "LOWER", "UPPER",
            "TRIM", "LENGTH", "LOCATE", "ABS", "MOD", "SQRT", "COALESCE", "NULLIF",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP");

    private static final Set<String> QUERY_KEYWORDS = SQL_KEYWORDS.stream()
            .flatMap(keyword -> Stream.of(keyword.split(" ")))
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private static final Pattern SQL_SOURCE = Pattern.compile(
            "(?i)\\b(?:from|join|update|into)\\s+([A-Za-z_][\\w.$]*)(?:\\s+(?:as\\s+)?([A-Za-z_]\\w*))?");

    private JpaQueryCompletionProvider() {
    }

    public static List<AutoCompleteItem> suggestions(SpringIndexSnapshot snapshot, Path file,
                                                     String source, int caretOffset) {
        if (snapshot == null || source == null || file == null) {
            return null;
        }
        int caret = Math.max(0, Math.min(caretOffset, source.length()));
        JpaQueryLiteralScanner.Scan scan = JpaQueryLiteralScanner.scan(source);
        JpaQueryLiteralScanner.QueryLiteral literal = scan.literalContaining(caret);
        if (literal == null) {
            return null;
        }

        QueryContext query = queryContext(scan, literal, source, caret);
        String beforeCaret = query.beforeCaret();
        String queryText = query.complete();
        if (insideDynamicExpression(beforeCaret)) {
            return List.of();
        }
        CompletionWord word = CompletionWord.at(beforeCaret);
        JpaQueryMethod method = methodContext(snapshot, file, source, caret);
        CompletionBuilder completion = new CompletionBuilder(word.prefix());

        if (word.parameter()) {
            addParameters(completion, method, true);
            return completion.items();
        }
        if (!word.ownerPath().isBlank()) {
            addProperties(completion, snapshot, queryText, word.ownerPath(),
                    literal.nativeSql());
            return completion.items();
        }
        String sourceKeyword = expectedSourceKeyword(word.headWithoutPrefix());
        if (!sourceKeyword.isBlank()) {
            if (literal.nativeSql() || !"join".equals(sourceKeyword)) {
                addEntities(completion, snapshot, literal.nativeSql());
            }
            if (!literal.nativeSql() && "join".equals(sourceKeyword)) {
                addAliases(completion, snapshot, queryText, false);
            }
            return completion.items();
        }

        addAliases(completion, snapshot, queryText, literal.nativeSql());
        addParameters(completion, method, false);
        addKeywords(completion, literal.nativeSql());
        addFunctions(completion, literal.nativeSql());
        if (beforeCaret.isBlank()) {
            addTemplates(completion, literal.nativeSql());
        }
        return completion.items();
    }

    public static boolean isInsideQuery(String source, int caretOffset) {
        if (source == null) {
            return false;
        }
        int caret = Math.max(0, Math.min(caretOffset, source.length()));
        return JpaQueryLiteralScanner.scan(source).literalContaining(caret) != null;
    }

    private static JpaQueryMethod methodContext(SpringIndexSnapshot snapshot, Path file,
                                                String source, int caretOffset) {
        List<JpaRepositoryInfo> current = JpaSourceParser.parse(file, source).repositories();
        List<JpaRepositoryInfo> repositories = current.isEmpty()
                ? snapshot.repositoriesIn(file) : current;
        int caretLine = lineOf(source, caretOffset);
        return repositories.stream()
                .flatMap(repository -> repository.methods().stream())
                .filter(method -> method.line() >= caretLine)
                .min(Comparator.comparingInt(method -> method.line() - caretLine))
                .orElse(null);
    }

    private static void addEntities(CompletionBuilder completion, SpringIndexSnapshot snapshot,
                                    boolean nativeSql) {
        snapshot.entities().stream()
                .filter(JpaEntity::persistent)
                .sorted(Comparator.comparing(JpaEntity::simpleName))
                .forEach(entity -> completion.add(
                        nativeSql ? entity.effectiveTable() : entity.simpleName(),
                        nativeSql ? text("detail.table", "tabela")
                                : text("detail.entity", "entidade JPA"),
                        entity.type(), AutoCompleteItem.Kind.CLASS));
    }

    private static void addAliases(CompletionBuilder completion, SpringIndexSnapshot snapshot,
                                   String query, boolean nativeSql) {
        Map<String, JpaEntity> aliases = aliases(snapshot, query, nativeSql);
        aliases.forEach((alias, entity) -> completion.add(alias, text("detail.alias", "alias de") + " "
                + (nativeSql ? entity.effectiveTable() : entity.simpleName()), entity.type(),
                AutoCompleteItem.Kind.VARIABLE));
    }

    private static void addProperties(CompletionBuilder completion, SpringIndexSnapshot snapshot,
                                      String query, String ownerPath,
                                      boolean nativeSql) {
        String[] path = ownerPath.split("\\.");
        if (path.length == 0) {
            return;
        }
        JpaEntity entity = aliases(snapshot, query, nativeSql).get(path[0]);
        if (entity == null) {
            return;
        }
        for (int i = 1; i < path.length && entity != null; i++) {
            Optional<JpaField> field = nativeSql
                    ? fieldByColumn(entity, path[i], snapshot)
                    : JpaPropertyResolver.resolvePath(entity, path[i], snapshot.entityLookup())
                    .map(fields -> fields.getLast());
            entity = field.filter(JpaField::navigable)
                    .flatMap(value -> snapshot.entityNamed(value.targetEntity()))
                    .orElse(null);
        }
        if (entity == null) {
            return;
        }
        JpaEntity resolvedEntity = entity;
        fieldsOf(resolvedEntity, snapshot).forEach(field -> completion.add(
                nativeSql ? field.effectiveColumn() : field.name(),
                field.navigable() ? text("detail.relation", "relacionamento") + " "
                        + field.targetEntity() : field.type(),
                resolvedEntity.simpleName(), AutoCompleteItem.Kind.PROPERTY));
    }

    private static void addParameters(CompletionBuilder completion, JpaQueryMethod method,
                                      boolean colonPresent) {
        if (method == null) {
            return;
        }
        method.parameters().forEach(parameter -> completion.add(
                colonPresent ? parameter : ":" + parameter,
                ":" + parameter, text("detail.methodParameter", "parâmetro do método"),
                text("description.parameter", "@Param / argumento"),
                AutoCompleteItem.Kind.PARAMETER));
    }

    private static void addKeywords(CompletionBuilder completion, boolean nativeSql) {
        String detail = nativeSql ? text("detail.sqlKeyword", "palavra-chave SQL")
                : text("detail.jpqlKeyword", "palavra-chave JPQL");
        (nativeSql ? SQL_KEYWORDS : JPQL_KEYWORDS).forEach(keyword ->
                completion.add(keyword, detail, "", AutoCompleteItem.Kind.KEYWORD));
    }

    private static void addFunctions(CompletionBuilder completion, boolean nativeSql) {
        String detail = nativeSql ? text("detail.sqlFunction", "função SQL")
                : text("detail.jpqlFunction", "função JPQL");
        FUNCTIONS.forEach(function -> {
            if (function.startsWith("CURRENT_")) {
                completion.add(function, detail, "", AutoCompleteItem.Kind.FUNCTION);
            } else {
                completion.addSnippet(function, function + "(${1:"
                        + text("placeholder.expression", "expressão") + "})", detail);
            }
        });
    }

    private static void addTemplates(CompletionBuilder completion, boolean nativeSql) {
        String table = text("placeholder.table", "tabela");
        String entity = text("placeholder.entity", "Entidade");
        String column = text("placeholder.column", "coluna");
        String field = text("placeholder.field", "campo");
        String value = text("placeholder.value", "valor");
        completion.addSnippet("SELECT … FROM …",
                nativeSql ? "SELECT ${1:*} FROM ${2:" + table + "} ${3:t} WHERE $0"
                        : "SELECT ${1:e} FROM ${2:" + entity + "} ${1:e} WHERE $0",
                nativeSql ? text("detail.sqlQuery", "consulta SQL")
                        : text("detail.jpqlQuery", "consulta JPQL"));
        completion.addSnippet("UPDATE … SET …",
                nativeSql ? "UPDATE ${1:" + table + "} ${2:t} SET ${3:" + column
                        + "} = :${4:" + value + "} WHERE $0"
                        : "UPDATE ${1:" + entity + "} ${2:e} SET ${2:e}.${3:" + field
                        + "} = :${4:" + value + "} WHERE $0",
                nativeSql ? text("detail.sqlUpdate", "atualização SQL")
                        : text("detail.jpqlUpdate", "atualização JPQL"));
        completion.addSnippet("DELETE FROM …",
                nativeSql ? "DELETE FROM ${1:" + table + "} WHERE $0"
                        : "DELETE FROM ${1:" + entity + "} ${2:e} WHERE $0",
                nativeSql ? text("detail.sqlDelete", "exclusão SQL")
                        : text("detail.jpqlDelete", "exclusão JPQL"));
    }

    private static QueryContext queryContext(JpaQueryLiteralScanner.Scan scan,
                                             JpaQueryLiteralScanner.QueryLiteral current,
                                             String source, int caret) {
        List<JpaQueryLiteralScanner.QueryLiteral> parts = scan.literals().values().stream()
                .filter(part -> part.expressionStart() == current.expressionStart()
                        && part.expressionEnd() == current.expressionEnd())
                .sorted(Comparator.comparingInt(JpaQueryLiteralScanner.QueryLiteral::start))
                .toList();
        StringBuilder complete = new StringBuilder();
        StringBuilder before = new StringBuilder();
        for (JpaQueryLiteralScanner.QueryLiteral part : parts) {
            String content = source.substring(part.contentStart(), part.contentEnd());
            complete.append(content);
            if (part.start() < current.start()) {
                before.append(content);
            } else if (part.start() == current.start()) {
                int contentCaret = Math.max(part.contentStart(), Math.min(caret, part.contentEnd()));
                before.append(source, part.contentStart(), contentCaret);
            }
        }
        return new QueryContext(complete.toString(), before.toString());
    }

    private static boolean insideDynamicExpression(String query) {
        int hash = Math.max(query.lastIndexOf("#{"), query.lastIndexOf("${"));
        return hash >= 0 && query.indexOf('}', hash + 2) < 0;
    }

    private static Map<String, JpaEntity> aliases(SpringIndexSnapshot snapshot,
                                                   String query, boolean nativeSql) {
        Map<String, JpaEntity> result = new LinkedHashMap<>();
        if (nativeSql) {
            Matcher matcher = SQL_SOURCE.matcher(query);
            while (matcher.find()) {
                JpaEntity entity = entityByTable(snapshot, matcher.group(1)).orElse(null);
                if (entity == null) {
                    continue;
                }
                String alias = matcher.group(2);
                if (alias != null && !QUERY_KEYWORDS.contains(alias.toLowerCase(Locale.ROOT))) {
                    result.put(alias, entity);
                }
                result.putIfAbsent(matcher.group(1), entity);
            }
        } else {
            JpqlQuery parsed = JpqlQuery.parse(query);
            parsed.aliases().keySet().forEach(alias -> {
                JpaEntity entity = snapshot.entityNamed(parsed.entityOf(alias)).orElse(null);
                String relation = parsed.relationOf(alias);
                if (entity != null && !relation.isBlank()) {
                    entity = entity.fieldNamed(relation)
                            .flatMap(field -> snapshot.entityNamed(field.targetEntity()))
                            .orElse(null);
                }
                if (entity != null) {
                    result.put(alias, entity);
                }
            });
        }
        return result;
    }

    private static Optional<JpaEntity> entityByTable(SpringIndexSnapshot snapshot, String table) {
        String wanted = table == null ? "" : table.replace("\"", "");
        int dot = wanted.lastIndexOf('.');
        if (dot >= 0) {
            wanted = wanted.substring(dot + 1);
        }
        String finalWanted = wanted;
        return snapshot.entities().stream()
                .filter(entity -> entity.effectiveTable().equalsIgnoreCase(finalWanted)
                        || entity.simpleName().equalsIgnoreCase(finalWanted))
                .findFirst();
    }

    private static Optional<JpaField> fieldByColumn(JpaEntity entity, String column,
                                                    SpringIndexSnapshot snapshot) {
        return fieldsOf(entity, snapshot).stream()
                .filter(field -> field.effectiveColumn().equalsIgnoreCase(column)
                        || field.name().equalsIgnoreCase(column))
                .findFirst();
    }

    private static List<JpaField> fieldsOf(JpaEntity entity, SpringIndexSnapshot snapshot) {
        List<JpaField> fields = new ArrayList<>();
        JpaEntity current = entity;
        int depth = 0;
        while (current != null && depth++ < 8) {
            current.fields().stream().filter(field -> !field.ignored()).forEach(fields::add);
            current = current.superType().isBlank() ? null
                    : snapshot.entityNamed(current.superType()).orElse(null);
        }
        return fields.stream().collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toMap(JpaField::name, field -> field,
                        (first, ignored) -> first, LinkedHashMap::new),
                map -> List.copyOf(map.values())));
    }

    private static String expectedSourceKeyword(String head) {
        Matcher matcher = Pattern.compile("(?is).*\\b(from|join|update|into)\\s*$").matcher(head);
        return matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, source.length()); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private record CompletionWord(String ownerPath, String prefix, boolean parameter,
                                  String headWithoutPrefix) {

        static CompletionWord at(String query) {
            int end = query.length();
            int start = end;
            while (start > 0 && Character.isJavaIdentifierPart(query.charAt(start - 1))) {
                start--;
            }
            String prefix = query.substring(start, end);
            if (start > 0 && query.charAt(start - 1) == ':') {
                return new CompletionWord("", prefix, true, query.substring(0, start - 1));
            }
            int pathStart = start;
            while (pathStart > 0) {
                char c = query.charAt(pathStart - 1);
                if (c != '.' && !Character.isJavaIdentifierPart(c)) {
                    break;
                }
                pathStart--;
            }
            String expression = query.substring(pathStart, start);
            String owner = expression.endsWith(".")
                    ? expression.substring(0, expression.length() - 1) : "";
            return new CompletionWord(owner, prefix, false, query.substring(0, start));
        }
    }

    private record QueryContext(String complete, String beforeCaret) {
    }

    private static final class CompletionBuilder {

        private final String prefix;
        private final Map<String, AutoCompleteItem> items = new LinkedHashMap<>();

        private CompletionBuilder(String prefix) {
            this.prefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        }

        void add(String value, String detail, String description, AutoCompleteItem.Kind kind) {
            add(value, value, detail, description, kind);
        }

        void add(String insert, String label, String detail, String description,
                 AutoCompleteItem.Kind kind) {
            if (items.size() >= MAX_SUGGESTIONS || insert == null || insert.isBlank()
                    || !insert.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return;
            }
            items.putIfAbsent(label.toLowerCase(Locale.ROOT), new AutoCompleteItem(
                    insert, label, detail, description, null, kind, List.of()));
        }

        void addSnippet(String label, String insert, String detail) {
            if (items.size() >= MAX_SUGGESTIONS
                    || !label.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return;
            }
            items.putIfAbsent(label.toLowerCase(Locale.ROOT),
                    AutoCompleteItem.snippet(label, insert, detail));
        }

        List<AutoCompleteItem> items() {
            return List.copyOf(items.values());
        }
    }
}
