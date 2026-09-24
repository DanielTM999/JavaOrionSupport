package dtm.ide.lsp;

import dtm.ide.sdk.JdkInstallation;
import dtm.ide.settings.JdtBuildMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JdtLsSettings {

    private JdtLsSettings() {
    }

    static Map<String, Object> build(JdkInstallation runtime, List<JdkInstallation> available,
                                     JdtBuildMode buildMode) {
        Map<String, Object> java = new LinkedHashMap<>();

        if (runtime != null) {
            java.put("home", runtime.home().toString());
        }
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("runtimes", runtimes(available, runtime));
        configuration.put("updateBuildConfiguration", "automatic");
        java.put("configuration", configuration);

        java.put("import", Map.of(
                "maven", Map.of("enabled", true),
                "gradle", Map.of("enabled", true, "wrapper", Map.of("enabled", true)),
                "generatesMetadataFilesAtProjectRoot", false,
                "exclusions", List.of("**/node_modules/**", "**/.metadata/**", "**/target/**",
                        "**/build/**", "**/.orion/**")));

        java.put("format", Map.of(
                "enabled", true,
                "comments", Map.of("enabled", true),
                "onType", Map.of("enabled", false)));

        java.put("completion", Map.of(
                "enabled", true,
                "guessMethodArguments", true,
                "importOrder", List.of("java", "javax", "jakarta", "org", "com"),
                "filteredTypes", List.of("com.sun.*", "sun.*", "jdk.internal.*",
                        "org.graalvm.*", "io.micrometer.shaded.*"),
                "favoriteStaticMembers", List.of(
                        "org.junit.jupiter.api.Assertions.*",
                        "org.junit.jupiter.api.Assumptions.*",
                        "org.mockito.Mockito.*",
                        "org.mockito.ArgumentMatchers.*",
                        "org.assertj.core.api.Assertions.*",
                        "org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*",
                        "org.springframework.test.web.servlet.result.MockMvcResultMatchers.*",
                        "java.util.Objects.requireNonNull")));

        java.put("referencesCodeLens", Map.of("enabled", true));
        java.put("implementationCodeLens", "all");

        java.put("inlayHints", Map.of("parameterNames", Map.of("enabled", "literals")));
        java.put("references", Map.of("includeDecompiledSources", true));
        java.put("signatureHelp", Map.of("enabled", true, "description", Map.of("enabled", true)));
        java.put("symbols", Map.of("includeSourceMethodDeclarations", true));
        java.put("sources", Map.of("organizeImports",
                Map.of("starThreshold", 99, "staticStarThreshold", 99)));
        java.put("contentProvider", Map.of("preferred", "fernflower"));
        java.put("jdt", Map.of("ls", Map.of("lombokSupport", Map.of("enabled", true))));
        java.put("autobuild", Map.of("enabled",
                buildMode != null && buildMode.isAutobuild()));
        java.put("maxConcurrentBuilds", Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        java.put("errors", Map.of("incompleteClasspath", Map.of("severity", "warning")));

        return Map.of("java", java);
    }

    private static List<Map<String, Object>> runtimes(List<JdkInstallation> available,
                                                      JdkInstallation preferredDefault) {
        List<Map<String, Object>> runtimes = new ArrayList<>();
        if (available == null) {
            return runtimes;
        }
        boolean defaultAssigned = false;
        List<Integer> declared = new ArrayList<>();

        for (JdkInstallation installation : available) {
            if (!installation.isJdk()) {
                continue;
            }
            String name = executionEnvironment(installation.major());
            if (name == null || declared.contains(installation.major())) {
                continue;
            }
            declared.add(installation.major());

            Map<String, Object> runtime = new LinkedHashMap<>();
            runtime.put("name", name);
            runtime.put("path", installation.home().toString());
            boolean isDefault = !defaultAssigned && preferredDefault != null
                    && installation.home().equals(preferredDefault.home());
            if (isDefault) {
                runtime.put("default", true);
                defaultAssigned = true;
            }
            runtimes.add(runtime);
        }
        return runtimes;
    }

    private static String executionEnvironment(int major) {
        if (major < 5 || major > 99) {
            return null;
        }
        return major <= 8 ? "JavaSE-1." + major : "JavaSE-" + major;
    }
}
