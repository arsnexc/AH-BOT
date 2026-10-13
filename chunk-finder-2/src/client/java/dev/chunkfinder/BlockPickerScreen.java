package dev.chunkfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class BlockPickerScreen extends Screen {
    private static final int PANEL = 0xF20E1118;
    private static final int ROW = 0xFF1C212D;
    private static final int ROW_HOVER = 0xFF242B39;
    private static final int TEXT = 0xFFE9ECF5;
    private static final int MUTED = 0xFF929BAD;
    private static final int ACCENT = 0xFF76BFFF;
    private static final int ROW_HEIGHT = 32;
    private static final int PAGE_SIZE = 8;
    private static final List<BlockEntry> BLOCKS = loadBlocks();

    private final Screen parent;
    private final ItemEspModule module;
    private EditBox search;
    private int page;

    public BlockPickerScreen(Screen parent, ItemEspModule module) {
        super(Component.literal("Choose a block"));
        this.parent = parent;
        this.module = module;
    }

    @Override
    protected void init() {
        Layout ui = layout();
        search = new EditBox(font, ui.x() + 18, ui.y() + 54, ui.width() - 36, 20,
                Component.literal("Search blocks"));
        search.setMaxLength(128);
        search.setHint(Component.literal("Search name or minecraft:stone"));
        search.setResponder(value -> page = 0);
        addRenderableWidget(search);
        setInitialFocus(search);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.renderBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, width, height, 0x8805070B);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        Layout ui = layout();
        graphics.fill(ui.x() - 1, ui.y() - 1, ui.x() + ui.width() + 1, ui.y() + ui.height() + 1,
                0xFF343947);
        graphics.fill(ui.x(), ui.y(), ui.x() + ui.width(), ui.y() + ui.height(), PANEL);
        graphics.fill(ui.x(), ui.y(), ui.x() + 3, ui.y() + ui.height(), ACCENT);
        graphics.drawString(font, Component.literal("CHOOSE A BLOCK"), ui.x() + 18, ui.y() + 18, TEXT, true);
        graphics.drawString(font, Component.literal("Search any registered block by name or ID"),
                ui.x() + 18, ui.y() + 34, MUTED, false);

        List<BlockEntry> matches = matches();
        int pageSize = pageSize(ui);
        int pageCount = Math.max(1, (matches.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pageCount - 1);
        int start = page * pageSize;
        int listY = ui.y() + 86;
        graphics.drawString(font, Component.literal(matches.size() + " blocks"),
                ui.x() + 18, ui.y() + 78, MUTED, false);
        for (int row = 0; row < pageSize; row++) {
            int index = start + row;
            if (index >= matches.size()) break;
            BlockEntry entry = matches.get(index);
            int y = listY + row * ROW_HEIGHT;
            boolean selected = entry.id().equals(module.targetBlockId());
            graphics.fill(ui.x() + 18, y, ui.x() + ui.width() - 18, y + ROW_HEIGHT - 3,
                    hovered(mouseX, mouseY, ui.x() + 18, y, ui.width() - 36, ROW_HEIGHT - 3)
                            ? ROW_HOVER : ROW);
            if (selected) graphics.fill(ui.x() + 18, y, ui.x() + 20, y + ROW_HEIGHT - 3, ACCENT);
            graphics.drawString(font, Component.literal(entry.name()), ui.x() + 28, y + 4,
                    selected ? 0xFFE7F5FF : TEXT, true);
            graphics.drawString(font, Component.literal(entry.id()), ui.x() + 28, y + 17, MUTED, false);
        }

        int footerY = ui.y() + ui.height() - 38;
        drawFooterButton(graphics, ui.x() + 18, footerY, 70, "Prev", page > 0,
                hovered(mouseX, mouseY, ui.x() + 18, footerY, 70, 22));
        String pageLabel = (page + 1) + " / " + pageCount;
        graphics.drawCenteredString(font, Component.literal(pageLabel), ui.x() + ui.width() / 2,
                footerY + 7, MUTED);
        drawFooterButton(graphics, ui.x() + ui.width() - 88, footerY, 70, "Next", page + 1 < pageCount,
                hovered(mouseX, mouseY, ui.x() + ui.width() - 88, footerY, 70, 22));
        graphics.drawString(font, Component.literal("ESC  Back"), ui.x() + 18,
                ui.y() + ui.height() - 17, MUTED, false);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        Layout ui = layout();
        int listY = ui.y() + 86;
        List<BlockEntry> matches = matches();
        int pageSize = pageSize(ui);
        int start = page * pageSize;
        for (int row = 0; row < pageSize; row++) {
            int index = start + row;
            int y = listY + row * ROW_HEIGHT;
            if (index < matches.size() && inside(event.x(), event.y(), ui.x() + 18, y,
                    ui.width() - 36, ROW_HEIGHT - 3)) {
                module.setTargetBlock(matches.get(index).id());
                Minecraft.getInstance().setScreen(parent);
                return true;
            }
        }

        int footerY = ui.y() + ui.height() - 38;
        int pageCount = Math.max(1, (matches.size() + pageSize - 1) / pageSize);
        if (inside(event.x(), event.y(), ui.x() + 18, footerY, 70, 22)) {
            page = Math.max(0, page - 1);
            return true;
        }
        if (inside(event.x(), event.y(), ui.x() + ui.width() - 88, footerY, 70, 22)) {
            page = Math.min(pageCount - 1, page + 1);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private List<BlockEntry> matches() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return BLOCKS;
        return BLOCKS.stream().filter(entry -> entry.id().contains(query)
                || entry.name().toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private void drawFooterButton(GuiGraphics graphics, int x, int y, int width, String label,
                                  boolean enabled, boolean isHovered) {
        int fill = enabled ? (isHovered ? 0xFF2D3A4C : 0xFF202B3A) : 0xFF171B23;
        graphics.fill(x, y, x + width, y + 22, fill);
        graphics.drawCenteredString(font, Component.literal(label), x + width / 2, y + 7,
                enabled ? TEXT : 0xFF5F6674);
    }

    private static List<BlockEntry> loadBlocks() {
        List<BlockEntry> entries = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block == Blocks.AIR) continue;
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            entries.add(new BlockEntry(block, id.toString(), block.getName().getString()));
        }
        entries.sort(Comparator.comparing(BlockEntry::id));
        return List.copyOf(entries);
    }

    private Layout layout() {
        int panelWidth = Math.min(620, Math.max(360, width - 24));
        int panelHeight = Math.min(430, Math.max(300, height - 24));
        return new Layout((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);
    }

    private int pageSize(Layout ui) {
        return Math.max(1, Math.min(PAGE_SIZE, (ui.height() - 146) / ROW_HEIGHT));
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static boolean hovered(int mouseX, int mouseY, int x, int y, int width, int height) {
        return inside(mouseX, mouseY, x, y, width, height);
    }

    private record BlockEntry(Block block, String id, String name) { }
    private record Layout(int x, int y, int width, int height) { }
}
