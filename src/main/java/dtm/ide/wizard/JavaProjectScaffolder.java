package dtm.ide.wizard;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.lang.model.SourceVersion;
import java.util.List;
import java.util.Locale;

@Slf4j
public final class JavaProjectScaffolder {

    public static final String DEFAULT_VERSION = "1.0.0-SNAPSHOT";

    public record ProjectRequest(
            Path directory,
            JavaTemplate template,
            String groupId,
            String artifactId,
            String version,
            String description,
            String packageName,
            int javaVersion,
            List<String> modules,
            boolean appendModuleToPackage
    ) {

        public ProjectRequest {
            groupId = blankTo(groupId, "com.example");
            artifactId = blankTo(artifactId, directory == null ? "demo"
                    : directory.getFileName().toString().toLowerCase(Locale.ROOT));
            version = blankTo(version, DEFAULT_VERSION);
            description = description == null ? "" : description.trim();
            packageName = blankTo(packageName, groupId + "." + artifactId.replace('-', '.'));
            javaVersion = javaVersion <= 0 ? 21 : javaVersion;
            modules = modules == null ? List.of() : List.copyOf(modules);
        }

        public ProjectRequest(Path directory, JavaTemplate template, String groupId, String artifactId,
                              String packageName, int javaVersion, List<String> modules) {
            this(directory, template, groupId, artifactId, "", "", packageName, javaVersion,
                    modules, false);
        }

        public ProjectRequest(Path directory, JavaTemplate template, String groupId, String artifactId,
                              String version, String description, String packageName,
                              int javaVersion, List<String> modules) {
            this(directory, template, groupId, artifactId, version, description, packageName,
                    javaVersion, modules, false);
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        String packagePath() {
            return packageName.replace('.', '/');
        }

        String modulePackageName(String module) {
            return appendModuleToPackage ? packageName + "." + modulePackageSegment(module)
                    : packageName;
        }

        String modulePackagePath(String module) {
            return modulePackageName(module).replace('.', '/');
        }

        String mainClassName() {
            StringBuilder name = new StringBuilder();
            for (String part : artifactId.split("[-_.]")) {
                if (!part.isBlank()) {
                    name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
                }
            }
            return name.isEmpty() ? "Main" : name + "Application";
        }
    }

    private JavaProjectScaffolder() {
    }

    static String modulePackageSegment(String module) {
        String segment = module.replaceAll("[^A-Za-z0-9_$]", "_").toLowerCase(Locale.ROOT);
        if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) {
            segment = "_" + segment;
        }
        return SourceVersion.isKeyword(segment) ? "_" + segment : segment;
    }

    public static Path create(ProjectRequest request) throws IOException {
        Path directory = request.directory();
        Files.createDirectories(directory);

        switch (request.template()) {
            case MAVEN_APPLICATION, MAVEN_LIBRARY -> createMaven(request);
            case MAVEN_MULTIMODULE -> createMultiModule(request);
            case GRADLE_APPLICATION -> createGradle(request);
            case PLAIN_JAVA -> createPlain(request);
        }
        return directory;
    }

    private static void createMaven(ProjectRequest request) throws IOException {
        write(request.directory().resolve("pom.xml"), mavenPom(request));
        createSources(request, request.directory());
        write(request.directory().resolve(".gitignore"), gitignore(true));
    }

    private static String mavenPom(ProjectRequest request) {
        String junitDependency = """
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.10.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                """;
        String mainClassPlugin = request.template().hasMainClass() ? """
                        <plugin>
                            <groupId>org.codehaus.mojo</groupId>
                            <artifactId>exec-maven-plugin</artifactId>
                            <version>3.2.0</version>
                            <configuration>
                                <mainClass>%s.%s</mainClass>
                            </configuration>
                        </plugin>
                """.formatted(request.packageName(), request.mainClassName()) : "";

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                %s
                    <properties>
                        <maven.compiler.release>%d</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                %s
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.2.5</version>
                            </plugin>
                %s        </plugins>
                    </build>
                </project>
                """.formatted(request.groupId(), request.artifactId(), request.version(),
                pomDescription(request), request.javaVersion(), junitDependency, mainClassPlugin);
    }

    private static String pomDescription(ProjectRequest request) {
        return request.description().isBlank() ? ""
                : "    <description>" + escapeXml(request.description()) + "</description>\n";
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String gradleDescription(ProjectRequest request) {
        return request.description().isBlank() ? ""
                : "description = \"" + request.description().replace("\"", "\\\"") + "\"\n";
    }

    private static void createMultiModule(ProjectRequest request) throws IOException {
        List<String> modules = request.modules().isEmpty()
                ? List.of("core", "app")
                : request.modules();

        StringBuilder moduleEntries = new StringBuilder();
        modules.forEach(module -> moduleEntries.append("        <module>")
                .append(module).append("</module>\n"));

        write(request.directory().resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <packaging>pom</packaging>
                %s
                    <properties>
                        <maven.compiler.release>%d</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                    <modules>
                %s    </modules>
                </project>
                """.formatted(request.groupId(), request.artifactId(), request.version(),
                pomDescription(request), request.javaVersion(), moduleEntries));

        for (String module : modules) {
            Path moduleRoot = request.directory().resolve(module);
            write(moduleRoot.resolve("pom.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                        <modelVersion>4.0.0</modelVersion>

                        <parent>
                            <groupId>%s</groupId>
                            <artifactId>%s</artifactId>
                            <version>%s</version>
                        </parent>

                        <artifactId>%s</artifactId>
                    </project>
                    """.formatted(request.groupId(), request.artifactId(), request.version(), module));

            String modulePackage = request.modulePackageName(module);
            Path packageDir = moduleRoot.resolve("src/main/java")
                    .resolve(request.modulePackagePath(module));
            write(packageDir.resolve(capitalize(module) + ".java"), """
                    package %s;

                    public class %s {
                    }
                    """.formatted(modulePackage, capitalize(module)));
        }
        write(request.directory().resolve(".gitignore"), gitignore(true));
    }

    private static void createGradle(ProjectRequest request) throws IOException {
        write(request.directory().resolve("settings.gradle.kts"),
                "rootProject.name = \"%s\"\n".formatted(request.artifactId()));

        write(request.directory().resolve("build.gradle.kts"), """
                plugins {
                    application
                }

                group = "%s"
                version = "%s"
                %s
                java {
                    toolchain {
                        languageVersion.set(JavaLanguageVersion.of(%d))
                    }
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
                }

                application {
                    mainClass.set("%s.%s")
                }

                tasks.test {
                    useJUnitPlatform()
                }
                """.formatted(request.groupId(), request.version(), gradleDescription(request),
                request.javaVersion(), request.packageName(), request.mainClassName()));

        createSources(request, request.directory());
        write(request.directory().resolve(".gitignore"), gitignore(false));
    }

    private static void createPlain(ProjectRequest request) throws IOException {
        Path packageDir = request.directory().resolve("src").resolve(request.packagePath());
        write(packageDir.resolve("Main.java"), """
                package %s;

                public class Main {

                    public static void main(String[] args) {
                        System.out.println("Ola, mundo!");
                    }
                }
                """.formatted(request.packageName()));
        write(request.directory().resolve(".gitignore"), "/.orion/\n*.class\n");
    }

    private static void createSources(ProjectRequest request, Path moduleRoot) throws IOException {
        Path mainPackage = moduleRoot.resolve("src/main/java").resolve(request.packagePath());
        Path testPackage = moduleRoot.resolve("src/test/java").resolve(request.packagePath());
        Files.createDirectories(moduleRoot.resolve("src/main/resources"));

        if (request.template().hasMainClass()) {
            write(mainPackage.resolve(request.mainClassName() + ".java"), """
                    package %s;

                    public class %s {

                        public static void main(String[] args) {
                            System.out.println("Ola, mundo!");
                        }
                    }
                    """.formatted(request.packageName(), request.mainClassName()));
        } else {
            write(mainPackage.resolve("Biblioteca.java"), """
                    package %s;

                    public class Biblioteca {

                        public String saudacao() {
                            return "Ola";
                        }
                    }
                    """.formatted(request.packageName()));
        }

        String testedType = request.template().hasMainClass()
                ? request.mainClassName()
                : "Biblioteca";
        write(testPackage.resolve(testedType + "Test.java"), """
                package %s;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertNotNull;

                class %sTest {

                    @Test
                    void oProjetoCompilaERoda() {
                        assertNotNull(%s.class);
                    }
                }
                """.formatted(request.packageName(), testedType, testedType));
    }

    private static String gitignore(boolean maven) {
        String buildOutput = maven ? "/target/\n" : "/build/\n/.gradle/\n";
        return buildOutput + """
                /.orion/
                *.class
                *.iml
                .idea/
                .settings/
                .classpath
                .project
                """;
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
