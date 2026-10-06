package dtm.ide.run.form;

import javax.swing.JComboBox;
import java.util.LinkedHashMap;
import java.util.Map;

final class ValueChoice {

    private final JComboBox<String> combo;
    private final Map<String, String> valueByDisplay = new LinkedHashMap<>();

    ValueChoice(JComboBox<String> combo) {
        this.combo = combo;
    }

    JComboBox<String> combo() {
        return combo;
    }

    void setOptions(Map<String, String> options) {
        String previous = value();
        valueByDisplay.clear();
        valueByDisplay.putAll(options);
        RunFormUi.fill(combo, java.util.List.copyOf(options.keySet()), null);
        select(previous);
    }

    String value() {
        Object selected = combo.getSelectedItem();
        return selected == null ? "" : valueByDisplay.getOrDefault(selected.toString(), "");
    }

    void select(String value) {
        String target = value == null ? "" : value;
        for (Map.Entry<String, String> entry : valueByDisplay.entrySet()) {
            if (entry.getValue().equals(target)) {
                combo.setSelectedItem(entry.getKey());
                return;
            }
        }
        if (combo.getItemCount() > 0) {
            combo.setSelectedIndex(0);
        }
    }

    boolean isUnknown(String value) {
        return value != null && !value.isBlank() && !valueByDisplay.containsValue(value);
    }
}
