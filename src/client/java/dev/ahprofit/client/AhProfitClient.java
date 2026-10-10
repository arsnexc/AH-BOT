package dev.ahprofit.client;

import dev.ahprofit.api.DonutApiClient;
import dev.ahprofit.config.ConfigManager;
import dev.ahprofit.database.MarketDatabase;
import dev.ahprofit.market.OpportunityEngine;
import dev.ahprofit.scanner.ScannerService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class AhProfitClient implements ClientModInitializer {
    public static final String MOD_ID = "ahprofit";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ConfigManager configManager;
    private static ScannerService scannerService;

    @Override
    public void onInitializeClient() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        configManager = new ConfigManager(configDirectory,
                message -> LOGGER.warn("{}", message),
                message -> LOGGER.error("{}", message));
        MarketDatabase database = new MarketDatabase(configDirectory.resolve("market.sqlite"));
        DonutApiClient apiClient = new DonutApiClient(configManager::apiKeyForRequest);
        scannerService = new ScannerService(configManager, database, apiClient, new OpportunityEngine(), AhProfitClient::notifyPlayer);

        AhProfitCommands.register(scannerService, configManager);
        AhProfitHud.register(scannerService, configManager);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scannerService.close();
            configManager.close();
        }, "AhProfit-shutdown"));

        LOGGER.info("AhProfit client initialized. Local command /ahprofit is available; all trading actions remain manual.");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void notifyPlayer(String message) {
        Minecraft client = Minecraft.getInstance();
        String safeMessage = redact(message);
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("[AhProfit] " + safeMessage));
            }
        });
    }

    private static String redact(String message) {
        String safe = message == null ? "" : message;
        ConfigManager currentConfig = configManager;
        if (currentConfig != null) {
            String secret = currentConfig.apiKeyForRequest();
            if (secret != null && !secret.isBlank()) {
                safe = safe.replace(secret, "[redacted]");
            }
        }
        return safe;
    }

    public static ScannerService scanner() {
        return scannerService;
    }

    public static ConfigManager config() {
        return configManager;
    }
}
