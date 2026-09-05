package dtm.ide.run;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaProjectKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRunValidationTest {

    @TempDir
    Path root;

    private JavaRunValidation.Context context;

    @BeforeEach
    void setUp() {
        JavaModule module = new JavaModule(root, "demo", "com.example", "demo", "jar",
                List.of(root.resolve("src/main/java")), List.of(root.resolve("src/test/java")),
                root.resolve("target/classes"));
        context = JavaRunValidation.Context.of(new JavaProjectDescriptor(root,
                JavaProjectKind.MAVEN, List.of(module), false, false, 21, null));
    }

    @Test
    void anApplicationNeedsAMainClass() {
        JavaRunValidation.Report report = validate(JavaRunTypes.APPLICATION, Map.of());

        assertFalse(report.isValid());
        assertTrue(report.messageFor(JavaRunTypes.MAIN_CLASS).isPresent());
    }

    @Test
    void aMainClassMustBeAQualifiedName() {
        assertFalse(validate(JavaRunTypes.APPLICATION,
                Map.of(JavaRunTypes.MAIN_CLASS, "com.exemplo 123")).isValid());
        assertTrue(validate(JavaRunTypes.APPLICATION,
                Map.of(JavaRunTypes.MAIN_CLASS, "com.exemplo.Main")).isValid());
    }

    @Test
    void springBootRejectsAPortOutsideTheValidRange() {
        assertFalse(validate(JavaRunTypes.SPRING_BOOT, Map.of(
                JavaRunTypes.MAIN_CLASS, "com.exemplo.Main",
                JavaRunTypes.SERVER_PORT, "99999")).isValid());
        assertTrue(validate(JavaRunTypes.SPRING_BOOT, Map.of(
                JavaRunTypes.MAIN_CLASS, "com.exemplo.Main",
                JavaRunTypes.SERVER_PORT, "9090")).isValid());
    }

    @Test
    void aJarMustExistWhenItIsNotBuiltBeforeRunning() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(JavaRunTypes.JAR_PATH, "target/app.jar");
        properties.put(JavaRunTypes.BUILD_BEFORE_RUN, "false");

        assertFalse(validate(JavaRunTypes.JAR, properties).isValid());

        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/app.jar"), "");
        assertTrue(validate(JavaRunTypes.JAR, properties).isValid());
    }

    @Test
    void aJarThatWillBePackagedIsNotRequiredToExistYet() {
        assertTrue(validate(JavaRunTypes.JAR, Map.of(
                JavaRunTypes.JAR_PATH, "target/app.jar",
                JavaRunTypes.BUILD_BEFORE_RUN, "true")).isValid());
    }

    @Test
    void anAbsolutePathToAJarOutsideTheProjectIsValid(@TempDir Path outside) throws Exception {
        Path external = outside.resolve("ferramenta.jar");
        Files.writeString(external, "");

        assertTrue(validate(JavaRunTypes.JAR, Map.of(
                JavaRunTypes.JAR_PATH, external.toString(),
                JavaRunTypes.BUILD_BEFORE_RUN, "false")).isValid());
    }

    @Test
    void anExternalJarThatDoesNotExistIsReported(@TempDir Path outside) {
        assertFalse(validate(JavaRunTypes.JAR, Map.of(
                JavaRunTypes.JAR_PATH, outside.resolve("ausente.jar").toString(),
                JavaRunTypes.BUILD_BEFORE_RUN, "false")).isValid());
    }

    @Test
    void onlyJarFilesAreAccepted() {
        JavaRunValidation.Report report = validate(JavaRunTypes.JAR,
                Map.of(JavaRunTypes.JAR_PATH, "target/app.zip"));

        assertFalse(report.isValid());
        assertTrue(report.messageFor(JavaRunTypes.JAR_PATH).isPresent());
    }

    @Test
    void mavenNeedsGoalsAndGradleNeedsTasks() {
        assertFalse(validate(JavaRunTypes.MAVEN, Map.of()).isValid());
        assertTrue(validate(JavaRunTypes.MAVEN,
                Map.of(JavaRunTypes.GOALS, "clean install")).isValid());

        assertFalse(validate(JavaRunTypes.GRADLE, Map.of()).isValid());
        assertTrue(validate(JavaRunTypes.GRADLE,
                Map.of(JavaRunTypes.TASKS, "clean build")).isValid());
    }

    @Test
    void aTestTargetIsRequiredOutsideTheAllScope() {
        assertTrue(validate(JavaRunTypes.TEST, Map.of(JavaRunTypes.TEST_SCOPE, "all")).isValid());
        assertFalse(validate(JavaRunTypes.TEST,
                Map.of(JavaRunTypes.TEST_SCOPE, "class")).isValid());
        assertTrue(validate(JavaRunTypes.TEST, Map.of(
                JavaRunTypes.TEST_SCOPE, "class",
                JavaRunTypes.TEST_TARGET, "com.exemplo.MinhaClasseTest")).isValid());
    }

    @Test
    void aTestMethodTargetNeedsAClassAndAMethod() {
        assertFalse(validate(JavaRunTypes.TEST, Map.of(
                JavaRunTypes.TEST_SCOPE, "method",
                JavaRunTypes.TEST_TARGET, "deveSomar")).isValid());
        assertTrue(validate(JavaRunTypes.TEST, Map.of(
                JavaRunTypes.TEST_SCOPE, "method",
                JavaRunTypes.TEST_TARGET, "com.exemplo.MinhaClasseTest#deveSomar")).isValid());
    }

    @Test
    void aFreeTestPatternIsAcceptedAsTyped() {
        assertTrue(validate(JavaRunTypes.TEST, Map.of(
                JavaRunTypes.TEST_SCOPE, "pattern",
                JavaRunTypes.TEST_TARGET, "*IntegrationTest")).isValid());
    }

    @Test
    void remoteConfigurationsValidateHostPortAndTimeout() {
        assertFalse(validate(JavaRunTypes.REMOTE, Map.of()).isValid());

        Map<String, Object> valid = Map.of(
                JavaRunTypes.REMOTE_MODE, JavaRunTypes.REMOTE_MODE_ATTACH,
                JavaRunTypes.REMOTE_HOST, "127.0.0.1",
                JavaRunTypes.REMOTE_PORT, "5005",
                JavaRunTypes.REMOTE_TIMEOUT, "30000");
        assertTrue(validate(JavaRunTypes.REMOTE, valid).isValid());

        Map<String, Object> badTimeout = new LinkedHashMap<>(valid);
        badTimeout.put(JavaRunTypes.REMOTE_TIMEOUT, "-1");
        assertFalse(validate(JavaRunTypes.REMOTE, badTimeout).isValid());
    }

    @Test
    void theEnvironmentEditorRequiresNameEqualsValueLines() {
        assertTrue(JavaRunValidation.environmentProblem("APP_ENV=dev\nDB_HOST=localhost")
                .isEmpty());
        assertTrue(JavaRunValidation.environmentProblem("# comentario\nAPP_ENV=dev").isEmpty());
        assertTrue(JavaRunValidation.environmentProblem("APP_ENV dev").isPresent());
    }

    @Test
    void anEmptyEnvironmentIsValid() {
        assertTrue(JavaRunValidation.environmentProblem("").isEmpty());
        assertTrue(JavaRunValidation.environmentProblem(null).isEmpty());
    }

    @Test
    void aMissingWorkingDirectoryIsReportedOnItsOwnField() {
        JavaRunValidation.Report report = validate(JavaRunTypes.APPLICATION, Map.of(
                JavaRunTypes.MAIN_CLASS, "com.exemplo.Main",
                JavaRunTypes.WORKING_DIRECTORY, "nao-existe"));

        assertEquals(1, report.problems().size());
        assertTrue(report.messageFor(JavaRunTypes.WORKING_DIRECTORY).isPresent());
    }

    @Test
    void aJdkThatDisappearedIsReported() {
        JavaRunValidation.Report report = validate(JavaRunTypes.APPLICATION, Map.of(
                JavaRunTypes.MAIN_CLASS, "com.exemplo.Main",
                JavaRunTypes.JDK_HOME, root.resolve("jdk-removida").toString()));

        assertTrue(report.messageFor(JavaRunTypes.JDK_HOME).isPresent());
    }

    @Test
    void aLenientContextIgnoresTheFileSystem() {
        JavaRunValidation.Report report = JavaRunValidation.validate(JavaRunTypes.APPLICATION,
                Map.of(JavaRunTypes.MAIN_CLASS, "com.exemplo.Main",
                        JavaRunTypes.WORKING_DIRECTORY, "nao-existe"),
                JavaRunValidation.Context.lenient(context.descriptor()));

        assertTrue(report.isValid());
    }

    @Test
    void unknownTypesAreNeverBlocked() {
        assertTrue(JavaRunValidation.validate("current_file", Map.of(), context).isValid());
    }

    private JavaRunValidation.Report validate(String type, Map<String, Object> properties) {
        return JavaRunValidation.validate(type, properties, context);
    }
}
