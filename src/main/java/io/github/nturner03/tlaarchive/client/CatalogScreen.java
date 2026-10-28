package io.github.nturner03.tlaarchive.client;

import io.github.nturner03.tlaarchive.catalog.CatalogEntry;
import io.github.nturner03.tlaarchive.catalog.CatalogIcons;
import io.github.nturner03.tlaarchive.catalog.CatalogMenu;
import io.github.nturner03.tlaarchive.catalog.CatalogPayloads;
import io.github.nturner03.tlaarchive.catalog.CatalogView;
import io.github.nturner03.tlaarchive.controller.ArchiveState;
import io.github.nturner03.tlaarchive.manifest.ArchiveManifest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The archive catalog (design: <i>Archive controller and current holdings</i>): the controller's state and manifest
 * label, search, category filters with totals, a missing-only filter, every entry's current and required amounts,
 * the selected entry's matching rule, and milestones apart from current readiness. Only visible rows are drawn, so a
 * manifest of 1,000+ entries stays cheap. The data comes from {@link ClientCatalog}.
 */
public final class CatalogScreen extends Screen implements MenuAccess<CatalogMenu> {
    private static final int MARGIN = 8;
    private static final int GAP = 6;
    private static final int LINE = 10;
    private static final int STATUS_LINES = 3;
    private static final int ROW = 20;
    private static final int CATEGORY_ROW = 12;

    private static final int PANEL = 0xA0000000;
    private static final int HOVER = 0x30FFFFFF;
    private static final int SELECTED = 0x50FFFFFF;
    private static final int WHITE = 0xFFFFFF;
    private static final int GRAY = 0xAAAAAA;
    private static final int DARK_GRAY = 0x777777;
    private static final int GREEN = 0x55FF55;
    private static final int GOLD = 0xFFAA00;
    private static final int RED_TEXT = 0xFF5555;

    private final CatalogMenu menu;

    // Layout, recomputed in init().
    private int controlsY;
    private int bodyTop;
    private int bodyBottom;
    private int categoryX;
    private int categoryWidth;
    private int listX;
    private int listWidth;
    private int detailX;
    private int detailWidth;

    @Nullable
    private EditBox search;
    private String query = "";
    private boolean missingOnly;
    /** Null shows every category. */
    @Nullable
    private String category;

    private int seenVersion = -1;
    @Nullable
    private List<CatalogEntry> shownEntries;
    @Nullable
    private CatalogView view;
    private ItemStack[] icons = new ItemStack[0];
    private int[] filtered = new int[0];
    private int selected = -1;
    private int listScroll;
    private int categoryScroll;

    public CatalogScreen(CatalogMenu menu, Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
    }

    @Override
    public CatalogMenu getMenu() {
        return menu;
    }

    @Override
    protected void init() {
        int inner = width - 2 * MARGIN;
        categoryWidth = Mth.clamp(inner * 22 / 100, 90, 140);
        detailWidth = Mth.clamp(inner * 30 / 100, 120, 200);
        categoryX = MARGIN;
        listX = categoryX + categoryWidth + GAP;
        detailX = width - MARGIN - detailWidth;
        listWidth = Math.max(40, detailX - GAP - listX);
        controlsY = MARGIN + STATUS_LINES * LINE + 4;
        bodyTop = controlsY + 22;
        bodyBottom = height - MARGIN;

        int searchWidth = Math.min(160, Math.max(60, listWidth - 96));
        search = new EditBox(font, listX, controlsY, searchWidth, 16, Component.translatable(
                "tla_archive.catalog.search"));
        search.setHint(Component.translatable("tla_archive.catalog.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            refilter();
        });
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(missingLabel(), button -> {
            missingOnly = !missingOnly;
            button.setMessage(missingLabel());
            refilter();
        }).bounds(listX + searchWidth + 4, controlsY - 1, 92, 18).build());
        clampScroll();
    }

    private Component missingLabel() {
        return Component.translatable(missingOnly ? "tla_archive.catalog.missing_only.on"
                : "tla_archive.catalog.missing_only.off");
    }

    // --- Data ---

    /** Rebuilds the view when {@link ClientCatalog} has new data. */
    private void refresh() {
        if (seenVersion == ClientCatalog.version()) {
            return;
        }
        seenVersion = ClientCatalog.version();
        var entries = ClientCatalog.entries().orElse(null);
        if (entries == null) {
            shownEntries = null;
            view = null;
            filtered = new int[0];
            return;
        }
        if (entries != shownEntries) {
            shownEntries = entries;
            icons = new ItemStack[entries.size()];
            var registries = minecraft != null && minecraft.level != null ? minecraft.level.registryAccess() : null;
            for (int i = 0; i < icons.length; i++) {
                icons[i] = registries == null ? ItemStack.EMPTY : CatalogIcons.icon(entries.get(i), registries);
            }
            if (category != null && categories().stream().noneMatch(c -> c.id().equals(category))) {
                category = null;
            }
            selected = -1;
        }
        var counts = ClientCatalog.counts(menu.containerId).map(CatalogPayloads.Counts::counts).orElse(null);
        view = new CatalogView(entries, counts);
        refilter();
    }

    private void refilter() {
        filtered = view == null ? new int[0] : view.filter(query, category, missingOnly);
        clampScroll();
    }

    private List<ArchiveManifest.Category> categories() {
        return ClientCatalog.header().map(CatalogPayloads.ManifestHeader::categories).orElse(List.of());
    }

    private String categoryName(String id) {
        return categories().stream().filter(c -> c.id().equals(id)).findFirst()
                .map(ArchiveManifest.Category::name).orElse(id);
    }

    private boolean stale() {
        return ClientCatalog.counts(menu.containerId).map(c -> c.state() != ArchiveState.LIVE).orElse(true);
    }

    // --- Rendering ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refresh();
        super.render(graphics, mouseX, mouseY, partialTick);
        renderStatus(graphics);
        graphics.fill(categoryX, bodyTop, categoryX + categoryWidth, bodyBottom, PANEL);
        graphics.fill(listX, bodyTop, listX + listWidth, bodyBottom, PANEL);
        graphics.fill(detailX, bodyTop, detailX + detailWidth, bodyBottom, PANEL);

        var header = ClientCatalog.header().orElse(null);
        if (header != null && !header.present()) {
            drawCentered(graphics, Component.translatable("tla_archive.catalog.no_manifest"), RED_TEXT);
            renderMilestones(graphics, bodyTop + 4);
            return;
        }
        if (view == null) {
            drawCentered(graphics, Component.translatable("tla_archive.catalog.loading"), GRAY);
            return;
        }
        renderReadiness(graphics);
        renderCategories(graphics, mouseX, mouseY);
        renderEntries(graphics, mouseX, mouseY);
        renderDetails(graphics);
    }

    private void drawCentered(GuiGraphics graphics, Component text, int color) {
        graphics.drawCenteredString(font, text, listX + listWidth / 2, bodyTop + 12, color);
    }

    private void renderStatus(GuiGraphics graphics) {
        var counts = ClientCatalog.latest(menu.containerId).orElse(null);
        graphics.drawString(font, title, MARGIN, MARGIN, WHITE);
        int titleWidth = font.width(title) + 12;
        if (counts == null) {
            graphics.drawString(font, Component.translatable("tla_archive.catalog.loading"), MARGIN + titleWidth,
                    MARGIN, GRAY);
            return;
        }
        int y = MARGIN;
        int x = MARGIN + titleWidth;
        for (var line : counts.status().subList(0, Math.min(STATUS_LINES, counts.status().size()))) {
            graphics.drawString(font, font.plainSubstrByWidth(line.getString(), width - MARGIN - x), x, y,
                    colorOf(line, GRAY));
            y += LINE;
        }
    }

    private static int colorOf(Component line, int fallback) {
        var color = line.getStyle().getColor();
        return color == null ? fallback : color.getValue();
    }

    private void renderReadiness(GuiGraphics graphics) {
        var totals = view.totals(null);
        var text = Component.translatable("tla_archive.catalog.ready", totals.ready(), totals.total(),
                totals.missing());
        int x = listX + Math.min(160, Math.max(60, listWidth - 96)) + 4 + 92 + GAP;
        int y = controlsY + 4;
        if (x + font.width(text) > width - MARGIN) {
            x = detailX;
        }
        graphics.drawString(font, text, x, y, stale() ? GOLD : WHITE);
    }

    private void renderCategories(GuiGraphics graphics, int mouseX, int mouseY) {
        var rows = categoryRows();
        int visible = (bodyBottom - bodyTop - 4) / CATEGORY_ROW;
        graphics.enableScissor(categoryX, bodyTop, categoryX + categoryWidth, bodyBottom);
        for (int r = categoryScroll; r < rows.size() && r < categoryScroll + visible; r++) {
            int y = bodyTop + 2 + (r - categoryScroll) * CATEGORY_ROW;
            var id = rows.get(r);
            boolean isSelected = id == null ? category == null : id.equals(category);
            if (isSelected) {
                graphics.fill(categoryX, y, categoryX + categoryWidth, y + CATEGORY_ROW, SELECTED);
            } else if (inside(mouseX, mouseY, categoryX, y, categoryWidth, CATEGORY_ROW)) {
                graphics.fill(categoryX, y, categoryX + categoryWidth, y + CATEGORY_ROW, HOVER);
            }
            var totals = view.totals(id);
            var count = totals.ready() + "/" + totals.total();
            int countWidth = font.width(count);
            var name = id == null ? Component.translatable("tla_archive.catalog.all").getString() : categoryName(id);
            graphics.drawString(font, font.plainSubstrByWidth(name, categoryWidth - countWidth - 10), categoryX + 3,
                    y + 2, isSelected ? WHITE : GRAY);
            graphics.drawString(font, count, categoryX + categoryWidth - countWidth - 3, y + 2,
                    totals.total() > 0 && totals.missing() == 0 ? GREEN : GRAY);
        }
        graphics.disableScissor();
    }

    /** Null first, for "All". */
    private List<String> categoryRows() {
        var rows = new ArrayList<String>();
        rows.add(null);
        categories().forEach(c -> rows.add(c.id()));
        return rows;
    }

    private void renderEntries(GuiGraphics graphics, int mouseX, int mouseY) {
        if (filtered.length == 0) {
            drawCentered(graphics, Component.translatable("tla_archive.catalog.no_match"), GRAY);
            return;
        }
        boolean stale = stale();
        int visible = visibleRows();
        graphics.enableScissor(listX, bodyTop, listX + listWidth, bodyBottom);
        for (int r = listScroll; r < filtered.length && r < listScroll + visible; r++) {
            int index = filtered[r];
            int y = bodyTop + (r - listScroll) * ROW;
            if (index == selected) {
                graphics.fill(listX, y, listX + listWidth, y + ROW, SELECTED);
            } else if (inside(mouseX, mouseY, listX, y, listWidth, ROW)) {
                graphics.fill(listX, y, listX + listWidth, y + ROW, HOVER);
            }
            var entry = view.entry(index);
            graphics.renderItem(icons[index], listX + 2, y + 2);
            var amounts = amountText(index);
            int amountsWidth = font.width(amounts);
            boolean ready = view.ready(index);
            graphics.drawString(font, font.plainSubstrByWidth(entry.displayName(), listWidth - amountsWidth - 30),
                    listX + 22, y + 6, ready ? WHITE : GRAY);
            int amountColor = view.count(index) < 0 ? DARK_GRAY : stale ? GOLD : ready ? GREEN : GRAY;
            graphics.drawString(font, amounts, listX + listWidth - amountsWidth - 4, y + 6, amountColor);
        }
        graphics.disableScissor();
        if (filtered.length > visible) {
            int track = bodyBottom - bodyTop;
            int thumb = Math.max(8, track * visible / filtered.length);
            int top = bodyTop + (track - thumb) * listScroll / Math.max(1, filtered.length - visible);
            graphics.fill(listX + listWidth - 2, top, listX + listWidth, top + thumb, 0x80FFFFFF);
        }
    }

    private String amountText(int index) {
        long count = view.count(index);
        return (count < 0 ? "?" : Long.toString(count)) + " / " + view.entry(index).quantity();
    }

    private void renderDetails(GuiGraphics graphics) {
        int x = detailX + 4;
        int textWidth = detailWidth - 8;
        int y = bodyTop + 4;
        if (selected < 0) {
            y = drawWrapped(graphics, Component.translatable("tla_archive.catalog.select_hint"), x, y, textWidth,
                    DARK_GRAY);
        } else {
            var entry = view.entry(selected);
            y = drawWrapped(graphics, Component.literal(entry.displayName()).withStyle(ChatFormatting.BOLD), x, y,
                    textWidth, WHITE);
            y = drawWrapped(graphics, Component.literal(entry.key()), x, y, textWidth, DARK_GRAY);
            y = drawWrapped(graphics, Component.translatable("tla_archive.catalog.category",
                    categoryName(entry.category())), x, y, textWidth, GRAY);
            y = drawWrapped(graphics, Component.translatable("tla_archive.catalog.amounts", amountText(selected)), x,
                    y, textWidth, view.ready(selected) ? GREEN : GRAY);
            if (entry.quantityNote().isPresent()) {
                y = drawWrapped(graphics, Component.literal(entry.quantityNote().get()), x, y, textWidth, GOLD);
            }
            y += 4;
            y = drawWrapped(graphics, Component.translatable("tla_archive.catalog.rule." + entry.rule() + ".name"),
                    x, y, textWidth, WHITE);
            y = drawWrapped(graphics, Component.translatable("tla_archive.catalog.rule." + entry.rule()), x, y,
                    textWidth, GRAY);
            if (entry.notes().isPresent()) {
                y = drawWrapped(graphics, Component.literal(entry.notes().get()), x, y, textWidth, GRAY);
            }
        }
        renderMilestones(graphics, y + 8);
    }

    /** Milestones and reconstruction status, apart from current readiness. */
    private void renderMilestones(GuiGraphics graphics, int y) {
        var counts = ClientCatalog.latest(menu.containerId).orElse(null);
        if (counts == null || counts.milestones().isEmpty()) {
            return;
        }
        int x = detailX + 4;
        int textWidth = detailWidth - 8;
        graphics.fill(detailX + 2, y - 4, detailX + detailWidth - 2, y - 3, 0x40FFFFFF);
        y = drawWrapped(graphics, Component.translatable("tla_archive.catalog.milestones"), x, y, textWidth, WHITE);
        for (var line : counts.milestones()) {
            y = drawWrapped(graphics, line, x, y, textWidth, colorOf(line, GRAY));
        }
    }

    private int drawWrapped(GuiGraphics graphics, Component text, int x, int y, int textWidth, int color) {
        for (FormattedCharSequence line : font.split(text, textWidth)) {
            if (y + LINE > bodyBottom) {
                return y;
            }
            graphics.drawString(font, line, x, y, color);
            y += LINE;
        }
        return y;
    }

    // --- Input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (view == null) {
            return false;
        }
        if (inside(mouseX, mouseY, categoryX, bodyTop + 2, categoryWidth, bodyBottom - bodyTop - 2)) {
            int r = categoryScroll + (int) (mouseY - bodyTop - 2) / CATEGORY_ROW;
            var rows = categoryRows();
            if (r >= 0 && r < rows.size()) {
                category = rows.get(r);
                listScroll = 0;
                refilter();
                return true;
            }
        }
        if (inside(mouseX, mouseY, listX, bodyTop, listWidth, bodyBottom - bodyTop)) {
            int r = listScroll + (int) (mouseY - bodyTop) / ROW;
            if (r >= 0 && r < filtered.length) {
                selected = filtered[r];
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) Math.signum(scrollY);
        if (inside(mouseX, mouseY, categoryX, bodyTop, categoryWidth, bodyBottom - bodyTop)) {
            categoryScroll -= step;
        } else {
            listScroll -= step * 3;
        }
        clampScroll();
        return true;
    }

    private int visibleRows() {
        return Math.max(1, (bodyBottom - bodyTop) / ROW);
    }

    private void clampScroll() {
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, filtered.length - visibleRows()));
        int categoryVisible = Math.max(1, (bodyBottom - bodyTop - 4) / CATEGORY_ROW);
        categoryScroll = Mth.clamp(categoryScroll, 0, Math.max(0, categories().size() + 1 - categoryVisible));
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        super.removed();
        ClientCatalog.clearCounts();
    }

    /** Closes the menu on the server too, which stops the count updates. */
    @Override
    public void onClose() {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.closeContainer();
        } else {
            super.onClose();
        }
    }
}
