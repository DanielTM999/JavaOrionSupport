package dtm.ide.wizard;

import dtm.stools.utils.ImageUtils;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;

final class WizardIcons {
    private WizardIcons() {
    }

    static Icon project(JavaTemplate template, boolean spring, int size) {
        String resource = spring ? "/imgs/wizard-spring.svg"
                : template.isMaven() ? "/imgs/wizard-maven.svg"
                : template.isGradle() ? "/imgs/wizard-gradle.svg"
                : "/imgs/wizard-java.svg";
        return ImageUtils.getIconByResource(WizardIcons.class, resource)
                .map(icon -> ImageUtils.resizeIcon(icon, size, size)).orElse(null);
    }

    static Icon folder(int size) { return tinted("/imgs/wizard-folder.svg", size); }
    static Icon search(int size) { return tinted("/imgs/wizard-search.svg", size); }
    static Icon create(int size) { return tinted("/imgs/wizard-create.svg", size); }
    static Icon cancel(int size) { return tinted("/imgs/wizard-cancel.svg", size); }
    static Icon dependency(int size) { return tinted("/imgs/wizard-dependency.svg", size); }
    static Icon loading(int size) { return tinted("/imgs/wizard-loading.svg", size); }
    static Icon error(int size) { return colored("/imgs/wizard-error.svg", size, new Color(0xE05252)); }

    private static Icon tinted(String resource, int size) {
        Color foreground = UIManager.getColor("Label.foreground");
        return colored(resource, size, foreground == null ? Color.LIGHT_GRAY : foreground);
    }

    private static Icon colored(String resource, int size, Color color) {
        return ImageUtils.getColoredIconByResource(WizardIcons.class, resource, color)
                .map(icon -> ImageUtils.resizeIcon(icon, size, size)).orElse(null);
    }
}
