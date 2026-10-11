package dev.ahprofit.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A profile must describe a server without a code change, and must never be able to break the
 * parser, because it comes from a hand-edited config file.
 */
final class ServerProfileTest {
    @Test
    @DisplayName("An unnamed profile reads as a neutral phrase, never as a blank or null")
    void blankNameIsNeutral() {
        assertEquals("this server", new ServerProfile("", Set.of("cash"), "", 600).displayName());
        assertEquals("this server", new ServerProfile("   ", Set.of("cash"), "", 600).displayName());
        assertEquals("this server", new ServerProfile(null, Set.of("cash"), null, 0).displayName());
        assertEquals("RemnantSMP", new ServerProfile("  RemnantSMP ", Set.of("cash"), "", 600).displayName());
    }

    @Test
    @DisplayName("Currency words are lower-cased, de-duplicated and never left empty")
    void currencyWordsAreNormalised() {
        ServerProfile profile = new ServerProfile("x",
                new LinkedHashSet<>(List.of("Mints", "mints", " CASH ", "", null)), "", 600);
        assertEquals(Set.of("mints", "cash"), profile.currencyWords());
        assertEquals(List.of("cash", "mints"), profile.sortedCurrencyWords());
        assertEquals(ServerProfile.defaults().currencyWords(), new ServerProfile("x", Set.of(), "", 600).currencyWords());
        assertEquals(ServerProfile.defaults().currencyWords(), new ServerProfile("x", null, "", 600).currencyWords());
    }

    @Test
    @DisplayName("A blank pattern is not a custom pattern, so the heuristics still run")
    void blankPatternIsNotCustom() {
        assertFalse(new ServerProfile("x", Set.of("cash"), "", 600).hasCustomPattern());
        assertFalse(new ServerProfile("x", Set.of("cash"), "   ", 600).hasCustomPattern());
        assertFalse(new ServerProfile("x", Set.of("cash"), null, 600).hasCustomPattern());
        assertTrue(new ServerProfile("x", Set.of("cash"), "(?:price)\\s*(\\d+)", 600).hasCustomPattern());
    }

    @Test
    @DisplayName("An out-of-range listing life falls back to the default rather than failing")
    void listingLifeIsClamped() {
        assertEquals(600, new ServerProfile("x", Set.of("cash"), "", 0).assumedTimeLeftSeconds());
        assertEquals(600, new ServerProfile("x", Set.of("cash"), "", -5).assumedTimeLeftSeconds());
        assertEquals(600,
                new ServerProfile("x", Set.of("cash"), "", 365 * 24 * 60 * 60 + 1).assumedTimeLeftSeconds());
        assertEquals(86_400, new ServerProfile("x", Set.of("cash"), "", 86_400).assumedTimeLeftSeconds());
    }

    @Test
    @DisplayName("The built-in words already cover the common secondary currencies")
    void defaultsCoverCommonWords() {
        Set<String> words = ServerProfile.defaults().currencyWords();
        assertTrue(words.contains("coins"));
        assertTrue(words.contains("mints"));
        assertTrue(words.contains("cash"));
        assertTrue(words.contains("gems"));
        assertTrue(words.contains("tokens"));
        assertTrue(words.contains("credits"));
        for (String word : words) {
            assertEquals(word, word.toLowerCase(Locale.ROOT));
        }
    }

    @Test
    @DisplayName("A null profile field is never a null field on the config")
    void nullProfileBecomesDefaults() {
        AhProfitConfig config = AhProfitConfig.defaults().withServerProfile(null);
        assertEquals(ServerProfile.defaults().name(), config.serverProfile().name());
        assertEquals(ServerProfile.defaults().currencyWords(), config.serverProfile().currencyWords());
    }
}
