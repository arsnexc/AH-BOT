package dev.chunkfinder;

public enum ModuleCategory {
    VISUALS("Visuals");
    private final String displayName;
    ModuleCategory(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
}