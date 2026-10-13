package dev.chunkfinder;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.fabricmc.loader.api.FabricLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ChunkFinderServer implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("chunkfinder-server.json");
    private static volatile boolean enabled;

    @Override
    public void onInitialize() {
        enabled = readEnabled();
        PayloadTypeRegistry.playS2C().register(FeaturePermissionPayload.TYPE, FeaturePermissionPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                syncPermission(handler.player, enabled));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("chunkfinder")
                        .then(Commands.literal("enable").requires(source -> source.permissions()
                                        .hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                .executes(context -> setEnabled(context.getSource(), true)))
                        .then(Commands.literal("disable").requires(source -> source.permissions()
                                        .hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                .executes(context -> setEnabled(context.getSource(), false)))
                        .then(Commands.literal("status").requires(source -> source.permissions()
                                        .hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                .executes(context -> {
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Chunk Finder server authorization is " + (enabled ? "enabled" : "disabled")
                                                    + ". Only operators can use the client features."), false);
                                    return 1;
                                }))));
    }

    private static int setEnabled(CommandSourceStack source, boolean value) {
        enabled = value;
        writeEnabled(value);
        MinecraftServer server = source.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) syncPermission(player, value);
        source.sendSuccess(() -> Component.literal("Chunk Finder server authorization "
                + (value ? "enabled" : "disabled") + ". Only operators are authorized."), true);
        return 1;
    }

    private static void syncPermission(ServerPlayer player, boolean serverEnabled) {
        if (ServerPlayNetworking.canSend(player, FeaturePermissionPayload.TYPE)) {
            ServerPlayNetworking.send(player, new FeaturePermissionPayload(serverEnabled
                    && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)));
        }
    }

    private static boolean readEnabled() {
        if (!Files.exists(CONFIG_FILE)) return false;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(CONFIG_FILE)).getAsJsonObject();
            return json.has("enabled") && json.get("enabled").getAsBoolean();
        } catch (RuntimeException | IOException exception) {
            System.err.println("[Chunk Finder] Could not read server authorization config; keeping features disabled.");
            return false;
        }
    }

    private static void writeEnabled(boolean value) {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", value);
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, GSON.toJson(json));
        } catch (IOException exception) {
            System.err.println("[Chunk Finder] Could not save server authorization config.");
        }
    }
}
