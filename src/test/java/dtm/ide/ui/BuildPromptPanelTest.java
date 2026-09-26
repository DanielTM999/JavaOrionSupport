package dtm.ide.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildPromptPanelTest {

    @Test
    void runningSplitsTheLineKeepingCaseAndQuotes() {
        AtomicReference<BuildPromptPanel.Choice> chosen = new AtomicReference<>();
        BuildPromptPanel panel = BuildPromptPanel.runGoal("", "api", List.of(), List.of(), true,
                chosen::set);
        panel.setFieldText("clean install -DskipTests \"-Dargs=a b\"");

        panel.answer(false);

        assertEquals(List.of("clean", "install", "-DskipTests", "-Dargs=a b"), chosen.get().goals());
        assertFalse(chosen.get().debug());
    }

    @Test
    void theSecondaryActionDebugs() {
        AtomicReference<BuildPromptPanel.Choice> chosen = new AtomicReference<>();
        BuildPromptPanel panel = BuildPromptPanel.runGoal("spring-boot:run", "", List.of(),
                List.of(), true, chosen::set);

        panel.answer(true);

        assertEquals(List.of("spring-boot:run"), chosen.get().goals());
        assertTrue(chosen.get().debug());
    }

    @Test
    void withoutDebugThereIsNoSecondaryAction() {
        AtomicReference<BuildPromptPanel.Choice> chosen = new AtomicReference<>();
        BuildPromptPanel panel = BuildPromptPanel.runGoal("test", "", List.of(), List.of(), false,
                chosen::set);

        assertFalse(panel.hasSecondaryAction());
        panel.answer(true);
        assertNull(chosen.get());
    }

    @Test
    void anEmptyLineOrCancelChoosesNothing() {
        AtomicReference<BuildPromptPanel.Choice> chosen = new AtomicReference<>();
        BuildPromptPanel panel = BuildPromptPanel.runGoal("   ", "", List.of(), List.of(), true,
                chosen::set);

        panel.answer(false);
        assertNull(chosen.get());

        panel.setFieldText("compile");
        panel.cancel();
        panel.answer(false);
        assertNull(chosen.get());
    }

    @Test
    void savingReturnsTheTrimmedName() {
        AtomicReference<String> saved = new AtomicReference<>();
        BuildPromptPanel panel = BuildPromptPanel.saveConfiguration("clean install",
                List.of("clean", "install"), saved::set);
        assertEquals("clean install", panel.fieldText());

        panel.setFieldText("  Build rapido  ");
        panel.answer(false);

        assertEquals("Build rapido", saved.get());
    }

    @Test
    void thePopupIsWideEnoughForTheCommandLine() {
        BuildPromptPanel panel = BuildPromptPanel.runGoal("", "", List.of("clean install"),
                List.of("test"), true, choice -> {
                });

        assertTrue(panel.popupSize().width >= panel.getPreferredSize().width);
        assertTrue(panel.popupSize().height > panel.getPreferredSize().height);
    }
}
