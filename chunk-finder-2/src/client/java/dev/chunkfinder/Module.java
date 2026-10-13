package dev.chunkfinder;

public abstract class Module {
    private final String name;
    private final ModuleCategory category;
    private boolean enabled;
    protected Module(String name, ModuleCategory category, boolean enabled) {
        this.name = name;
        this.category = category;
        this.enabled = enabled;
    }
    public String name() { return name; }
    public ModuleCategory category() { return category; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}