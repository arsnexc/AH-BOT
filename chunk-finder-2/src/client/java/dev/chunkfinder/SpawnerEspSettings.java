package dev.chunkfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SpawnerEspSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("spawner_esp.json");

    private boolean enabled;
    private boolean traces = true;
    private boolean blockHighlight = true;
    private int searchRadius = 6;

    static SpawnerEspSettings load() {
        SpawnerEspSettings result = new SpawnerEspSettings();
        if (!Files.exists(FILE)) return result;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            if (json.has("enabled")) result.enabled = json.get("enabled").getAsBoolean();
            if (json.has("traces")) result.traces = json.get("traces").getAsBoolean();
            if (json.has("blockHighlight")) result.blockHighlight = json.get("blockHighlight").getAsBoolean();
            if (json.has("searchRadius")) result.searchRadius = clamp(json.get("searchRadius").getAsInt(), 2, 12);
        } catch (RuntimeException | IOException exception) {
            ChunkFinderClient.LOGGER.warn("Could not read Item ESP settings; using defaults", exception);
        }
        return result;
    }

    void save() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("traces", traces);
        json.addProperty("blockHighlight", blockHighlight);
        json.addProperty("searchRadius", searchRadius);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(json));
        } catch (IOException exception) {
            ChunkFinderClient.LOGGER.warn("Could not save Item ESP settings", exception);
        }
    }

    boolean enabled() { return enabled; }
    boolean traces() { return traces; }
    boolean blockHighlight() { return blockHighlight; }
    int searchRadius() { return searchRadius; }
    void setEnabled(boolean value) { enabled = value; save(); }
    void setTraces(boolean value) { traces = value; save(); }
    void setBlockHighlight(boolean value) { blockHighlight = value; save(); }
    void setSearchRadius(int value) { searchRadius = clamp(value, 2, 12); save(); }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
