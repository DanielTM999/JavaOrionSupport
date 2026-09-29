package dtm.ide.run.form;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.JdkInstallation;
import dtm.stools.i18n.I18n;
import dtm.stools.component.popup.ModernComponentDialog;
import dtm.stools.component.popup.ModernDialog;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RunFormContext {

    private final Supplier<JavaProjectDescriptor> descriptorSupplier;
    private final RunFormChoicesLoader loader;
    private final Supplier<List<RunConfigurationData>> configurationsSupplier;
    private final Supplier<ModernDialog.ModernDialogBuilder> dialogBuilder;
    private final Supplier<ModernComponentDialog.ModernComponentDialogBuilder<Boolean>> componentDialogBuilder;

    public RunFormContext(Supplier<JavaProjectDescriptor> descriptorSupplier,
                          Supplier<List<JdkInstallation>> jdkSupplier,
                          Executor background,
                          Executor ui) {
        this.descriptorSupplier = descriptorSupplier;
        this.configurationsSupplier = List::of;
        this.dialogBuilder = ModernDialog::builder;
        this.componentDialogBuilder = ModernComponentDialog::builder;
        this.loader = new RunFormChoicesLoader(descriptorSupplier, jdkSupplier,
                background == null ? Runnable::run : background,
                ui == null ? SwingUtilities::invokeLater : ui,
                projectJdkLabel());
    }

    private RunFormContext(Supplier<JavaProjectDescriptor> descriptorSupplier,
                           RunFormChoicesLoader loader,
                           Supplier<List<RunConfigurationData>> configurationsSupplier,
                           Supplier<ModernDialog.ModernDialogBuilder> dialogBuilder,
                           Supplier<ModernComponentDialog.ModernComponentDialogBuilder<Boolean>> componentDialogBuilder) {
        this.descriptorSupplier = descriptorSupplier;
        this.loader = loader;
        this.configurationsSupplier = configurationsSupplier == null
                ? List::of : configurationsSupplier;
        this.dialogBuilder = dialogBuilder == null ? ModernDialog::builder : dialogBuilder;
        this.componentDialogBuilder = componentDialogBuilder == null
                ? ModernComponentDialog::builder : componentDialogBuilder;
    }

    public static RunFormContext of(Supplier<JavaProjectDescriptor> descriptorSupplier) {
        return new RunFormContext(descriptorSupplier, List::of, Runnable::run, Runnable::run);
    }

    public static RunFormContext sharing(Supplier<JavaProjectDescriptor> descriptorSupplier,
                                         RunFormChoicesLoader loader,
                                         Supplier<List<RunConfigurationData>> configurations) {
        return sharing(descriptorSupplier, loader, configurations, null, null);
    }

    public static RunFormContext sharing(Supplier<JavaProjectDescriptor> descriptorSupplier,
                                         RunFormChoicesLoader loader,
                                         Supplier<List<RunConfigurationData>> configurations,
                                         Supplier<ModernDialog.ModernDialogBuilder> dialogBuilder,
                                         Supplier<ModernComponentDialog.ModernComponentDialogBuilder<Boolean>> componentDialogBuilder) {
        return new RunFormContext(descriptorSupplier, loader, configurations,
                dialogBuilder, componentDialogBuilder);
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

    public ModernDialog.ModernDialogBuilder dialogBuilder() {
        return dialogBuilder.get();
    }

    public ModernComponentDialog.ModernComponentDialogBuilder<Boolean> componentDialogBuilder() {
        return componentDialogBuilder.get();
    }
}
