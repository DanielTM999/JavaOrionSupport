package dtm.ide.wizard;

import dtm.ide.api.extension.wizard.ProjectWizardCallback;
import dtm.stools.component.feedback.steps.StepsPanel;
import dtm.stools.component.form.FormField;
import dtm.stools.component.inputfields.duallistfield.DualListField;
import dtm.stools.component.inputfields.tagfield.TagInputField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import org.junit.jupiter.api.Test;

import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaProjectWizardViewTest {

    @Test
    void exposesIconsForEveryProjectTemplate() {
        JavaTemplate.available().forEach(template ->
                assertNotNull(new JavaProjectWizard(template).getIcon(), template.name()));
        assertNotNull(JavaProjectWizard.springBoot(false).getIcon());
        assertNotNull(JavaProjectWizard.springBoot(true).getIcon());
        assertNotNull(JavaProjectWizard.springBootMultiModule().getIcon());
        assertEquals("java-spring-boot-maven-multimodule",
                JavaProjectWizard.springBootMultiModule().getId());
    }

    @Test
    void providerRegistersTheSpringMultiModuleWizardWithoutDuplicateIds() {
        var wizards = new JavaProjectWizardProvider().getProjectWizards();

        assertTrue(wizards.stream().anyMatch(wizard ->
                "java-spring-boot-maven-multimodule".equals(wizard.getId())));
        assertEquals(wizards.size(),
                wizards.stream().map(wizard -> wizard.getId()).distinct().count());
    }

    @Test
    void buildsTheWizardWithSwingToolsFields() {
        JPanel view = view(JavaTemplate.MAVEN_APPLICATION, false);

        assertTrue(contains(view, MaskedTextField.class));
        assertTrue(view.getPreferredSize().width >= 600);
        assertTrue(view.getPreferredSize().height >= 480);
    }

    @Test
    void mavenTemplatesAskForTheFullSetOfCoordinates() {
        JPanel view = view(JavaTemplate.MAVEN_APPLICATION, false);

        assertTrue(field(view, "groupId").isPresent());
        assertTrue(field(view, "artifactId").isPresent());
        assertTrue(field(view, "version").isPresent(), "a versao passou a ser editavel");
        assertTrue(field(view, "package").isPresent());
    }

    @Test
    void theVersionIsOptionalWhileTheCoordinatesAreNot() {
        JPanel view = view(JavaTemplate.MAVEN_APPLICATION, false);

        assertTrue(field(view, "groupId").orElseThrow().isRequired());
        assertTrue(field(view, "artifactId").orElseThrow().isRequired());
        assertFalse(field(view, "version").orElseThrow().isRequired());
    }

    @Test
    void plainJavaHasNoArtifactCoordinates() {
        JPanel view = view(JavaTemplate.PLAIN_JAVA, false);

        assertTrue(field(view, "groupId").isEmpty());
        assertTrue(field(view, "artifactId").isEmpty());
        assertTrue(field(view, "package").isPresent());
        assertEquals(2, steps(view).getSteps().size(), "projeto e revisao");
    }

    @Test
    void theMultiModuleTemplateLetsTheUserNameTheModules() {
        JPanel view = view(JavaTemplate.MAVEN_MULTIMODULE, false);

        FormField modules = field(view, "modules").orElseThrow();
        assertTrue(modules.getControl() instanceof TagInputField);
        assertEquals(List.of("core", "app"), ((TagInputField) modules.getControl()).getTags());
    }

    @Test
    void springWizardShowsAvailableAndSelectedDependencyLists() {
        JPanel view = view(JavaTemplate.MAVEN_APPLICATION, true);

        assertTrue(contains(view, DualListField.class));
        assertTrue(count(view, JList.class) >= 2, "uma lista de disponiveis e uma de selecionadas");
        assertTrue(field(view, "boot").isPresent());
        assertTrue(field(view, "packaging").isPresent());
        assertEquals(4, steps(view).getSteps().size(),
                "projeto, coordenadas, dependencias e revisao");
    }

    @Test
    void springMultiModuleWizardCombinesDependenciesAndModules() {
        JPanel view = view(JavaTemplate.MAVEN_MULTIMODULE, true);

        FormField modules = field(view, "modules").orElseThrow();
        assertEquals(List.of("web", "core"), ((TagInputField) modules.getControl()).getTags());
        assertTrue(contains(view, DualListField.class));
        assertEquals(4, steps(view).getSteps().size(),
                "projeto, coordenadas, dependencias e revisao");
    }

    @Test
    void theLocationFieldReadsTheTextAndNotItsWrapper() {
        JPanel view = view(JavaTemplate.MAVEN_APPLICATION, false);

        Object value = field(view, "location").orElseThrow().getValue();

        assertNotNull(value);
        assertFalse(String.valueOf(value).isBlank(), "a pasta comeca com a home do usuario");
    }

    private static JPanel view(JavaTemplate template, boolean springBoot) {
        return new JavaProjectWizardView(template, springBoot, new ProjectWizardCallback() {
            @Override public void notifyProjectCreated(Path path) { }

            @Override public void cancel() { }
        });
    }

    private static Optional<FormField> field(Component root, String name) {
        return all(root).stream()
                .filter(FormField.class::isInstance)
                .map(FormField.class::cast)
                .filter(field -> field.getFieldName().equals(name))
                .findFirst();
    }

    private static StepsPanel steps(Component root) {
        return all(root).stream()
                .filter(StepsPanel.class::isInstance)
                .map(StepsPanel.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static boolean contains(Component component, Class<?> type) {
        return all(component).stream().anyMatch(type::isInstance);
    }

    private static int count(Component component, Class<?> type) {
        return (int) all(component).stream().filter(type::isInstance).count();
    }

    private static List<Component> all(Component root) {
        List<Component> result = new ArrayList<>();
        result.add(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                result.addAll(all(child));
            }
        }
        return result;
    }
}
