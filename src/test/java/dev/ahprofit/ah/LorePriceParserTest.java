package dev.ahprofit.ah;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LorePriceParserTest {
    private static BigDecimal parse(String... lines) {
        return LorePriceParser.findPrice(List.of(lines), null);
    }

    private static void assertPrice(String expected, String... lines) {
        BigDecimal found = parse(lines);
        assertEquals(0, new BigDecimal(expected).compareTo(found),
                "expected " + expected + " but got " + found + " from " + List.of(lines));
    }

    @Test
    void readsAKeywordPriceLine() {
        assertPrice("1234567", "\u00a7ePrice: \u00a7a$1,234,567");
        assertPrice("2500000", "Buy It Now: \u00a7f2.5M");
        assertPrice("1500", "\u00a77Cost \u00a7f1,500 coins");
        assertPrice("2000", "\u00a7aPrice: 2000");
    }

    @Test
    void stripsSectionSignFormatting() {
        assertPrice("128000", "\u00a7b\u00a7lTotal\u00a7r \u00a7f128,000");
        assertPrice("999", "\u00a7x\u00a7f\u00a7f\u00a70\u00a70\u00a70\u00a70Price \u00a7f999");
    }

    @Test
    void neverMistakesACountForAPrice() {
        // The old parser returned 64 here, which silently corrupted every spread.
        assertNull(parse("64 Diamonds", "Seller: Steve"));
        assertNull(parse("\u00a7fDiamond \u00a77x64"));
        assertNull(parse("Amount: 64"));
        assertNull(parse("Quantity: 12 items"));
        assertNull(parse("64 x Diamond Sword"));
    }

    @Test
    void prefersThePriceLineOverACountOnAnotherLine() {
        assertPrice("45000", "64 x Diamond", "Price: 45,000");
        assertPrice("45000", "Amount: 64", "Price: 45,000");
    }

    @Test
    void aLoneAmountOnItsOwnLineIsAPrice() {
        assertPrice("3200", "\u00a7a3,200");
        assertPrice("3200", "\u00a7a$3,200");
        assertPrice("3200", "\u00a7a3200 coins");
    }

    @Test
    void handlesMagnitudeSuffixes() {
        assertPrice("2000000", "Price: 2M");
        assertPrice("1500", "Price: 1.5k");
        assertPrice("3000000", "Price: 3 million");
        assertPrice("5000000000", "Price: 5b");
    }

    @Test
    void aSuffixNeverSwallowsAnOrdinaryWord() {
        // "5 boxes" must not become five billion.
        assertPrice("5", "Price: 5 boxes");
        assertPrice("12", "Buy 12 bricks");
        assertNull(parse("5 boxes"));
    }

    @Test
    void handlesGroupingSeparators() {
        assertPrice("1234567", "Price: 1,234,567");
        assertPrice("1234567", "Price: 1.234.567");
        assertPrice("1500", "Price: 1.500");
        assertPrice("1234.56", "Price: 1234.56");
    }

    @Test
    void usesTheLastAmountOnAPriceLine() {
        assertPrice("900", "Price: was 1200, now 900");
    }

    @Test
    void findsPricesInExtraSourcesSuchAsCustomData() {
        BigDecimal found = LorePriceParser.findPrice(List.of("\u00a77Seller: Steve"), "Diamond",
                List.of("{price:128000,item:minecraft:diamond}"));
        assertEquals(0, new BigDecimal("128000").compareTo(found));
    }

    @Test
    void returnsNullWhenThereIsNothingToRead() {
        assertNull(parse());
        assertNull(parse("", "   "));
        assertNull(parse("\u00a77Seller: Steve", "\u00a77Expires in 2h"));
    }

    @Test
    void rejectsImplausibleAmounts() {
        assertNull(parse("Price: 0"));
        assertNull(parse("Price: -500"));
    }

    @Test
    void equalAmountsCompareEqualWhateverTheirWrittenForm() {
        BigDecimal a = parse("Price: 8,000");
        BigDecimal b = parse("Price: 8000");
        assertEquals(a, b);
        assertEquals(0, a.compareTo(b));
    }
}
