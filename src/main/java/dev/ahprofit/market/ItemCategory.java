package dev.ahprofit.market;

import java.util.Locale;

public enum ItemCategory {
    RESOURCES,
    BUILDING,
    REDSTONE,
    EQUIPMENT,
    CONTAINERS,
    OTHER;

    public static ItemCategory classify(String rawId) {
        String id = rawId == null ? "" : rawId.toLowerCase(Locale.ROOT);
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;

        if (path.contains("shulker_box") || path.equals("bundle")) {
            return CONTAINERS;
        }
        if (isEquipment(path)) {
            return EQUIPMENT;
        }
        if (isRedstone(path)) {
            return REDSTONE;
        }
        if (isResource(path)) {
            return RESOURCES;
        }
        if (isBuilding(path)) {
            return BUILDING;
        }
        return OTHER;
    }

    public static boolean mayHaveHiddenItemVariant(String rawId) {
        String id = rawId == null ? "" : rawId.toLowerCase(Locale.ROOT);
        String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return isEquipment(path)
                || path.endsWith("_potion")
                || path.equals("potion")
                || path.equals("enchanted_book")
                || path.equals("written_book")
                || path.equals("writable_book")
                || path.equals("suspicious_stew")
                || path.equals("tipped_arrow")
                || path.equals("firework_star")
                || path.equals("firework_rocket")
                || path.endsWith("_banner")
                || path.equals("player_head")
                || path.equals("filled_map");
    }

    private static boolean isEquipment(String path) {
        return path.equals("elytra")
                || path.equals("mace")
                || path.equals("trident")
                || path.equals("bow")
                || path.equals("crossbow")
                || path.equals("shield")
                || path.equals("fishing_rod")
                || path.equals("shears")
                || path.endsWith("_sword")
                || path.endsWith("_pickaxe")
                || path.endsWith("_axe")
                || path.endsWith("_shovel")
                || path.endsWith("_hoe")
                || path.endsWith("_helmet")
                || path.endsWith("_chestplate")
                || path.endsWith("_leggings")
                || path.endsWith("_boots");
    }

    private static boolean isRedstone(String path) {
        return path.contains("redstone")
                || path.contains("repeater")
                || path.contains("comparator")
                || path.contains("observer")
                || path.contains("piston")
                || path.contains("hopper")
                || path.contains("dropper")
                || path.contains("dispenser")
                || path.contains("tripwire")
                || path.contains("daylight_detector")
                || path.contains("target");
    }

    private static boolean isResource(String path) {
        return path.contains("gilded_blackstone")
                || path.contains("shulker_shell")
                || path.contains("ancient_debris")
                || path.contains("netherite")
                || path.contains("diamond")
                || path.contains("emerald")
                || path.contains("amethyst")
                || path.contains("quartz")
                || path.contains("lapis")
                || path.contains("ingot")
                || path.contains("raw_")
                || path.contains("ore")
                || path.contains("coal")
                || path.contains("copper")
                || path.contains("gold")
                || path.contains("iron")
                || path.contains("ender_pearl")
                || path.contains("blaze_rod")
                || path.contains("ghast_tear")
                || path.contains("phantom_membrane");
    }

    private static boolean isBuilding(String path) {
        return path.endsWith("_planks")
                || path.endsWith("_log")
                || path.endsWith("_wood")
                || path.endsWith("_wool")
                || path.endsWith("_terracotta")
                || path.endsWith("_concrete")
                || path.endsWith("_glass")
                || path.endsWith("_bricks")
                || path.endsWith("_slab")
                || path.endsWith("_stairs")
                || path.endsWith("_wall")
                || path.endsWith("_stone")
                || path.endsWith("_dirt")
                || path.endsWith("_sand")
                || path.endsWith("_gravel")
                || path.endsWith("_leaves")
                || path.endsWith("_coral_block")
                || path.endsWith("_ice")
                || path.endsWith("_block");
    }
}
