package dtm.ide;

import dtm.ide.build.BuildDiagnostic;
import dtm.ide.build.BuildDiagnosticParser;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class BuildProblemsCoordinator {

    private final Map<Path, List<Diagnostic>> buildDiagnostics = new ConcurrentHashMap<>();
    private final Map<Path, List<BuildDiagnostic>> liveProblems = new ConcurrentHashMap<>();
    private volatile List<BuildDiagnostic> buildProblems = List.of();

    List<Diagnostic> diagnostics(Path file) {
        return buildDiagnostics.getOrDefault(file, List.of());
    }

    void publishLive(Path file, List<BuildDiagnostic> problems) {
        if (problems == null || problems.isEmpty()) {
            liveProblems.remove(file);
        } else {
            liveProblems.put(file, List.copyOf(problems));
        }
    }

    List<BuildDiagnostic> buildProblems() {
        return buildProblems;
    }

    List<BuildDiagnostic> liveProblems() {
        return liveProblems.values().stream().flatMap(List::stream).toList();
    }

    Set<Path> paths() {
        Set<Path> paths = new LinkedHashSet<>(buildDiagnostics.keySet());
        paths.addAll(liveProblems.keySet());
        return paths;
    }

    Set<Path> replaceBuild(List<BuildDiagnostic> problems) {
        Set<Path> affected = new LinkedHashSet<>(buildDiagnostics.keySet());
        buildProblems = problems == null ? List.of() : List.copyOf(problems);
        buildDiagnostics.clear();
        BuildDiagnosticParser.byFile(buildProblems).forEach((file, diagnostics) ->
                buildDiagnostics.put(file, diagnostics.stream()
                        .map(BuildDiagnostic::toEditorDiagnostic)
                        .toList()));
        affected.addAll(buildDiagnostics.keySet());
        return affected;
    }

    Set<Path> clearBuild() {
        return replaceBuild(List.of());
    }

    Set<Path> clearAll() {
        Set<Path> affected = paths();
        buildDiagnostics.clear();
        liveProblems.clear();
        buildProblems = List.of();
        return affected;
    }

    void clearLive() {
        liveProblems.clear();
    }

    void removeBelow(Path deleted) {
        buildDiagnostics.keySet().removeIf(candidate -> candidate.startsWith(deleted));
        liveProblems.keySet().removeIf(candidate -> candidate.startsWith(deleted));
        List<BuildDiagnostic> retained = new ArrayList<>();
        for (BuildDiagnostic problem : buildProblems) {
            if (problem.file() == null || !problem.file().startsWith(deleted)) {
                retained.add(problem);
            }
        }
        buildProblems = List.copyOf(retained);
    }
}
