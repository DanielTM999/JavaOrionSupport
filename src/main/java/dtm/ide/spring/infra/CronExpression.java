package dtm.ide.spring.infra;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class CronExpression {

    private static final List<String> FIELD_NAMES =
            List.of("segundo", "minuto", "hora", "dia do mes", "mes", "dia da semana");

    private static final int[] MIN = {0, 0, 0, 1, 1, 0};
    private static final int[] MAX = {59, 59, 23, 31, 12, 7};

    private static final Map<String, String> MACROS = Map.of(
            "@yearly", "0 0 0 1 1 *",
            "@annually", "0 0 0 1 1 *",
            "@monthly", "0 0 0 1 * *",
            "@weekly", "0 0 0 * * 0",
            "@daily", "0 0 0 * * *",
            "@midnight", "0 0 0 * * *",
            "@hourly", "0 0 * * * *");

    private static final List<String> MONTH_NAMES = List.of(
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    private static final List<String> DAY_NAMES = List.of(
            "sun", "mon", "tue", "wed", "thu", "fri", "sat");

    private CronExpression() {
    }

    public static Optional<String> validate(String expression) {
        if (expression == null || expression.isBlank()) {
            return Optional.of("expressao vazia");
        }
        String value = expression.trim();
        if (value.startsWith("${")) {
            return Optional.empty();
        }
        if (value.startsWith("@")) {
            return MACROS.containsKey(value.toLowerCase(Locale.ROOT))
                    ? Optional.empty()
                    : Optional.of("macro desconhecida: " + value);
        }
        String[] fields = value.split("\\s+");
        if (fields.length != 6) {
            return Optional.of("a expressao precisa de 6 campos e tem " + fields.length);
        }
        for (int i = 0; i < fields.length; i++) {
            Optional<String> error = validateField(fields[i], i);
            if (error.isPresent()) {
                return Optional.of(FIELD_NAMES.get(i) + ": " + error.get());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateField(String field, int index) {
        if (field.isBlank()) {
            return Optional.of("campo vazio");
        }
        for (String part : field.split(",")) {
            Optional<String> error = validatePart(part, index);
            if (error.isPresent()) {
                return error;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validatePart(String part, int index) {
        String value = part;
        int step = value.indexOf('/');
        if (step >= 0) {
            String increment = value.substring(step + 1);
            if (!isNumber(increment)) {
                return Optional.of("incremento invalido: " + increment);
            }
            value = value.substring(0, step);
        }
        if (value.equals("*") || value.equals("?")) {
            return Optional.empty();
        }
        if (index == 3 && (value.equalsIgnoreCase("L") || value.toUpperCase(Locale.ROOT).endsWith("W"))) {
            return Optional.empty();
        }
        if (index == 5 && value.toUpperCase(Locale.ROOT).contains("#")) {
            return Optional.empty();
        }
        int range = value.indexOf('-');
        if (range > 0) {
            Optional<String> lower = validateBound(value.substring(0, range), index);
            return lower.isPresent() ? lower : validateBound(value.substring(range + 1), index);
        }
        return validateBound(value, index);
    }

    private static Optional<String> validateBound(String value, int index) {
        if (value.isBlank()) {
            return Optional.of("valor vazio");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (index == 4 && MONTH_NAMES.contains(normalized)) {
            return Optional.empty();
        }
        if (index == 5 && DAY_NAMES.contains(normalized)) {
            return Optional.empty();
        }
        if (!isNumber(value)) {
            return Optional.of("valor invalido: " + value);
        }
        int parsed = Integer.parseInt(value);
        if (parsed < MIN[index] || parsed > MAX[index]) {
            return Optional.of("valor fora do intervalo " + MIN[index] + "-" + MAX[index]
                    + ": " + value);
        }
        return Optional.empty();
    }

    private static boolean isNumber(String value) {
        if (value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
