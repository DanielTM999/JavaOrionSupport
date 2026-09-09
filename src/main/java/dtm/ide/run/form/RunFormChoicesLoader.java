package dtm.ide.run.form;

import dtm.ide.build.BuildToolModel;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.run.BuildTargetSuggestions;
import dtm.ide.run.JarCandidates;
import dtm.ide.run.MainClassScanner;
import dtm.ide.sdk.JdkInstallation;
import dtm.ide.spring.config.SpringConfigIndex;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public final class RunFormChoicesLoader {

    private final Supplier<JavaProjectDescriptor> descriptorSupplier;
    private final Supplier<List<JdkInstallation>> jdkSupplier;
    private final Executor background;
    private final Executor ui;
    private final String projectJdkLabel;

    private final Object lock = new Object();
    private final List<Consumer<RunFormChoices>> pending = new ArrayList<>();
    private JavaProjectDescriptor cachedFor;
    private RunFormChoices cached;
    private boolean loading;

    public RunFormChoicesLoader(Supplier<JavaProjectDescriptor> descriptorSupplier,
                                Supplier<List<JdkInstallation>> jdkSupplier,
                                Executor background,
                                Executor ui,
                                String projectJdkLabel) {
        this.descriptorSupplier = descriptorSupplier;
        this.jdkSupplier = jdkSupplier;
        this.background = background;
        this.ui = ui;
        this.projectJdkLabel = projectJdkLabel;
    }

    public void request(Consumer<RunFormChoices> onReady) {
        if (onReady == null) {
            return;
        }
        JavaProjectDescriptor descriptor = descriptorSupplier == null ? null : descriptorSupplier.get();
        synchronized (lock) {
            if (cached != null && cachedFor == descriptor) {
                RunFormChoices ready = cached;
                onReady.accept(ready);
                return;
            }
            pending.add(onReady);
            if (loading) {
                return;
            }
            loading = true;
        }
        background.execute(() -> compute(descriptor));
    }

    public void invalidate() {
        synchronized (lock) {
            cached = null;
            cachedFor = null;
        }
    }

    private void compute(JavaProjectDescriptor descriptor) {
        RunFormChoices choices;
        try {
            choices = load(descriptor);
        } catch (Exception error) {
            log.warn("Falha ao carregar as opcoes das configuracoes de execucao", error);
            choices = RunFormChoices.empty();
        }
        List<Consumer<RunFormChoices>> waiting;
        synchronized (lock) {
            cached = choices;
            cachedFor = descriptor;
            loading = false;
            waiting = List.copyOf(pending);
            pending.clear();
        }
        RunFormChoices ready = choices;
        ui.execute(() -> waiting.forEach(callback -> callback.accept(ready)));
    }

    private RunFormChoices load(JavaProjectDescriptor descriptor) {
        Map<String, String> jdks = new LinkedHashMap<>();
        jdks.put(projectJdkLabel, "");
        for (JdkInstallation installation : jdks(descriptor)) {
            jdks.put(installation.displayName() + "  -  " + installation.home(),
                    installation.home().toString());
        }
        if (descriptor == null) {
            return new RunFormChoices(List.of(), jdks, List.of(), List.of(), List.of(), List.of());
        }

        List<String> modules = descriptor.buildableModules().stream()
                .map(JavaModule::name).toList();
        BuildToolModel model = BuildToolModel.load(descriptor);

        return new RunFormChoices(
                modules,
                jdks,
                MainClassScanner.scan(descriptor),
                JarCandidates.find(descriptor, null),
                BuildTargetSuggestions.targets(model, null, descriptor.isGradle()),
                BuildTargetSuggestions.profiles(model, descriptor.isMaven()),
                springProfiles(descriptor));
    }

    private static List<String> springProfiles(JavaProjectDescriptor descriptor) {
        if (!descriptor.spring()) {
            return List.of();
        }
        List<Path> resourceRoots = new ArrayList<>();
        for (JavaModule module : descriptor.buildableModules()) {
            resourceRoots.add(module.root().resolve("src").resolve("main").resolve("resources"));
        }
        return SpringConfigIndex.scan(resourceRoots).profiles();
    }

    private List<JdkInstallation> jdks(JavaProjectDescriptor descriptor) {
        if (jdkSupplier == null) {
            return List.of();
        }
        List<JdkInstallation> installations = jdkSupplier.get();
        return installations == null ? List.of() : installations;
    }
}
