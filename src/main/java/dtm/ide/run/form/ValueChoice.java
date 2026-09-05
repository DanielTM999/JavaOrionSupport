package dtm.ide.run.form;

import javax.swing.JComboBox;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Combo em que o texto exibido e diferente do valor persistido -- usado pela selecao de JDK,
 * que mostra "Temurin 21.0.4" e grava o caminho da instalacao.
 */
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

    /** Valor persistido da opcao selecionada. */
    String value() {
        Object selected = combo.getSelectedItem();
        return selected == null ? "" : valueByDisplay.getOrDefault(selected.toString(), "");
    }

    /** Seleciona a opcao cujo valor persistido e {@code value}, ou a primeira quando ausente. */
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

    /** {@code true} quando {@code value} nao corresponde a nenhuma opcao conhecida. */
    boolean isUnknown(String value) {
        return value != null && !value.isBlank() && !valueByDisplay.containsValue(value);
    }
}
