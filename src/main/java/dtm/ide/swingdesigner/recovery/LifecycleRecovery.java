package dtm.ide.swingdesigner.recovery;

import dtm.ide.swingdesigner.runtime.SwingViewClient;
import dtm.ide.swingdesigner.runtime.ViewResult;
import dtm.ide.swingdesigner.runtime.ViewWarning;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

@Slf4j
public final class LifecycleRecovery {

    public static final int MAX_DEPTH = 3;
    public static final int MAX_STATEMENTS = 200;

    private final Predicate<String> ownedByRoot;
    private final Function<String, Optional<Path>> sources;
    private final Function<RecoveryPlanner.Plan, SwingViewClient.Interpreted> interpreter;

    public LifecycleRecovery(Predicate<String> ownedByRoot,
                             Function<String, Optional<Path>> sources,
                             Function<RecoveryPlanner.Plan, SwingViewClient.Interpreted> interpreter) {
        this.ownedByRoot = ownedByRoot;
        this.sources = sources;
        this.interpreter = interpreter;
    }

    public static LifecycleRecovery using(Predicate<String> ownedByRoot, Function<String, Optional<Path>> sources,
                                          SwingViewClient client, boolean stubs) {
        return new LifecycleRecovery(ownedByRoot, sources,
                plan -> client.interpret(plan.statements(), plan.priorLocals(), stubs));
    }

    public ViewResult run(ViewResult initial) {
        if (initial == null || initial.failed() || initial.errors().isEmpty()) {
            return initial;
        }
        List<ViewWarning> warnings = new ArrayList<>(initial.warnings());
        Deque<Pending> queue = new ArrayDeque<>();
        initial.errors().forEach(error -> queue.add(new Pending(error, 0)));
        Set<String> visited = new HashSet<>();
        ViewResult snapshot = null;
        int used = 0;
        boolean interrupted = false;
        while (!queue.isEmpty() && used < MAX_STATEMENTS && !interrupted) {
            Pending pending = queue.poll();
            for (ViewWarning.Frame location : ownedFrames(pending.failure())) {
                String key = location.className() + "#" + location.sourceMethod() + "@" + location.line();
                if (used >= MAX_STATEMENTS || !visited.add(key)) {
                    continue;
                }
                Optional<RecoveryPlanner.Plan> plan = plan(location);
                if (plan.isEmpty() || plan.get().executable() == 0) {
                    continue;
                }
                SwingViewClient.Interpreted interpreted;
                try {
                    interpreted = interpreter.apply(plan.get());
                } catch (RuntimeException e) {
                    log.debug("Recuperacao falhou em {}: {}", key, e.toString());
                    warnings.add(ViewWarning.info("Recuperacao de " + location.sourceMethod() + " interrompida: "
                            + e.getMessage()));
                    interrupted = true;
                    break;
                }
                used += plan.get().statements().size();
                snapshot = interpreted.view();
                warnings.add(ViewWarning.info(summary(location, plan.get(), interpreted)));
                for (ViewWarning warning : interpreted.view().warnings()) {
                    warnings.add(warning);
                    if (warning.isError() && pending.depth() + 1 < MAX_DEPTH) {
                        queue.add(new Pending(warning, pending.depth() + 1));
                    }
                }
            }
        }
        return snapshot == null ? initial.withWarnings(warnings) : initial.withSnapshot(snapshot, warnings);
    }

    private List<ViewWarning.Frame> ownedFrames(ViewWarning failure) {
        List<ViewWarning.Frame> owned = new ArrayList<>();
        for (ViewWarning.Frame frame : failure.frames()) {
            if (frame.line() > 0 && ownedByRoot.test(frame.className())
                    && sources.apply(frame.outerClassName()).isPresent()) {
                owned.add(frame);
            }
        }
        return owned;
    }

    private Optional<RecoveryPlanner.Plan> plan(ViewWarning.Frame frame) {
        Optional<Path> source = sources.apply(frame.outerClassName());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(source.get());
            return RecoveryPlanner.plan(text, frame.className(), frame.sourceMethod(), frame.line());
        } catch (IOException | RuntimeException e) {
            log.debug("Nao foi possivel ler {} para recuperar: {}", source.get(), e.toString());
            return Optional.empty();
        }
    }

    static String summary(ViewWarning.Frame frame, RecoveryPlanner.Plan plan, SwingViewClient.Interpreted result) {
        long ok = result.outcomes().stream().filter(SwingViewClient.Outcome::ok).count();
        long failed = result.outcomes().stream().filter(outcome -> "failed".equals(outcome.status())).count();
        StringBuilder text = new StringBuilder("Recuperacao: ").append(frame.sourceMethod())
                .append(" parou na linha ").append(frame.line()).append("; executei ").append(ok)
                .append(" de ").append(plan.statements().size()).append(" instrucoes seguintes");
        if (failed > 0) {
            text.append(", ").append(failed).append(" falharam");
        }
        if (plan.unsupported() > 0) {
            text.append(", ").append(plan.unsupported()).append(" nao suportadas (lambdas, if, for...)");
        }
        if (!result.synthesized().isEmpty()) {
            text.append(". Valores de design para: ").append(String.join(", ", result.synthesized()));
        }
        return text.append('.').toString();
    }

    private record Pending(ViewWarning failure, int depth) {
    }
}
