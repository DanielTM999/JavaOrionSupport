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
                         String title,
                         List<ViewWarning> warnings) {

    public ViewResult {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean failed() {
        return error != null || root == null;
    }

    public List<ViewWarning> errors() {
        return warnings.stream().filter(ViewWarning::isError).toList();
    }

    public ViewResult withSnapshot(ViewResult snapshot, List<ViewWarning> mergedWarnings) {
        return new ViewResult(snapshot.image(), snapshot.width(), snapshot.height(), snapshot.root(),
                constructor, attempts, error, stackTrace, window, title, mergedWarnings);
    }

    public ViewResult withWarnings(List<ViewWarning> mergedWarnings) {
        return new ViewResult(image, width, height, root, constructor, attempts, error, stackTrace, window,
                title, mergedWarnings);
    }
}
