package dtm.ide.coverage;

import dtm.ide.sdk.JdkService;
import lombok.extern.slf4j.Slf4j;
import org.jacoco.agent.AgentJar;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Slf4j
public class CoverageProvisioner {

    public static final String AGENT_FILE_NAME = "jacocoagent.jar";

    private final JdkService jdkService;

    public CoverageProvisioner(JdkService jdkService) {
        this.jdkService = jdkService;
    }

    public Optional<Path> ensureAgent() {
        Path target = agentPath().orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        if (Files.isRegularFile(target) && sizeOf(target) > 0) {
            return Optional.of(target);
        }
        try {
            Files.createDirectories(target.getParent());
            File extracted = target.toFile();
            AgentJar.extractTo(extracted);
            return Files.isRegularFile(target) ? Optional.of(target) : Optional.empty();
        } catch (Exception error) {
            log.warn("Nao foi possivel extrair o agente JaCoCo para {}: {}",
                    target, error.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Path> agentPath() {
        Path sdkRoot = jdkService == null ? null : jdkService.sdkRoot();
        return sdkRoot == null
                ? Optional.empty()
                : Optional.of(sdkRoot.resolve("jacoco").resolve(AGENT_FILE_NAME));
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
