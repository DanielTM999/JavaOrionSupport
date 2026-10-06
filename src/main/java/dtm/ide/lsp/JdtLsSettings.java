package dtm.ide.lsp;

import dtm.ide.sdk.JdkInstallation;
import dtm.ide.settings.InlayHintsMode;
import dtm.ide.settings.JdtBuildMode;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JdtLsSettings {

    private JdtLsSettings() {
    }

    static Map<String, Object> build(JdkInstallation runtime, List<JdkInstallation> available,
                                     JdtBuildMode buildMode) {
        return build(runtime, available, buildMode, InlayHintsMode.LITERALS);
    }

    static Map<String, Object> build(JdkInstallation runtime, List<JdkInstallation> available,
                                     JdtBuildMode buildMode, InlayHintsMode inlayHints) {
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

        java.put("edit", Map.of("validateAllOpenBuffersOnChanges", false));

        java.put("referencesCodeLens", Map.of("enabled", true));
        java.put("implementationCodeLens", "all");

        java.put("inlayHints", inlayHints(inlayHints));
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

    @SuppressWarnings("unchecked")
    static Map<String, Object> withMavenSettings(Map<String, Object> settings, java.nio.file.Path root) {
        var resolver = new dtm.ide.deps.MavenLocalRepositoryResolver();
        Map<String, Object> java = new LinkedHashMap<>((Map<String, Object>) settings.getOrDefault("java", Map.of()));
        Map<String, Object> configuration = new LinkedHashMap<>((Map<String, Object>) java.getOrDefault("configuration", Map.of()));
        Map<String, Object> maven = new LinkedHashMap<>();
        var user = resolver.userSettings(root);
        var global = resolver.globalSettings();
        if (user != null && Files.isRegularFile(user)) maven.put("userSettings", user.toString());
        if (global != null && Files.isRegularFile(global)) maven.put("globalSettings", global.toString());
        configuration.put("maven", maven);
        java.put("configuration", configuration);
        return Map.of("java", java);
    }

    static Map<String, Object> inlayHints(InlayHintsMode mode) {
        InlayHintsMode effective = mode == null ? InlayHintsMode.LITERALS : mode;
        boolean all = effective == InlayHintsMode.ALL;
        return Map.of(
                "parameterNames", Map.of("enabled", effective.key()),
                "parameterTypes", Map.of("enabled", all),
                "variableTypes", Map.of("enabled", all));
    }

    /** Copy of {@code settings} with the inlay hint section replaced. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withInlayHints(Map<String, Object> settings, InlayHintsMode mode) {
        Object java = settings == null ? null : settings.get("java");
        if (!(java instanceof Map<?, ?> section)) {
            return settings;
        }
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) section);
        copy.put("inlayHints", inlayHints(mode));
        return Map.of("java", copy);
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
