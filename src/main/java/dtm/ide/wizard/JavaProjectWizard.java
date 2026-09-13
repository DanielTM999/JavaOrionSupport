package dtm.ide.wizard;

import dtm.ide.api.extension.wizard.ProjectWizard;
import dtm.ide.api.extension.wizard.ProjectWizardCallback;

import javax.swing.Icon;
import javax.swing.JPanel;

public final class JavaProjectWizard implements ProjectWizard {

    private final JavaTemplate template;
    private final boolean springBoot;

    public JavaProjectWizard(JavaTemplate template) {
        this(template, false);
    }

    private JavaProjectWizard(JavaTemplate template, boolean springBoot) {
        this.template = template;
        this.springBoot = springBoot;
    }

    public static JavaProjectWizard springBoot(boolean gradle) {
        return new JavaProjectWizard(
                gradle ? JavaTemplate.GRADLE_APPLICATION : JavaTemplate.MAVEN_APPLICATION, true);
    }

    public static JavaProjectWizard springBootMultiModule() {
        return new JavaProjectWizard(JavaTemplate.MAVEN_MULTIMODULE, true);
    }

    @Override
    public String getId() {
        if (springBoot) {
            if (template == JavaTemplate.MAVEN_MULTIMODULE) {
                return "java-spring-boot-maven-multimodule";
            }
            return template.isGradle() ? "java-spring-boot-gradle" : "java-spring-boot-maven";
        }
        return "java-" + template.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    @Override
    public String getName() {
        if (springBoot) {
            if (template == JavaTemplate.MAVEN_MULTIMODULE) {
                return "Spring Boot multi-modulo (Maven)";
            }
            return template.isGradle() ? "Spring Boot (Gradle)" : "Spring Boot (Maven)";
        }
        return template.displayName();
    }

    @Override
    public String getLanguage() {
        return "Java";
    }

    @Override
    public Icon getIcon() {
        return WizardIcons.project(template, springBoot, 24);
    }

    @Override
    public JPanel getView(ProjectWizardCallback callback) {
        return new JavaProjectWizardView(template, springBoot, callback);
    }
}
