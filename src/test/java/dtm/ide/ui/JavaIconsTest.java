package dtm.ide.ui;

import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaIconsTest {

    @Test
    void typeIconsAreMultiResolutionAtTheRequestedSize() {
        ImageIcon icon = assertInstanceOf(ImageIcon.class, JavaIcons.javaInterface(16));
        assertEquals(16, icon.getIconWidth());
        assertEquals(16, icon.getIconHeight());
        MultiResolutionImage image = assertInstanceOf(MultiResolutionImage.class, icon.getImage());
        Image doubled = image.getResolutionVariant(32, 32);
        assertEquals(32, doubled.getWidth(null));
    }

    @Test
    void recordAndExceptionIconsAreBundled() {
        for (Icon icon : new Icon[]{JavaIcons.javaRecord(16), JavaIcons.javaException(16)}) {
            ImageIcon image = assertInstanceOf(ImageIcon.class, icon);
            assertInstanceOf(MultiResolutionImage.class, image.getImage());
            assertEquals(16, image.getIconWidth());
        }
    }

    @Test
    void trimsTransparentPaddingIntoASquare() {
        BufferedImage source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        for (int y = 40; y < 60; y++) {
            for (int x = 30; x < 70; x++) {
                source.setRGB(x, y, 0xFFFF0000);
            }
        }

        BufferedImage trimmed = JavaIcons.trimToSquare(source);

        assertEquals(40, trimmed.getWidth());
        assertEquals(40, trimmed.getHeight());
        assertEquals(0xFFFF0000, trimmed.getRGB(0, 20));
    }

    @Test
    void contentFillsMostOfTheSmallIcon() {
        BufferedImage small = render(JavaIcons.javaClass(16));
        int minX = 16;
        int maxX = -1;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if ((small.getRGB(x, y) >>> 24) > 24) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }
        assertTrue(maxX - minX + 1 >= 13, "conteudo ocupa " + (maxX - minX + 1) + "px de 16");
    }

    private static BufferedImage render(Icon icon) {
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        icon.paintIcon(null, g2, 0, 0);
        g2.dispose();
        return image;
    }
}
