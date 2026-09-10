package dtm.ide.spring.jpa;

import dtm.ide.spring.JavaSourceLexer;
import dtm.ide.spring.JavaSourceLexer.Annotation;
import dtm.ide.spring.JavaSourceLexer.Source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JpaSourceParser {

    private static final Set<String> ENTITY_ANNOTATIONS =
            Set.of("entity", "mappedsuperclass", "embeddable");

    private static final Set<String> REPOSITORY_SUPERTYPES = Set.of(
            "Repository",
            "CrudRepository",
            "ListCrudRepository",
            "PagingAndSortingRepository",
            "ListPagingAndSortingRepository",
            "JpaRepository",
            "MongoRepository",
            "R2dbcRepository",
            "ReactiveCrudRepository");

    private static final Map<String, JpaField.Relation> RELATION_ANNOTATIONS = Map.of(
            "onetoone", JpaField.Relation.ONE_TO_ONE,
            "onetomany", JpaField.Relation.ONE_TO_MANY,
            "manytoone", JpaField.Relation.MANY_TO_ONE,
            "manytomany", JpaField.Relation.MANY_TO_MANY,
            "embedded", JpaField.Relation.EMBEDDED,
            "embeddedid", JpaField.Relation.EMBEDDED,
            "elementcollection", JpaField.Relation.NONE);

    private static final Set<String> COLLECTION_TYPES =
            Set.of("List", "Set", "Collection", "Iterable", "SortedSet", "Map");

    private static final Pattern STATIC_MODIFIER = Pattern.compile("\\bstatic\\b");

    private JpaSourceParser() {
    }

    public record ParseResult(List<JpaEntity> entities, List<JpaRepositoryInfo> repositories) {

        public ParseResult {
            entities = entities == null ? List.of() : List.copyOf(entities);
            repositories = repositories == null ? List.of() : List.copyOf(repositories);
        }

        public static ParseResult empty() {
            return new ParseResult(List.of(), List.of());
        }

        public boolean isEmpty() {
            return entities.isEmpty() && repositories.isEmpty();
        }
    }

    public static ParseResult parse(Path file, String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return ParseResult.empty();
        }
        Source source = Source.of(rawSource);
        return parse(file, source, JavaSourceLexer.packageOf(source.structural()),
                JavaSourceLexer.lineStarts(rawSource));
    }

    public static ParseResult parse(Path file, Source source, String packageName, int[] lineStarts) {
        if (source == null || source.structural().isBlank()) {
            return ParseResult.empty();
        }
        String code = source.structural();
        List<JpaEntity> entities = new ArrayList<>();
        List<JpaRepositoryInfo> repositories = new ArrayList<>();

        Matcher types = JavaSourceLexer.TYPE_DECLARATION.matcher(code);
        while (types.find()) {
            String keyword = types.group(1);
            String simpleName = types.group(2);
            int bodyStart = code.indexOf('{', types.end());
            if (bodyStart < 0) {
                continue;
            }
            int bodyEnd = JavaSourceLexer.matchingBrace(code, bodyStart);
            String header = code.substring(types.end(), bodyStart);
            Source body = source.sub(bodyStart + 1, bodyEnd);
            int bodyOffset = bodyStart + 1;
            int line = JavaSourceLexer.lineOf(lineStarts, types.start());

            List<Annotation> annotations = JavaSourceLexer.annotationsBefore(source, types.start());
            String qualifiedName = packageName.isBlank() ? simpleName
                    : packageName + "." + simpleName;

            if (JavaSourceLexer.hasAnyAnnotation(annotations, ENTITY_ANNOTATIONS)) {
                entities.add(entityOf(file, qualifiedName, simpleName, header, annotations, body,
                        lineStarts, bodyOffset, line));
                continue;
            }
            if ("interface".equals(keyword)) {
                JpaRepositoryInfo repository = repositoryOf(file, qualifiedName, simpleName, header,
                        body, lineStarts, bodyOffset, line);
                if (repository != null) {
                    repositories.add(repository);
                }
            }
        }
        return new ParseResult(entities, repositories);
    }

    private static JpaEntity entityOf(Path file, String qualifiedName, String simpleName,
                                      String header, List<Annotation> annotations, Source body,
                                      int[] lineStarts, int bodyOffset, int line) {
        String table = JavaSourceLexer.namedArgument(
                JavaSourceLexer.argumentsOf(annotations, "table"), "name");
        if (table.isBlank()) {
            table = JavaSourceLexer.firstStringLiteral(
                    JavaSourceLexer.argumentsOf(annotations, "table"));
        }
        List<String> supertypes = JavaSourceLexer.supertypesOf(header);
        String superType = supertypes.isEmpty() ? "" : supertypes.getFirst();

        return new JpaEntity(
                qualifiedName,
                simpleName,
                table,
                file,
                line,
                fieldsOf(body, lineStarts, bodyOffset),
                superType,
                JavaSourceLexer.hasAnnotation(annotations, "mappedsuperclass"),
                JavaSourceLexer.hasAnnotation(annotations, "embeddable"),
                hasNoArgConstructor(body, simpleName, annotations));
    }

    private static List<JpaField> fieldsOf(Source body, int[] lineStarts, int bodyOffset) {
        List<JpaField> fields = new ArrayList<>();
        Matcher matcher = JavaSourceLexer.FIELD.matcher(body.structural());
        while (matcher.find()) {
            if (STATIC_MODIFIER.matcher(matcher.group()).find()) {
                continue;
            }
            String type = matcher.group(1).replaceAll("\\s+", "");
            String name = matcher.group(2);
            List<Annotation> annotations = JavaSourceLexer.annotationsBefore(body, matcher.start());

            JpaField.Relation relation = relationOf(annotations);
            fields.add(new JpaField(
                    name,
                    type,
                    columnOf(annotations),
                    relation,
                    targetEntityOf(type, relation, annotations),
                    JavaSourceLexer.lineOf(lineStarts, bodyOffset + matcher.start()),
                    JavaSourceLexer.hasAnnotation(annotations, "id")
                            || JavaSourceLexer.hasAnnotation(annotations, "embeddedid"),
                    JavaSourceLexer.hasAnnotation(annotations, "transient")));
        }
        return fields;
    }

    private static JpaField.Relation relationOf(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            JpaField.Relation relation = RELATION_ANNOTATIONS.get(annotation.simpleName());
            if (relation != null && relation != JpaField.Relation.NONE) {
                return relation;
            }
        }
        return JpaField.Relation.NONE;
    }

    private static String columnOf(List<Annotation> annotations) {
        String column = JavaSourceLexer.namedArgument(
                JavaSourceLexer.argumentsOf(annotations, "column"), "name");
        if (!column.isBlank()) {
            return column;
        }
        column = JavaSourceLexer.firstStringLiteral(
                JavaSourceLexer.argumentsOf(annotations, "column"));
        if (!column.isBlank()) {
            return column;
        }
        return JavaSourceLexer.namedArgument(
                JavaSourceLexer.argumentsOf(annotations, "joincolumn"), "name");
    }

    private static String targetEntityOf(String type, JpaField.Relation relation,
                                         List<Annotation> annotations) {
        if (relation == JpaField.Relation.NONE) {
            return "";
        }
        for (Annotation annotation : annotations) {
            if (RELATION_ANNOTATIONS.get(annotation.simpleName()) == null) {
                continue;
            }
            String declared = JavaSourceLexer.namedArgumentRegion(
                    annotation.arguments(), "targetEntity");
            Matcher matcher = Pattern.compile("([A-Za-z_][\\w.]*)\\s*\\.\\s*class")
                    .matcher(declared);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        String bare = simpleNameOf(type);
        if (!COLLECTION_TYPES.contains(bare)) {
            return bare;
        }
        List<String> arguments = JavaSourceLexer.typeArgumentsOf(type);
        return arguments.isEmpty() ? "" : simpleNameOf(arguments.getLast());
    }

    private static boolean hasNoArgConstructor(Source body, String simpleName,
                                               List<Annotation> annotations) {
        if (JavaSourceLexer.hasAnnotation(annotations, "noargsconstructor")
                || JavaSourceLexer.hasAnnotation(annotations, "data")) {
            return true;
        }
        boolean anyConstructor = false;
        Matcher methods = JavaSourceLexer.METHOD.matcher(body.structural());
        while (methods.find()) {
            if (methods.group(1) != null || !simpleName.equals(methods.group(2))) {
                continue;
            }
            anyConstructor = true;
            if (methods.group(3).isBlank()) {
                return true;
            }
        }
        return !anyConstructor;
    }

    private static JpaRepositoryInfo repositoryOf(Path file, String qualifiedName, String simpleName,
                                                  String header, Source body, int[] lineStarts,
                                                  int bodyOffset, int line) {
        String entityType = "";
        String idType = "";
        boolean repository = false;
        for (String supertype : JavaSourceLexer.supertypesOf(header)) {
            if (!REPOSITORY_SUPERTYPES.contains(simpleNameOf(supertype))) {
                continue;
            }
            repository = true;
            List<String> arguments = JavaSourceLexer.typeArgumentsOf(supertype);
            if (arguments.size() >= 2) {
                entityType = simpleNameOf(arguments.get(0));
                idType = simpleNameOf(arguments.get(1));
            }
            break;
        }
        if (!repository) {
            return null;
        }
        return new JpaRepositoryInfo(qualifiedName, simpleName, entityType, idType, file, line,
                queryMethodsOf(body, lineStarts, bodyOffset));
    }

    private static List<JpaQueryMethod> queryMethodsOf(Source body, int[] lineStarts, int bodyOffset) {
        List<JpaQueryMethod> methods = new ArrayList<>();
        Matcher matcher = JavaSourceLexer.METHOD.matcher(body.structural());
        while (matcher.find()) {
            if (matcher.group(1) == null) {
                continue;
            }
            String name = matcher.group(2);
            List<Annotation> annotations = JavaSourceLexer.annotationsBefore(body, matcher.start());
            Annotation query = JavaSourceLexer.annotationNamed(annotations, "query");
            boolean declaredQuery = query != null;
            String queryArguments = declaredQuery ? query.arguments() : "";
            String valueRegion = JavaSourceLexer.namedArgumentRegion(queryArguments, "value");
            String queryExpression = valueRegion.isBlank()
                    ? JavaSourceLexer.firstArgumentRegion(queryArguments) : valueRegion;
            queryExpression = queryExpression.strip();
            if (queryExpression.endsWith(",")) {
                queryExpression = queryExpression.substring(0, queryExpression.length() - 1);
            }
            String jpql = JavaSourceLexer.concatenatedStringLiterals(queryExpression);
            JpaDerivedQuery.Parsed parsed = JpaDerivedQuery.parse(name);
            boolean nativeQuery = Pattern.compile("^true\\b").matcher(
                    JavaSourceLexer.namedArgumentRegion(
                            queryArguments, "nativeQuery").trim()).find();
            methods.add(new JpaQueryMethod(
                    name,
                    JavaSourceLexer.lineOf(lineStarts, bodyOffset + matcher.start()),
                    parsed.subject(),
                    parsed.conditions(),
                    parsed.orderBy(),
                    jpql,
                    parsed.derived(),
                    parameterNamesOf(body.literal(), matcher.start(3), matcher.end(3)),
                    JavaSourceLexer.hasAnnotation(annotations, "modifying"),
                    nativeQuery,
                    declaredQuery));
        }
        return methods;
    }

    private static List<String> parameterNamesOf(String literal, int start, int end) {
        if (start < 0 || end > literal.length() || start >= end) {
            return List.of();
        }
        String raw = literal.substring(start, end);
        List<String> names = new ArrayList<>();
        for (String declaration : JavaSourceLexer.splitTopLevel(raw)) {
            String param = JavaSourceLexer.firstStringLiteral(declaration);
            if (!param.isBlank()) {
                names.add(param);
                continue;
            }
            String cleaned = JavaSourceLexer.ANNOTATION.matcher(declaration).replaceAll(" ").trim();
            int space = cleaned.lastIndexOf(' ');
            if (space > 0) {
                names.add(cleaned.substring(space + 1).trim());
            }
        }
        return List.copyOf(names);
    }

    private static String simpleNameOf(String type) {
        if (type == null) {
            return "";
        }
        String name = type.trim();
        int generics = name.indexOf('<');
        if (generics > 0) {
            name = name.substring(0, generics);
        }
        int lastDot = name.lastIndexOf('.');
        return (lastDot >= 0 ? name.substring(lastDot + 1) : name).trim();
    }
}
