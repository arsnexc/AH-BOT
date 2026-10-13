package dev.chunkfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Paginated, scale-aware controls; no settings are placed beyond the visible panel. */
public final class ModuleSettingsScreen extends Screen {
    private static final int BG = 0xF20B1019;
    private static final int PANEL = 0xFF151D2A;
    private static final int CARD = 0xFF1C2634;
    private static final int HOVER = 0xFF273648;
    private static final int WHITE = 0xFFF0F5FF;
    private static final int MUTED = 0xFF9EADBF;
    private static final int CYAN = 0xFF77D4F7;
    private static final int GREEN = 0xFF7DE3BE;
    private final ModuleManager modules;
    private Module selected;
    private int modulePage;
    private int settingPage;
    private String notice = "";

    public ModuleSettingsScreen(ModuleManager modules) {
        super(Component.literal("Chunk Finder 2 · Module Studio"));
        this.modules = modules;
        this.selected = modules.modules().getFirst();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.renderBackground(g, mouseX, mouseY, delta);
        g.fill(0, 0, width, height, 0x9C060A11);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        Layout ui = layout();
        int divider = ui.x + ui.listWidth;
        g.fill(ui.x - 2, ui.y - 2, ui.x + ui.w + 2, ui.y + ui.h + 2, 0xFF33485D);
        g.fill(ui.x, ui.y, ui.x + ui.w, ui.y + ui.h, BG);
        g.fill(ui.x, ui.y, ui.x + ui.w, ui.y + 3, CYAN);
        g.fill(ui.x, ui.y + 49, ui.x + ui.w, ui.y + 50, 0xFF344152);
        g.fill(ui.x, ui.y + 50, divider, ui.y + ui.h - 34, PANEL);
        g.fill(divider, ui.y + 50, divider + 1, ui.y + ui.h - 34, 0xFF344152);
        g.fill(ui.x, ui.y + ui.h - 34, ui.x + ui.w, ui.y + ui.h - 33, 0xFF344152);
        g.drawString(font, Component.literal("◈  CHUNK FINDER"), ui.x + 16, ui.y + 14, WHITE, true);
        g.drawString(font, Component.literal("MODULE STUDIO  /  2.4"), ui.x + 16, ui.y + 29, MUTED, false);
        g.drawString(font, Component.literal(modules.modules().size() + " VISUAL MODULES"),
                divider + 16, ui.y + 21, CYAN, true);
        g.fill(ui.x + ui.w - 32, ui.y + 12, ui.x + ui.w - 10, ui.y + 35,
                inside(mouseX, mouseY, ui.x + ui.w - 32, ui.y + 12, 22, 23) ? HOVER : CARD);
        g.drawCenteredString(font, Component.literal("×"), ui.x + ui.w - 21, ui.y + 19, WHITE);
        drawModuleList(g, ui, mouseX, mouseY);
        drawSettings(g, ui, mouseX, mouseY);
        g.drawString(font, Component.literal("ESC  CLOSE"), ui.x + 16, ui.y + ui.h - 23, MUTED, false);
        g.drawString(font, Component.literal(fit(notice.isEmpty() ? "Local, client-visible data only" : notice,
                ui.w - 140)), divider + 16, ui.y + ui.h - 23,
                notice.isEmpty() ? MUTED : 0xFFFFCC8F, false);
    }

    private void drawModuleList(GuiGraphics g, Layout ui, int mx, int my) {
        int x = ui.x + 12, w = ui.listWidth - 24;
        g.drawString(font, Component.literal("LIBRARY  /  VISUALS"), x, ui.y + 64, MUTED, true);
        int count = modulePageSize(ui);
        int pages = (modules.modules().size() + count - 1) / count;
        modulePage = Math.min(modulePage, pages - 1);
        for (int i = 0; i < count; i++) {
            int index = modulePage * count + i;
            if (index >= modules.modules().size()) break;
            Module module = modules.modules().get(index);
            int y = ui.y + 86 + i * 57;
            boolean active = module == selected;
            boolean hovered = inside(mx, my, x, y, w, 50);
            g.fill(x, y, x + w, y + 50, active ? 0xFF253B50 : hovered ? HOVER : CARD);
            if (active) g.fill(x, y, x + 3, y + 50, CYAN);
            g.drawString(font, Component.literal(fit(module.name(), w - 20)), x + 10, y + 9, WHITE, true);
            String detail = module.isEnabled() ? "● ACTIVE" : "○ OFFLINE";
            g.drawString(font, Component.literal(detail), x + 10, y + 29,
                    module.isEnabled() ? GREEN : MUTED, false);
        }
        int footer = ui.y + ui.h - 60;
        nav(g, x, footer, 34, "‹", modulePage > 0, mx, my);
        g.drawCenteredString(font, Component.literal((modulePage + 1) + " / " + pages),
                x + w / 2, footer + 7, MUTED);
        nav(g, x + w - 34, footer, 34, "›", modulePage + 1 < pages, mx, my);
    }

    private void drawSettings(GuiGraphics g, Layout ui, int mx, int my) {
        int x = ui.x + ui.listWidth + 16;
        int w = ui.w - ui.listWidth - 32;
        g.drawString(font, Component.literal(fit(selected.name().toUpperCase(), w)),
                x, ui.y + 62, WHITE, true);
        g.drawString(font, Component.literal(fit(description(selected), w)),
                x, ui.y + 77, MUTED, false);
        List<Control> controls = controls();
        int pageSize = settingsPageSize(ui);
        int pages = Math.max(1, (controls.size() + pageSize - 1) / pageSize);
        settingPage = Math.min(settingPage, pages - 1);
        for (int i = 0; i < pageSize; i++) {
            int index = settingPage * pageSize + i;
            if (index >= controls.size()) break;
            Control control = controls.get(index);
            int y = ui.y + 99 + i * 56;
            g.fill(x, y, x + w, y + 48, inside(mx, my, x, y, w, 48) ? HOVER : CARD);
            g.drawString(font, Component.literal(fit(control.label, w - 92)), x + 11, y + 8, WHITE, true);
            g.drawString(font, Component.literal(fit(control.hint, w - 22)), x + 11, y + 29, MUTED, false);
            if (control.kind == Kind.SLIDER) {
                int barX = x + w - 87, barWidth = 73;
                g.fill(barX, y + 13, barX + barWidth, y + 18, 0xFF3B4A5C);
                int fill = Math.round((control.value - control.min) * barWidth / (float)(control.max - control.min));
                g.fill(barX, y + 13, barX + fill, y + 18, CYAN);
                g.drawCenteredString(font, Component.literal(Integer.toString(control.value)),
                        barX + barWidth / 2, y + 26, WHITE);
            } else {
                String action = control.kind == Kind.TOGGLE ? (control.value == 1 ? "ON" : "OFF") : "OPEN ›";
                int bx = x + w - 76;
                g.fill(bx, y + 9, x + w - 10, y + 30,
                        control.kind == Kind.TOGGLE && control.value == 1 ? 0xFF257F70 : 0xFF34475A);
                g.drawCenteredString(font, Component.literal(action), bx + 33, y + 16, WHITE);
            }
        }
        int footer = ui.y + ui.h - 60;
        nav(g, x, footer, 34, "‹", settingPage > 0, mx, my);
        g.drawCenteredString(font, Component.literal(fit(status(selected), w - 92)),
                x + w / 2, footer + 7, GREEN);
        nav(g, x + w - 34, footer, 34, "›", settingPage + 1 < pages, mx, my);
    }

    private List<Control> controls() {
        List<Control> list = new ArrayList<>();
        Minecraft client = Minecraft.getInstance();
        if (selected instanceof FreecamModule freecam) {
            list.add(toggle("Freecam", "Detach camera; press G to toggle", freecam.isEnabled(), () -> freecam.toggle(client)));
            list.add(slider("Movement speed", "Hold Sprint for faster movement", freecam.speed(), 1, 10, freecam::setSpeed));
        } else {
            list.add(toggle("Module enabled", "Active only in a loaded world", selected.isEnabled(),
                    () -> selected.setEnabled(!selected.isEnabled())));
        }
        if (selected instanceof ChunkFinderModule chunk) {
            list.add(slider("Sensitivity", "Higher values accept weaker clues", chunk.sensitivity(), 1, 10,
                    value -> chunk.adjustSensitivity(value - chunk.sensitivity())));
            list.add(slider("Search radius", "Nearby chunks; does not load new ones", chunk.simulationDistance(), 2, 12,
                    value -> chunk.adjustSimulationDistance(value - chunk.simulationDistance())));
        } else if (selected instanceof AncientDebrisFinderModule debris) {
            list.add(slider("Search radius", "Only loaded Nether chunks", debris.searchRadius(), 2, 12, debris::setSearchRadius));
            list.add(toggle("Trace lines", "Connect your view to each block", debris.tracesEnabled(),
                    () -> debris.setTracesEnabled(!debris.tracesEnabled())));
            list.add(toggle("Outlines", "Highlight debris through terrain", debris.blockHighlightEnabled(),
                    () -> debris.setBlockHighlightEnabled(!debris.blockHighlightEnabled())));
            list.add(action("Rescan loaded chunks", "Refresh previously scanned blocks", debris::requestRescan));
        } else if (selected instanceof ItemEspModule esp) {
            list.add(action("Block: " + esp.targetBlockName(), "Open searchable block picker",
                    () -> client.setScreen(new BlockPickerScreen(this, esp))));
            list.add(slider("Search radius", "Only loaded chunks", esp.searchRadius(), 2, 12, esp::setSearchRadius));
            list.add(toggle("Trace lines", "Connect view to detected blocks", esp.tracesEnabled(),
                    () -> esp.setTracesEnabled(!esp.tracesEnabled())));
            list.add(toggle("Outlines", "Highlight blocks through terrain", esp.blockHighlightEnabled(),
                    () -> esp.setBlockHighlightEnabled(!esp.blockHighlightEnabled())));
            list.add(action("Rescan loaded chunks", "Refresh previously scanned blocks", esp::requestRescan));
        } else if (selected instanceof StorageEspModule storage) {
            list.add(slider("Search radius", "Only loaded chunks", storage.searchRadius(), 2, 12, storage::setSearchRadius));
            list.add(toggle("Trace lines", "Connect view to storage blocks", storage.tracesEnabled(),
                    () -> storage.setTracesEnabled(!storage.tracesEnabled())));
            list.add(toggle("Outlines", "Highlight storage through terrain", storage.blockHighlightEnabled(),
                    () -> storage.setBlockHighlightEnabled(!storage.blockHighlightEnabled())));
            list.add(action("Rescan loaded chunks", "Refresh previously scanned blocks", storage::requestRescan));
        } else if (selected instanceof SpawnerEspModule spawners) {
            list.add(slider("Search radius", "Mob and trial spawners in loaded chunks", spawners.searchRadius(), 2, 12,
                    spawners::setSearchRadius));
            list.add(toggle("Trace lines", "Connect view to detected spawners", spawners.tracesEnabled(),
                    () -> spawners.setTracesEnabled(!spawners.tracesEnabled())));
            list.add(toggle("Outlines", "Highlight spawners through terrain", spawners.blockHighlightEnabled(),
                    () -> spawners.setBlockHighlightEnabled(!spawners.blockHighlightEnabled())));
            list.add(action("Rescan loaded chunks", "Refresh previously scanned blocks", spawners::requestRescan));
        } else if (selected instanceof EntityEspModule entities) {
            list.add(slider("Max distance", "Only client-visible loaded entities", entities.range(), 16, 128,
                    entities::setRange));
            String label = entities.mode() == EntityEspModule.Mode.LIVING ? "Include players" : "Show stack counts";
            String hint = entities.mode() == EntityEspModule.Mode.LIVING
                    ? "Highlight players as well as mobs" : "List item stack sizes in the HUD";
            list.add(toggle(label, hint, entities.option(), () -> entities.setOption(!entities.option())));
        } else if (selected instanceof TrajectoriesModule trajectories) {
            list.add(slider("Prediction steps", "Approximate flight; not a guarantee", trajectories.steps(), 12, 72,
                    trajectories::setSteps));
            list.add(toggle("Dropped items", "Include moving item entities", trajectories.showItems(),
                    () -> trajectories.setShowItems(!trajectories.showItems())));
        } else if (selected instanceof TargetRendererModule target) {
            list.add(slider("Max distance", "Vanilla crosshair hit still determines target", target.range(), 8, 64,
                    target::setRange));
            list.add(toggle("Highlight blocks", "Entities are always highlighted", target.showBlocks(),
                    () -> target.setShowBlocks(!target.showBlocks())));
        }
        return list;
    }

    private static String description(Module module) {
        if (module instanceof ChunkFinderModule) return "Geodes and possible activity clues";
        if (module instanceof AncientDebrisFinderModule) return "Locate visible Nether debris in loaded chunks";
        if (module instanceof ItemEspModule) return "Highlight a selected block through walls";
        if (module instanceof StorageEspModule) return "Chests, barrels, shulkers and other storage";
        if (module instanceof SpawnerEspModule) return "Mob and trial spawners in loaded chunks";
        if (module instanceof EntityEspModule e) return e.mode() == EntityEspModule.Mode.LIVING
                ? "Players and mobs sent to your client" : "Dropped item entities sent to your client";
        if (module instanceof TrajectoriesModule) return "Preview moving projectile and item paths";
        if (module instanceof TargetRendererModule) return "Outline your current crosshair target";
        return "Explore without moving your player";
    }

    private static String status(Module module) {
        if (module instanceof AncientDebrisFinderModule d) return d.detectedCount() + " debris  ·  " + d.scanStatus();
        if (module instanceof ItemEspModule e) return e.detectedCount() + " blocks  ·  " + e.scanStatus();
        if (module instanceof StorageEspModule e) return e.detectedCount() + " storage  ·  " + e.scanStatus();
        if (module instanceof SpawnerEspModule e) return e.detectedCount() + " spawners  ·  " + e.scanStatus();
        return module.isEnabled() ? "ACTIVE  ·  client-side" : "READY  ·  offline";
    }

    private static Control toggle(String label, String hint, boolean enabled, Runnable click) {
        return new Control(label, hint, Kind.TOGGLE, enabled ? 1 : 0, 0, 1, click, null);
    }
    private static Control slider(String label, String hint, int value, int min, int max, IntConsumer update) {
        return new Control(label, hint, Kind.SLIDER, value, min, max, null, update);
    }
    private static Control action(String label, String hint, Runnable click) {
        return new Control(label, hint, Kind.ACTION, 0, 0, 0, click, null);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        double mx = event.x(), my = event.y();
        Layout ui = layout();
        if (inside(mx, my, ui.x + ui.w - 32, ui.y + 12, 22, 23)) { onClose(); return true; }
        int count = modulePageSize(ui), pages = (modules.modules().size() + count - 1) / count;
        int listX = ui.x + 12, listW = ui.listWidth - 24, footer = ui.y + ui.h - 60;
        for (int i = 0; i < count; i++) {
            int index = modulePage * count + i;
            if (index < modules.modules().size() && inside(mx, my, listX, ui.y + 86 + i * 57, listW, 50)) {
                selected = modules.modules().get(index);
                settingPage = 0;
                notice = "";
                return true;
            }
        }
        if (inside(mx, my, listX, footer, 34, 24) && modulePage > 0) { modulePage--; return true; }
        if (inside(mx, my, listX + listW - 34, footer, 34, 24) && modulePage + 1 < pages) {
            modulePage++; return true;
        }
        int x = ui.x + ui.listWidth + 16, w = ui.w - ui.listWidth - 32;
        List<Control> controls = controls();
        int size = settingsPageSize(ui), settingsPages = Math.max(1, (controls.size() + size - 1) / size);
        for (int i = 0; i < size; i++) {
            int index = settingPage * size + i;
            int rowY = ui.y + 99 + i * 56;
            if (index >= controls.size() || !inside(mx, my, x, rowY, w, 48)) continue;
            Control c = controls.get(index);
            if (c.kind == Kind.SLIDER) {
                double fraction = Math.max(0.0, Math.min(1.0, (mx - (x + w - 87)) / 73.0));
                c.change.accept(c.min + (int) Math.round(fraction * (c.max - c.min)));
            } else c.click.run();
            notice = "Saved locally";
            return true;
        }
        if (inside(mx, my, x, footer, 34, 24) && settingPage > 0) { settingPage--; return true; }
        if (inside(mx, my, x + w - 34, footer, 34, 24) && settingPage + 1 < settingsPages) {
            settingPage++; return true;
        }
        return inside(mx, my, ui.x, ui.y, ui.w, ui.h) || super.mouseClicked(event, doubleClick);
    }

    private void nav(GuiGraphics g, int x, int y, int w, String text, boolean enabled, int mx, int my) {
        g.fill(x, y, x + w, y + 24,
                enabled && inside(mx, my, x, y, w, 24) ? HOVER : CARD);
        g.drawCenteredString(font, Component.literal(text), x + w / 2, y + 7, enabled ? WHITE : 0xFF5C6A78);
    }
    private String fit(String text, int max) {
        if (max <= 0) return "";
        if (font.width(text) <= max) return text;
        return font.substrByWidth(Component.literal(text), Math.max(1, max - font.width("…"))).getString() + "…";
    }
    private Layout layout() {
        int w = Math.min(850, Math.max(280, width - 20));
        int h = Math.min(530, Math.max(190, height - 20));
        int listW = Math.max(132, Math.min(240, w / 3));
        return new Layout((width - w) / 2, (height - h) / 2, w, h, listW);
    }
    private static int modulePageSize(Layout ui) { return Math.max(1, Math.min(7, (ui.h - 157) / 57)); }
    private static int settingsPageSize(Layout ui) { return Math.max(1, (ui.h - 164) / 56); }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
    @Override public void onClose() { minecraft.setScreen(null); }
    private enum Kind { TOGGLE, SLIDER, ACTION }
    private record Control(String label, String hint, Kind kind, int value, int min, int max,
                           Runnable click, IntConsumer change) { }
    private record Layout(int x, int y, int w, int h, int listWidth) { }
}
