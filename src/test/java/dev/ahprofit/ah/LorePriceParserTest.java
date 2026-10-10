package dev.ahprofit.ah;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LorePriceParserTest {
    @Test
    void readsKeywordLinesFirst() {
        assertEquals(new BigDecimal("1234"), LorePriceParser.findPrice(List.of("Seller: Steve", "Price: $1,234")));
        assertEquals(new BigDecimal("5000000"), LorePriceParser.findPrice(List.of("Buyout: 5,000,000")));
        assertEquals(new BigDecimal("1500"), LorePriceParser.findPrice(List.of("Cost: $1.5k")));
    }

    @Test
    void quantityLinesAreNeverMistakenForAPrice() {
        assertNull(LorePriceParser.findPrice(List.of("64 x Diamond")));
        assertNull(LorePriceParser.findPrice(List.of("Diamond")));
        assertNull(LorePriceParser.findPrice(List.of("Seller: Steve", "Click to buy")));
    }

    @Test
    void lastAmountOnAKeywordLineWinsSoLeadingQuantitiesAreIgnored() {
        assertEquals(new BigDecimal("1234"), LorePriceParser.findPrice(List.of("Price: 64 items for $1,234")));
    }

    @Test
    void fallsBackToTheDisplayNameWhenLoreHasNoPrice() {
        assertEquals(new BigDecimal("2500"), LorePriceParser.findPrice(List.of("Seller: Steve"), "$2,500 Diamond"));
    }

    @Test
    void formattingCodesAreStripped() {
        assertEquals(new BigDecimal("900"), LorePriceParser.findPrice(List.of("\u00a7aPrice: \u00a7f$900")));
    }

    @Test
    void implausibleAmountsAreRejected() {
        assertNull(LorePriceParser.findPrice(List.of("Price: $0")));
        assertNull(LorePriceParser.findPrice(List.of("Price: 9999999999999999")));
        assertNull(LorePriceParser.findPrice(List.of()));
        assertNull(LorePriceParser.findPrice(null));
    }
}
