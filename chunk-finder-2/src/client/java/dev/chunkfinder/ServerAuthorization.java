package dev.chunkfinder;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

final class ServerAuthorization {
    // Unlocked: Defaulted to true so features work everywhere
    private static volatile boolean authorized = true;

    private ServerAuthorization() { }

    static void register() {
        // Disabled networking registration to prevent servers from locking the mod
    }

    static boolean authorized() { return authorized; }
}
