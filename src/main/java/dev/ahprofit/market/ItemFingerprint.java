package dev.ahprofit.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ahprofit.api.ApiItem;
import dev.ahprofit.api.ItemAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds a stable, quantity-independent identity for the marketable item variant. */
public final class ItemFingerprint {
    private ItemFingerprint() {
    }

    public static Result of(ApiItem item) {
        JsonObject canonical = itemIdentity(item, false);
        String digest = sha256(canonical.toString());
        ItemCategory category = ItemCategory.classify(item.id());
        boolean comparable = item.additionalFields().size() == 0 && item.contentsComplete();

        if (ItemCategory.mayHaveHiddenItemVariant(item.id())) {
            ItemAttributes attributes = item.attributes();
            boolean durabilityAvailable = item.additionalFields().has("damage")
                    || item.additionalFields().has("durability")
                    || item.additionalFields().has("max_damage");
            comparable &= attributes.enchantmentLevelsKnown()
                    && attributes.trimKnown()
                    && durabilityAvailable;
        }

        if (category == ItemCategory.CONTAINERS) {
            comparable &= item.contentsFieldPresent() && item.contentsComplete();
        }

        if (hasUnmodeledAttributeFields(item)) {
            comparable = false;
        }

        return new Result(digest, canonical.toString(), comparable, category, describeVariant(item));
    }

    private static JsonObject itemIdentity(ApiItem item, boolean includeCount) {
        JsonObject result = new JsonObject();
        result.addProperty("id", item.id().toLowerCase(java.util.Locale.ROOT));
        if (includeCount) {
            result.addProperty("count", item.count());
        }
        if (!item.displayName().isBlank()) {
            result.addProperty("displayName", item.displayName());
        }

        JsonArray lore = new JsonArray();
        item.lore().forEach(lore::add);
        result.add("lore", lore);

        ItemAttributes attributes = item.attributes();
        JsonObject attributeObject = new JsonObject();
        attributeObject.addProperty("enchantmentLevelsKnown", attributes.enchantmentLevelsKnown());
        JsonObject enchantments = new JsonObject();
        new TreeMap<>(attributes.enchantmentLevels()).forEach(enchantments::addProperty);
        attributeObject.add("enchantmentLevels", enchantments);
        attributeObject.addProperty("trimKnown", attributes.trimKnown());
        attributeObject.addProperty("trimMaterial", attributes.trimMaterial());
        attributeObject.addProperty("trimPattern", attributes.trimPattern());
        attributeObject.add("additional", canonicalize(attributes.additionalFields()).getAsJsonObject());
        result.add("attributes", attributeObject);

        result.addProperty("contentsFieldPresent", item.contentsFieldPresent());
        result.addProperty("contentsComplete", item.contentsComplete());
        JsonArray contents = new JsonArray();
        List<String> contentIdentities = new ArrayList<>();
        for (ApiItem content : item.contents()) {
            contentIdentities.add(itemIdentity(content, true).toString());
        }
        Collections.sort(contentIdentities);
        contentIdentities.forEach(contents::add);
        result.add("contents", contents);
        result.add("additionalFields", canonicalize(item.additionalFields()));
        return result;
    }

    private static boolean hasUnmodeledAttributeFields(ApiItem item) {
        Set<String> modeledAttributeExtensions = Set.of("damage", "durability", "max_damage");
        for (Map.Entry<String, JsonElement> entry : item.additionalFields().entrySet()) {
            if (!modeledAttributeExtensions.contains(entry.getKey())) {
                return true;
            }
        }
        for (Map.Entry<String, JsonElement> entry : item.attributes().additionalFields().entrySet()) {
            if (!entry.getKey().equals("attributes") && !entry.getKey().equals("attribute_modifiers")) {
                return true;
            }
        }
        return false;
    }

    private static JsonElement canonicalize(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return element == null ? com.google.gson.JsonNull.INSTANCE : element.deepCopy();
        }
        if (element.isJsonArray()) {
            JsonArray normalized = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                normalized.add(canonicalize(child));
            }
            return normalized;
        }
        JsonObject normalized = new JsonObject();
        TreeMap<String, JsonElement> fields = new TreeMap<>();
        element.getAsJsonObject().entrySet().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
        fields.forEach((key, value) -> normalized.add(key, canonicalize(value)));
        return normalized;
    }

    private static String describeVariant(ApiItem item) {
        List<String> parts = new ArrayList<>();
        if (!item.displayName().isBlank()) {
            parts.add("named “" + item.displayName() + "”");
        }
        if (!item.attributes().enchantmentLevels().isEmpty()) {
            item.attributes().enchantmentLevels().forEach((name, level) -> parts.add(name + " " + level));
        }
        if (!item.attributes().trimMaterial().isBlank() || !item.attributes().trimPattern().isBlank()) {
            parts.add("trim " + item.attributes().trimMaterial() + " " + item.attributes().trimPattern());
        }
        if (!item.contents().isEmpty()) {
            parts.add("filled container");
        }
        return parts.isEmpty() ? "standard variant" : String.join(", ", parts);
    }

    public static String displayName(ApiItem item) {
        if (!item.displayName().isBlank()) {
            return item.displayName();
        }
        String path = item.id().contains(":") ? item.id().substring(item.id().indexOf(':') + 1) : item.id();
        String[] words = path.replace('_', ' ').split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.isEmpty() ? item.id() : result.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte next : digest) {
                result.append(Character.forDigit((next >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(next & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    public record Result(String value, String canonicalJson, boolean comparable, ItemCategory category, String variantDescription) {
    }
}
