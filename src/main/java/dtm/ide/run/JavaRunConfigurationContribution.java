package dtm.ide.run;

import dtm.ide.api.extension.runconfig.RunConfigurationContribution;
import dtm.ide.api.extension.runconfig.RunConfigurationForm;
import dtm.ide.run.form.ApplicationRunForm;
import dtm.ide.run.form.BuildToolRunForm;
import dtm.ide.run.form.JarRunForm;
import dtm.ide.run.form.RemoteRunForm;
import dtm.ide.run.form.RunFormContext;
import dtm.ide.run.form.TestRunForm;
import dtm.ide.ui.JavaIcons;
import dtm.stools.i18n.I18n;

import javax.swing.Icon;

public final class JavaRunConfigurationContribution implements RunConfigurationContribution {

    private static String text(String key, String fallback) {
        return I18n.getText(JavaRunConfigurationContribution.class, key, fallback);
    }

    private final String type;
    private final RunFormContext context;

    public JavaRunConfigurationContribution(String type, RunFormContext context) {
        this.type = type;
        this.context = context;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getDisplayName() {
        return switch (type) {
            case JavaRunTypes.SPRING_BOOT -> text("type.springBoot", "Spring Boot");
            case JavaRunTypes.JAR -> text("type.jar", "Java: JAR");
            case JavaRunTypes.MAVEN -> text("type.maven", "Maven");
            case JavaRunTypes.GRADLE -> text("type.gradle", "Gradle");
            case JavaRunTypes.TEST -> text("type.test", "Java: Testes");
            case JavaRunTypes.REMOTE -> text("type.remote", "Remote JVM");
            default -> text("type.application", "Java: Aplicacao");
        };
    }

    @Override
    public Icon getIcon() {
        return switch (type) {
            case JavaRunTypes.SPRING_BOOT -> JavaIcons.spring(JavaIcons.SMALL);
            case JavaRunTypes.JAR -> JavaIcons.jar(JavaIcons.SMALL);
            case JavaRunTypes.MAVEN -> JavaIcons.maven(JavaIcons.SMALL);
            case JavaRunTypes.GRADLE -> JavaIcons.gradle(JavaIcons.SMALL);
            case JavaRunTypes.TEST -> JavaIcons.test(JavaIcons.SMALL);
            case JavaRunTypes.REMOTE -> JavaIcons.remote(JavaIcons.SMALL);
            default -> JavaIcons.java(JavaIcons.SMALL);
        };
    }

    @Override
    public RunConfigurationForm createForm() {
        return switch (type) {
            case JavaRunTypes.JAR -> new JarRunForm(context);
            case JavaRunTypes.MAVEN, JavaRunTypes.GRADLE -> new BuildToolRunForm(type, context);
            case JavaRunTypes.TEST -> new TestRunForm(context);
            case JavaRunTypes.REMOTE -> new RemoteRunForm(context);
            default -> new ApplicationRunForm(type, context);
        };
    }
}
