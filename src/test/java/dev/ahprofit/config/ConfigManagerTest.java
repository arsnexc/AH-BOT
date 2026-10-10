package dev.ahprofit.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void apiKeyIsKeptOutOfGeneralSettingsAndLogs() throws Exception {
        String secret = "test-secret-that-must-not-leak";
        Path configDirectory = temporaryDirectory.resolve("ahprofit");
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve("credentials.json"),
                "{\"apiKey\":\"" + secret + "\"}", StandardCharsets.UTF_8);
        List<String> logs = new ArrayList<>();
        ConfigManager manager = new ConfigManager(configDirectory, logs::add, logs::add);
        try {
            assertEquals(secret, manager.apiKeyForRequest());
            assertFalse(String.join("\n", logs).contains(secret));
            manager.update(current -> current.withManualBalance(new BigDecimal("2000000"), System.currentTimeMillis()));
        } finally {
            manager.close();
        }

        String settings = Files.readString(configDirectory.resolve("settings.json"));
        String credentials = Files.readString(configDirectory.resolve("credentials.json"));
        assertFalse(settings.contains(secret));
        assertFalse(settings.contains("apiKey"));
        assertTrue(credentials.contains(secret));
    }

    @Test
    void localModePersistsAndSurvivesBankrollCopies() throws Exception {
        Path directory = temporaryDirectory.resolve("local-settings");
        try (ConfigManager manager = new ConfigManager(directory, ignored -> { }, ignored -> { })) {
            assertEquals(MarketDataMode.AUTO, manager.get().marketDataMode());
            manager.update(current -> current.withMarketDataMode(MarketDataMode.LOCAL)
                    .withManualBalance(new BigDecimal("100000"), System.currentTimeMillis())
                    .withMinimumReserve(new BigDecimal("1000"))
                    .withMinimumProfitFloor(new BigDecimal("500"))
                    .withMinimumRoiPercent(new BigDecimal("25")));
            assertEquals(MarketDataMode.LOCAL, manager.get().marketDataMode());
        }
        try (ConfigManager reopened = new ConfigManager(directory, ignored -> { }, ignored -> { })) {
            assertEquals(MarketDataMode.LOCAL, reopened.get().marketDataMode());
            assertTrue(reopened.apiKeyForRequest().isBlank());
        }
        assertTrue(Files.readString(directory.resolve("settings.json")).contains("\"marketDataMode\": \"local\""));
    }

    @Test
    void legacySettingsDefaultToAutoAndUnknownModesWarn() throws Exception {
        Path directory = temporaryDirectory.resolve("legacy-settings");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("settings.json"), "{\"schemaVersion\":1}");
        try (ConfigManager manager = new ConfigManager(directory, ignored -> { }, ignored -> { })) {
            assertEquals(MarketDataMode.AUTO, manager.get().marketDataMode());
        }
        Files.writeString(directory.resolve("settings.json"), "{\"schemaVersion\":1,\"marketDataMode\":\"unknown\"}");
        try (ConfigManager manager = new ConfigManager(directory, ignored -> { }, ignored -> { })) {
            assertEquals(MarketDataMode.AUTO, manager.get().marketDataMode());
            assertTrue(manager.startupWarnings().stream().anyMatch(message -> message.contains("marketDataMode")));
        }
        assertTrue(MarketDataMode.AUTO.usesLocalData(false));
        assertFalse(MarketDataMode.AUTO.usesLocalData(true));
        assertTrue(MarketDataMode.LOCAL.usesLocalData(true));
        assertFalse(MarketDataMode.API.usesLocalData(false));
        assertEquals(MarketDataMode.LOCAL, MarketDataMode.parse("LOCAL"));
    }

    @Test
    void invalidControlCharactersAreRejectedFromCredentials() throws Exception {
        Path configDirectory = temporaryDirectory.resolve("invalid-key");
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve("credentials.json"), "{\"apiKey\":\"abc\\tdef\"}");
        List<String> warnings = new ArrayList<>();
        try (ConfigManager manager = new ConfigManager(configDirectory, warnings::add, ignored -> { })) {
            assertTrue(manager.apiKeyForRequest().isEmpty());
            assertTrue(warnings.stream().anyMatch(message -> message.toLowerCase().contains("invalid format")));
        }
    }

    @Test
    void futureSettingsSchemaIsNotOverwritten() throws Exception {
        Path configDirectory = temporaryDirectory.resolve("future-schema");
        Files.createDirectories(configDirectory);
        Path settings = configDirectory.resolve("settings.json");
        String source = "{\"schemaVersion\":77,\"manualBalance\":\"123456\"}";
        Files.writeString(settings, source);

        try (ConfigManager manager = new ConfigManager(configDirectory, ignored -> { }, ignored -> { })) {
            assertEquals(BigDecimal.ZERO, manager.get().manualBalance());
            assertTrue(manager.startupWarnings().stream().anyMatch(message -> message.contains("newer than this mod")));
        }

        assertEquals(source, Files.readString(settings));
    }

    @Test
    void malformedSettingsAreBackedUpAndSafeDefaultsAreUsed() throws Exception {
        Path configDirectory = temporaryDirectory.resolve("corrupt-settings");
        Files.createDirectories(configDirectory);
        Files.writeString(configDirectory.resolve("settings.json"), "{not-json");

        try (ConfigManager manager = new ConfigManager(configDirectory, ignored -> { }, ignored -> { })) {
            assertEquals(BigDecimal.ZERO, manager.get().manualBalance());
            assertTrue(manager.startupWarnings().stream().anyMatch(message -> message.contains("safe defaults")));
        }

        try (var files = Files.list(configDirectory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("settings.invalid-")));
        }
    }
}
