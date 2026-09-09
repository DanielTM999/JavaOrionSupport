package dtm.ide.spring;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.spring.infra.SpringInfraModel;
import dtm.ide.spring.jpa.JpaEntity;
import dtm.ide.spring.jpa.JpaRepositoryInfo;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class SpringBeanIndex {

    private static final int MAX_FILES = 20_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "spring-bean-index");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicReference<SpringIndexSnapshot> snapshot =
            new AtomicReference<>(SpringIndexSnapshot.empty(null));

    public SpringIndexSnapshot snapshot() {
        return snapshot.get();
    }

    public CompletableFuture<SpringIndexSnapshot> rebuild(JavaProjectDescriptor descriptor) {
        if (descriptor == null) {
            SpringIndexSnapshot empty = SpringIndexSnapshot.empty(null);
            snapshot.set(empty);
            return CompletableFuture.completedFuture(empty);
        }
        return CompletableFuture.supplyAsync(() -> {
            SpringIndexSnapshot built = scan(descriptor);
            snapshot.set(built);
            log.info("Indice Spring: {} bean(s), {} injecao(oes), {} entidade(s) JPA em {}",
                    built.beans().size(), built.injections().size(), built.entities().size(),
                    descriptor.root());
            return built;
        }, executor);
    }

    public CompletableFuture<SpringIndexSnapshot> refreshFile(Path file) {
        if (file == null || !JavaProjectConventions.isJava(file)) {
            return CompletableFuture.completedFuture(snapshot.get());
        }
        return CompletableFuture.supplyAsync(() -> {
            SpringSourceParser.ParseResult parsed = SpringSourceParser.parse(
                    file, JavaProjectConventions.readOrEmpty(file));
            SpringIndexSnapshot updated = snapshot.get().replacingFile(file, parsed);
            snapshot.set(updated);
            return updated;
        }, executor);
    }

    public SpringIndexSnapshot applyRuntimeBeans(List<SpringBean> runtimeBeans) {
        return snapshot.updateAndGet(current -> current.withRuntimeBeans(runtimeBeans));
    }

    public void clear() {
        snapshot.set(SpringIndexSnapshot.empty(null));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private SpringIndexSnapshot scan(JavaProjectDescriptor descriptor) {
        List<SpringBean> beans = new ArrayList<>();
        List<SpringInjection> injections = new ArrayList<>();
        List<SpringEndpoint> endpoints = new ArrayList<>();
        List<JpaEntity> entities = new ArrayList<>();
        List<JpaRepositoryInfo> repositories = new ArrayList<>();
        List<SpringPropertyUsage> propertyUsages = new ArrayList<>();
        List<JavaType> types = new ArrayList<>();
        SpringInfraModel infra = SpringInfraModel.empty();
        int visited = 0;

        for (Path sourceRoot : sourceRootsOf(descriptor)) {
            int remaining = MAX_FILES - visited;
            if (remaining <= 0) {
                log.warn("Indice Spring interrompido em {} arquivos.", MAX_FILES);
                return new SpringIndexSnapshot(descriptor.root(), beans, injections, endpoints,
                        entities, repositories, propertyUsages, types, infra);
            }
            for (Path file : JavaProjectConventions.javaSources(sourceRoot, 0, remaining)) {
                visited++;
                SpringSourceParser.ParseResult parsed = SpringSourceParser.parse(
                        file, JavaProjectConventions.readOrEmpty(file));
                beans.addAll(parsed.beans());
                injections.addAll(parsed.injections());
                endpoints.addAll(parsed.endpoints());
                entities.addAll(parsed.entities());
                repositories.addAll(parsed.repositories());
                propertyUsages.addAll(parsed.propertyUsages());
                types.addAll(parsed.types());
                infra = infra.merge(parsed.infra());
            }
        }
        return new SpringIndexSnapshot(descriptor.root(), beans, injections, endpoints,
                entities, repositories, propertyUsages, types, infra);
    }

    static List<Path> sourceRootsOf(JavaProjectDescriptor descriptor) {
        List<Path> roots = new ArrayList<>();
        for (JavaModule module : descriptor.modules()) {
            for (Path sourceRoot : module.existingSourceRoots()) {
                if (sourceRoot.getFileName() != null
                        && "java".equals(sourceRoot.getFileName().toString())) {
                    roots.add(sourceRoot);
                }
            }
        }
        if (roots.isEmpty()) {
            descriptor.modules().forEach(module -> roots.addAll(module.existingSourceRoots()));
        }
        return roots;
    }
}
