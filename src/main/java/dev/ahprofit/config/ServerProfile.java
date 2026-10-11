package dev.ahprofit.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How one server writes its auction-house prices.
 *
 * <p>The key-free paths read whatever menu the server opens, so nothing here is specific to any one
 * server. What does differ is the text: the currency a server prints next to its price, and how the
 * price line is laid out. A profile lets a server be described without a code change, and a custom
 * pattern lets an unusual format be matched exactly.
 */
public record ServerProfile(String name, Set<String> currencyWords, String pricePattern,
                            int assumedTimeLeftSeconds) {
    /** Currency words recognised out of the box, including RemnantSMP's mints. */
    private static final Set<String> DEFAULT_CURRENCY_WORDS = Set.of(
            "coins", "coin", "money", "cash", "usd", "dollars", "dollar", "mints", "mint",
            "gems", "gem", "tokens", "token", "credits", "credit", "emeralds");

    public ServerProfile {
        name = name == null ? "" : name.trim();
        pricePattern = pricePattern == null ? "" : pricePattern.trim();
        if (currencyWords == null || currencyWords.isEmpty()) {
            currencyWords = DEFAULT_CURRENCY_WORDS;
        } else {
            Set<String> normalised = new LinkedHashSet<>();
            for (String word : currencyWords) {
                if (word != null && !word.isBlank()) {
                    normalised.add(word.trim().toLowerCase(Locale.ROOT));
                }
            }
            currencyWords = normalised.isEmpty() ? DEFAULT_CURRENCY_WORDS : Set.copyOf(normalised);
        }
        if (assumedTimeLeftSeconds <= 0 || assumedTimeLeftSeconds > 365 * 24 * 60 * 60) {
            assumedTimeLeftSeconds = 600;
        }
    }

    public static ServerProfile defaults() {
        return new ServerProfile("", DEFAULT_CURRENCY_WORDS, "", 600);
    }

    /** The server's name as shown to the player, or a neutral phrase when none is configured. */
    public String displayName() {
        return name.isBlank() ? "this server" : name;
    }

    public List<String> sortedCurrencyWords() {
        return currencyWords.stream().sorted().toList();
    }

    public boolean hasCustomPattern() {
        return !pricePattern.isBlank();
    }
}
