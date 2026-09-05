package dtm.ide.sdk;

public interface DownloadProgressListener {

    DownloadProgressListener NOOP = new DownloadProgressListener() {
    };

    default void onStart(String id, String label) {
    }

    default void onProgress(String id, String label, int percent) {
    }

    default void onFinish(String id) {
    }
}
