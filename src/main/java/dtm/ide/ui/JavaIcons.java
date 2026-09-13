package dtm.ide.ui;

import dtm.ide.project.JavaProjectDescriptor;
import dtm.stools.configs.UiTokens;
import dtm.stools.utils.ImageUtils;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class JavaIcons {

    public static final int SMALL = 16;

    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    private JavaIcons() {
    }

    public static Icon buildTool(JavaProjectDescriptor descriptor, int size) {
        if (descriptor == null) {
            return java(size);
        }
        if (descriptor.isMaven()) {
            return maven(size);
        }
        return descriptor.isGradle() ? gradle(size) : java(size);
    }

    public static Icon maven(int size) {
        return tinted("/imgs/maven.png", size);
    }

    public static Icon gradle(int size) {
        return tinted("/imgs/grandle.png", size);
    }

    public static Icon java(int size) {
        return tinted("/imgs/wizard-java.svg", size);
    }

    public static Icon spring(int size) {
        Color foreground = UIManager.getColor("Label.foreground");
        return colored("/imgs/java-spring.svg", size,
                foreground == null ? UiTokens.foreground() : foreground);
    }

    public static Icon test(int size) {
        return tinted("/imgs/java-test.svg", size);
    }

    public static Icon jar(int size) {
        return tinted("/imgs/java-jar.svg", size);
    }

    public static Icon remote(int size) {
        return tinted("/imgs/java-remote.svg", size);
    }

    public static Icon run(int size) {
        return colored("/imgs/java-run.svg", size, UiTokens.success());
    }

    public static Icon debug(int size) {
        return colored("/imgs/java-debug.svg", size, UiTokens.info());
    }

    public static Icon sync(int size) {
        return colored("/imgs/java-sync.svg", size, UiTokens.primary());
    }

    public static Icon refresh(int size) {
        return tinted("/imgs/java-refresh.svg", size);
    }

    public static Icon stop(int size) {
        return colored("/imgs/java-stop.svg", size, UiTokens.danger());
    }

    public static Icon passed(int size) {
        return colored("/imgs/java-passed.svg", size, UiTokens.success());
    }

    public static Icon failed(int size) {
        return colored("/imgs/java-failed.svg", size, UiTokens.danger());
    }

    public static Icon skipped(int size) {
        return colored("/imgs/java-skipped.svg", size, UiTokens.muted());
    }

    public static Icon goal(int size) {
        return tinted("/imgs/java-goal.svg", size);
    }

    public static Icon plugin(int size) {
        return tinted("/imgs/java-plugin.svg", size);
    }

    public static Icon repository(int size) {
        return tinted("/imgs/java-repository.svg", size);
    }

    public static Icon todo(int size) {
        return tinted("/imgs/java-todo.svg", size);
    }

    public static Icon module(int size) {
        return tinted("/imgs/java-module.svg", size);
    }

    public static Icon folder(int size) {
        return tinted("/imgs/wizard-folder.svg", size);
    }

    public static Icon dependency(int size) {
        return tinted("/imgs/wizard-dependency.svg", size);
    }

    public static Icon create(int size) {
        return tinted("/imgs/wizard-create.svg", size);
    }

    public static Icon search(int size) {
        return tinted("/imgs/wizard-search.svg", size);
    }

    public static Icon error(int size) {
        return colored("/imgs/wizard-error.svg", size, UiTokens.danger());
    }

    private static Icon tinted(String resource, int size) {
        return colored(resource, size, UiTokens.foreground());
    }

    private static Icon colored(String resource, int size, Color color) {
        Color effective = color == null ? Color.LIGHT_GRAY : color;
        return CACHE.computeIfAbsent(key(resource, size, effective), ignored ->
                ImageUtils.getColoredIconByResource(JavaIcons.class, resource, effective)
                        .map(icon -> ImageUtils.resizeIcon(icon, size, size))
                        .orElse(null));
    }

    private static Icon plain(String resource, int size) {
        return CACHE.computeIfAbsent(key(resource, size, null), ignored ->
                ImageUtils.getIconByResource(JavaIcons.class, resource)
                        .map(icon -> ImageUtils.resizeIcon(icon, size, size))
                        .orElse(null));
    }

    private static String key(String resource, int size, Color color) {
        return resource + "|" + size + "|" + (color == null ? "raw" : color.getRGB());
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
