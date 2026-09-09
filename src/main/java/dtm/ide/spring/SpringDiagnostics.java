package dtm.ide.spring;

import dtm.ide.inspection.JavaInspection;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpringDiagnostics {

    private static String text(String key, String fallback) {
        return dtm.stools.i18n.I18n.getText(SpringDiagnostics.class, key, fallback);
    }

    private static final String SOURCE = "spring";

    private static final Pattern PATH_TEMPLATE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");

    private static final Pattern PATH_VARIABLE = Pattern.compile(
            "@PathVariable\\s*(?:\\(\\s*(?:(?:name|value)\\s*=\\s*)?\"([^\"]*)\"\\s*\\))?"
                    + "\\s*(?:final\\s+)?[A-Za-z_][\\w.<>\\[\\]]*\\s+([A-Za-z_]\\w*)");

    private static final Pattern NON_PUBLIC_TRANSACTIONAL = Pattern.compile(
            "(?m)^[ \\t]*@Transactional\\b[^\\n]*\\n(?:[ \\t]*@[^\\n]*\\n)*"
                    + "[ \\t]*(private|protected|final)\\s");

    private SpringDiagnostics() {
    }

    public static List<Diagnostic> analyze(SpringIndexSnapshot snapshot, Path file, String source) {
        if (snapshot == null || snapshot.isEmpty() || file == null) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(ambiguousInjections(snapshot, file));
        diagnostics.addAll(fieldInjections(snapshot, file));
        diagnostics.addAll(circularDependencies(snapshot, file));
        diagnostics.addAll(uninterceptableTransactional(source));
        diagnostics.addAll(unresolvedQualifiers(snapshot, file));
        diagnostics.addAll(unsatisfiedInjections(snapshot, file));
        diagnostics.addAll(duplicatedEndpoints(snapshot, file));
        diagnostics.addAll(danglingPathVariables(snapshot, file, source));
        return diagnostics;
    }

    private static List<Diagnostic> ambiguousInjections(SpringIndexSnapshot snapshot, Path file) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (SpringInjection injection : snapshot.injectionsIn(file)) {
            if (snapshot.targetOf(injection).expectsMany()) {
                continue;
            }
            List<SpringBean> candidates =
                    snapshot.beansProviding(injection.targetType(), injection.file());
            if (candidates.size() < 2 || snapshot.resolve(injection).isPresent()) {
                continue;
            }
            String names = String.join(", ", candidates.stream().map(SpringBean::simpleName).toList());
            diagnostics.add(diagnostic(injection.line(), DiagnosticSeverity.ERROR,
                    text("diagnostic.ambiguous", "Mais de um bean satisfaz")
                            + " " + injection.targetSimpleName() + " (" + names + "). "
                            + text("diagnostic.ambiguous.hint",
                            "Use @Qualifier no ponto de injecao ou @Primary num dos beans.")));
        }
        return diagnostics;
    }

    private static List<Diagnostic> fieldInjections(SpringIndexSnapshot snapshot, Path file) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (SpringInjection injection : snapshot.injectionsIn(file)) {
            if (injection.kind() != SpringInjection.Kind.FIELD) {
                continue;
            }
            diagnostics.add(diagnostic(injection.line(), DiagnosticSeverity.HINT,
                    text("diagnostic.fieldInjection",
                            "Injecao por campo dificulta testes; prefira o construtor."),
                    JavaInspection.SPRING_FIELD_INJECTION));
        }
        return diagnostics;
    }

    private static List<Diagnostic> circularDependencies(SpringIndexSnapshot snapshot, Path file) {
        Map<String, List<SpringInjection>> graph = constructorGraph(snapshot);
        List<Diagnostic> diagnostics = new ArrayList<>();

        for (SpringBean bean : snapshot.beansIn(file)) {
            List<String> cycle = findCycle(bean.type(), graph, snapshot);
            if (cycle.isEmpty()) {
                continue;
            }
            SpringInjection firstStep = graph.getOrDefault(bean.type(), List.of()).stream()
                    .findFirst()
                    .orElse(null);
            int line = firstStep == null ? bean.line() : firstStep.line();
            diagnostics.add(diagnostic(line, DiagnosticSeverity.ERROR,
                    text("diagnostic.cycle", "Dependencia circular entre beans:")
                            + " " + String.join(" -> ", cycle)));
        }
        return diagnostics;
    }

    private static List<Diagnostic> unresolvedQualifiers(SpringIndexSnapshot snapshot, Path file) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (SpringInjection injection : snapshot.injectionsIn(file)) {
            if (!injection.hasQualifier()) {
                continue;
            }
            if (!snapshot.beansMatchingQualifier(injection.qualifier()).isEmpty()) {
                continue;
            }
            StringBuilder message = new StringBuilder()
                    .append(text("diagnostic.unknownQualifier", "Nenhum bean com este qualifier"))
                    .append(": ")
                    .append(injection.qualifier());
            String closest = closestName(injection.qualifier(), snapshot.beanNames());
            if (!closest.isBlank()) {
                message.append(". ")
                        .append(text("diagnostic.useInstead", "Voce quis dizer"))
                        .append(' ')
                        .append(closest)
                        .append('?');
            }
            diagnostics.add(diagnostic(injection.line(), DiagnosticSeverity.ERROR,
                    message.toString()));
        }
        return diagnostics;
    }

    private static List<Diagnostic> unsatisfiedInjections(SpringIndexSnapshot snapshot, Path file) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (SpringInjection injection : snapshot.injectionsIn(file)) {
            InjectionTarget target = snapshot.targetOf(injection);
            if (target.optional() || target.expectsMany()) {
                continue;
            }
            if (!snapshot.beansProviding(injection.targetType(), injection.file()).isEmpty()) {
                continue;
            }
            if (!isProjectType(snapshot, target, injection)) {
                continue;
            }
            diagnostics.add(diagnostic(injection.line(), DiagnosticSeverity.WARNING,
                    text("diagnostic.unsatisfied", "Nenhum bean satisfaz esta injecao")
                            + ": " + target.simpleName(),
                    JavaInspection.SPRING_UNSATISFIED));
        }
        return diagnostics;
    }

    private static boolean isProjectType(SpringIndexSnapshot snapshot, InjectionTarget target,
                                         SpringInjection injection) {
        JavaType context = snapshot.types().stream()
                .filter(type -> injection.file().equals(type.file()))
                .findFirst()
                .orElse(null);
        return snapshot.typeGraph().resolve(target.type(), context).isPresent();
    }

    private static List<Diagnostic> duplicatedEndpoints(SpringIndexSnapshot snapshot, Path file) {
        Map<String, List<SpringEndpoint>> byRoute = new HashMap<>();
        for (SpringEndpoint endpoint : snapshot.endpoints()) {
            byRoute.computeIfAbsent(endpoint.method() + " " + endpoint.path(),
                    key -> new ArrayList<>()).add(endpoint);
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (SpringEndpoint endpoint : snapshot.endpoints()) {
            if (!file.equals(endpoint.file())) {
                continue;
            }
            List<SpringEndpoint> sharing = byRoute.get(endpoint.method() + " " + endpoint.path());
            if (sharing == null || sharing.size() < 2) {
                continue;
            }
            String others = String.join(", ", sharing.stream()
                    .filter(other -> other != endpoint)
                    .map(other -> SpringBean.simpleNameOf(other.handlerType())
                            + "." + other.handlerName())
                    .toList());
            diagnostics.add(diagnostic(endpoint.line(), DiagnosticSeverity.ERROR,
                    text("diagnostic.duplicatedEndpoint", "Rota mapeada mais de uma vez")
                            + " (" + endpoint.method() + " " + endpoint.path() + "): " + others));
        }
        return diagnostics;
    }

    private static List<Diagnostic> danglingPathVariables(SpringIndexSnapshot snapshot, Path file,
                                                          String source) {
        if (source == null || source.isBlank() || !source.contains("@PathVariable")) {
            return List.of();
        }
        String code = SpringSourceParser.blankComments(source);
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (SpringEndpoint endpoint : snapshot.endpoints()) {
            if (!file.equals(endpoint.file())) {
                continue;
            }
            Set<String> declared = templateVariables(endpoint.path());
            for (String variable : pathVariablesOf(code, endpoint.line())) {
                if (declared.contains(variable)) {
                    continue;
                }
                diagnostics.add(diagnostic(endpoint.line(), DiagnosticSeverity.ERROR,
                        text("diagnostic.danglingPathVariable",
                                "@PathVariable sem correspondente no template da rota")
                                + ": " + variable + " (" + endpoint.path() + ")"));
            }
        }
        return diagnostics;
    }

    private static Set<String> templateVariables(String path) {
        Set<String> variables = new HashSet<>();
        Matcher matcher = PATH_TEMPLATE.matcher(path == null ? "" : path);
        while (matcher.find()) {
            variables.add(matcher.group(1).trim());
        }
        return variables;
    }

    private static List<String> pathVariablesOf(String code, int line) {
        String declaration = lineText(code, line);
        if (declaration == null || !declaration.contains("@PathVariable")) {
            return List.of();
        }
        List<String> variables = new ArrayList<>();
        Matcher matcher = PATH_VARIABLE.matcher(declaration);
        while (matcher.find()) {
            String explicit = matcher.group(1);
            String named = matcher.group(2);
            variables.add(explicit != null ? explicit : named);
        }
        return variables;
    }

    private static String lineText(String code, int line) {
        String[] lines = code.split("\n", -1);
        return line >= 1 && line <= lines.length ? lines[line - 1] : null;
    }

    private static String closestName(String target, List<String> candidates) {
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        int limit = Math.max(2, target.length() / 2);
        for (String candidate : candidates) {
            int distance = dtm.ide.spring.jpa.JpaNaming.editDistance(target, candidate);
            if (distance < bestDistance && distance <= limit) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private static Map<String, List<SpringInjection>> constructorGraph(SpringIndexSnapshot snapshot) {
        Map<String, List<SpringInjection>> graph = new HashMap<>();
        for (SpringInjection injection : snapshot.injections()) {
            if (injection.kind() == SpringInjection.Kind.CONSTRUCTOR) {
                graph.computeIfAbsent(injection.ownerType(), key -> new ArrayList<>()).add(injection);
            }
        }
        return graph;
    }

    private static List<String> findCycle(String start, Map<String, List<SpringInjection>> graph,
                                          SpringIndexSnapshot snapshot) {
        Deque<String> path = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        return walk(start, start, graph, snapshot, path, visited) ? simpleNames(path, start) : List.of();
    }

    private static boolean walk(String current, String start, Map<String, List<SpringInjection>> graph,
                                SpringIndexSnapshot snapshot, Deque<String> path, Set<String> visited) {
        if (!visited.add(current)) {
            return false;
        }
        path.addLast(current);
        for (SpringInjection injection : graph.getOrDefault(current, List.of())) {
            Optional<SpringBean> target = snapshot.resolve(injection);
            if (target.isEmpty()) {
                continue;
            }
            String next = target.get().type();
            if (next.equals(start)) {
                path.addLast(next);
                return true;
            }
            if (walk(next, start, graph, snapshot, path, visited)) {
                return true;
            }
        }
        path.removeLast();
        return false;
    }

    private static List<String> simpleNames(Deque<String> path, String start) {
        List<String> names = new ArrayList<>();
        boolean started = false;
        for (String type : path) {
            if (type.equals(start)) {
                started = true;
            }
            if (started) {
                names.add(SpringBean.simpleNameOf(type));
            }
        }
        return names;
    }

    private static List<Diagnostic> uninterceptableTransactional(String source) {
        if (source == null || source.isBlank() || !source.contains("@Transactional")) {
            return List.of();
        }
        String code = SpringSourceParser.blankComments(source);
        List<Diagnostic> diagnostics = new ArrayList<>();
        Matcher matcher = NON_PUBLIC_TRANSACTIONAL.matcher(code);

        while (matcher.find()) {
            int line = lineNumberOf(code, matcher.start());
            diagnostics.add(diagnostic(line, DiagnosticSeverity.WARNING,
                    transactionalMessage(), JavaInspection.SPRING_TRANSACTIONAL));
        }
        return diagnostics;
    }

    private static String transactionalMessage() {
        return text("diagnostic.transactional",
                "@Transactional so vale em metodo publico e nao final: o proxy do "
                        + "Spring nao intercepta este metodo.");
    }

    private static Diagnostic diagnostic(int line, DiagnosticSeverity severity, String message) {
        return diagnostic(line, severity, message, SOURCE);
    }

    private static Diagnostic diagnostic(int line, DiagnosticSeverity severity, String message,
                                         JavaInspection inspection) {
        return diagnostic(line, severity, message, inspection.id());
    }

    private static Diagnostic diagnostic(int line, DiagnosticSeverity severity, String message,
                                         String source) {
        int editorLine = Math.max(0, line - 1);
        return new Diagnostic(editorLine, 0, editorLine, Integer.MAX_VALUE, severity, message,
                source, null);
    }

    private static int lineNumberOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
