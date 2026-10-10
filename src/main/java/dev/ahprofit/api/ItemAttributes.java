package dev.ahprofit.api;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Attributes exposed by the DonutSMP item schema. Missing data is kept distinct from known-empty data. */
public record ItemAttributes(
        Map<String, Integer> enchantmentLevels,
        boolean enchantmentLevelsKnown,
        String trimMaterial,
        String trimPattern,
        boolean trimKnown,
        boolean attributesObjectPresent,
        JsonObject additionalFields
) {
    public ItemAttributes {
        enchantmentLevels = Collections.unmodifiableMap(new TreeMap<>(enchantmentLevels == null ? Map.of() : enchantmentLevels));
        trimMaterial = trimMaterial == null ? "" : trimMaterial;
        trimPattern = trimPattern == null ? "" : trimPattern;
        additionalFields = additionalFields == null ? new JsonObject() : additionalFields.deepCopy();
    }

    @Override
    public JsonObject additionalFields() {
        return additionalFields.deepCopy();
    }

    public static ItemAttributes unknown() {
        return new ItemAttributes(Map.of(), false, "", "", false, false, new JsonObject());
    }
}
