package dtm.ide.spring;

import dtm.ide.editor.JavaSourceText;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpringSourceParser {

    private static final Pattern PACKAGE =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private static final Pattern TYPE_DECLARATION =
            Pattern.compile("\\b(class|interface|record|enum)\\s+([A-Za-z_]\\w*)");

    private static final Pattern ANNOTATION =
            Pattern.compile("@([A-Za-z_][\\w.]*)\\s*(?:\\(\\s*(.*?)\\s*\\))?", Pattern.DOTALL);

    private static final Pattern FIELD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:private|protected|public|final|static|transient|volatile)\\s+)*"
                    + "([A-Za-z_][\\w.]*(?:\\s*<[^;=]*>)?)\\s+([A-Za-z_]\\w*)\\s*[;=]");

    private static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|abstract|synchronized|default)\\s+)*"
                    + "(?:<[^>]+>\\s*)?"
                    + "(?:([A-Za-z_][\\w.]*(?:\\s*<[^{;]*>)?(?:\\[\\])?)\\s+)?"
                    + "([A-Za-z_]\\w*)\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)");

    private static final Pattern QUALIFIER_ARGUMENT =
            Pattern.compile("@Qualifier\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    private static final Set<String> INJECTION_ANNOTATIONS =
            Set.of("autowired", "inject", "resource");

    private SpringSourceParser() {
    }

    public record ParseResult(List<SpringBean> beans, List<SpringInjection> injections,
                              List<SpringEndpoint> endpoints) {

        public ParseResult {
            beans = beans == null ? List.of() : List.copyOf(beans);
            injections = injections == null ? List.of() : List.copyOf(injections);
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        }

        static ParseResult empty() {
            return new ParseResult(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return beans.isEmpty() && injections.isEmpty() && endpoints.isEmpty();
        }
    }

    private record Source(String structural, String literal) {

        static Source of(String raw) {
            String withoutComments = JavaSourceText.blankComments(raw);
            return new Source(JavaSourceText.blankStringContents(withoutComments), withoutComments);
        }

        Source sub(int from, int to) {
            int start = Math.max(0, Math.min(from, structural.length()));
            int end = Math.max(start, Math.min(to, structural.length()));
            return new Source(structural.substring(start, end), literal.substring(start, end));
        }
    }

    public static ParseResult parse(Path file, String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return ParseResult.empty();
        }
        Source source = Source.of(rawSource);
        String code = source.structural();
        String packageName = packageOf(code);
        int[] lineStarts = lineStarts(rawSource);

        List<SpringBean> beans = new ArrayList<>();
        List<SpringInjection> injections = new ArrayList<>();
        List<SpringEndpoint> endpoints = new ArrayList<>();

        Matcher types = TYPE_DECLARATION.matcher(code);
        while (types.find()) {
            String simpleName = types.group(2);
            int bodyStart = code.indexOf('{', types.end());
            if (bodyStart < 0) {
                continue;
            }
            int bodyEnd = matchingBrace(code, bodyStart);
            String header = code.substring(types.end(), bodyStart);
            Source body = source.sub(bodyStart + 1, bodyEnd);
            int bodyOffset = bodyStart + 1;

            List<Annotation> annotations = annotationsBefore(source, types.start());
            SpringStereotype stereotype = stereotypeOf(annotations);
            if (stereotype == null) {
                continue;
            }
            String qualifiedName = packageName.isBlank() ? simpleName : packageName + "." + simpleName;

            beans.add(new SpringBean(
                    beanNameOf(annotations, stereotype, simpleName),
                    qualifiedName,
                    simpleName,
                    stereotype,
                    file,
                    lineOf(lineStarts, types.start()),
                    supertypesOf(header),
                    profilesOf(annotations),
                    hasAnnotation(annotations, "primary"),
                    qualifierOf(annotations),
                    isConditional(annotations)));

            injections.addAll(constructorInjections(body, simpleName, qualifiedName, file,
                    lineStarts, bodyOffset));
            injections.addAll(fieldInjections(body, qualifiedName, file, lineStarts, bodyOffset));

            if (stereotype.declaresBeans()) {
                beans.addAll(beanMethods(body, packageName, file, lineStarts, bodyOffset));
                injections.addAll(beanMethodInjections(body, qualifiedName, file,
                        lineStarts, bodyOffset));
            }
            if (stereotype.isWebController()) {
                endpoints.addAll(endpoints(body, annotations, qualifiedName, file,
                        lineStarts, bodyOffset));
            }
        }
        return new ParseResult(beans, injections, endpoints);
    }

    private static final java.util.Map<String, String> MAPPING_METHODS = java.util.Map.of(
            "getmapping", "GET",
            "postmapping", "POST",
            "putmapping", "PUT",
            "deletemapping", "DELETE",
            "patchmapping", "PATCH",
            "requestmapping", SpringEndpoint.ANY_METHOD);

    private static List<SpringEndpoint> endpoints(Source body, List<Annotation> typeAnnotations,
                                                  String handlerType, Path file,
                                                  int[] lineStarts, int offset) {
        String classPath = pathOf(typeAnnotations);
        List<SpringEndpoint> endpoints = new ArrayList<>();

        Matcher methods = METHOD.matcher(body.structural());
        while (methods.find()) {
            List<Annotation> annotations = annotationsBefore(body, methods.start());
            Annotation mapping = annotations.stream()
                    .filter(annotation -> MAPPING_METHODS.containsKey(annotation.simpleName()))
                    .findFirst()
                    .orElse(null);
            if (mapping == null) {
                continue;
            }
            endpoints.add(new SpringEndpoint(
                    httpMethodOf(mapping),
                    SpringEndpoint.join(classPath, pathOf(List.of(mapping))),
                    handlerType,
                    methods.group(2),
                    file,
                    lineOf(lineStarts, offset + methods.start()),
                    producesOf(mapping.arguments())));
        }
        return endpoints;
    }

    private static String pathOf(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (!MAPPING_METHODS.containsKey(annotation.simpleName())) {
                continue;
            }
            String arguments = annotation.arguments();
            String named = namedArgument(arguments, "path");
            if (named.isBlank()) {
                named = namedArgument(arguments, "value");
            }
            if (!named.isBlank()) {
                return named;
            }
            String direct = firstStringLiteral(arguments);
            if (!direct.isBlank()) {
                return direct;
            }
        }
        return "/";
    }

    private static String httpMethodOf(Annotation mapping) {
        String declared = MAPPING_METHODS.get(mapping.simpleName());
        if (!SpringEndpoint.ANY_METHOD.equals(declared)) {
            return declared;
        }
        Matcher matcher = Pattern.compile("RequestMethod\\.([A-Z]+)").matcher(mapping.arguments());
        return matcher.find() ? matcher.group(1) : SpringEndpoint.ANY_METHOD;
    }

    private static List<String> producesOf(String arguments) {
        String produces = namedArgumentRegion(arguments, "produces");
        if (produces.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher literals = STRING_LITERAL.matcher(produces);
        while (literals.find()) {
            values.add(literals.group(1));
        }
        return values;
    }

    private static String namedArgument(String arguments, String name) {
        return firstStringLiteral(namedArgumentRegion(arguments, name));
    }

    private static String namedArgumentRegion(String arguments, String name) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=").matcher(arguments);
        if (!matcher.find()) {
            return "";
        }
        String rest = arguments.substring(matcher.end());
        Matcher next = Pattern.compile("\\b[A-Za-z_]\\w*\\s*=").matcher(rest);
        return next.find() ? rest.substring(0, next.start()) : rest;
    }

    private static SpringStereotype stereotypeOf(List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            SpringStereotype stereotype = SpringStereotype.fromAnnotation(annotation.name());
            if (stereotype != null && stereotype != SpringStereotype.BEAN_METHOD) {
                return stereotype;
            }
        }
        return null;
    }

    private static String beanNameOf(List<Annotation> annotations, SpringStereotype stereotype,
                                     String simpleName) {
        for (Annotation annotation : annotations) {
            if (SpringStereotype.fromAnnotation(annotation.name()) != stereotype) {
                continue;
            }
            String explicit = firstStringLiteral(annotation.arguments());
            if (!explicit.isBlank()) {
                return explicit;
            }
        }
        return SpringBean.defaultBeanName(simpleName);
    }

    private static List<SpringBean> beanMethods(Source body, String packageName, Path file,
                                                int[] lineStarts, int offset) {
        List<SpringBean> beans = new ArrayList<>();
        Matcher methods = METHOD.matcher(body.structural());
        while (methods.find()) {
            List<Annotation> annotations = annotationsBefore(body, methods.start());
            if (!hasAnnotation(annotations, "bean")) {
                continue;
            }
            String returnType = methods.group(1);
            String methodName = methods.group(2);
            if (returnType == null || returnType.isBlank()) {
                continue;
            }
            String simpleType = SpringBean.simpleNameOf(returnType);
            String explicitName = firstStringLiteral(argumentsOf(annotations, "bean"));

            beans.add(new SpringBean(
                    explicitName.isBlank() ? methodName : explicitName,
                    packageName.isBlank() ? simpleType : packageName + "." + simpleType,
                    simpleType,
                    SpringStereotype.BEAN_METHOD,
                    file,
                    lineOf(lineStarts, offset + methods.start()),
                    List.of(),
                    profilesOf(annotations),
                    hasAnnotation(annotations, "primary"),
                    qualifierOf(annotations),
                    isConditional(annotations)));
        }
        return beans;
    }

    private static List<SpringInjection> constructorInjections(Source body, String simpleName,
                                                               String ownerType, Path file,
                                                               int[] lineStarts, int offset) {
        List<SpringInjection> injections = new ArrayList<>();
        Matcher methods = METHOD.matcher(body.structural());
        while (methods.find()) {
            boolean isConstructor = simpleName.equals(methods.group(2)) && methods.group(1) == null;
            if (!isConstructor) {
                continue;
            }
            int line = lineOf(lineStarts, offset + methods.start());
            String parameters = body.literal().substring(methods.start(3), methods.end(3));
            for (Parameter parameter : parseParameters(parameters)) {
                injections.add(new SpringInjection(ownerType, parameter.type(), parameter.name(),
                        SpringInjection.Kind.CONSTRUCTOR, file, line, parameter.qualifier()));
            }
        }
        return injections;
    }

    private static List<SpringInjection> beanMethodInjections(Source body, String ownerType, Path file,
                                                              int[] lineStarts, int offset) {
        List<SpringInjection> injections = new ArrayList<>();
        Matcher methods = METHOD.matcher(body.structural());
        while (methods.find()) {
            if (!hasAnnotation(annotationsBefore(body, methods.start()), "bean")) {
                continue;
            }
            int line = lineOf(lineStarts, offset + methods.start());
            String parameters = body.literal().substring(methods.start(3), methods.end(3));
            for (Parameter parameter : parseParameters(parameters)) {
                injections.add(new SpringInjection(ownerType, parameter.type(), parameter.name(),
                        SpringInjection.Kind.CONSTRUCTOR, file, line, parameter.qualifier()));
            }
        }
        return injections;
    }

    private static List<SpringInjection> fieldInjections(Source body, String ownerType, Path file,
                                                         int[] lineStarts, int offset) {
        List<SpringInjection> injections = new ArrayList<>();
        Matcher fields = FIELD.matcher(body.structural());
        while (fields.find()) {
            List<Annotation> annotations = annotationsBefore(body, fields.start());
            boolean injected = annotations.stream()
                    .anyMatch(annotation -> INJECTION_ANNOTATIONS.contains(annotation.simpleName()));
            if (!injected) {
                continue;
            }
            injections.add(new SpringInjection(ownerType, fields.group(1), fields.group(2),
                    SpringInjection.Kind.FIELD, file,
                    lineOf(lineStarts, offset + fields.start()), qualifierOf(annotations)));
        }
        return injections;
    }

    private record Annotation(String name, String arguments) {
        String simpleName() {
            String value = name;
            int lastDot = value.lastIndexOf('.');
            return (lastDot >= 0 ? value.substring(lastDot + 1) : value).toLowerCase(Locale.ROOT);
        }
    }

    private static List<Annotation> annotationsBefore(Source source, int position) {
        String code = source.structural();
        int start = Math.min(position, code.length());
        while (start > 0) {
            char c = code.charAt(start - 1);
            if (c == ')') {
                start = openingParenthesis(code, start - 1);
                continue;
            }
            if (c == ';' || c == '{' || c == '}') {
                break;
            }
            start--;
        }
        String region = source.literal().substring(Math.max(0, start), position);
        List<Annotation> annotations = new ArrayList<>();
        Matcher matcher = ANNOTATION.matcher(region);
        while (matcher.find()) {
            annotations.add(new Annotation(matcher.group(1),
                    matcher.group(2) == null ? "" : matcher.group(2)));
        }
        return annotations;
    }

    private static int openingParenthesis(String code, int closeIndex) {
        int depth = 0;
        for (int i = closeIndex; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return 0;
    }

    private static boolean hasAnnotation(List<Annotation> annotations, String simpleName) {
        return annotations.stream().anyMatch(annotation -> annotation.simpleName().equals(simpleName));
    }

    private static boolean isConditional(List<Annotation> annotations) {
        return annotations.stream().anyMatch(a -> a.simpleName().startsWith("conditionalon"));
    }

    private static String argumentsOf(List<Annotation> annotations, String simpleName) {
        return annotations.stream()
                .filter(annotation -> annotation.simpleName().equals(simpleName))
                .map(Annotation::arguments)
                .findFirst()
                .orElse("");
    }

    private static String qualifierOf(List<Annotation> annotations) {
        return firstStringLiteral(argumentsOf(annotations, "qualifier"));
    }

    private static List<String> profilesOf(List<Annotation> annotations) {
        String arguments = argumentsOf(annotations, "profile");
        if (arguments.isBlank()) {
            return List.of();
        }
        List<String> profiles = new ArrayList<>();
        Matcher literals = STRING_LITERAL.matcher(arguments);
        while (literals.find()) {
            if (!literals.group(1).isBlank()) {
                profiles.add(literals.group(1));
            }
        }
        return profiles;
    }

    private record Parameter(String type, String name, String qualifier) {
    }

    static List<Parameter> parseParameters(String rawParameters) {
        if (rawParameters == null || rawParameters.isBlank()) {
            return List.of();
        }
        List<Parameter> parameters = new ArrayList<>();
        for (String raw : splitTopLevel(rawParameters)) {
            String declaration = raw.trim();
            if (declaration.isEmpty()) {
                continue;
            }
            String qualifier = "";
            Matcher qualifierMatcher = QUALIFIER_ARGUMENT.matcher(declaration);
            if (qualifierMatcher.find()) {
                qualifier = qualifierMatcher.group(1);
            }
            declaration = ANNOTATION.matcher(declaration).replaceAll(" ").trim();
            declaration = declaration.replaceAll("\\bfinal\\b", " ").trim();

            int lastSpace = lastTopLevelSpace(declaration);
            if (lastSpace <= 0) {
                continue;
            }
            String type = declaration.substring(0, lastSpace).trim();
            String name = declaration.substring(lastSpace + 1).trim();
            if (!type.isBlank() && !name.isBlank()) {
                parameters.add(new Parameter(type, name, qualifier));
            }
        }
        return parameters;
    }

    static List<String> supertypesOf(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        Set<String> supertypes = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?:extends|implements)\\s+([^{]+)").matcher(header);
        while (matcher.find()) {
            for (String candidate : splitTopLevel(matcher.group(1))) {
                String name = candidate.trim().replaceAll("\\s+", " ");
                for (String part : name.split("\\bimplements\\b|\\bextends\\b")) {
                    String supertype = part.trim();
                    if (!supertype.isBlank()) {
                        supertypes.add(supertype);
                    }
                }
            }
        }
        return List.copyOf(supertypes);
    }

    private static String packageOf(String code) {
        Matcher matcher = PACKAGE.matcher(code);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String blankComments(String source) {
        return JavaSourceText.blankComments(source);
    }

    static int matchingBrace(String code, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return code.length();
    }

    static List<String> splitTopLevel(String value) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    private static int lastTopLevelSpace(String declaration) {
        int depth = 0;
        int lastSpace = -1;
        for (int i = 0; i < declaration.length(); i++) {
            char c = declaration.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                lastSpace = i;
            }
        }
        return lastSpace;
    }

    private static String firstStringLiteral(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        Matcher matcher = STRING_LITERAL.matcher(arguments);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int[] lineStarts(String source) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    static int lineOf(int[] lineStarts, int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);
        if (index >= 0) {
            return index + 1;
        }
        return -index - 1;
    }
}
