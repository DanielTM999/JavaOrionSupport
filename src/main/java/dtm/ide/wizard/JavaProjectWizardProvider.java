package dtm.ide.wizard;

import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.extension.wizard.ProjectWizard;
import dtm.ide.api.extension.wizard.ProjectWizardProvider;
import dtm.ide.api.plugin.PluginScope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Singleton
@PluginReference(id = "java-project-wizard", singleton = true, scope = PluginScope.APPLICATION)
public class JavaProjectWizardProvider extends ProjectWizardProvider {

    @Override
    public Collection<ProjectWizard> getProjectWizards() {
        List<ProjectWizard> wizards = new ArrayList<>();

        wizards.add(JavaProjectWizard.springBoot(false));
        wizards.add(JavaProjectWizard.springBoot(true));

        for (JavaTemplate template : JavaTemplate.available()) {
            wizards.add(new JavaProjectWizard(template));
        }
        return wizards;
    }
}
