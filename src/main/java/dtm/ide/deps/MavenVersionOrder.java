package dtm.ide.deps;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MavenVersionOrder {

    public static final Comparator<String> DESCENDING = (left, right) -> compare(right, left);

    private static final Map<String, Integer> QUALIFIERS = Map.ofEntries(
            Map.entry("snapshot", -5), Map.entry("alpha", -4), Map.entry("a", -4),
            Map.entry("beta", -3), Map.entry("b", -3), Map.entry("milestone", -2),
            Map.entry("m", -2), Map.entry("rc", -1), Map.entry("cr", -1),
            Map.entry("", 0), Map.entry("final", 0), Map.entry("ga", 0),
            Map.entry("release", 0), Map.entry("sp", 1));

    private MavenVersionOrder() {
    }

    public static int compare(String left, String right) {
        List<String> a = tokens(left);
        List<String> b = tokens(right);
        int size = Math.max(a.size(), b.size());
        for (int index = 0; index < size; index++) {
            String av = index < a.size() ? a.get(index) : "";
            String bv = index < b.size() ? b.get(index) : "";
            int compared = compareToken(av, bv);
            if (compared != 0) {
                return compared;
            }
        }
        return safe(left).compareToIgnoreCase(safe(right));
    }

    private static int compareToken(String left, String right) {
        boolean leftNumber = left.chars().allMatch(Character::isDigit) && !left.isEmpty();
        boolean rightNumber = right.chars().allMatch(Character::isDigit) && !right.isEmpty();
        if (leftNumber && rightNumber) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumber != rightNumber) {
            return leftNumber ? 1 : -1;
        }
        String a = left.toLowerCase(Locale.ROOT);
        String b = right.toLowerCase(Locale.ROOT);
        Integer aq = QUALIFIERS.get(a);
        Integer bq = QUALIFIERS.get(b);
        if (aq != null || bq != null) {
            return Integer.compare(aq == null ? -1 : aq, bq == null ? -1 : bq);
        }
        return a.compareTo(b);
    }

    private static List<String> tokens(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Boolean digits = null;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!Character.isLetterOrDigit(character)) {
                flush(result, current);
                digits = null;
                continue;
            }
            boolean nextDigits = Character.isDigit(character);
            if (digits != null && digits != nextDigits) {
                flush(result, current);
            }
            current.append(character);
            digits = nextDigits;
        }
        flush(result, current);
        return result;
    }

    private static void flush(List<String> target, StringBuilder token) {
        if (!token.isEmpty()) {
            target.add(token.toString());
            token.setLength(0);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
