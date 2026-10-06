package dtm.ide.swingdesigner;

import dtm.ide.build.BuildResult;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class SwingDesignerWorkspace implements AutoCloseable {

    private final SwingDesignerEnvironment environment;
    private final Map<Path, ModuleSession> sessions = new ConcurrentHashMap<>();

    public SwingDesignerWorkspace(SwingDesignerEnvironment environment) {
        this.environment = environment;
    }

    public SwingDesignerEnvironment environment() {
        return environment;
    }

    public Optional<ModuleSession> sessionFor(Path file) {
        JavaProjectDescriptor descriptor = environment.descriptor();
        if (descriptor == null || file == null) {
            return Optional.empty();
        }
        Optional<JavaModule> module = descriptor.moduleOf(file);
        if (module.isEmpty() || module.get().isAggregator()) {
            return Optional.empty();
        }
        return Optional.of(sessions.computeIfAbsent(module.get().root(),
                root -> new ModuleSession(module.get(), environment)));
    }

    public BuildResult compile(ModuleSession session) {
        BuildResult result = environment.compile(session.module(), line -> { });
        if (result == null || result.successful()) {
            session.refreshAfterBuild();
        }
        return result;
    }

    public List<ModuleSession> sessionsOwning(Path file) {
        List<ModuleSession> owners = new ArrayList<>();
        for (ModuleSession session : sessions.values()) {
            if (session.ownsSource(file)) {
                owners.add(session);
            }
        }
        return owners;
    }

    @Override
    public void close() {
        for (ModuleSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }
}
