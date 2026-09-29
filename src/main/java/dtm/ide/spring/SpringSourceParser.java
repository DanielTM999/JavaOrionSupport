package dtm.ide.spring;

import dtm.ide.spring.JavaSourceLexer.Annotation;
import dtm.ide.spring.jpa.JpaEntity;
import dtm.ide.spring.jpa.JpaRepositoryInfo;
import dtm.ide.spring.infra.SpringInfraModel;
import dtm.ide.spring.infra.SpringInfraParser;
import dtm.ide.spring.jpa.JpaSourceParser;
import dtm.ide.spring.JavaSourceLexer.Parameter;
import dtm.ide.spring.JavaSourceLexer.Source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpringSourceParser {

    private static final Set<String> INJECTION_ANNOTATIONS =
            Set.of("autowired", "inject", "resource", "named");

    private static final Set<String> QUALIFIER_ANNOTATIONS = Set.of("qualifier", "named");

    private static final Pattern VALUE_ANNOTATION = Pattern.compile(
            "@Value\\s*\\(\\s*\"([^\"]*)\"\\s*\\)");

    private static final Pattern MODIFIER_STATIC = Pattern.compile("\\bstatic\\b");

    private static final Pattern MODIFIER_FINAL = Pattern.compile("\\bfinal\\b");

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\$\\{\\s*([^:}\\s]+)\\s*(?::([^}]*))?}");

    private SpringSourceParser() {
    }

    public record ParseResult(List<SpringBean> beans, List<SpringInjection> injections,
                              List<SpringEndpoint> endpoints, List<JpaEntity> entities,
                              List<JpaRepositoryInfo> repositories,
                              List<SpringPropertyUsage> propertyUsages,
                              List<JavaType> types,
                              SpringInfraModel infra) {

        public ParseResult {
            beans = beans == null ? List.of() : List.copyOf(beans);
            injections = injections == null ? List.of() : List.copyOf(injections);
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            entities = entities == null ? List.of() : List.copyOf(entities);
            repositories = repositories == null ? List.of() : List.copyOf(repositories);
            propertyUsages = propertyUsages == null ? List.of() : List.copyOf(propertyUsages);
            types = types == null ? List.of() : List.copyOf(types);
            infra = infra == null ? SpringInfraModel.empty() : infra;
        }

        public ParseResult(List<SpringBean> beans, List<SpringInjection> injections,
                           List<SpringEndpoint> endpoints) {
            this(beans, injections, endpoints, List.of(), List.of(), List.of(), List.of(),
                    SpringInfraModel.empty());
        }

        static ParseResult empty() {
            return new ParseResult(List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), SpringInfraModel.empty());
        }

        public boolean isEmpty() {
            return beans.isEmpty() && injections.isEmpty() && endpoints.isEmpty()
                    && entities.isEmpty() && repositories.isEmpty() && propertyUsages.isEmpty()
                    && types.isEmpty() && infra.isEmpty();
        }
    }

    public static ParseResult parse(Path file, String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return ParseResult.empty();
        }
        Source source = Source.of(rawSource);
        String code = source.structural();
        String packageName = JavaSourceLexer.packageOf(code);
        int[] lineStarts = JavaSourceLexer.lineStarts(rawSource);

        List<SpringBean> beans = new ArrayList<>();
        List<SpringInjection> injections = new ArrayList<>();
        List<SpringEndpoint> endpoints = new ArrayList<>();
        List<SpringPropertyUsage> configurationProperties = new ArrayList<>();
        List<JavaType> declaredTypes = new ArrayList<>();
        List<String> imports = JavaSourceLexer.importsOf(code);

        Matcher types = JavaSourceLexer.TYPE_DECLARATION.matcher(code);
        while (types.find()) {
            String simpleName = types.group(2);
            int bodyStart = code.indexOf('{', types.end());
            if (bodyStart < 0) {
                continue;
            }
            int bodyEnd = JavaSourceLexer.matchingBrace(code, bodyStart);
            String header = code.substring(types.end(), bodyStart);
            Source body = source.sub(bodyStart + 1, bodyEnd);
            int bodyOffset = bodyStart + 1;
            String qualifiedName = packageName.isBlank() ? simpleName : packageName + "." + simpleName;
            int declarationLine = JavaSourceLexer.lineOf(lineStarts, types.start());

            declaredTypes.add(new JavaType(
                    qualifiedName,
                    simpleName,
                    packageName,
                    JavaSourceLexer.supertypesOf(header),
                    imports,
                    JavaSourceLexer.isAnnotationDeclaration(code, types.start())
                            ? JavaType.Kind.ANNOTATION
                            : JavaType.Kind.of(types.group(1)),
                    List.of(),
                    file,
                    declarationLine));

            List<Annotation> annotations = JavaSourceLexer.annotationsBefore(source, types.start());
            declaredTypes.set(declaredTypes.size() - 1, withAnnotations(
                    declaredTypes.getLast(), annotations));
            SpringStereotype stereotype = stereotypeOf(annotations);
            if (stereotype == null) {
                continue;
            }

            beans.add(new SpringBean(
                    beanNameOf(annotations, stereotype, simpleName),
                    qualifiedName,
                    simpleName,
                    stereotype,
                    file,
                    JavaSourceLexer.lineOf(lineStarts, types.start()),
                    JavaSourceLexer.supertypesOf(header),
                    profilesOf(annotations),
                    JavaSourceLexer.hasAnnotation(annotations, "primary"),
                    qualifierOf(annotations),
                    isConditional(annotations),
                    traitsOf(annotations)));

            List<SpringInjection> constructorInjections = constructorInjections(body, simpleName,
                    qualifiedName, file, lineStarts, bodyOffset);
            injections.addAll(constructorInjections);
            injections.addAll(lombokConstructorInjections(body, annotations, simpleName,
                    qualifiedName, file, lineStarts, bodyOffset, constructorInjections));
            injections.addAll(fieldInjections(body, qualifiedName, file, lineStarts, bodyOffset));
            injections.addAll(setterInjections(body, qualifiedName, file, lineStarts, bodyOffset));

            if (stereotype.declaresBeans()) {
                beans.addAll(beanMethods(body, packageName, file, lineStarts, bodyOffset));
                injections.addAll(beanMethodInjections(body, qualifiedName, file,
                        lineStarts, bodyOffset));
            }
            if (stereotype.isWebController()) {
                endpoints.addAll(endpoints(body, annotations, qualifiedName, file,
                        lineStarts, bodyOffset));
            }
            configurationProperties.addAll(configurationProperties(annotations, body, qualifiedName,
                    file, lineStarts, bodyOffset, JavaSourceLexer.lineOf(lineStarts, types.start())));
        }
        JpaSourceParser.ParseResult jpa = JpaSourceParser.parse(file, source, packageName, lineStarts);
        List<SpringPropertyUsage> usages = new ArrayList<>(
                propertyUsages(source, packageName, file, lineStarts));
        usages.addAll(configurationProperties);
        return new ParseResult(beans, injections, endpoints, jpa.entities(), jpa.repositories(),
                usages, declaredTypes,
                SpringInfraParser.parse(file, source, packageName, lineStarts));
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

        Matcher methods = JavaSourceLexer.METHOD.matcher(body.structural());
        while (methods.find()) {
            List<Annotation> annotations = JavaSourceLexer.annotationsBefore(body, methods.start());
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
                    JavaSourceLexer.lineOf(lineStarts, offset + methods.start()),
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
            String named = JavaSourceLexer.namedArgument(arguments, "path");
            if (named.isBlank()) {
                named = JavaSourceLexer.namedArgument(arguments, "value");
            }
            if (!named.isBlank()) {
                return named;
            }
            String direct = JavaSourceLexer.firstStringLiteral(arguments);
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
        return JavaSourceLexer.stringLiterals(
                JavaSourceLexer.namedArgumentRegion(arguments, "produces"));
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
            String explicit = JavaSourceLexer.firstStringLiteral(annotation.arguments());
            if (!explicit.isBlank()) {
                return explicit;
            }
        }
        return SpringBean.defaultBeanName(simpleName);
    }

    private static List<SpringBean> beanMethods(Source body, String packageName, Path file,
                                                int[] lineStarts, int offset) {
        List<SpringBean> beans = new ArrayList<>();
        Matcher methods = JavaSourceLexer.METHOD.matcher(body.structural());
        while (methods.find()) {
            List<Annotation> annotations = JavaSourceLexer.annotationsBefore(body, methods.start());
            if (!JavaSourceLexer.hasAnnotation(annotations, "bean")) {
                continue;
            }
            String returnType = methods.group(1);
            String methodName = methods.group(2);
            if (returnType == null || returnType.isBlank()) {
                continue;
            }
            String simpleType = SpringBean.simpleNameOf(returnType);
            String explicitName = JavaSourceLexer.firstStringLiteral(
                    JavaSourceLexer.argumentsOf(annotations, "bean"));

            beans.add(new SpringBean(
                    explicitName.isBlank() ? methodName : explicitName,
                    packageName.isBlank() ? simpleType : packageName + "." + simpleType,
                    simpleType,
                    SpringStereotype.BEAN_METHOD,
                    file,
                    JavaSourceLexer.lineOf(lineStarts, offset + methods.start()),
                    List.of(),
                    profilesOf(annotations),
                    JavaSourceLexer.hasAnnotation(annotations, "primary"),
                    qualifierOf(annotations),
                    isConditional(annotations),
                    traitsOf(annotations)));
        }
        return beans;
    }

    private static List<SpringInjection> constructorInjections(Source body, String simpleName,
                                                               String ownerType, Path file,
                                                               int[] lineStarts, int offset) {
        List<SpringInjection> injections = new ArrayList<>();
        Matcher methods = JavaSourceLexer.METHOD.matcher(body.structural());
        while (methods.find()) {
            boolean isConstructor = simpleName.equals(methods.group(2)) && methods.group(1) == null;
            if (!isConstructor) {
                continue;
            }
            int line = JavaSourceLexer.lineOf(lineStarts, offset + methods.start());
            String parameters = body.literal().substring(methods.start(3), methods.end(3));
            for (Parameter parameter : JavaSourceLexer.parseParameters(parameters)) {
                injections.add(new SpringInjection(ownerType, parameter.type(), parameter.name(),
                        SpringInjection.Kind.CONSTRUCTOR, file, line, parameter.qualifier(),
                        parameter.annotations()));
            }
        }
        return injections;
    }

    private static List<SpringInjection> lombokConstructorInjections(Source body,
                                                                     List<Annotation> typeAnnotations,
                                                                     String simpleName, String ownerType,
                                                                     Path file, int[] lineStarts, int offset,
                                                                     List<SpringInjection> explicit) {
        boolean allArgs = JavaSourceLexer.hasAnnotation(typeAnnotations, "allargsconstructor");
        boolean requiredArgs = JavaSourceLexer.hasAnnotation(typeAnnotations, "requiredargsconstructor")
                || (JavaSourceLexer.hasAnnotation(typeAnnotations, "data")
                && !hasExplicitConstructor(body, simpleName));
        if (!allArgs && !requiredArgs) {
            return List.of();
        }
        Set<String> covered = new java.util.HashSet<>();
        explicit.forEach(injection -> covered.add(injection.memberName()));
        String code = body.structural();
        int[] depth = braceDepths(code);
        List<SpringInjection> injections = new ArrayList<>();
        Matcher fields = JavaSourceLexer.FIELD.matcher(code);
        while (fields.find()) {
            if (depth[fields.start(1)] != 0) {
                continue;
            }
            String modifiers = code.substring(fields.start(), fields.start(1));
            if (MODIFIER_STATIC.matcher(modifiers).find() || fields.group().endsWith("=")) {
                continue;
            }
            List<Annotation> annotations = JavaSourceLexer.annotationsBefore(body, fields.start());
            boolean required = MODIFIER_FINAL.matcher(modifiers).find()
                    || JavaSourceLexer.hasAnnotation(annotations, "nonnull");
            if ((!allArgs && !required) || JavaSourceLexer.hasAnnotation(annotations, "value")
                    || JavaSourceLexer.hasAnyAnnotation(annotations, INJECTION_ANNOTATIONS)
                    || !covered.add(fields.group(2))) {
                continue;
            }
            injections.add(new SpringInjection(ownerType, fields.group(1), fields.group(2),
                    SpringInjection.Kind.CONSTRUCTOR, file,
                    JavaSourceLexer.lineOf(lineStarts, offset + fields.start(1)),
                    qualifierOf(annotations),
                    annotations.stream().map(Annotation::simpleName).distinct().toList()));
        }
        return injections;
    }

    private static boolean hasExplicitConstructor(Source body, String simpleName) {
        Matcher methods = JavaSourceLexer.METHOD.matcher(body.structural());
        while (methods.find()) {
            if (simpleName.equals(methods.group(2)) && methods.group(1) == null) {
                return true;
            }
        }
        return false;
    }

    static int[] braceDepths(String code) {
        int[] depths = new int[code.length() + 1];
        int depth = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '}') {
                depth = Math.max(0, depth - 1);
            }
            depths[i] = depth;
            if (c == '{') {
                depth++;
            }
        }
        depths[code.length()] = depth;
        return depths;
    }

    private static List<SpringInjection> beanMethodInjections(Source body, String ownerType, Path file,
                                                              int[] lineStarts, int offset) {
        List<SpringInjection> injections = new ArrayList<>();
        Matcher methods = JavaSourceLexer.METHOD.matcher(body.structural());
        while (methods.find()) {
            if (!JavaSourceLexer.hasAnnotation(
                    JavaSourceLexer.annotationsBefore(body, methods.start()), "bean")) {
                continue;
            }
            int line = JavaSourceLexer.lineOf(lineStarts, offset + methods.start());
            String parameters = body.literal().substring(methods.start(3), methods.end(3));
            for (Parameter parameter : JavaSourceLexer.parseParameters(parameters)) {
                injections.add(new SpringInjection(ownerType, parameter.type(), parameter.name(),
                        SpringInjection.Kind.CONSTRUCTOR, file, line, parameter.qualifier(),
                        parameter.annotations()));
            }
        }
        return injections;
    }

    private static List<SpringInjection> fieldInjections(Source body, String ownerType, Path file,
                                                         int[] lineStarts, int offset) {
        List<SpringInjection> injections = new ArrayList<>();
        Matcher fields = JavaSourceLexer.FIELD.matcher(body.structural());
        while (fields.find()) {
            List<Annotation> annotations = JavaSourceLexer.annotationsBefore(body, fields.start());
            if (!JavaSourceLexer.hasAnyAnnotation(annotations, INJECTION_ANNOTATIONS)) {
                continue;
            }
            injections.add(new SpringInjection(ownerType, fields.group(1), fields.group(2),
                    SpringInjection.Kind.FIELD, file,
                    JavaSourceLexer.lineOf(lineStarts, offset + fields.start()),
                    qualifierOf(annotations),
                    annotations.stream().map(Annotation::simpleName).distinct().toList()));
        }
        return injections;
    }

    private static List<SpringInjection> setterInjections(Source body, String ownerType, Path file,
                                                          int[] lineStarts, int offset) {
        List<SpringInjection> injections = new ArrayList<>();
        Matcher methods = JavaSourceLexer.METHOD.matcher(body.structural());
        while (methods.find()) {
            String methodName = methods.group(2);
            if (methods.group(1) == null || !isSetterName(methodName)) {
                continue;
            }
            List<Annotation> annotations = JavaSourceLexer.annotationsBefore(body, methods.start());
            if (!JavaSourceLexer.hasAnyAnnotation(annotations, INJECTION_ANNOTATIONS)) {
                continue;
            }
            int line = JavaSourceLexer.lineOf(lineStarts, offset + methods.start());
            String parameters = body.literal().substring(methods.start(3), methods.end(3));
            String methodQualifier = qualifierOf(annotations);
            for (Parameter parameter : JavaSourceLexer.parseParameters(parameters)) {
                String qualifier = parameter.qualifier().isBlank()
                        ? methodQualifier : parameter.qualifier();
                injections.add(new SpringInjection(ownerType, parameter.type(), parameter.name(),
                        SpringInjection.Kind.SETTER, file, line, qualifier,
                        parameter.annotations()));
            }
        }
        return injections;
    }

    private static List<SpringPropertyUsage> propertyUsages(Source source, String packageName,
                                                            Path file, int[] lineStarts) {
        List<SpringPropertyUsage> usages = new ArrayList<>();
        String literal = source.literal();

        Matcher values = VALUE_ANNOTATION.matcher(literal);
        while (values.find()) {
            Matcher placeholder = PLACEHOLDER.matcher(values.group(1));
            while (placeholder.find()) {
                usages.add(new SpringPropertyUsage(
                        placeholder.group(1),
                        placeholder.group(2) == null ? "" : placeholder.group(2),
                        packageName,
                        SpringPropertyUsage.Kind.VALUE,
                        file,
                        JavaSourceLexer.lineOf(lineStarts, values.start())));
            }
        }

        return usages;
    }

    private static List<SpringPropertyUsage> configurationProperties(List<Annotation> annotations,
                                                                     Source body, String ownerType,
                                                                     Path file, int[] lineStarts,
                                                                     int bodyOffset, int typeLine) {
        String arguments = JavaSourceLexer.argumentsOf(annotations, "configurationproperties");
        if (arguments.isBlank()) {
            return List.of();
        }
        String prefix = JavaSourceLexer.namedArgument(arguments, "prefix");
        if (prefix.isBlank()) {
            prefix = JavaSourceLexer.firstStringLiteral(arguments);
        }
        if (prefix.isBlank()) {
            return List.of();
        }
        List<SpringPropertyUsage> usages = new ArrayList<>();
        usages.add(new SpringPropertyUsage(prefix, "", ownerType,
                SpringPropertyUsage.Kind.CONFIGURATION_PROPERTIES, file, typeLine));

        Matcher fields = JavaSourceLexer.FIELD.matcher(body.structural());
        while (fields.find()) {
            if (fields.group().contains("static")) {
                continue;
            }
            usages.add(new SpringPropertyUsage(
                    prefix + "." + fields.group(2),
                    "",
                    ownerType,
                    SpringPropertyUsage.Kind.CONFIGURATION_PROPERTIES,
                    file,
                    JavaSourceLexer.lineOf(lineStarts, bodyOffset + fields.start())));
        }
        return usages;
    }

    private static boolean isSetterName(String methodName) {
        return methodName != null && methodName.length() > 3 && methodName.startsWith("set")
                && Character.isUpperCase(methodName.charAt(3));
    }

    private static boolean isConditional(List<Annotation> annotations) {
        return annotations.stream().anyMatch(a -> a.simpleName().startsWith("conditionalon"));
    }

    private static JavaType withAnnotations(JavaType type, List<Annotation> annotations) {
        return new JavaType(type.qualifiedName(), type.simpleName(), type.packageName(),
                type.supertypes(), type.imports(), type.kind(),
                annotations.stream().map(Annotation::simpleName).distinct().toList(),
                type.file(), type.line());
    }

    private static String qualifierOf(List<Annotation> annotations) {
        for (String candidate : QUALIFIER_ANNOTATIONS) {
            String value = JavaSourceLexer.firstStringLiteral(
                    JavaSourceLexer.argumentsOf(annotations, candidate));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    static List<String> qualifierAnnotationNames(List<Annotation> annotations) {
        return annotations.stream()
                .map(Annotation::simpleName)
                .filter(name -> !QUALIFIER_ANNOTATIONS.contains(name))
                .distinct()
                .toList();
    }

    private static SpringBeanTraits traitsOf(List<Annotation> annotations) {
        String scope = JavaSourceLexer.firstStringLiteral(
                JavaSourceLexer.argumentsOf(annotations, "scope"));
        List<String> dependsOn = JavaSourceLexer.stringLiterals(
                JavaSourceLexer.argumentsOf(annotations, "dependson"));
        Integer order = orderOf(annotations);
        return new SpringBeanTraits(scope, JavaSourceLexer.hasAnnotation(annotations, "lazy"),
                order, dependsOn, qualifierAnnotationNames(annotations));
    }

    private static Integer orderOf(List<Annotation> annotations) {
        for (String name : List.of("order", "priority")) {
            String arguments = JavaSourceLexer.argumentsOf(annotations, name);
            if (arguments.isBlank()) {
                continue;
            }
            Matcher matcher = Pattern.compile("(-?\\d+)").matcher(arguments);
            if (matcher.find()) {
                try {
                    return Integer.valueOf(matcher.group(1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static List<String> profilesOf(List<Annotation> annotations) {
        return JavaSourceLexer.stringLiterals(JavaSourceLexer.argumentsOf(annotations, "profile"))
                .stream()
                .filter(profile -> !profile.isBlank())
                .toList();
    }

    static List<Parameter> parseParameters(String rawParameters) {
        return JavaSourceLexer.parseParameters(rawParameters);
    }

    static List<String> supertypesOf(String header) {
        return JavaSourceLexer.supertypesOf(header);
    }

    static String blankComments(String source) {
        return JavaSourceLexer.blankComments(source);
    }

    static int matchingBrace(String code, int openIndex) {
        return JavaSourceLexer.matchingBrace(code, openIndex);
    }

    static List<String> splitTopLevel(String value) {
        return JavaSourceLexer.splitTopLevel(value);
    }

    static int lineOf(int[] lineStarts, int offset) {
        return JavaSourceLexer.lineOf(lineStarts, offset);
    }
}
