package dtm.ide.run.chain;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunProcessHandle;
import dtm.stools.i18n.I18n;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public final class RunChainExecutor {

    private static String text(String key, String fallback) {
        return I18n.getText(RunChainExecutor.class, key, fallback);
    }

    private static final long POLL_INTERVAL_MILLIS = 120;

    private final RunChainHost host;
    private final Consumer<String> output;

    public RunChainExecutor(RunChainHost host, Consumer<String> output) {
        this.host = host == null ? RunChainHost.EMPTY : host;
        this.output = output == null ? line -> {
        } : output;
    }

    public Optional<String> run(List<RunChainStep> steps, String currentConfigurationId) {
        if (steps == null || steps.isEmpty()) {
            return Optional.empty();
        }
        Set<String> known = knownConfigurationIds();
        Set<String> visited = new HashSet<>();
        if (currentConfigurationId != null && !currentConfigurationId.isBlank()) {
            visited.add(currentConfigurationId);
        }

        int total = steps.size();
        for (int index = 0; index < total; index++) {
            RunChainStep step = steps.get(index);
            String prefix = "[" + (index + 1) + "/" + total + "] ";

            if (!visited.add(step.configurationId())) {
                output.accept(prefix + text("skipped.repeated",
                        "Ignorado para evitar execucao ciclica:") + " " + step.display());
                continue;
            }
            if (!known.isEmpty() && !known.contains(step.configurationId())) {
                return Optional.of(text("error.missing",
                        "A configuracao referenciada nao existe mais:") + " " + step.display());
            }

            output.accept(prefix + step.display()
                    + (step.debug() ? " (Debug)" : " (Run)"));

            RunProcessHandle handle;
            try {
                handle = host.execute(step.configurationId(), step.debug());
            } catch (Exception error) {
                return Optional.of(text("error.failed", "Falha ao executar o passo")
                        + " " + step.display() + ": " + rootMessage(error));
            }
            if (step.waitForExit()) {
                Optional<String> interrupted = await(handle, step);
                if (interrupted.isPresent()) {
                    return interrupted;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> await(RunProcessHandle handle, RunChainStep step) {
        if (handle == null) {
            return Optional.empty();
        }
        while (handle.isAlive()) {
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return Optional.of(text("error.interrupted",
                        "A cadeia foi interrompida durante o passo") + " " + step.display());
            }
        }
        return Optional.empty();
    }

    private Set<String> knownConfigurationIds() {
        Set<String> ids = new HashSet<>();
        for (RunConfigurationData configuration : host.configurations()) {
            if (configuration != null && configuration.getId() != null) {
                ids.add(configuration.getId());
            }
        }
        return ids;
    }

    private static String rootMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
