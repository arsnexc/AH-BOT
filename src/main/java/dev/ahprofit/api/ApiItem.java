package dev.ahprofit.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A typed representation of the public fields in DonutSMP's api.Item schema. */
public record ApiItem(
        String id,
        int count,
        String displayName,
        List<String> lore,
        ItemAttributes attributes,
        List<ApiItem> contents,
        boolean contentsFieldPresent,
        boolean contentsComplete,
        JsonObject additionalFields,
        String rawJson
) {
    private static final Set<String> KNOWN_FIELDS = Set.of("id", "count", "display_name", "enchants", "lore", "contents");
    private static final int MAX_ITEM_NESTING = 8;
    private static final int MAX_CONTENTS = 256;

    public ApiItem {
        id = id == null ? "" : id;
        displayName = displayName == null ? "" : displayName;
        lore = List.copyOf(lore == null ? List.of() : lore);
        attributes = attributes == null ? ItemAttributes.unknown() : attributes;
        contents = List.copyOf(contents == null ? List.of() : contents);
        additionalFields = additionalFields == null ? new JsonObject() : additionalFields.deepCopy();
        rawJson = rawJson == null ? "{}" : rawJson;
    }

    @Override
    public JsonObject additionalFields() {
        return additionalFields.deepCopy();
    }

    public static ApiItem fromJson(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Item must be an object.");
        }
        return fromJson(parsed.getAsJsonObject(), 0);
    }

    public static ApiItem fromJson(JsonObject object) {
        return fromJson(object, 0);
    }

    private static ApiItem fromJson(JsonObject object, int depth) {
        if (depth > MAX_ITEM_NESTING) {
            throw new IllegalArgumentException("Item contents are nested too deeply.");
        }

        String id = requiredString(object, "id");
        int count = requiredPositiveInt(object, "count");
        String displayName = optionalString(object, "display_name", "");
        List<String> lore = parseStringArray(object.get("lore"), "lore", 1_024);
        ItemAttributes attributes = parseAttributes(object.get("enchants"));

        JsonElement contentsElement = object.get("contents");
        boolean contentsFieldPresent = contentsElement != null && !contentsElement.isJsonNull();
        boolean contentsComplete = true;
        List<ApiItem> contents = new ArrayList<>();
        if (contentsFieldPresent) {
            if (!contentsElement.isJsonArray()) {
                contentsComplete = false;
            } else {
                JsonArray contentArray = contentsElement.getAsJsonArray();
                if (contentArray.size() > MAX_CONTENTS) {
                    contentsComplete = false;
                } else {
                    for (JsonElement child : contentArray) {
                        if (child == null || child.isJsonNull() || !child.isJsonObject()) {
                            contentsComplete = false;
                            continue;
                        }
                        try {
                            contents.add(fromJson(child.getAsJsonObject(), depth + 1));
                        } catch (IllegalArgumentException exception) {
                            contentsComplete = false;
                        }
                    }
                }
            }
        }

        JsonObject additional = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!KNOWN_FIELDS.contains(entry.getKey())) {
                additional.add(entry.getKey(), entry.getValue() == null ? null : entry.getValue().deepCopy());
            }
        }

        return new ApiItem(
                id,
                count,
                displayName,
                lore,
                attributes,
                contents,
                contentsFieldPresent,
                contentsComplete,
                additional,
                object.toString()
        );
    }

    public JsonObject toJson() {
        JsonElement element = JsonParser.parseString(rawJson);
        return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static ItemAttributes parseAttributes(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return ItemAttributes.unknown();
        }
        JsonObject data = element.getAsJsonObject();
        JsonObject additional = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            if (!entry.getKey().equals("enchantments") && !entry.getKey().equals("trim")) {
                additional.add(entry.getKey(), entry.getValue() == null ? null : entry.getValue().deepCopy());
            }
        }

        Map<String, Integer> levels = new HashMap<>();
        boolean levelsKnown = false;
        JsonElement enchantmentsElement = data.get("enchantments");
        if (enchantmentsElement != null && enchantmentsElement.isJsonObject()) {
            JsonObject enchantments = enchantmentsElement.getAsJsonObject();
            JsonElement levelsElement = enchantments.get("levels");
            if (levelsElement != null && levelsElement.isJsonObject()) {
                levelsKnown = true;
                for (Map.Entry<String, JsonElement> entry : levelsElement.getAsJsonObject().entrySet()) {
                    if (entry.getValue() != null && entry.getValue().isJsonPrimitive()
                            && entry.getValue().getAsJsonPrimitive().isNumber()) {
                        try {
                            levels.put(entry.getKey(), entry.getValue().getAsInt());
                        } catch (NumberFormatException ignored) {
                            // A malformed individual enchant is omitted; the incomplete map remains conservative.
                            levelsKnown = false;
                        }
                    } else {
                        levelsKnown = false;
                    }
                }
            }
        }

        String material = "";
        String pattern = "";
        boolean trimKnown = false;
        JsonElement trimElement = data.get("trim");
        if (trimElement != null && trimElement.isJsonObject()) {
            JsonObject trim = trimElement.getAsJsonObject();
            JsonElement materialElement = trim.get("material");
            JsonElement patternElement = trim.get("pattern");
            if (materialElement != null && materialElement.isJsonPrimitive()
                    && patternElement != null && patternElement.isJsonPrimitive()) {
                material = materialElement.getAsString();
                pattern = patternElement.getAsString();
                trimKnown = true;
            }
        }

        return new ItemAttributes(levels, levelsKnown, material, pattern, trimKnown, true, additional);
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing or invalid item field: " + name);
        }
        String value = element.getAsString().trim();
        if (value.isEmpty() || value.length() > 256) {
            throw new IllegalArgumentException("Empty or overlong item field: " + name);
        }
        return value;
    }

    private static int requiredPositiveInt(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing or invalid item field: " + name);
        }
        try {
            int value = new BigDecimal(element.getAsString()).intValueExact();
            if (value <= 0 || value > 1_000_000) {
                throw new IllegalArgumentException("Invalid item quantity.");
            }
            return value;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid item quantity.");
        }
    }

    private static String optionalString(JsonObject object, String name, String fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Invalid item field: " + name);
        }
        String value = element.getAsString();
        if (value.length() > 512) {
            throw new IllegalArgumentException("Item display name is overlong.");
        }
        return value;
    }

    private static List<String> parseStringArray(JsonElement element, String field, int limit) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonArray() || element.getAsJsonArray().size() > limit) {
            throw new IllegalArgumentException("Invalid item field: " + field);
        }
        List<String> result = new ArrayList<>();
        for (JsonElement child : element.getAsJsonArray()) {
            if (child == null || child.isJsonNull() || !child.isJsonPrimitive()
                    || !child.getAsJsonPrimitive().isString() || child.getAsString().length() > 4_096) {
                throw new IllegalArgumentException("Invalid item field: " + field);
            }
            result.add(child.getAsString());
        }
        return result;
    }
}
