package dev.ahprofit.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.ahprofit.market.ItemCategory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Loads versioned settings and isolates the API key in a restricted local file. */
public final class ConfigManager implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final BigDecimal MAX_BALANCE = new BigDecimal("1000000000000000");

    private final Path directory;
    private final Path settingsFile;
    private final Path credentialsFile;
    private final Consumer<String> warningLogger;
    private final Consumer<String> errorLogger;
    private final ExecutorService writer;
    private final AtomicReference<AhProfitConfig> config = new AtomicReference<>(AhProfitConfig.defaults());
    private final AtomicReference<String> apiKey = new AtomicReference<>("");
    private final AtomicReference<List<String>> startupWarnings = new AtomicReference<>(List.of());
    private volatile boolean settingsWritable = true;

    public ConfigManager(Path directory, Consumer<String> warningLogger, Consumer<String> errorLogger) {
        this.directory = directory;
        this.settingsFile = directory.resolve("settings.json");
        this.credentialsFile = directory.resolve("credentials.json");
        this.warningLogger = warningLogger == null ? ignored -> { } : warningLogger;
        this.errorLogger = errorLogger == null ? ignored -> { } : errorLogger;
        this.writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "AhProfit-config-writer");
            thread.setDaemon(true);
            return thread;
        });

        List<String> warnings = new ArrayList<>();
        try {
            Files.createDirectories(directory);
        } catch (IOException | SecurityException exception) {
            warnings.add("Could not create the AhProfit config directory; settings will remain session-only.");
            settingsWritable = false;
            logError("Could not create the AhProfit config directory.", exception);
        }

        SettingsLoad loaded = loadSettings();
        config.set(loaded.config());
        settingsWritable &= loaded.writable();
        warnings.addAll(loaded.warnings());
        if (loaded.needsWrite() && settingsWritable) {
            saveSettings(loaded.config());
        }

        CredentialLoad credentials = loadCredentialsFromDisk();
        apiKey.set(credentials.apiKey());
        warnings.addAll(credentials.warnings());
        if (credentials.needsWrite()) {
            saveCredentials("");
        }
        startupWarnings.set(List.copyOf(warnings));
        warnings.forEach(this.warningLogger);
    }

    public AhProfitConfig get() {
        return config.get();
    }

    public List<String> startupWarnings() {
        return startupWarnings.get();
    }

    public Path credentialsPath() {
        return credentialsFile;
    }

    public Path marketInputPath() {
        return directory.resolve("market-input.json");
    }

    public Path localMarketDatabasePath() {
        return directory.resolve("local-market.sqlite");
    }

    /** Live listings recorded from the open auction-house menu; merged with the manual input. */
    public Path observedAuctionPath() {
        return directory.resolve("ah-observed.json");
    }

    /** Used only by the pinned DonutSMP HTTPS transport; never include this value in status or logs. */
    public String apiKeyForRequest() {
        return apiKey.get();
    }

    public AhProfitConfig update(UnaryOperator<AhProfitConfig> update) {
        AhProfitConfig next = config.updateAndGet(current -> update.apply(current));
        if (settingsWritable) {
            saveSettings(next);
        } else {
            warningLogger.accept("Settings are running with safe defaults because the settings file is not writable or has a newer schema.");
        }
        return next;
    }

    /** Reloads only the credentials file; call on a background thread before a manual retry. */
    public boolean reloadCredentials() {
        CredentialLoad loaded = loadCredentialsFromDisk();
        apiKey.set(loaded.apiKey());
        loaded.warnings().forEach(warningLogger);
        if (loaded.needsWrite()) {
            saveCredentials("");
        }
        return !loaded.apiKey().isBlank();
    }

    private SettingsLoad loadSettings() {
        if (!Files.exists(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
            return new SettingsLoad(AhProfitConfig.defaults(), List.of(), true, true);
        }
        if (Files.isSymbolicLink(settingsFile)) {
            return new SettingsLoad(AhProfitConfig.defaults(),
                    List.of("Refusing to read a symbolic-link settings file; replace it with a regular JSON file."), false, false);
        }

        try {
            String content = Files.readString(settingsFile, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("Settings root must be an object.");
            }
            return decodeSettings(parsed.getAsJsonObject());
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            preserveInvalidSettings();
            return new SettingsLoad(AhProfitConfig.defaults(),
                    List.of("Settings JSON was invalid; safe defaults were loaded and the old file was backed up."), true, true);
        }
    }

    private SettingsLoad decodeSettings(JsonObject json) {
        AhProfitConfig defaults = AhProfitConfig.defaults();
        List<String> warnings = new ArrayList<>();
        int schema = integer(json, "schemaVersion", 1, 0, 100, warnings);
        if (schema > 1) {
            warnings.add("Settings schema is newer than this mod; safe defaults are active and the file will not be overwritten.");
            return new SettingsLoad(defaults, warnings, false, false);
        }
        if (schema == 0) {
            warnings.add("Settings schema was missing/legacy; recognized fields were migrated to schema 1.");
        }

        String username = string(json, "username", defaults.username(), warnings, 32);
        String balanceMode = string(json, "balanceMode", "manual", warnings, 16);
        if (!balanceMode.equalsIgnoreCase("manual")) {
            warnings.add("Only manual balance mode is supported; API statistics are not treated as a verified spendable wallet.");
        }

        MarketDataMode marketDataMode;
        try {
            marketDataMode = MarketDataMode.parse(string(json, "marketDataMode", "auto", warnings, 16));
        } catch (IllegalArgumentException exception) {
            warnings.add("Invalid setting 'marketDataMode'; auto mode was used.");
            marketDataMode = MarketDataMode.AUTO;
        }

        BigDecimal balance = decimal(json, "manualBalance", defaults.manualBalance(), BigDecimal.ZERO, MAX_BALANCE, warnings);
        long balanceUpdated = longInteger(json, "manualBalanceUpdatedAtMillis", 0, 0, Long.MAX_VALUE, warnings);
        BigDecimal reservePercent = decimal(json, "reservePercent", defaults.reservePercent(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        BigDecimal minimumReserve = decimal(json, "minimumReserve", defaults.minimumReserve(), BigDecimal.ZERO, MAX_BALANCE, warnings);
        BigDecimal maxListingBalancePercent = decimal(json, "maxListingBalancePercent", defaults.maxListingBalancePercent(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        BigDecimal maxListingDeployablePercent = decimal(json, "maxListingDeployablePercent", defaults.maxListingDeployablePercent(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        BigDecimal maxTotalExposurePercent = decimal(json, "maxTotalExposurePercent", defaults.maxTotalExposurePercent(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        BigDecimal maxCommodityExposurePercent = decimal(json, "maxCommodityExposurePercent", defaults.maxCommodityExposurePercent(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        BigDecimal maxCategoryExposurePercent = decimal(json, "maxCategoryExposurePercent", defaults.maxCategoryExposurePercent(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        int pollInterval = integer(json, "pollIntervalSeconds", defaults.pollIntervalSeconds(), 10, 3_600, warnings);
        BigDecimal minimumRoi = decimal(json, "minimumRoiPercent", defaults.minimumRoiPercent(), BigDecimal.ZERO, new BigDecimal("1000"), warnings);
        BigDecimal minimumDiscount = decimal(json, "minimumDiscountPercent", defaults.minimumDiscountPercent(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        BigDecimal profitFloor = decimal(json, "minimumProfitFloor", defaults.minimumProfitFloor(), BigDecimal.ZERO, MAX_BALANCE, warnings);
        BigDecimal profitBalancePercent = decimal(json, "minimumProfitBalancePercent", defaults.minimumProfitBalancePercent(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        int minimumSales = integer(json, "minimumHistoricalTransactions", defaults.minimumHistoricalTransactions(), 1, 500, warnings);
        int maxDataAge = integer(json, "maxDataAgeSeconds", defaults.maxDataAgeSeconds(), 30, 86_400, warnings);
        BigDecimal feePercent = decimal(json, "assumedSellingFeePercent", defaults.assumedSellingFeePercent(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        BigDecimal riskBuffer = decimal(json, "baseRiskBufferPercent", defaults.baseRiskBufferPercent(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        BigDecimal volatilityMultiplier = decimal(json, "volatilityBufferMultiplier", defaults.volatilityBufferMultiplier(), BigDecimal.ZERO, new BigDecimal("10"), warnings);
        int maxRecommendations = integer(json, "maxRecommendations", defaults.maxRecommendations(), 1, 10, warnings);
        boolean showHud = bool(json, "showHud", defaults.showHud(), warnings);
        boolean alerts = bool(json, "alertsEnabled", defaults.alertsEnabled(), warnings);
        Set<ItemCategory> categories = categories(json.get("enabledCategories"), warnings);
        Set<String> excludedItems = excludedItems(json.get("excludedItems"), warnings);
        BigDecimal minimumConfidence = decimal(json, "minimumConfidence", defaults.minimumConfidence(),
                BigDecimal.ZERO, BigDecimal.ONE, warnings);
        int maximumDaysToClear = integer(json, "maximumDaysToClear", defaults.maximumDaysToClear(), 1, 90, warnings);
        boolean automationEnabled = bool(json, "automationEnabled", defaults.automationEnabled(), warnings);
        boolean automationDryRun = bool(json, "automationDryRun", defaults.automationDryRun(), warnings);
        BigDecimal automationMaxSpendPerAction = decimal(json, "automationMaxSpendPerAction",
                defaults.automationMaxSpendPerAction(), BigDecimal.ZERO, MAX_BALANCE, warnings);
        BigDecimal automationMaxSpendPerHour = decimal(json, "automationMaxSpendPerHour",
                defaults.automationMaxSpendPerHour(), BigDecimal.ZERO, MAX_BALANCE, warnings);
        int automationMaxActionsPerHour = integer(json, "automationMaxActionsPerHour",
                defaults.automationMaxActionsPerHour(), 1, 500, warnings);
        int automationCooldownSeconds = integer(json, "automationCooldownSeconds",
                defaults.automationCooldownSeconds(), 3, 3_600, warnings);
        BigDecimal automationMinimumScore = decimal(json, "automationMinimumScore",
                defaults.automationMinimumScore(), BigDecimal.ZERO, new BigDecimal("100"), warnings);
        int automationSellSlot = integer(json, "automationSellSlot", defaults.automationSellSlot(), -1, 255, warnings);
        int automationConfirmSlot = integer(json, "automationConfirmSlot", defaults.automationConfirmSlot(), -1, 255, warnings);
        boolean askOnlyAnalysis = bool(json, "askOnlyAnalysis", defaults.askOnlyAnalysis(), warnings);
        int askMinimumListings = integer(json, "askMinimumListings", defaults.askMinimumListings(), 2, 50, warnings);
        BigDecimal askMinimumSpreadPercent = decimal(json, "askMinimumSpreadPercent",
                defaults.askMinimumSpreadPercent(), BigDecimal.ZERO, new BigDecimal("99"), warnings);
        BigDecimal askUndercutPercent = decimal(json, "askUndercutPercent", defaults.askUndercutPercent(),
                BigDecimal.ZERO, new BigDecimal("50"), warnings);

        AhProfitConfig result = new AhProfitConfig(
                1, username, "manual", balance, balanceUpdated, reservePercent, minimumReserve,
                maxListingBalancePercent, maxListingDeployablePercent, maxTotalExposurePercent,
                maxCommodityExposurePercent, maxCategoryExposurePercent, pollInterval, minimumRoi,
                minimumDiscount, profitFloor, profitBalancePercent, minimumSales, maxDataAge,
                feePercent, riskBuffer, volatilityMultiplier, maxRecommendations, showHud, alerts,
                categories, excludedItems, marketDataMode, minimumConfidence, maximumDaysToClear,
                automationEnabled, automationDryRun, automationMaxSpendPerAction, automationMaxSpendPerHour,
                automationMaxActionsPerHour, automationCooldownSeconds, automationMinimumScore, automationSellSlot, automationConfirmSlot,
                askOnlyAnalysis, askMinimumListings, askMinimumSpreadPercent, askUndercutPercent
        );
        return new SettingsLoad(result, warnings, true, schema == 0);
    }

    private CredentialLoad loadCredentialsFromDisk() {
        List<String> warnings = new ArrayList<>();
        if (!Files.exists(credentialsFile, LinkOption.NOFOLLOW_LINKS)) {
            return new CredentialLoad("", List.of("No API key is configured. Auto mode can use local market-input.json; live API mode needs a key from /api."), true);
        }
        if (Files.isSymbolicLink(credentialsFile)) {
            return new CredentialLoad("", List.of("Refusing to read a symbolic-link credentials file."), false);
        }

        try {
            restrictPermissions(credentialsFile);
            String content = Files.readString(credentialsFile, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                return new CredentialLoad("", List.of("Credentials JSON must be an object with an apiKey string."), false);
            }
            JsonElement keyElement = parsed.getAsJsonObject().get("apiKey");
            if (keyElement == null || keyElement.isJsonNull()) {
                return new CredentialLoad("", List.of("No DonutSMP API key is configured; local data mode remains available."), false);
            }
            if (!keyElement.isJsonPrimitive() || !keyElement.getAsJsonPrimitive().isString()) {
                return new CredentialLoad("", List.of("The credentials apiKey field must be a string; no key was loaded."), false);
            }
            String key = keyElement.getAsString().trim();
            if (key.length() > 4_096 || key.chars().anyMatch(Character::isISOControl)) {
                return new CredentialLoad("", List.of("The credentials apiKey field has an invalid format; no key was loaded."), false);
            }
            return new CredentialLoad(key, warnings, false);
        } catch (IOException | JsonParseException | IllegalStateException | SecurityException exception) {
            return new CredentialLoad("", List.of("Could not read credentials.json; API requests are paused, but local data mode remains available."), false);
        }
    }

    private void saveSettings(AhProfitConfig value) {
        String json = encodeSettings(value);
        submitWrite(settingsFile, json, false, "Could not save AhProfit settings.");
    }

    private void saveCredentials(String key) {
        JsonObject json = new JsonObject();
        json.addProperty("apiKey", key == null ? "" : key);
        submitWrite(credentialsFile, GSON.toJson(json) + System.lineSeparator(), true,
                "Could not save the private credentials file.");
    }

    private void submitWrite(Path file, String content, boolean privateFile, String safeFailureMessage) {
        try {
            writer.execute(() -> {
                try {
                    writeAtomically(file, content, privateFile);
                } catch (IOException | SecurityException exception) {
                    logError(safeFailureMessage, exception);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            warningLogger.accept("Configuration save was skipped because AhProfit is shutting down.");
        }
    }

    private void writeAtomically(Path file, String content, boolean privateFile) throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Refusing to replace a symbolic link.");
        }
        Path temporary = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        try {
            if (privateFile) {
                restrictPermissions(temporary);
            }
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (privateFile) {
                restrictPermissions(temporary);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            if (privateFile) {
                restrictPermissions(file);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void restrictPermissions(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException exception) {
            warningLogger.accept("This filesystem does not support POSIX private-file permissions; protect the Minecraft config directory with your OS account.");
        }
    }

    private void preserveInvalidSettings() {
        try {
            if (Files.exists(settingsFile, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(settingsFile)) {
                Path backup = directory.resolve("settings.invalid-" + Instant.now().toEpochMilli() + ".json");
                Files.copy(settingsFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
        } catch (IOException | SecurityException exception) {
            logError("Could not back up invalid settings.json.", exception);
        }
    }

    private static String encodeSettings(AhProfitConfig config) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("username", config.username());
        json.addProperty("balanceMode", "manual");
        json.addProperty("marketDataMode", config.marketDataMode().configValue());
        json.addProperty("manualBalance", config.manualBalance().toPlainString());
        json.addProperty("manualBalanceUpdatedAtMillis", config.manualBalanceUpdatedAtMillis());
        json.addProperty("reservePercent", config.reservePercent().toPlainString());
        json.addProperty("minimumReserve", config.minimumReserve().toPlainString());
        json.addProperty("maxListingBalancePercent", config.maxListingBalancePercent().toPlainString());
        json.addProperty("maxListingDeployablePercent", config.maxListingDeployablePercent().toPlainString());
        json.addProperty("maxTotalExposurePercent", config.maxTotalExposurePercent().toPlainString());
        json.addProperty("maxCommodityExposurePercent", config.maxCommodityExposurePercent().toPlainString());
        json.addProperty("maxCategoryExposurePercent", config.maxCategoryExposurePercent().toPlainString());
        json.addProperty("pollIntervalSeconds", config.pollIntervalSeconds());
        json.addProperty("minimumRoiPercent", config.minimumRoiPercent().toPlainString());
        json.addProperty("minimumDiscountPercent", config.minimumDiscountPercent().toPlainString());
        json.addProperty("minimumProfitFloor", config.minimumProfitFloor().toPlainString());
        json.addProperty("minimumProfitBalancePercent", config.minimumProfitBalancePercent().toPlainString());
        json.addProperty("minimumHistoricalTransactions", config.minimumHistoricalTransactions());
        json.addProperty("maxDataAgeSeconds", config.maxDataAgeSeconds());
        json.addProperty("assumedSellingFeePercent", config.assumedSellingFeePercent().toPlainString());
        json.addProperty("baseRiskBufferPercent", config.baseRiskBufferPercent().toPlainString());
        json.addProperty("volatilityBufferMultiplier", config.volatilityBufferMultiplier().toPlainString());
        json.addProperty("maxRecommendations", config.maxRecommendations());
        json.addProperty("showHud", config.showHud());
        json.addProperty("alertsEnabled", config.alertsEnabled());

        JsonArray categories = new JsonArray();
        config.enabledCategories().stream().map(Enum::name).sorted().forEach(categories::add);
        json.add("enabledCategories", categories);
        JsonArray excludedItems = new JsonArray();
        config.excludedItems().stream().sorted().forEach(excludedItems::add);
        json.add("excludedItems", excludedItems);
        json.addProperty("minimumConfidence", config.minimumConfidence().toPlainString());
        json.addProperty("maximumDaysToClear", config.maximumDaysToClear());
        json.addProperty("automationEnabled", config.automationEnabled());
        json.addProperty("automationDryRun", config.automationDryRun());
        json.addProperty("automationMaxSpendPerAction", config.automationMaxSpendPerAction().toPlainString());
        json.addProperty("automationMaxSpendPerHour", config.automationMaxSpendPerHour().toPlainString());
        json.addProperty("automationMaxActionsPerHour", config.automationMaxActionsPerHour());
        json.addProperty("automationCooldownSeconds", config.automationCooldownSeconds());
        json.addProperty("automationMinimumScore", config.automationMinimumScore().toPlainString());
        json.addProperty("automationSellSlot", config.automationSellSlot());
        json.addProperty("automationConfirmSlot", config.automationConfirmSlot());
        json.addProperty("askOnlyAnalysis", config.askOnlyAnalysis());
        json.addProperty("askMinimumListings", config.askMinimumListings());
        json.addProperty("askMinimumSpreadPercent", config.askMinimumSpreadPercent().toPlainString());
        json.addProperty("askUndercutPercent", config.askUndercutPercent().toPlainString());
        return GSON.toJson(json) + System.lineSeparator();
    }

    private static String string(JsonObject json, String key, String fallback, List<String> warnings, int maxLength) {
        JsonElement element = json.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            warnings.add("Invalid setting '" + key + "'; default used.");
            return fallback;
        }
        String value = element.getAsString().trim();
        if (value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            warnings.add("Invalid setting '" + key + "'; default used.");
            return fallback;
        }
        return value;
    }

    private static BigDecimal decimal(JsonObject json, String key, BigDecimal fallback,
                                      BigDecimal minimum, BigDecimal maximum, List<String> warnings) {
        JsonElement element = json.get(key);
        if (element == null) {
            return fallback;
        }
        try {
            if (!element.isJsonPrimitive() || (!element.getAsJsonPrimitive().isNumber() && !element.getAsJsonPrimitive().isString())) {
                throw new NumberFormatException();
            }
            BigDecimal value = new BigDecimal(element.getAsString());
            if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
                throw new NumberFormatException();
            }
            return value.stripTrailingZeros();
        } catch (NumberFormatException exception) {
            warnings.add("Invalid setting '" + key + "'; safe default used.");
            return fallback;
        }
    }

    private static int integer(JsonObject json, String key, int fallback, int minimum, int maximum, List<String> warnings) {
        long value = longInteger(json, key, fallback, minimum, maximum, warnings);
        return Math.toIntExact(value);
    }

    private static long longInteger(JsonObject json, String key, long fallback, long minimum, long maximum, List<String> warnings) {
        JsonElement element = json.get(key);
        if (element == null) {
            return fallback;
        }
        try {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new NumberFormatException();
            }
            long value = new BigDecimal(element.getAsString()).longValueExact();
            if (value < minimum || value > maximum) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException | ArithmeticException exception) {
            warnings.add("Invalid setting '" + key + "'; safe default used.");
            return fallback;
        }
    }

    private static boolean bool(JsonObject json, String key, boolean fallback, List<String> warnings) {
        JsonElement element = json.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            warnings.add("Invalid setting '" + key + "'; safe default used.");
            return fallback;
        }
        return element.getAsBoolean();
    }

    private static Set<ItemCategory> categories(JsonElement element, List<String> warnings) {
        if (element == null) {
            return AhProfitConfig.defaults().enabledCategories();
        }
        if (!element.isJsonArray()) {
            warnings.add("Invalid 'enabledCategories'; all categories were enabled safely.");
            return AhProfitConfig.defaults().enabledCategories();
        }
        Set<ItemCategory> result = EnumSet.noneOf(ItemCategory.class);
        for (JsonElement value : element.getAsJsonArray()) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                warnings.add("Ignored an invalid item category filter.");
                continue;
            }
            try {
                result.add(ItemCategory.valueOf(value.getAsString().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                warnings.add("Ignored an unknown item category filter.");
            }
        }
        return result.isEmpty() ? AhProfitConfig.defaults().enabledCategories() : result;
    }

    private static Set<String> excludedItems(JsonElement element, List<String> warnings) {
        if (element == null) {
            return Set.of();
        }
        if (!element.isJsonArray()) {
            warnings.add("Invalid 'excludedItems'; no item exclusions were loaded.");
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                warnings.add("Ignored an invalid excluded item ID.");
                continue;
            }
            String id = value.getAsString().toLowerCase(Locale.ROOT).trim();
            if (ITEM_ID.matcher(id).matches()) {
                result.add(id);
            } else {
                warnings.add("Ignored an invalid excluded item ID.");
            }
        }
        return result;
    }

    private void logError(String message, Throwable exception) {
        String detail = exception.getMessage();
        errorLogger.accept(detail == null || detail.isBlank() ? message : message + " (" + detail + ")");
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record SettingsLoad(AhProfitConfig config, List<String> warnings, boolean writable, boolean needsWrite) {
    }

    private record CredentialLoad(String apiKey, List<String> warnings, boolean needsWrite) {
    }
}
