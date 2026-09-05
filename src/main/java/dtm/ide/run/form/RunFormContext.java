package dtm.ide.run.form;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkInstallation;
import dtm.stools.i18n.I18n;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RunFormContext {

    private final Supplier<JavaProjectDescriptor> descriptorSupplier;
    private final RunFormChoicesLoader loader;
    private final Supplier<List<RunConfigurationData>> configurationsSupplier;

    public RunFormContext(Supplier<JavaProjectDescriptor> descriptorSupplier,
                          Supplier<List<JdkInstallation>> jdkSupplier,
                          Executor background,
                          Executor ui) {
        this.descriptorSupplier = descriptorSupplier;
        this.configurationsSupplier = List::of;
        this.loader = new RunFormChoicesLoader(descriptorSupplier, jdkSupplier,
                background == null ? Runnable::run : background,
                ui == null ? SwingUtilities::invokeLater : ui,
                projectJdkLabel());
    }

    private RunFormContext(Supplier<JavaProjectDescriptor> descriptorSupplier,
                           RunFormChoicesLoader loader,
                           Supplier<List<RunConfigurationData>> configurationsSupplier) {
        this.descriptorSupplier = descriptorSupplier;
        this.loader = loader;
        this.configurationsSupplier = configurationsSupplier == null
                ? List::of : configurationsSupplier;
    }

    public static RunFormContext of(Supplier<JavaProjectDescriptor> descriptorSupplier) {
        return new RunFormContext(descriptorSupplier, List::of, Runnable::run, Runnable::run);
    }

    public static RunFormContext sharing(Supplier<JavaProjectDescriptor> descriptorSupplier,
                                         RunFormChoicesLoader loader,
                                         Supplier<List<RunConfigurationData>> configurations) {
        return new RunFormContext(descriptorSupplier, loader, configurations);
    }

    public List<RunConfigurationData> configurations() {
        List<RunConfigurationData> configurations = configurationsSupplier.get();
        return configurations == null ? List.of() : configurations;
    }

    private static String projectJdkLabel() {
        return I18n.getText(RunConfigurationFormBase.class, "field.jdk.project", "JDK do projeto");
    }

    public JavaProjectDescriptor descriptor() {
        return descriptorSupplier == null ? null : descriptorSupplier.get();
    }

    public void requestChoices(Consumer<RunFormChoices> onReady) {
        loader.request(onReady);
    }

    public RunFormChoicesLoader loader() {
        return loader;
    }
}
