package dev.chunkfinder;

import net.minecraft.client.Minecraft;

final class FeatureAccess {
    private FeatureAccess() { }

    static boolean allowed(Minecraft client) {
        if (client == null || client.player == null || client.level == null) return false;
        return true;
    }

    static String status(Minecraft client) {
        if (!allowed(client)) return "Waiting for game to load";
        return client.hasSingleplayerServer()
                ? "Single-player client scan"
                : "Client-visible loaded chunks";
    }
}
