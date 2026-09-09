package dtm.ide.settings;

import dtm.ide.todo.TodoScanner;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Slf4j
public final class JavaPluginSettings {

    private static final String FILE_NAME = "java-settings.properties";

    private static final String KEY_LANGUAGE_SERVER_MODE = "languageServerMode";
    private static final String KEY_LANGUAGE_SERVER_MEMORY = "languageServerMemory";
    private static final String KEY_FORMAT_ON_SAVE = "formatOnSave";
    private static final String KEY_ORGANIZE_IMPORTS_ON_SAVE = "organizeImportsOnSave";
    private static final String KEY_DEFAULT_JDK = "defaultJdkVersion";
    private static final String KEY_BUILD_OFFLINE = "buildOffline";
    private static final String KEY_SKIP_TESTS_ON_RUN = "skipTestsOnRun";
    private static final String KEY_HOT_RELOAD_MODE = "hotReloadMode";
    private static final String KEY_LOMBOK_SUPPORT = "lombokSupport";
    private static final String KEY_TODO_MARKERS = "todoMarkers";
    private static final String KEY_BUILD_FILE_COMPLETION = "buildFileCompletion";
    private static final String KEY_COVERAGE_GUTTER = "coverageGutter";
    private static final String KEY_SPRING_SUPPORT = "springSupport";
    private static final String KEY_SPRING_CODE_LENS = "springCodeLens";
    private static final String KEY_SPRING_LIVE = "springLive";
    private static final String KEY_SPRING_NAVIGATION = "springNavigation";
    private static final String KEY_SPRING_JPA = "springJpa";
    private static final String KEY_SPRING_CONFIG_NAVIGATION = "springConfigNavigation";
    private static final String KEY_SPRING_RUNTIME_BEANS = "springRuntimeBeans";
    private static final String KEY_SPRING_INFRA = "springInfra";
    private static final String KEY_DISABLED_INSPECTIONS = "disabledInspections";
    private static final String KEY_SPRING_BASE_URL = "springBaseUrl";
    private static final String KEY_SAFE_DELETE = "safeDelete";
    private static final String KEY_JDT_BUILD_MODE = "jdtBuildMode";

    public static final String DEFAULT_SPRING_BASE_URL = "http://localhost:8080";
    public static final String DEFAULT_LANGUAGE_SERVER_MEMORY = "2G";

    private final Path settingsFile;

    private LanguageServerMode languageServerMode = LanguageServerMode.AUTO;
    private String languageServerMemory = DEFAULT_LANGUAGE_SERVER_MEMORY;
    private boolean formatOnSave;
    private boolean organizeImportsOnSave;
    private int defaultJdkVersion = 21;
    private boolean buildOffline;
    private boolean skipTestsOnRun = true;
    private HotReloadMode hotReloadMode = HotReloadMode.MANUAL;
    private boolean lombokSupport = true;
    private List<String> todoMarkers = TodoScanner.DEFAULT_MARKERS;
    private boolean buildFileCompletion = true;
    private boolean coverageGutter = true;
    private boolean springSupport = true;
    private boolean springCodeLens = true;
    private boolean springLive = true;
    private boolean springNavigation = true;
    private boolean springJpa = true;
    private boolean springConfigNavigation = true;
    private boolean springRuntimeBeans = true;
    private boolean springInfra = true;
    private java.util.Set<String> disabledInspections = new java.util.LinkedHashSet<>();
    private String springBaseUrl = DEFAULT_SPRING_BASE_URL;
    private boolean safeDelete = true;
    private JdtBuildMode jdtBuildMode = JdtBuildMode.PROJECT_BUILD;

    public JavaPluginSettings(Path settingsDirectory) {
        this.settingsFile = settingsDirectory == null ? null : settingsDirectory.resolve(FILE_NAME);
        load();
    }

    public LanguageServerMode getLanguageServerMode() {
        return languageServerMode;
    }

    public void setLanguageServerMode(LanguageServerMode mode) {
        this.languageServerMode = mode == null ? LanguageServerMode.AUTO : mode;
    }

    public String getLanguageServerMemory() {
        return languageServerMemory;
    }

    public void setLanguageServerMemory(String memory) {
        this.languageServerMemory = memory == null || memory.isBlank()
                ? DEFAULT_LANGUAGE_SERVER_MEMORY : memory.trim();
    }

    public boolean isFormatOnSave() {
        return formatOnSave;
    }

    public void setFormatOnSave(boolean value) {
        this.formatOnSave = value;
    }

    public boolean isOrganizeImportsOnSave() {
        return organizeImportsOnSave;
    }

    public void setOrganizeImportsOnSave(boolean value) {
        this.organizeImportsOnSave = value;
    }

    public int getDefaultJdkVersion() {
        return defaultJdkVersion;
    }

    public void setDefaultJdkVersion(int version) {
        this.defaultJdkVersion = version > 0 && version < 100 ? version : 21;
    }

    public boolean isBuildOffline() {
        return buildOffline;
    }

    public void setBuildOffline(boolean value) {
        this.buildOffline = value;
    }

    public boolean isSkipTestsOnRun() {
        return skipTestsOnRun;
    }

    public void setSkipTestsOnRun(boolean value) {
        this.skipTestsOnRun = value;
    }

    public HotReloadMode getHotReloadMode() {
        return hotReloadMode;
    }

    public void setHotReloadMode(HotReloadMode mode) {
        hotReloadMode = mode == null ? HotReloadMode.MANUAL : mode;
    }

    public boolean isSafeDelete() {
        return safeDelete;
    }

    public void setSafeDelete(boolean value) {
        this.safeDelete = value;
    }

    public JdtBuildMode getJdtBuildMode() {
        return jdtBuildMode == null ? JdtBuildMode.PROJECT_BUILD : jdtBuildMode;
    }

    public void setJdtBuildMode(JdtBuildMode value) {
        this.jdtBuildMode = value == null ? JdtBuildMode.PROJECT_BUILD : value;
    }

    public boolean isSpringSupport() {
        return springSupport;
    }

    public void setSpringSupport(boolean value) {
        this.springSupport = value;
    }

    public boolean isLombokSupport() {
        return lombokSupport;
    }

    public void setLombokSupport(boolean value) {
        this.lombokSupport = value;
    }

    public List<String> getTodoMarkers() {
        return todoMarkers;
    }

    public void setTodoMarkers(List<String> value) {
        this.todoMarkers = value == null || value.isEmpty()
                ? TodoScanner.DEFAULT_MARKERS : List.copyOf(value);
    }

    public boolean isBuildFileCompletion() {
        return buildFileCompletion;
    }

    public void setBuildFileCompletion(boolean value) {
        this.buildFileCompletion = value;
    }

    public boolean isCoverageGutter() {
        return coverageGutter;
    }

    public void setCoverageGutter(boolean value) {
        this.coverageGutter = value;
    }

    private static java.util.Set<String> splitInspections(String raw) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (String part : raw == null ? new String[0] : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ids.add(trimmed);
            }
        }
        return ids;
    }

    private static List<String> splitMarkers(String raw) {
        List<String> markers = new ArrayList<>();
        for (String part : raw == null ? new String[0] : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                markers.add(trimmed.toUpperCase(java.util.Locale.ROOT));
            }
        }
        return markers.isEmpty() ? TodoScanner.DEFAULT_MARKERS : List.copyOf(markers);
    }

    public boolean isSpringCodeLens() {
        return springCodeLens;
    }

    public void setSpringCodeLens(boolean value) {
        this.springCodeLens = value;
    }

    public boolean isSpringLive() {
        return springLive;
    }

    public void setSpringLive(boolean value) {
        this.springLive = value;
    }

    public boolean isSpringNavigation() {
        return springNavigation;
    }

    public void setSpringNavigation(boolean value) {
        this.springNavigation = value;
    }

    public boolean isSpringJpa() {
        return springJpa;
    }

    public void setSpringJpa(boolean value) {
        this.springJpa = value;
    }

    public java.util.Set<String> getDisabledInspections() {
        return java.util.Set.copyOf(disabledInspections);
    }

    public boolean isInspectionDisabled(String inspectionId) {
        return inspectionId != null && disabledInspections.contains(inspectionId);
    }

    public void setInspectionDisabled(String inspectionId, boolean disabled) {
        if (inspectionId == null || inspectionId.isBlank()) {
            return;
        }
        if (disabled) {
            disabledInspections.add(inspectionId);
        } else {
            disabledInspections.remove(inspectionId);
        }
    }

    public void setDisabledInspections(java.util.Collection<String> ids) {
        disabledInspections = ids == null
                ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(ids);
    }

    public boolean isSpringInfra() {
        return springInfra;
    }

    public void setSpringInfra(boolean value) {
        this.springInfra = value;
    }

    public boolean isSpringRuntimeBeans() {
        return springRuntimeBeans;
    }

    public void setSpringRuntimeBeans(boolean value) {
        this.springRuntimeBeans = value;
    }

    public boolean isSpringConfigNavigation() {
        return springConfigNavigation;
    }

    public void setSpringConfigNavigation(boolean value) {
        this.springConfigNavigation = value;
    }

    public String getSpringBaseUrl() {
        return springBaseUrl;
    }

    public void setSpringBaseUrl(String url) {
        this.springBaseUrl = url == null || url.isBlank() ? DEFAULT_SPRING_BASE_URL : url.trim();
    }

    public void restoreDefaults() {
        languageServerMode = LanguageServerMode.AUTO;
        languageServerMemory = DEFAULT_LANGUAGE_SERVER_MEMORY;
        formatOnSave = false;
        organizeImportsOnSave = false;
        defaultJdkVersion = 21;
        buildOffline = false;
        skipTestsOnRun = true;
        hotReloadMode = HotReloadMode.MANUAL;
        lombokSupport = true;
        todoMarkers = TodoScanner.DEFAULT_MARKERS;
        buildFileCompletion = true;
        coverageGutter = true;
        springSupport = true;
        springCodeLens = true;
        springLive = true;
        springNavigation = true;
        springJpa = true;
        springConfigNavigation = true;
        springRuntimeBeans = true;
        springInfra = true;
        disabledInspections = new java.util.LinkedHashSet<>();
        springBaseUrl = DEFAULT_SPRING_BASE_URL;
        safeDelete = true;
        jdtBuildMode = JdtBuildMode.PROJECT_BUILD;
    }

    public void load() {
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) {
            return;
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(settingsFile)) {
            properties.load(in);
        } catch (Exception e) {
            log.debug("Falha ao carregar as preferencias Java: {}", e.getMessage());
            return;
        }
        languageServerMode = LanguageServerMode.fromKey(
                properties.getProperty(KEY_LANGUAGE_SERVER_MODE, LanguageServerMode.AUTO.key()));
        languageServerMemory = properties.getProperty(KEY_LANGUAGE_SERVER_MEMORY,
                DEFAULT_LANGUAGE_SERVER_MEMORY);
        formatOnSave = bool(properties, KEY_FORMAT_ON_SAVE, false);
        organizeImportsOnSave = bool(properties, KEY_ORGANIZE_IMPORTS_ON_SAVE, false);
        defaultJdkVersion = integer(properties, KEY_DEFAULT_JDK, 21);
        buildOffline = bool(properties, KEY_BUILD_OFFLINE, false);
        skipTestsOnRun = bool(properties, KEY_SKIP_TESTS_ON_RUN, true);
        hotReloadMode = HotReloadMode.fromKey(properties.getProperty(KEY_HOT_RELOAD_MODE,
                HotReloadMode.MANUAL.key()));
        springSupport = bool(properties, KEY_SPRING_SUPPORT, true);
        safeDelete = bool(properties, KEY_SAFE_DELETE, true);
        jdtBuildMode = JdtBuildMode.fromKey(properties.getProperty(KEY_JDT_BUILD_MODE,
                JdtBuildMode.PROJECT_BUILD.key()));
        lombokSupport = bool(properties, KEY_LOMBOK_SUPPORT, true);
        todoMarkers = splitMarkers(properties.getProperty(KEY_TODO_MARKERS, ""));
        buildFileCompletion = bool(properties, KEY_BUILD_FILE_COMPLETION, true);
        coverageGutter = bool(properties, KEY_COVERAGE_GUTTER, true);
        springCodeLens = bool(properties, KEY_SPRING_CODE_LENS, true);
        springLive = bool(properties, KEY_SPRING_LIVE, true);
        springNavigation = bool(properties, KEY_SPRING_NAVIGATION, true);
        springJpa = bool(properties, KEY_SPRING_JPA, true);
        springConfigNavigation = bool(properties, KEY_SPRING_CONFIG_NAVIGATION, true);
        springRuntimeBeans = bool(properties, KEY_SPRING_RUNTIME_BEANS, true);
        springInfra = bool(properties, KEY_SPRING_INFRA, true);
        disabledInspections = splitInspections(properties.getProperty(KEY_DISABLED_INSPECTIONS));
        springBaseUrl = properties.getProperty(KEY_SPRING_BASE_URL, DEFAULT_SPRING_BASE_URL);
    }

    public void save() {
        if (settingsFile == null) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty(KEY_LANGUAGE_SERVER_MODE, getLanguageServerMode().key());
        properties.setProperty(KEY_LANGUAGE_SERVER_MEMORY, languageServerMemory);
        properties.setProperty(KEY_FORMAT_ON_SAVE, Boolean.toString(formatOnSave));
        properties.setProperty(KEY_ORGANIZE_IMPORTS_ON_SAVE, Boolean.toString(organizeImportsOnSave));
        properties.setProperty(KEY_DEFAULT_JDK, Integer.toString(defaultJdkVersion));
        properties.setProperty(KEY_BUILD_OFFLINE, Boolean.toString(buildOffline));
        properties.setProperty(KEY_SKIP_TESTS_ON_RUN, Boolean.toString(skipTestsOnRun));
        properties.setProperty(KEY_HOT_RELOAD_MODE, hotReloadMode.key());
        properties.setProperty(KEY_SPRING_SUPPORT, Boolean.toString(springSupport));
        properties.setProperty(KEY_SAFE_DELETE, Boolean.toString(safeDelete));
        properties.setProperty(KEY_JDT_BUILD_MODE, getJdtBuildMode().key());
        properties.setProperty(KEY_LOMBOK_SUPPORT, Boolean.toString(lombokSupport));
        properties.setProperty(KEY_TODO_MARKERS, String.join(",", todoMarkers));
        properties.setProperty(KEY_BUILD_FILE_COMPLETION, Boolean.toString(buildFileCompletion));
        properties.setProperty(KEY_COVERAGE_GUTTER, Boolean.toString(coverageGutter));
        properties.setProperty(KEY_SPRING_CODE_LENS, Boolean.toString(springCodeLens));
        properties.setProperty(KEY_SPRING_LIVE, Boolean.toString(springLive));
        properties.setProperty(KEY_SPRING_NAVIGATION, Boolean.toString(springNavigation));
        properties.setProperty(KEY_SPRING_JPA, Boolean.toString(springJpa));
        properties.setProperty(KEY_SPRING_CONFIG_NAVIGATION,
                Boolean.toString(springConfigNavigation));
        properties.setProperty(KEY_SPRING_RUNTIME_BEANS, Boolean.toString(springRuntimeBeans));
        properties.setProperty(KEY_SPRING_INFRA, Boolean.toString(springInfra));
        properties.setProperty(KEY_DISABLED_INSPECTIONS, String.join(",", disabledInspections));
        properties.setProperty(KEY_SPRING_BASE_URL, springBaseUrl);

        try {
            Path parent = settingsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var out = Files.newOutputStream(settingsFile)) {
                properties.store(out, "JavaOrionSupport");
            }
        } catch (Exception e) {
            log.debug("Falha ao salvar as preferencias Java: {}", e.getMessage());
        }
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(fallback)));
    }

    private static int integer(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
