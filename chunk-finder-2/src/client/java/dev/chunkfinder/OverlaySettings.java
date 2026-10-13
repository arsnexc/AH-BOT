package dev.chunkfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small, independent settings file for each lightweight visual overlay. */
final class OverlaySettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final int min;
    private final int max;
    private boolean enabled;
    private boolean option = true;
    private int range;

    OverlaySettings(String fileName, int defaultRange, int min, int max) {
        this.file = FabricLoader.getInstance().getConfigDir().resolve(fileName);
        this.min = min;
        this.max = max;
        this.range = defaultRange;
        if (!Files.exists(file)) return;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
            if (json.has("option")) option = json.get("option").getAsBoolean();
            if (json.has("range")) range = clamp(json.get("range").getAsInt());
        } catch (RuntimeException | IOException exception) {
            ChunkFinderClient.LOGGER.warn("Could not read {} settings; using defaults", fileName, exception);
        }
    }

    boolean enabled() { return enabled; }
    boolean option() { return option; }
    int range() { return range; }
    void setEnabled(boolean value) { enabled = value; save(); }
    void setOption(boolean value) { option = value; save(); }
    void setRange(int value) { range = clamp(value); save(); }
    private int clamp(int value) { return Math.max(min, Math.min(max, value)); }

    private void save() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("option", option);
        json.addProperty("range", range);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(json));
        } catch (IOException exception) {
            ChunkFinderClient.LOGGER.warn("Could not save {} settings", file.getFileName(), exception);
        }
    }
}
