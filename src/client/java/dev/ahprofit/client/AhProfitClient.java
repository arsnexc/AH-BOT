package dev.ahprofit.client;

import dev.ahprofit.api.DonutApiClient;
import dev.ahprofit.client.ah.AuctionAutomationService;
import dev.ahprofit.client.ah.AuctionHouseRecorder;
import dev.ahprofit.config.ConfigManager;
import dev.ahprofit.database.MarketDatabase;
import dev.ahprofit.market.OpportunityEngine;
import dev.ahprofit.scanner.ScannerService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
    private static AuctionAutomationService automationService;
    private static AuctionHouseRecorder recorder;

    @Override
    public void onInitializeClient() {
        Path configDirectory = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        configManager = new ConfigManager(configDirectory,
                message -> LOGGER.warn("{}", message),
                message -> LOGGER.error("{}", message));
        MarketDatabase database = new MarketDatabase(configDirectory.resolve("market.sqlite"));
        DonutApiClient apiClient = new DonutApiClient(configManager::apiKeyForRequest);
        scannerService = new ScannerService(configManager, database, apiClient, new OpportunityEngine(), AhProfitClient::notifyPlayer);
        automationService = new AuctionAutomationService(scannerService, configManager);
        recorder = new AuctionHouseRecorder(configManager.observedAuctionPath(), scannerService);

        AhProfitCommands.register(scannerService, configManager, automationService, recorder);
        AhProfitHud.register(scannerService, configManager, automationService);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            automationService.onClientTick(client);
            recorder.onClientTick(client);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scannerService.close();
            configManager.close();
        }, "AhProfit-shutdown"));

        LOGGER.info("AhProfit client initialized. Local command /ahprofit is available; trading is manual unless you explicitly enable /ahprofit ah on.");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void notifyPlayer(String message) {
        Minecraft client = Minecraft.getInstance();
        String safeMessage = redact(message);
        client.execute(() -> {
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal("[AhProfit] " + safeMessage), false);
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

    public static AuctionHouseRecorder recorder() {
        return recorder;
    }

    public static ScannerService scanner() {
        return scannerService;
    }

    public static ConfigManager config() {
        return configManager;
    }
}
