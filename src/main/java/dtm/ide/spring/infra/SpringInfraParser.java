package dtm.ide.spring.infra;

import dtm.ide.spring.JavaSourceLexer;
import dtm.ide.spring.JavaSourceLexer.Annotation;
import dtm.ide.spring.JavaSourceLexer.Parameter;
import dtm.ide.spring.JavaSourceLexer.Source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpringInfraParser {

    private static final Map<String, String> CACHE_ANNOTATIONS = Map.of(
            "cacheable", "Cacheable",
            "cacheevict", "CacheEvict",
            "cacheput", "CachePut",
            "caching", "Caching");

    private static final Map<String, String> SECURITY_ANNOTATIONS = Map.of(
            "preauthorize", "PreAuthorize",
            "postauthorize", "PostAuthorize",
            "prefilter", "PreFilter",
            "postfilter", "PostFilter");

    private static final Pattern PUBLISH_EVENT = Pattern.compile(
            "publishEvent\\s*\\(\\s*new\\s+([A-Za-z_][\\w.]*)");

    private SpringInfraParser() {
    }

    public static SpringInfraModel parse(Path file, Source source, String packageName,
                                         int[] lineStarts) {
        if (source == null || source.structural().isBlank()) {
            return SpringInfraModel.empty();
        }
        String code = source.structural();
        List<ScheduledTask> scheduled = new ArrayList<>();
        List<EventHandler> handlers = new ArrayList<>();
        List<EventPublication> publications = new ArrayList<>();
        List<CacheUsage> caches = new ArrayList<>();
        List<SecurityRule> rules = new ArrayList<>();

        boolean enablesScheduling = code.contains("@EnableScheduling");
        boolean enablesCaching = code.contains("@EnableCaching");
        boolean enablesMethodSecurity = code.contains("@EnableMethodSecurity")
                || code.contains("@EnableGlobalMethodSecurity");

        Matcher types = JavaSourceLexer.TYPE_DECLARATION.matcher(code);
        while (types.find()) {
            String simpleName = types.group(2);
            int bodyStart = code.indexOf('{', types.end());
            if (bodyStart < 0) {
                continue;
            }
            int bodyEnd = JavaSourceLexer.matchingBrace(code, bodyStart);
            Source body = source.sub(bodyStart + 1, bodyEnd);
            int bodyOffset = bodyStart + 1;
            String ownerType = packageName.isBlank() ? simpleName : packageName + "." + simpleName;

            Matcher methods = JavaSourceLexer.METHOD.matcher(body.structural());
            while (methods.find()) {
                List<Annotation> annotations =
                        JavaSourceLexer.annotationsBefore(body, methods.start());
                if (annotations.isEmpty()) {
                    continue;
                }
                int line = JavaSourceLexer.lineOf(lineStarts, bodyOffset + methods.start());
                String methodName = methods.group(2);
                String rawParameters = body.literal()
                        .substring(methods.start(3), methods.end(3));

                if (JavaSourceLexer.hasAnnotation(annotations, "scheduled")) {
                    scheduled.add(scheduledOf(annotations, ownerType, methodName, file, line));
                }
                if (JavaSourceLexer.hasAnnotation(annotations, "eventlistener")
                        || JavaSourceLexer.hasAnnotation(annotations, "transactionaleventlistener")) {
                    handlers.add(handlerOf(annotations, ownerType, methodName, rawParameters,
                            file, line));
                }
                caches.addAll(cachesOf(annotations, ownerType, methodName, file, line));
                rules.addAll(rulesOf(annotations, ownerType, methodName, file, line));
            }

            Matcher publish = PUBLISH_EVENT.matcher(body.structural());
            while (publish.find()) {
                publications.add(new EventPublication(ownerType, publish.group(1), file,
                        JavaSourceLexer.lineOf(lineStarts, bodyOffset + publish.start())));
            }
        }
        return new SpringInfraModel(scheduled, handlers, publications, caches, rules,
                enablesScheduling, enablesCaching, enablesMethodSecurity);
    }

    private static ScheduledTask scheduledOf(List<Annotation> annotations, String ownerType,
                                             String methodName, Path file, int line) {
        String arguments = JavaSourceLexer.argumentsOf(annotations, "scheduled");
        return new ScheduledTask(
                ownerType,
                methodName,
                JavaSourceLexer.namedArgument(arguments, "cron"),
                rawArgument(arguments, "fixedDelay"),
                rawArgument(arguments, "fixedRate"),
                rawArgument(arguments, "initialDelay"),
                file,
                line);
    }

    private static String rawArgument(String arguments, String name) {
        String region = JavaSourceLexer.namedArgumentRegion(arguments, name);
        if (region.isBlank()) {
            String fallback = JavaSourceLexer.namedArgumentRegion(arguments, name + "String");
            region = fallback;
        }
        return region.replace(",", "").trim();
    }

    private static EventHandler handlerOf(List<Annotation> annotations, String ownerType,
                                          String methodName, String rawParameters, Path file,
                                          int line) {
        List<Parameter> parameters = JavaSourceLexer.parseParameters(rawParameters);
        String eventType = parameters.isEmpty() ? "" : parameters.getFirst().type();
        if (eventType.isBlank()) {
            String classes = JavaSourceLexer.argumentsOf(annotations, "eventlistener");
            Matcher matcher = Pattern.compile("([A-Za-z_][\\w.]*)\\s*\\.\\s*class")
                    .matcher(classes);
            if (matcher.find()) {
                eventType = matcher.group(1);
            }
        }
        return new EventHandler(ownerType, methodName, eventType,
                JavaSourceLexer.hasAnnotation(annotations, "transactionaleventlistener"),
                file, line);
    }

    private static List<CacheUsage> cachesOf(List<Annotation> annotations, String ownerType,
                                             String methodName, Path file, int line) {
        List<CacheUsage> usages = new ArrayList<>();
        for (Annotation annotation : annotations) {
            String operation = CACHE_ANNOTATIONS.get(annotation.simpleName());
            if (operation == null) {
                continue;
            }
            List<String> names = JavaSourceLexer.stringLiterals(
                    JavaSourceLexer.namedArgumentRegion(annotation.arguments(), "cacheNames"));
            if (names.isEmpty()) {
                names = JavaSourceLexer.stringLiterals(
                        JavaSourceLexer.namedArgumentRegion(annotation.arguments(), "value"));
            }
            if (names.isEmpty()) {
                names = JavaSourceLexer.stringLiterals(annotation.arguments());
            }
            usages.add(new CacheUsage(ownerType, methodName, operation, names, file, line));
        }
        return usages;
    }

    private static List<SecurityRule> rulesOf(List<Annotation> annotations, String ownerType,
                                              String methodName, Path file, int line) {
        List<SecurityRule> rules = new ArrayList<>();
        for (Annotation annotation : annotations) {
            String name = SECURITY_ANNOTATIONS.get(annotation.simpleName());
            if (name == null) {
                continue;
            }
            rules.add(new SecurityRule(ownerType, methodName, name,
                    JavaSourceLexer.firstStringLiteral(annotation.arguments()), file, line));
        }
        return rules;
    }
}
