package dtm.ide;

import dtm.ide.build.BuildDiagnostic;
import dtm.ide.build.BuildDiagnosticParser;
import dtm.ide.inspection.JavaDiagnosticEdits;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class BuildProblemsCoordinator {

    enum Channel {
        BUILD,
        TEST
    }

    private static final Set<String> COMPILER_SOURCES = Set.of("javac", "maven", "gradle", "ecj");

    private final Map<Channel, List<BuildDiagnostic>> channels = new EnumMap<>(Channel.class);
    private final Map<Path, List<BuildDiagnostic>> liveProblems = new ConcurrentHashMap<>();
    private volatile Map<Path, List<Diagnostic>> buildDiagnostics = Map.of();
    private volatile List<BuildDiagnostic> buildProblems = List.of();

    public List<Diagnostic> diagnostics(Path file) {
        return buildDiagnostics.getOrDefault(file, List.of());
    }

    public void publishLive(Path file, List<BuildDiagnostic> problems) {
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
        return replace(Channel.BUILD, problems);
    }

    synchronized Set<Path> replace(Channel channel, List<BuildDiagnostic> problems) {
        Set<Path> affected = new LinkedHashSet<>(buildDiagnostics.keySet());
        channels.put(channel, problems == null ? List.of() : List.copyOf(problems));
        reindex();
        affected.addAll(buildDiagnostics.keySet());
        return affected;
    }

    synchronized Set<Path> clearBuild() {
        Set<Path> affected = new LinkedHashSet<>(buildDiagnostics.keySet());
        channels.clear();
        reindex();
        return affected;
    }

    public synchronized boolean supersedeCompilerProblems(Path file) {
        return removeMatching(problem -> file.equals(problem.file()) && isCompilerProblem(problem));
    }

    synchronized boolean supersedeAll(Path file) {
        return removeMatching(problem -> file.equals(problem.file()));
    }

    synchronized boolean move(Path file, String before, String after) {
        boolean changed = false;
        for (Map.Entry<Channel, List<BuildDiagnostic>> entry : channels.entrySet()) {
            List<BuildDiagnostic> shifted = moved(entry.getValue(), file, before, after);
            changed |= !shifted.equals(entry.getValue());
            entry.setValue(shifted);
        }
        List<BuildDiagnostic> live = liveProblems.get(file);
        if (live != null) {
            List<BuildDiagnostic> shifted = moved(live, file, before, after);
            changed |= !shifted.equals(live);
            liveProblems.put(file, shifted);
        }
        if (changed) reindex();
        return changed;
    }

    private static List<BuildDiagnostic> moved(List<BuildDiagnostic> problems, Path file, String before, String after) {
        List<BuildDiagnostic> result = new ArrayList<>();
        for (BuildDiagnostic problem : problems) {
            if (!file.equals(problem.file()) || !problem.hasLocation()) {
                result.add(problem);
                continue;
            }
            List<Diagnostic> retained = JavaDiagnosticEdits.retainUnaffected(
                    List.of(problem.toEditorDiagnostic()), before, after);
            if (!retained.isEmpty()) {
                Diagnostic moved = retained.getFirst();
                result.add(new BuildDiagnostic(problem.file(), moved.startLine() + 1,
                        moved.startCol() + 1, problem.severity(), problem.message(), problem.source()));
            }
        }
        return List.copyOf(result);
    }

    static boolean isCompilerProblem(BuildDiagnostic problem) {
        return problem.file() != null
                && problem.file().getFileName() != null
                && problem.file().getFileName().toString().endsWith(".java")
                && COMPILER_SOURCES.contains(problem.source());
    }

    synchronized Set<Path> clearAll() {
        Set<Path> affected = paths();
        channels.clear();
        liveProblems.clear();
        reindex();
        return affected;
    }

    void clearLive() {
        liveProblems.clear();
    }

    synchronized void removeBelow(Path deleted) {
        liveProblems.keySet().removeIf(candidate -> candidate.startsWith(deleted));
        removeMatching(problem -> problem.file() != null && problem.file().startsWith(deleted));
    }

    private boolean removeMatching(Predicate<BuildDiagnostic> matcher) {
        boolean changed = false;
        for (Map.Entry<Channel, List<BuildDiagnostic>> entry : channels.entrySet()) {
            List<BuildDiagnostic> retained = new ArrayList<>(entry.getValue().size());
            for (BuildDiagnostic problem : entry.getValue()) {
                if (matcher.test(problem)) {
                    changed = true;
                } else {
                    retained.add(problem);
                }
            }
            entry.setValue(List.copyOf(retained));
        }
        if (changed) {
            reindex();
        }
        return changed;
    }

    private void reindex() {
        List<BuildDiagnostic> all = new ArrayList<>();
        for (Channel channel : Channel.values()) {
            all.addAll(channels.getOrDefault(channel, List.of()));
        }
        Map<Path, List<Diagnostic>> index = new ConcurrentHashMap<>();
        BuildDiagnosticParser.byFile(all).forEach((file, diagnostics) ->
                index.put(file, diagnostics.stream()
                        .map(BuildDiagnostic::toEditorDiagnostic)
                        .toList()));
        buildProblems = List.copyOf(all);
        buildDiagnostics = index;
    }
}
