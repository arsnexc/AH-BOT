package dev.ahprofit.ah;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts an asking price from the text of an auction-house menu item.
 *
 * <p>Server GUIs disagree about where the price lives and how it is written, so this prefers a line
 * that names a price, then a line that is nothing but an amount, and only then any other line. A
 * line that looks like a quantity ("64 x Diamond", "Amount: 64") is never treated as a price,
 * because a wrong price is worse than no price: it silently corrupts every spread.
 */
public final class LorePriceParser {
    private LorePriceParser() {
    }

    /**
     * Section signs followed by any single formatting code, plus the stray ellipsis GUI rendering
     * adds. The code class must include every letter, because the hex-colour marker "x" is not a
     * colour digit and a surviving "x" would glue itself to the next word.
     */
    private static final Pattern FORMATTING = Pattern.compile("[\\u00a7][0-9a-zA-Z]?|\\u2026");

    /**
     * A number with optional grouping separators and an optional decimal part, followed by an
     * optional magnitude suffix. The trailing word boundary is essential: without it the single
     * letter "b" in "5 boxes" would be read as billions.
     */
    private static final Pattern AMOUNT = Pattern.compile(
            "([0-9][0-9]*(?:[.,'][0-9]{3})*(?:\\.[0-9]+)?)(?:\\s*(k|m|b|kk|mil|million|billion)\\b)?(?![a-z])",
            Pattern.CASE_INSENSITIVE);

    /** A line that is nothing but an amount, optionally wrapped in currency words or punctuation. */
    private static final Pattern BARE_AMOUNT_LINE = Pattern.compile(
            "(?i)^[^0-9a-z]*[0-9][0-9.,']*(?:\\s*(?:k|m|b|kk|mil|million|billion))?"
            + "\\s*(?:coins?|money|cash|usd|dollars?)?\\s*[.!?]*$");

    /** Words that mean "this line is the price". */
    private static final Pattern PRICE_KEYWORD = Pattern.compile(
            "(?i)\\b(price|cost|buy|buyout|buy it now|bin|total|asking|value|for sale|bid)\\b");

    /** Words that mean "this line is a count, not a price". */
    private static final Pattern QUANTITY_KEYWORD = Pattern.compile(
            "(?i)\\b(amount|qty|quantity|items?|stacks?|units?|available|listed|stock|x)\\b");

    /** "64 x Diamond" or "Diamond x64": a count glued to an item name. */
    private static final Pattern COUNT_GLUE = Pattern.compile("(?i)(?<![a-z0-9])\\d+\\s*x\\b|\\bx\\s*\\d+(?![a-z0-9])");

    /** Currency symbols, which every server shares. */
    private static final Pattern CURRENCY_SYMBOLS = Pattern.compile("\\$|\\u20ac|\\u00a3");

    /** Currency words recognised when no server profile is configured. */
    private static final Pattern DEFAULT_CURRENCY = Pattern.compile(
            "(?i)\\b(coins?|money|cash|usd|dollars?|mints?|gems?|tokens?|credits?)\\b");

    /**
     * A line that is nothing but a number and one word: "64 Diamonds", "5 boxes", "160 mints".
     *
     * <p>This is the shape of a quantity line, not a price line, so it is never treated as a price.
     * It is also what protects the operator: a currency word mistakenly set to an item name would
     * otherwise turn every stack size into a price.
     */
    private static final Pattern COUNT_ONLY_LINE =
            Pattern.compile("(?i)\\s*[0-9][0-9.,']*\\s*[a-z]+\\s*");

    private static final BigDecimal MAXIMUM = new BigDecimal("1000000000000000");

    public static BigDecimal findPrice(List<String> lines) {
        return findPrice(lines, null, null, null);
    }

    /**
     * @param extraLine an additional line, typically the item's display name, tried after the lore.
     */
    public static BigDecimal findPrice(List<String> lines, String extraLine) {
        return findPrice(lines, extraLine, null, null);
    }

    /**
     * @param extraLines further text to search, such as custom-data components or a full tooltip.
     *                 Searched after the lore and the display name, before giving up.
     */
    public static BigDecimal findPrice(List<String> lines, String extraLine, List<String> extraLines) {
        return findPrice(lines, extraLine, extraLines, null);
    }

    /**
     * @param extraLines further text to search, such as custom-data components or a full tooltip.
     *                 Searched after the lore and the display name, before giving up.
     * @param profile   how this server writes prices; null uses the built-in currency words and no
     *                 custom pattern.
     */
    public static BigDecimal findPrice(List<String> lines, String extraLine, List<String> extraLines,
                                       dev.ahprofit.config.ServerProfile profile) {
        List<String> cleaned = new java.util.ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                String stripped = stripFormatting(line);
                if (!stripped.isBlank()) {
                    cleaned.add(stripped);
                }
            }
        }
        if (extraLine != null && !extraLine.isBlank()) {
            cleaned.add(stripFormatting(extraLine));
        }
        if (extraLines != null) {
            for (String line : extraLines) {
                String stripped = stripFormatting(line);
                if (!stripped.isBlank()) {
                    cleaned.add(stripped);
                }
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }

        // 0. A server-supplied pattern is authoritative: the operator knows their own format, so it
        // is tried before any heuristic and its first capture group is the amount.
        if (profile != null && profile.hasCustomPattern()) {
            try {
                // Lore text mixes case ("Price:", "PRICE", "price"), so the operator's own
                // pattern is matched case-insensitively like every built-in pattern is.
                Pattern custom = Pattern.compile(profile.pricePattern(),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                for (String line : cleaned) {
                    Matcher customMatcher = custom.matcher(line);
                    if (customMatcher.find() && customMatcher.groupCount() >= 1) {
                        BigDecimal value = toAmount(customMatcher.group(1), null);
                        if (value != null) {
                            return value;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // An unusable pattern must not stop the built-in heuristics from running.
            }
        }

        Pattern currency = currencyPattern(profile);

        // 1. A line that names a price wins, unless it is really a count.
        for (String line : cleaned) {
            if (PRICE_KEYWORD.matcher(line).find() && !COUNT_GLUE.matcher(line).find()) {
                BigDecimal value = lastAmount(line);
                if (value != null) {
                    return value;
                }
            }
        }
        // 2. A line carrying an explicit currency marker, which the profile decides.
        for (String line : cleaned) {
            if (QUANTITY_KEYWORD.matcher(line).find() || COUNT_GLUE.matcher(line).find()) {
                continue;
            }
            if (COUNT_ONLY_LINE.matcher(line).matches()) {
                continue;
            }
            if (!currency.matcher(line).find()) {
                continue;
            }
            BigDecimal value = lastAmount(line);
            if (value != null) {
                return value;
            }
        }
        // 3. A line that is essentially only an amount.
        for (String line : cleaned) {
            if (!BARE_AMOUNT_LINE.matcher(line).matches()) {
                continue;
            }
            BigDecimal value = lastAmount(line);
            if (value != null) {
                return value;
            }
        }
        // Deliberately no final fallback: "64 Diamonds" and "Expires in 2h" must never become a
        // price, because one wrong price silently corrupts every spread computed from it.
        return null;
    }

    /** The currency matcher for a profile: its words plus the shared symbols. */
    private static Pattern currencyPattern(dev.ahprofit.config.ServerProfile profile) {
        if (profile == null) {
            return DEFAULT_CURRENCY;
        }
        StringBuilder words = new StringBuilder();
        for (String word : profile.currencyWords()) {
            if (!words.isEmpty()) {
                words.append('|');
            }
            words.append(java.util.regex.Pattern.quote(word));
        }
        if (words.isEmpty()) {
            return DEFAULT_CURRENCY;
        }
        // The shared symbols are always accepted, whatever the profile declares, so a server that
        // writes "$160.00" needs no configuration at all.
        return Pattern.compile("(?i)\\b(?:" + words + ")\\b|" + CURRENCY_SYMBOLS.pattern());
    }

    /** @return the last money-looking amount on the line, or null when none is plausible. */
    public static BigDecimal lastAmount(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher matcher = AMOUNT.matcher(line);
        BigDecimal last = null;
        while (matcher.find()) {
            if (isNegated(line, matcher.start())) {
                continue;
            }
            BigDecimal parsed = toAmount(matcher.group(1), matcher.group(2));
            if (parsed != null) {
                last = parsed;
            }
        }
        return last;
    }

    /**
     * Normalises a written number. Grouping separators are accepted as commas, dots, spaces,
     * apostrophes, or underscores, but a single dot followed by exactly two digits is read as a
     * decimal point so "1234.56" is one thousand two hundred and thirty-four point five six.
     */
    /** True when the amount is written as a subtraction, which is never an asking price. */
    private static boolean isNegated(String line, int start) {
        for (int index = start - 1; index >= 0; index--) {
            char character = line.charAt(index);
            if (Character.isWhitespace(character)) {
                continue;
            }
            return character == '-' || character == '\u2212';
        }
        return false;
    }

    private static BigDecimal toAmount(String digits, String suffix) {
        if (digits == null) {
            return null;
        }
        String normalized = digits.trim().replace("_", "");
        // A lone dot with nothing after it is not a number.
        if (normalized.isEmpty() || normalized.chars().allMatch(character -> character == '.')) {
            return null;
        }
        boolean hasComma = normalized.indexOf(',') >= 0;
        boolean hasDot = normalized.indexOf('.') >= 0;
        if (hasComma) {
            normalized = normalized.replace(",", "");
        } else if (hasDot) {
            int first = normalized.indexOf('.');
            int last = normalized.lastIndexOf('.');
            if (first != last) {
                // Several dots can only be grouping: 1.234.567
                normalized = normalized.replace(".", "");
            } else if (normalized.length() - 1 - last >= 3) {
                // Three or more digits after a lone dot is grouping: 1.500
                normalized = normalized.replace(".", "");
            }
        }
        normalized = normalized.replace("'", "").replace(" ", "");
        if (normalized.isEmpty() || normalized.indexOf('.') == normalized.length() - 1) {
            normalized = normalized.replace(".", "");
        }
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(normalized);
            if (suffix != null) {
                value = value.multiply(switch (suffix.toLowerCase(Locale.ROOT)) {
                    case "k", "kk", "mil" -> new BigDecimal("1000");
                    case "m", "million" -> new BigDecimal("1000000");
                    case "b", "billion" -> new BigDecimal("1000000000");
                    default -> BigDecimal.ONE;
                }, MathContext.DECIMAL128);
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
