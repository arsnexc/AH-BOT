package dev.chunkfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ChunkFinderSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("chunkfinder.json");
    private boolean enabled;
    private int sensitivity = 5;
    private int simulationDistance = 6;

    static ChunkFinderSettings load() {
        ChunkFinderSettings result = new ChunkFinderSettings();
        if (!Files.exists(FILE)) return result;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
            if (json.has("enabled")) result.enabled = json.get("enabled").getAsBoolean();
            if (json.has("sensitivity")) result.sensitivity = clamp(json.get("sensitivity").getAsInt(), 1, 10);
            if (json.has("simulationDistance")) result.simulationDistance = clamp(json.get("simulationDistance").getAsInt(), 2, 12);
        } catch (RuntimeException | IOException exception) {
            ChunkFinderClient.LOGGER.warn("Could not read Chunk Finder settings; using defaults", exception);
        }
        return result;
    }

    void save() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("sensitivity", sensitivity);
        json.addProperty("simulationDistance", simulationDistance);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(json));
        } catch (IOException exception) {
            ChunkFinderClient.LOGGER.warn("Could not save Chunk Finder settings", exception);
        }
    }
    boolean enabled() { return enabled; }
    void setEnabled(boolean value) { enabled = value; save(); }
    int sensitivity() { return sensitivity; }
    void setSensitivity(int value) { sensitivity = clamp(value, 1, 10); save(); }
    int simulationDistance() { return simulationDistance; }
    void setSimulationDistance(int value) { simulationDistance = clamp(value, 2, 12); save(); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}