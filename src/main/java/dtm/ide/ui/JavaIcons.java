package dtm.ide.ui;

import dtm.ide.project.JavaProjectDescriptor;
import dtm.stools.configs.UiTokens;
import dtm.stools.utils.ImageUtils;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class JavaIcons {

    public static final int SMALL = 16;

    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, BufferedImage> TRIMMED_SOURCES = new ConcurrentHashMap<>();
    private static final double[] RESOLUTION_SCALES = {1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0};
    private static final int TRIM_ALPHA_THRESHOLD = 24;

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
        return plain("/imgs/wizard-java.svg", size);
    }

    public static Icon javaClass(int size) {
        return typeIcon("/imgs/java/javaClass.png", size);
    }

    public static Icon javaInterface(int size) {
        return typeIcon("/imgs/java/javaInterface.png", size);
    }

    public static Icon javaEnum(int size) {
        return typeIcon("/imgs/java/javaEnum.png", size);
    }

    public static Icon javaAbstract(int size) {
        return typeIcon("/imgs/java/javaAbstract.png", size);
    }

    public static Icon javaRecord(int size) {
        return typeIcon("/imgs/java/javaRecord.png", size);
    }

    public static Icon javaException(int size) {
        return typeIcon("/imgs/java/javaException.png", size);
    }

    public static Icon spring(int size) {
        return plain("/imgs/wizard-spring.svg", size);
    }

    public static Icon springExplorer(int size) {
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

    public static Icon rerun(int size) {
        return colored("/imgs/java-rerun.svg", size, UiTokens.success());
    }

    public static Icon expand(int size) {
        return tinted("/imgs/java-expand.svg", size);
    }

    public static Icon collapse(int size) {
        return tinted("/imgs/java-collapse.svg", size);
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

    private static Icon typeIcon(String resource, int size) {
        int target = Math.max(8, size);
        String key = key(resource, target, null) + "|hq";
        Icon cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        BufferedImage source = trimmedSource(resource);
        Icon icon = source == null ? plain(resource, target) : new ImageIcon(multiResolution(source, target));
        if (icon != null) {
            CACHE.put(key, icon);
        }
        return icon;
    }

    private static BufferedImage trimmedSource(String resource) {
        BufferedImage cached = TRIMMED_SOURCES.get(resource);
        if (cached != null) {
            return cached;
        }
        try (InputStream input = JavaIcons.class.getResourceAsStream(resource)) {
            if (input == null) {
                return null;
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                return null;
            }
            BufferedImage trimmed = trimToSquare(image);
            TRIMMED_SOURCES.put(resource, trimmed);
            return trimmed;
        } catch (IOException e) {
            return null;
        }
    }

    static BufferedImage trimToSquare(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > TRIM_ALPHA_THRESHOLD) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return toArgb(image);
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        int side = Math.max(width, height);
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = square.createGraphics();
        try {
            g2.drawImage(image, (side - width) / 2, (side - height) / 2, (side - width) / 2 + width, (side - height) / 2 + height,
                    minX, minY, maxX + 1, maxY + 1, null);
        } finally {
            g2.dispose();
        }
        return square;
    }

    static Image multiResolution(BufferedImage source, int size) {
        List<Image> variants = new ArrayList<>();
        int previous = -1;
        for (double scale : RESOLUTION_SCALES) {
            int pixels = Math.max(1, (int) Math.round(size * scale));
            if (pixels == previous) {
                continue;
            }
            variants.add(downscale(source, pixels));
            previous = pixels;
        }
        return new BaseMultiResolutionImage(variants.toArray(Image[]::new));
    }

    static BufferedImage downscale(BufferedImage source, int size) {
        BufferedImage current = toArgb(source);
        int width = current.getWidth();
        int height = current.getHeight();
        while (width / 2 >= size && height / 2 >= size) {
            width /= 2;
            height /= 2;
            current = resize(current, width, height);
        }
        return width == size && height == size ? current : resize(current, size, size);
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = target.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g2.drawImage(source, 0, 0, width, height, null);
        } finally {
            g2.dispose();
        }
        return target;
    }

    private static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }
        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = converted.createGraphics();
        try {
            g2.drawImage(image, 0, 0, null);
        } finally {
            g2.dispose();
        }
        return converted;
    }

    private static String key(String resource, int size, Color color) {
        return resource + "|" + size + "|" + (color == null ? "raw" : color.getRGB());
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
