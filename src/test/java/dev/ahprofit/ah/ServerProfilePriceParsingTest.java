package dev.ahprofit.ah;

import dev.ahprofit.config.ServerProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The same menu must read correctly on a server that calls its currency something else, and an
 * unusual layout must be matchable exactly instead of guessed at.
 */
final class ServerProfilePriceParsingTest {
    private static final ServerProfile REMNANT = new ServerProfile(
            "RemnantSMP", Set.of("mints", "dollars"), "", 600);

    private static BigDecimal parse(ServerProfile profile, String... lines) {
        return LorePriceParser.findPrice(List.of(lines), null, null, profile);
    }

    private static void assertPrice(String expected, ServerProfile profile, String... lines) {
        BigDecimal found = parse(profile, lines);
        assertEquals(0, new BigDecimal(expected).compareTo(found),
                "expected " + expected + " but got " + found + " from " + List.of(lines));
    }

    private static void assertNoPrice(ServerProfile profile, String... lines) {
        assertNull(parse(profile, lines), "expected no price from " + List.of(lines));
    }

    @Test
    @DisplayName("A symbol price reads on any server, with or without a profile")
    void symbolPricesNeedNoProfile() {
        assertPrice("8000", null, "\u00a7ePrice: \u00a7a$8,000");
        assertPrice("8000", REMNANT, "\u00a7ePrice: \u00a7a$8,000");
        assertPrice("160", null, "Buy price: $160.00");
        assertPrice("160", REMNANT, "Buy price: $160.00");
    }

    @Test
    @DisplayName("RemnantSMP's mints are recognised out of the box, with no profile configured")
    void mintsWorkWithoutAnyConfiguration() {
        assertPrice("160", null, "Price \u00a7f160 mints");
        assertPrice("160", REMNANT, "Price \u00a7f160 mints");
        assertPrice("1234", null, "Cost \u00a7f1,234 mints");
        assertNoPrice(null, "\u00a77Expires in 2h", "\u00a77Seller: Steve");
    }

    @Test
    @DisplayName("A currency word no default covers is only read once the profile declares it")
    void novelCurrencyWordNeedsAProfile() {
        ServerProfile souls = new ServerProfile("SoulSMP", Set.of("souls"), "", 600);
        assertNoPrice(null, "Only 1234 souls for a stack");
        assertPrice("1234", souls, "Only 1234 souls for a stack");
    }

    @Test
    @DisplayName("A line that is only a number and one word is a count, never a price")
    void numberPlusOneWordIsNeverAPrice() {
        assertNoPrice(REMNANT, "64 Diamonds", "Seller: Steve");
        assertNoPrice(REMNANT, "5 boxes");
        // The safety net: a currency word mistakenly set to an item name must not turn stack sizes
        // into prices, so "64 Diamonds" stays a count even when diamonds are declared as currency.
        ServerProfile careless = new ServerProfile("CarelessSMP", Set.of("diamonds", "coins"), "", 600);
        assertNoPrice(careless, "64 Diamonds", "Seller: Steve");
        assertNoPrice(careless, "160 mints", "Seller: Steve");
        // But a labelled line on the same menu still reads.
        assertPrice("64", careless, "64 Diamonds", "Price: 64");
    }

    @Test
    @DisplayName("A custom pattern beats every heuristic, including a competing keyword line")
    void customPatternWins() {
        ServerProfile profile = new ServerProfile("OddSMP", Set.of("cash"),
                "(?:asking|want)\\s*:?\\s*\\$?([0-9][0-9.,]*)", 600);
        // Without the pattern the first line looks like a price line and would win.
        assertPrice("2000", null, "\u00a7aPrice: 2000", "\u00a77Asking: $45,000");
        assertPrice("45000", profile, "\u00a7aPrice: 2000", "\u00a77Asking: $45,000");
    }

    @Test
    @DisplayName("A custom pattern must be able to match a price that carries no keyword at all")
    void patternMatchesWithoutAKeyword() {
        ServerProfile profile = new ServerProfile("OddSMP", Set.of("cash"),
                "\\b([0-9][0-9.,]*)\\s*souls\\b", 600);
        assertNoPrice(null, "Only 1234 souls for a stack");
        assertPrice("1234", profile, "Only 1234 souls for a stack");
    }

    @Test
    @DisplayName("An unusable custom pattern falls back to the heuristics instead of failing")
    void brokenPatternIsIgnored() {
        ServerProfile unbalanced = new ServerProfile("OddSMP", Set.of("cash"), "(unclosed", 600);
        assertPrice("8000", unbalanced, "\u00a7ePrice: \u00a7a$8,000");

        // A pattern with no capture group cannot name an amount, so it is skipped, not fatal.
        ServerProfile noCaptureGroup = new ServerProfile("OddSMP", Set.of("cash"), "\\d{4,}", 600);
        assertPrice("8000", noCaptureGroup, "\u00a7ePrice: \u00a7a$8,000");
    }

    @Test
    @DisplayName("The shared currency symbols are recognised whatever the profile declares")
    void symbolsAlwaysWork() {
        ServerProfile wordsOnly = new ServerProfile("SMP", Set.of("tokens"), "", 600);
        assertPrice("8000", wordsOnly, "\u00a7ePrice: \u00a7a$8,000");
        assertPrice("8000", wordsOnly, "\u00a7ePrice: \u00a7a\u20ac8,000");
    }

    @Test
    @DisplayName("A profile never turns a non-price line into a price")
    void profileNeverInvitesFalsePositives() {
        assertNoPrice(REMNANT, "\u00a7fDiamond \u00a77x64");
        assertNoPrice(REMNANT, "Amount \u00a7f64 mints available");
        assertNoPrice(REMNANT, "Quantity: 12 items");
        assertNoPrice(REMNANT, "Price: 0");
        assertNoPrice(REMNANT, "Price: -500");
        assertNoPrice(REMNANT);
    }
}
