package dev.ahprofit.ah;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a price from auction-house lore text.
 *
 * <p>Server GUIs put the price in different places, so keyword lines such as "Price: $1,234" are
 * preferred, and only the last amount on a line is used so a leading quantity like "64 x Diamond"
 * is never mistaken for the price.
 */
public final class LorePriceParser {
    private LorePriceParser() {
    }

    private static final Pattern MONEY = Pattern.compile("\\$?\\s*([0-9][0-9_,]*(?:\\.[0-9]+)?)\\s*([kKmM])?");
    private static final Pattern PRICE_KEYWORD = Pattern.compile(
            "(?i)\\b(price|cost|buy|buyout|total|amount|for sale|asking|value)\\b");
    private static final Pattern QUANTITY_LINE = Pattern.compile("(?i)\\b\\d+\\s*x\\b|\\bitems?\\b|\\bstacks?\\b");
    private static final Pattern FORMATTING = Pattern.compile("[\\u00a7\\u2026][0-9a-fk-orA-FK-OR]?");
    private static final BigDecimal MAXIMUM = new BigDecimal("1000000000000000");

    public static BigDecimal findPrice(List<String> lines) {
        return findPrice(lines, null);
    }

    /** @param extraLine an additional line, typically the item's display name, tried after the lore. */
    public static BigDecimal findPrice(List<String> lines, String extraLine) {
        if ((lines == null || lines.isEmpty()) && (extraLine == null || extraLine.isBlank())) {
            return null;
        }
        List<String> cleaned = new java.util.ArrayList<>();
        if (lines != null) {
            lines.stream().map(LorePriceParser::stripFormatting).forEach(cleaned::add);
        }
        if (extraLine != null && !extraLine.isBlank()) {
            cleaned.add(stripFormatting(extraLine));
        }
        for (String line : cleaned) {
            if (PRICE_KEYWORD.matcher(line).find()) {
                BigDecimal value = lastAmount(line);
                if (value != null) {
                    return value;
                }
            }
        }
        for (String line : cleaned) {
            if (QUANTITY_LINE.matcher(line).find()) {
                continue;
            }
            BigDecimal value = lastAmount(line);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** @return the last money-looking amount on the line, or null when none is plausible. */
    public static BigDecimal lastAmount(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher matcher = MONEY.matcher(line);
        BigDecimal last = null;
        while (matcher.find()) {
            BigDecimal parsed = toAmount(matcher.group(1), matcher.group(2));
            if (parsed != null) {
                last = parsed;
            }
        }
        return last;
    }

    private static BigDecimal toAmount(String digits, String suffix) {
        if (digits == null) {
            return null;
        }
        String normalized = digits.replace(",", "").replace("_", "").trim();
        if (normalized.isEmpty() || normalized.chars().allMatch(character -> character == '.')) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(normalized);
            if (suffix != null) {
                value = value.multiply(switch (suffix.toLowerCase(Locale.ROOT)) {
                    case "k" -> new BigDecimal("1000");
                    case "m" -> new BigDecimal("1000000");
                    default -> BigDecimal.ONE;
                }, java.math.MathContext.DECIMAL128);
            }
            if (value.signum() <= 0 || value.compareTo(MAXIMUM) > 0) {
                return null;
            }
            // Canonicalise so equal amounts compare equal regardless of how they were written.
            BigDecimal canonical = value.stripTrailingZeros();
            return canonical.signum() == 0 ? BigDecimal.ZERO : new BigDecimal(canonical.toPlainString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static String stripFormatting(String line) {
        if (line == null) {
            return "";
        }
        return FORMATTING.matcher(line).replaceAll("").trim();
    }
}
