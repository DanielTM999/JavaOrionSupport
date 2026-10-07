package dtm.ide.lsp;

import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkVendor;
import dtm.ide.sdk.SdkDownloader;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
final class JdtLsProcess {
    private static final int MIN_AUTO_SHARED_ARCHIVE_MAJOR = 19;

    interface Host {
        Path resolveMavenRepository();
        void launchedMavenRepository(Path path);
        String maxHeap();
        Path lombokAgentJar();
        void launchedLombokAgentJar(Path path);
    }

    private final Host host;

    JdtLsProcess(Host host) {
        this.host = host;
    }

    List<String> buildCommand(JdkInstallation runtime,
                              JdtLsProvisioner.JdtLsInstallation installation,
                              Path workspace) {
        List<String> command = new ArrayList<>();
        command.add(runtime.javaExecutable().toString());
        command.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        command.add("-Dosgi.bundles.defaultStartLevel=4");
        command.add("-Declipse.product=org.eclipse.jdt.ls.core.product");
        command.add("-Dlog.level=WARNING");
        command.add("-Dfile.encoding=UTF-8");
        Path launchedMavenRepository = host.resolveMavenRepository();
        host.launchedMavenRepository(launchedMavenRepository);
        if (launchedMavenRepository != null) command.add("-Dmaven.repo.local=" + launchedMavenRepository);
        command.add("-Djava.import.generatesMetadataFilesAtProjectRoot=false");
        command.add("-DDetectVMInstallationsJob.disabled=true");
        command.add("-Dsun.zip.disableMemoryMapping=true");
        command.add("-Xms256m");
        command.add("-Xmx" + host.maxHeap());
        Path lombok = host.lombokAgentJar();
        host.launchedLombokAgentJar(lombok);
        if (runtime.vendor() != JdkVendor.SEMERU) {
            command.add("-XX:+UseParallelGC");
            command.add("-XX:GCTimeRatio=4");
            command.add("-XX:AdaptiveSizePolicyWeight=90");
            command.add("-Xlog:disable");
            if (lombok == null && runtime.major() >= MIN_AUTO_SHARED_ARCHIVE_MAJOR) {
                command.add("-XX:+AutoCreateSharedArchive");
                command.add("-XX:SharedArchiveFile=" + installation.home()
                        .resolve("jdtls-jdk" + runtime.major() + ".jsa"));
            }
        }
        if (lombok != null) {
            command.add("-javaagent:" + lombok);
        }
        command.add("--add-modules=ALL-SYSTEM");
        command.add("--add-opens");
        command.add("java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/java.lang=ALL-UNNAMED");
        command.add("-jar");
        command.add(installation.launcherJar().toString());
        command.add("-configuration");
        command.add(installation.configDir().toString());
        command.add("-data");
        command.add(workspace.toString());
        return command;
    }

    static void removeLegacyOverlappingWorkspace(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path legacyRoot = normalizedRoot.resolve(".orion").resolve("jdtls").normalize();
        Path legacyWorkspace = legacyRoot.resolve("workspace");
        if (legacyRoot.startsWith(normalizedRoot.resolve(".orion"))
                && Files.isDirectory(legacyWorkspace.resolve(".metadata"))) {
            SdkDownloader.deleteRecursively(legacyRoot);
        }
    }

    static void stopOrphanedWorkspaceServers(JdtLsWorkspaceLease lease) {
        Path workspace = lease.workspace();
        lease.recordedServer().ifPresent(handle -> {
            log.warn("Encerrando JDT LS orfao registrado pid={} do workspace {}", handle.pid(), workspace);
            terminateProcessTree(handle);
        });
        for (ProcessHandle handle : lease.serversHoldingMetadata()) {
            log.warn("Encerrando JDT LS orfao pid={} que segurava o workspace {}", handle.pid(), workspace);
            terminateProcessTree(handle);
        }
        try (var processes = ProcessHandle.allProcesses()) {
            processes.filter(ProcessHandle::isAlive)
                    .filter(handle -> handle.pid() != ProcessHandle.current().pid())
                    .filter(handle -> handle.parent().map(ProcessHandle::isAlive).orElse(false) == false)
                    .filter(handle -> isJdtLsForWorkspace(
                            handle.info().command().orElse(""),
                            handle.info().arguments().orElseGet(() -> new String[0]), workspace))
                    .forEach(handle -> {
                        log.warn("Encerrando JDT LS orfao pid={} do workspace {}",
                                handle.pid(), workspace);
                        terminateProcessTree(handle);
                    });
        } catch (Exception e) {
            log.debug("Nao foi possivel procurar JDT LS orfao em {}: {}",
                    workspace, e.getMessage());
        }
    }

    static boolean isJdtLsForWorkspace(String command, String[] arguments, Path workspace) {
        if (workspace == null || arguments == null || command == null
                || !command.toLowerCase(java.util.Locale.ROOT).contains("java")) {
            return false;
        }
        boolean launcher = false;
        boolean sameWorkspace = false;
        String expected = workspace.toAbsolutePath().normalize().toString();
        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i] == null ? "" : arguments[i];
            if (argument.toLowerCase(java.util.Locale.ROOT)
                    .contains("org.eclipse.equinox.launcher")) {
                launcher = true;
            }
            if ("-data".equals(argument) && i + 1 < arguments.length) {
                try {
                    String candidate = Path.of(arguments[i + 1]).toAbsolutePath()
                            .normalize().toString();
                    sameWorkspace = expected.equalsIgnoreCase(candidate);
                } catch (Exception ignored) {
                    sameWorkspace = expected.equalsIgnoreCase(arguments[i + 1]);
                }
            }
        }
        return launcher && sameWorkspace;
    }

    static void terminateProcessTree(ProcessHandle handle) {
        if (handle == null) {
            return;
        }
        List<ProcessHandle> descendants;
        try (var children = handle.descendants()) {
            descendants = children.toList();
        } catch (RuntimeException error) {
            descendants = List.of();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        if (handle.isAlive()) {
            handle.destroy();
            try {
                handle.onExit().get(3, TimeUnit.SECONDS);
            } catch (TimeoutException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                log.debug("Falha ao aguardar encerramento normal do processo JDT LS", error);
            }
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (handle.isAlive()) {
            handle.destroyForcibly();
            try {
                handle.onExit().get(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                log.debug("Falha ao aguardar encerramento forcado do processo JDT LS", error);
            }
        }
    }

}
