package dtm.ide.lsp;

import dtm.ide.sdk.JdkInstallation;
import dtm.ide.sdk.JdkVendor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

}
