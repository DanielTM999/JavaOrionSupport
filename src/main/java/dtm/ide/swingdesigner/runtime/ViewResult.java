package dtm.ide.swingdesigner.runtime;

import java.awt.image.BufferedImage;
import java.util.List;

public record ViewResult(BufferedImage image,
                         int width,
                         int height,
                         SnapshotNode root,
                         ConstructorUse constructor,
                         List<String> attempts,
                         String error,
                         String stackTrace,
                         boolean window,
                         String title) {

    public ViewResult {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public boolean failed() {
        return error != null || root == null;
    }
}
