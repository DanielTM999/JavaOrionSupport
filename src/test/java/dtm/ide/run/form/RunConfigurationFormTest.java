package dtm.ide.run.form;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.api.extension.runconfig.RunConfigurationForm;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import dtm.ide.run.JavaRunConfigurationContribution;
import dtm.ide.run.JavaRunTypes;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComboBox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunConfigurationFormTest {

    @TempDir
    Path root;

    private RunFormContext context;
    private JavaProjectDescriptor descriptor;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        JavaModule module = new JavaModule(root, "demo", "com.example", "demo", "jar",
                List.of(), List.of(), root.resolve("target/classes"));
        descriptor = new JavaProjectDescriptor(root, JavaProjectKind.MAVEN,
                List.of(module), true, true, 21, null);
        context = RunFormContext.of(() -> descriptor);
    }

    // --- Regressao do nome ---------------------------------------------------

    @Test
    void noFormEverSendsATitleBack() {
        for (String type : List.of(JavaRunTypes.APPLICATION, JavaRunTypes.SPRING_BOOT,
                JavaRunTypes.JAR, JavaRunTypes.MAVEN, JavaRunTypes.GRADLE,
                JavaRunTypes.TEST, JavaRunTypes.REMOTE)) {
            RunConfigurationForm form = formFor(type);
            form.setData(RunConfigurationData.builder()
                    .type(type)
                    .title("Minha API Local")
                    .properties(Map.of())
                    .build());

            assertNull(form.getData().getTitle(),
                    type + ": o titulo pertence ao Workbench e nunca volta do plugin");
        }
    }

    @Test
    void aCustomNameSurvivesASaveAndReloadCycle() {
        RunConfigurationForm form = formFor(JavaRunTypes.SPRING_BOOT);
        RunConfigurationData saved = RunConfigurationData.builder()
                .type(JavaRunTypes.SPRING_BOOT)
                .title("Minha API Local")
                .properties(Map.of(JavaRunTypes.MAIN_CLASS, "com.exemplo.Aplicacao"))
                .build();

        form.setData(saved);
        RunConfigurationData applied = form.getData();

        // O Workbench mantem o titulo porque o plugin devolve null em vez do rotulo do tipo.
        assertNull(applied.getTitle());
        assertEquals("com.exemplo.Aplicacao",
                applied.getProperties().get(JavaRunTypes.MAIN_CLASS));
        assertEquals("Minha API Local", saved.getTitle(), "a configuracao original fica intacta");
    }

    @Test
    void propertiesWrittenByOlderVersionsArePreserved() {
        RunConfigurationForm form = formFor(JavaRunTypes.APPLICATION);
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put(JavaRunTypes.MAIN_CLASS, "com.exemplo.Main");
        legacy.put("propriedadeAntiga", "valor");

        form.setData(RunConfigurationData.builder()
                .type(JavaRunTypes.APPLICATION).title("Antiga").properties(legacy).build());

        assertEquals("valor", form.getData().getProperties().get("propriedadeAntiga"));
    }

    // --- Round-trip das propriedades ----------------------------------------

    @Test
    void applicationPropertiesSurviveTheRoundTrip() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JavaRunTypes.MAIN_CLASS, "com.exemplo.Main");
        properties.put(JavaRunTypes.PROGRAM_ARGUMENTS, "--modo rapido");
        properties.put(JavaRunTypes.VM_OPTIONS, "-Xmx512m");
        properties.put(JavaRunTypes.SPRING_PROFILES, "dev");
        properties.put(JavaRunTypes.SERVER_PORT, "9090");
        properties.put(JavaRunTypes.ENVIRONMENT, "APP_ENV=dev");
        properties.put(JavaRunTypes.BUILD_BEFORE_RUN, "false");

        assertRoundTrip(JavaRunTypes.SPRING_BOOT, properties);
    }

    @Test
    void springRunProfilesAndFileProfilesStayIndependent() throws IOException {
        Path file = root.resolve("src/main/resources/application.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "spring.profiles.active=from-file\n");
        ApplicationRunForm form = (ApplicationRunForm) formFor(JavaRunTypes.SPRING_BOOT);
        form.setData(RunConfigurationData.builder().type(JavaRunTypes.SPRING_BOOT)
                .properties(Map.of(JavaRunTypes.MAIN_CLASS, "com.example.Main",
                        JavaRunTypes.SPRING_PROFILES, "from-jvm")).build());

        assertEquals("from-file", ((MaskedTextField) form.cell("springFileProfiles")
                .control()).getText());
        ((MaskedTextField) form.cell("springFileProfiles").control()).setText("changed-file");
        assertEquals("from-jvm", form.getData().getProperties().get(JavaRunTypes.SPRING_PROFILES));
        assertEquals("spring.profiles.active=from-file\n", Files.readString(file));

        form.applyProjectChanges();
        assertEquals("changed-file", SpringProfileFile.read(file));
        assertEquals("from-jvm", form.getData().getProperties().get(JavaRunTypes.SPRING_PROFILES));
    }

    @Test
    void changingOnlyJvmProfilesDoesNotWriteTheSpringFile() throws IOException {
        Path file = root.resolve("src/main/resources/application.properties");
        Files.createDirectories(file.getParent());
        String original = "# project default\nspring.profiles.active=local\n";
        Files.writeString(file, original);
        ApplicationRunForm form = (ApplicationRunForm) formFor(JavaRunTypes.SPRING_BOOT);
        form.setData(RunConfigurationData.builder().type(JavaRunTypes.SPRING_BOOT)
                .properties(Map.of(JavaRunTypes.MAIN_CLASS, "com.example.Main")).build());

        ((MaskedTextField) form.cell(JavaRunTypes.SPRING_PROFILES).control()).setText("prod");
        form.applyProjectChanges();

        assertEquals(original, Files.readString(file));
        assertEquals("prod", form.getData().getProperties().get(JavaRunTypes.SPRING_PROFILES));
    }

    @Test
    void multipleSpringFilesRequireAnExplicitChoice() throws IOException {
        Path resources = root.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application.properties"),
                "spring.profiles.active=local\n");
        Files.writeString(resources.resolve("application.yml"),
                "spring.profiles.active: staging\n");
        ApplicationRunForm form = (ApplicationRunForm) formFor(JavaRunTypes.SPRING_BOOT);
        form.setData(RunConfigurationData.builder().type(JavaRunTypes.SPRING_BOOT)
                .properties(Map.of(JavaRunTypes.MAIN_CLASS, "com.example.Main")).build());

        JComboBox<?> selector = (JComboBox<?>) form.cell(JavaRunTypes.SPRING_CONFIG_FILE).control();
        assertNull(selector.getSelectedItem());
        selector.setSelectedItem("application.yml");
        assertEquals("staging", ((MaskedTextField) form.cell("springFileProfiles")
                .control()).getText());
        assertEquals("application.yml",
                form.getData().getProperties().get(JavaRunTypes.SPRING_CONFIG_FILE));
    }

    @Test
    void jarPropertiesSurviveTheRoundTrip() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JavaRunTypes.JAR_PATH, "target/demo.jar");
        properties.put(JavaRunTypes.PROGRAM_ARGUMENTS, "--porta 8080");
        properties.put(JavaRunTypes.VM_OPTIONS, "-Xmx256m");

        assertRoundTrip(JavaRunTypes.JAR, properties);
    }

    @Test
    void mavenPropertiesSurviveTheRoundTrip() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JavaRunTypes.GOALS, "clean install");
        properties.put(JavaRunTypes.PROFILES, "ci");
        properties.put(JavaRunTypes.RUNNER_ARGUMENTS, "-DskipTests");
        properties.put(JavaRunTypes.OFFLINE, "true");

        assertRoundTrip(JavaRunTypes.MAVEN, properties);
    }

    @Test
    void gradlePropertiesSurviveTheRoundTrip() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JavaRunTypes.TASKS, "clean build");
        properties.put(JavaRunTypes.RUNNER_ARGUMENTS, "--stacktrace");
        properties.put(JavaRunTypes.OFFLINE, "false");

        assertRoundTrip(JavaRunTypes.GRADLE, properties);
    }

    @Test
    void testPropertiesSurviveTheRoundTrip() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JavaRunTypes.TEST_SCOPE, "method");
        properties.put(JavaRunTypes.TEST_TARGET, "com.exemplo.UmTest#deveSomar");
        properties.put(JavaRunTypes.RUNNER_ARGUMENTS, "-Dgroups=lento");

        assertRoundTrip(JavaRunTypes.TEST, properties);
    }

    @Test
    void remotePropertiesSurviveTheRoundTrip() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JavaRunTypes.REMOTE_MODE, JavaRunTypes.REMOTE_MODE_LISTEN);
        properties.put(JavaRunTypes.REMOTE_HOST, "0.0.0.0");
        properties.put(JavaRunTypes.REMOTE_PORT, "6006");
        properties.put(JavaRunTypes.REMOTE_TIMEOUT, "45000");

        assertRoundTrip(JavaRunTypes.REMOTE, properties);
    }

    // --- Padroes -------------------------------------------------------------

    @Test
    void remoteConfigurationsStartOnLoopbackAttachAndPort5005() {
        RunConfigurationForm form = formFor(JavaRunTypes.REMOTE);
        form.setData(RunConfigurationData.builder()
                .type(JavaRunTypes.REMOTE).properties(Map.of()).build());

        Map<String, Object> properties = form.getData().getProperties();
        assertEquals(JavaRunTypes.REMOTE_MODE_ATTACH,
                properties.get(JavaRunTypes.REMOTE_MODE));
        assertEquals("127.0.0.1", properties.get(JavaRunTypes.REMOTE_HOST));
        assertEquals("5005", properties.get(JavaRunTypes.REMOTE_PORT));
        assertEquals("30000", properties.get(JavaRunTypes.REMOTE_TIMEOUT));
    }

    @Test
    void buildBeforeRunIsOnForApplicationsAndOffForExternalJars() {
        assertEquals("true", defaults(JavaRunTypes.APPLICATION)
                .get(JavaRunTypes.BUILD_BEFORE_RUN));
        assertEquals("true", defaults(JavaRunTypes.SPRING_BOOT)
                .get(JavaRunTypes.BUILD_BEFORE_RUN));
        assertEquals("false", defaults(JavaRunTypes.JAR).get(JavaRunTypes.BUILD_BEFORE_RUN));
    }

    @Test
    void aNullConfigurationDoesNotBreakTheForm() {
        RunConfigurationForm form = formFor(JavaRunTypes.APPLICATION);
        form.setData(null);

        assertNotNull(form.getData());
        assertEquals(JavaRunTypes.APPLICATION, form.getData().getType());
    }

    @Test
    void legacyCommaSeparatedEnvironmentBecomesOneVariablePerLine() {
        assertEquals("APP_ENV=dev" + System.lineSeparator() + "DB_HOST=localhost",
                RunConfigurationFormBase.normalizeEnvironment("APP_ENV=dev, DB_HOST=localhost"));
    }

    @Test
    void aValueContainingCommasIsNotSplitIntoTwoVariables() {
        assertEquals("PROFILES=dev,local",
                RunConfigurationFormBase.normalizeEnvironment("PROFILES=dev,local"));
    }

    @Test
    void everyTypeExposesADisplayNameAndAnIcon() {
        for (String type : JavaRunTypes.ALL) {
            JavaRunConfigurationContribution contribution =
                    new JavaRunConfigurationContribution(type, context);
            assertTrue(contribution.getDisplayName() != null
                    && !contribution.getDisplayName().isBlank(), type);
            assertEquals(type, contribution.getType());
        }
    }

    @Test
    void jarSourceDefaultsToProjectAndRoundTripsAsExternal() {
        assertEquals(JavaRunTypes.JAR_SOURCE_PROJECT,
                defaults(JavaRunTypes.JAR).get(JavaRunTypes.JAR_SOURCE));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JavaRunTypes.JAR_SOURCE, JavaRunTypes.JAR_SOURCE_EXTERNAL);
        properties.put(JavaRunTypes.JAR_PATH, "C:/ferramentas/cli.jar");
        assertRoundTrip(JavaRunTypes.JAR, properties);
    }

    @Test
    void anExternalJarDropsTheModuleAndThePackagingStep() {
        RunConfigurationForm form = formFor(JavaRunTypes.JAR);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JavaRunTypes.JAR_SOURCE, JavaRunTypes.JAR_SOURCE_EXTERNAL);
        properties.put(JavaRunTypes.JAR_PATH, "C:/ferramentas/cli.jar");
        properties.put(JavaRunTypes.MODULE, "demo");
        properties.put(JavaRunTypes.BUILD_BEFORE_RUN, "true");

        form.setData(RunConfigurationData.builder()
                .type(JavaRunTypes.JAR).properties(properties).build());
        Map<String, Object> saved = form.getData().getProperties();

        assertEquals("", saved.get(JavaRunTypes.MODULE),
                "um JAR de fora do projeto nao carrega um modulo obsoleto");
        assertEquals("false", saved.get(JavaRunTypes.BUILD_BEFORE_RUN));
    }

    @Test
    void theModuleAndPackagingCellsAreHiddenOnlyForExternalJars() {
        assertFalse(jarCellsVisible(JavaRunTypes.JAR_SOURCE_EXTERNAL));
        assertTrue(jarCellsVisible(JavaRunTypes.JAR_SOURCE_PROJECT));
    }

    @Test
    void aProjectJarKeepsItsModuleAndPackagingChoice() {
        RunConfigurationForm form = formFor(JavaRunTypes.JAR);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JavaRunTypes.JAR_SOURCE, JavaRunTypes.JAR_SOURCE_PROJECT);
        properties.put(JavaRunTypes.JAR_PATH, "target/demo.jar");
        properties.put(JavaRunTypes.MODULE, "demo");
        properties.put(JavaRunTypes.BUILD_BEFORE_RUN, "true");

        form.setData(RunConfigurationData.builder()
                .type(JavaRunTypes.JAR).properties(properties).build());
        Map<String, Object> saved = form.getData().getProperties();

        assertEquals("demo", saved.get(JavaRunTypes.MODULE));
        assertEquals("true", saved.get(JavaRunTypes.BUILD_BEFORE_RUN));
    }

    @Test
    void theModuleSurvivesChoicesThatArriveAfterSetData() {
        Deque<Runnable> queue = new ArrayDeque<>();
        RunFormContext deferred = RunFormContext.sharing(() -> descriptor,
                new RunFormChoicesLoader(() -> descriptor, List::of, queue::add, queue::add,
                        "JDK do projeto"), List::of);
        RunConfigurationForm form =
                new JavaRunConfigurationContribution(JavaRunTypes.APPLICATION, deferred).createForm();

        form.setData(RunConfigurationData.builder()
                .type(JavaRunTypes.APPLICATION)
                .properties(Map.of(JavaRunTypes.MAIN_CLASS, "com.exemplo.Main",
                        JavaRunTypes.MODULE, "demo"))
                .build());
        while (!queue.isEmpty()) {
            queue.poll().run();
        }

        assertEquals("demo", form.getData().getProperties().get(JavaRunTypes.MODULE),
                "o modulo gravado nao pode se perder enquanto a lista ainda carrega");
    }

    private boolean jarCellsVisible(String source) {
        JarRunForm form = (JarRunForm) formFor(JavaRunTypes.JAR);
        form.setData(RunConfigurationData.builder()
                .type(JavaRunTypes.JAR)
                .properties(Map.of(JavaRunTypes.JAR_SOURCE, source,
                        JavaRunTypes.JAR_PATH, "demo.jar"))
                .build());
        form.getComponent();
        return isVisible(form, JavaRunTypes.MODULE) && isVisible(form, JavaRunTypes.BUILD_BEFORE_RUN);
    }

    private static boolean isVisible(RunConfigurationFormBase form, String field) {
        FormFieldCell cell = form.cell(field);
        return cell != null && cell.isVisible();
    }

    @Test
    void everyTypeKeepsTheBeforeLaunchChain() {
        String chain = "abc-1|run|true|Maven build\ndef-2|debug|false|Remote";

        for (String type : List.of(JavaRunTypes.APPLICATION, JavaRunTypes.SPRING_BOOT,
                JavaRunTypes.JAR, JavaRunTypes.MAVEN, JavaRunTypes.GRADLE,
                JavaRunTypes.TEST, JavaRunTypes.REMOTE)) {
            RunConfigurationForm form = formFor(type);
            form.setData(RunConfigurationData.builder()
                    .type(type)
                    .properties(Map.of(JavaRunTypes.BEFORE_LAUNCH_CHAIN, chain))
                    .build());

            assertEquals(chain,
                    form.getData().getProperties().get(JavaRunTypes.BEFORE_LAUNCH_CHAIN),
                    type + " perdeu a cadeia de execucao");
        }
    }

    @Test
    void aConfigurationWithoutChainStoresAnEmptyOne() {
        assertEquals("", defaults(JavaRunTypes.APPLICATION)
                .get(JavaRunTypes.BEFORE_LAUNCH_CHAIN));
    }

    private Map<String, Object> defaults(String type) {
        RunConfigurationForm form = formFor(type);
        form.setData(RunConfigurationData.builder().type(type).properties(Map.of()).build());
        return form.getData().getProperties();
    }

    private void assertRoundTrip(String type, Map<String, Object> properties) {
        RunConfigurationForm form = formFor(type);
        form.setData(RunConfigurationData.builder()
                .type(type).title("Configuracao").properties(properties).build());

        Map<String, Object> loaded = form.getData().getProperties();
        properties.forEach((key, expected) ->
                assertEquals(expected, loaded.get(key), type + " perdeu a propriedade " + key));
        assertEquals(type, form.getData().getType());
    }

    private RunConfigurationForm formFor(String type) {
        return new JavaRunConfigurationContribution(type, context).createForm();
    }
}
