package dtm.ide.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.ide.lsp.api.StatusListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class JdtProjectCommands {
    private static final ObjectMapper JSON = new ObjectMapper();

    interface Host {
        LspJsonRpcClient client();
        boolean isInteractive();
        Path launchedMavenRepository();
        Path resolveMavenRepository();
        Map<String, Object> effectiveSettings();
        void effectiveSettings(Map<String, Object> settings);
        void clearNavigationCache();
        void resynchronizeAfterProjectUpdate();
        void workspaceBuildProgress(StatusListener progress);
    }

    private final LspRequests requests;
    private final Host host;

    JdtProjectCommands(LspRequests requests, Host host) {
        this.requests = requests;
        this.host = host;
    }

    public boolean updateProjectConfiguration(Path projectRoot) {
        if (projectRoot == null || !host.isInteractive()) {
            return false;
        }
        LspJsonRpcClient rpc = host.client();
        if (rpc == null) {
            return false;
        }
        if (!java.util.Objects.equals(host.launchedMavenRepository(), host.resolveMavenRepository())) return false;
        Map<String, Object> effectiveSettings = JdtLsSettings.withMavenSettings(host.effectiveSettings(), projectRoot);
        host.effectiveSettings(effectiveSettings);
        rpc.notify("workspace/didChangeConfiguration", Map.of("settings", effectiveSettings));
        host.clearNavigationCache();
        rpc.notify("java/projectConfigurationUpdate",
                Map.of("uri", LspConversions.toUri(projectRoot)));
        return true;
    }

    public void resynchronizeAfterProjectUpdate() {
        host.resynchronizeAfterProjectUpdate();
    }

    public String buildWorkspace(boolean fullBuild) {
        return buildWorkspace(fullBuild, null);
    }

    public String buildWorkspace(boolean fullBuild, StatusListener progress) {
        host.workspaceBuildProgress(progress);
        try {
            JsonNode result = requests.requestInteractive("java/buildWorkspace", fullBuild, 120_000);
            return result == null || result.isNull() ? "FAILED" : result.asText("FAILED");
        } finally {
            host.workspaceBuildProgress(null);
        }
    }

    public java.util.Optional<String> runtimeClasspath(Path projectOrSource) {
        if (projectOrSource == null || !host.isInteractive()) {
            return java.util.Optional.empty();
        }
        JsonNode result = requests.requestInteractive("workspace/executeCommand", Map.of(
                "command", "java.project.getClasspaths",
                "arguments", runtimeClasspathArguments(projectOrSource)), 30_000);
        if (result == null || result.isNull()) {
            return java.util.Optional.empty();
        }
        List<String> entries = new ArrayList<>();
        for (JsonNode value : result.path("classpaths")) {
            if (!value.asText("").isBlank()) {
                entries.add(value.asText());
            }
        }
        for (JsonNode value : result.path("modulepaths")) {
            if (!value.asText("").isBlank()) {
                entries.add(value.asText());
            }
        }
        return entries.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(String.join(java.io.File.pathSeparator, entries));
    }

    static List<String> runtimeClasspathArguments(Path projectOrSource) {
        String options = JSON.createObjectNode().put("scope", "runtime").toString();
        return List.of(LspConversions.toUri(projectOrSource), options);
    }

}
