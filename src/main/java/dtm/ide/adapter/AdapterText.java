package dtm.ide.adapter;

import dtm.ide.JavaIdeAdapter;
import dtm.stools.i18n.I18n;

public final class AdapterText {
    private AdapterText() {
    }

    public static String text(String key, String fallback) {
        return I18n.getText(JavaIdeAdapter.class, key, fallback);
    }
}
