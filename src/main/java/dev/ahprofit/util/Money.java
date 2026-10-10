package dev.ahprofit.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

public final class Money {
    public static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private Money() {
    }

    public static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent, MATH_CONTEXT).divide(ONE_HUNDRED, MATH_CONTEXT);
    }

    public static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, MATH_CONTEXT);
    }

    public static String format(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return "$" + format.format(amount);
    }

    public static String formatPercent(BigDecimal percent) {
        Objects.requireNonNull(percent, "percent");
        DecimalFormat format = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));
        format.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return format.format(percent) + "%";
    }

    public static BigDecimal parseUserAmount(String input) {
        if (input == null || input.length() > 64) {
            throw new IllegalArgumentException("Enter a valid non-negative amount, for example 2000000 or 2,000,000.");
        }
        String cleaned = input.trim().replace("$", "").replace(",", "").replace("_", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Amount cannot be empty.");
        }
        final BigDecimal amount;
        try {
            amount = new BigDecimal(cleaned);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Enter a valid amount, for example 2000000 or 2,000,000.");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Amount must not be negative.");
        }
        if (amount.compareTo(new BigDecimal("1000000000000000")) > 0) {
            throw new IllegalArgumentException("Amount is too large; the maximum accepted value is 1,000,000,000,000,000.");
        }
        return amount.stripTrailingZeros();
    }

    public static BigDecimal parseUserPercent(String input, String label) {
        BigDecimal value = parseUserAmount(input.replace("%", ""));
        if (value.compareTo(new BigDecimal("1000")) > 0) {
            throw new IllegalArgumentException(label + " must be between 0 and 1,000 percent.");
        }
        return value;
    }
}
