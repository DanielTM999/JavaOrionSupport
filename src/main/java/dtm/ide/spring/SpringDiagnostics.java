package dtm.ide.spring;

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
        return diagnostics;
    }

    private static List<Diagnostic> ambiguousInjections(SpringIndexSnapshot snapshot, Path file) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (SpringInjection injection : snapshot.injectionsIn(file)) {
            List<SpringBean> candidates = snapshot.beansProviding(injection.targetType());
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
                            "Injecao por campo dificulta testes; prefira o construtor.")));
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
                    text("diagnostic.transactional",
                            "@Transactional so vale em metodo publico e nao final: o proxy do "
                                    + "Spring nao intercepta este metodo.")));
        }
        return diagnostics;
    }

    private static Diagnostic diagnostic(int line, DiagnosticSeverity severity, String message) {
        int editorLine = Math.max(0, line - 1);
        return new Diagnostic(editorLine, 0, editorLine, Integer.MAX_VALUE, severity, message,
                SOURCE, null);
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
