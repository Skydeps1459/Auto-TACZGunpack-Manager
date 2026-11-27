package com.liokasanovich.gun_management.client.gui.widget;

import com.liokasanovich.gun_management.client.texture.PackIconCache;
import com.liokasanovich.gun_management.core.GunpackInfo;
import com.liokasanovich.gun_management.core.GunpackUpdateChecker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Scrollable list widget for displaying local TaCZ gunpacks with icon, metadata, and quick toggle support.
 */
public class GunpackListWidget extends ObjectSelectionList<GunpackListWidget.Entry> {
    private final Consumer<GunpackInfo> onSelect;
    private final Consumer<GunpackInfo> onDoubleClick;

    public GunpackListWidget(
            Minecraft mc,
            int width,
            int height,
            int y,
            int itemHeight,
            Consumer<GunpackInfo> onSelect,
            Consumer<GunpackInfo> onDoubleClick
    ) {
        super(mc, width, height, y, y + height, itemHeight);
        this.setRenderBackground(false);
        this.setRenderTopAndBottom(false);
        this.onSelect = onSelect;
        this.onDoubleClick = onDoubleClick;
    }

    public void setPacks(List<GunpackInfo> packs) {
        clearEntries();
        for (GunpackInfo pack : packs) {
            addEntry(new Entry(pack));
        }
    }

    @Override
    public int getRowWidth() {
        return Math.max(100, this.width - 18);
    }

    @Override
    protected int getScrollbarPosition() {
        return this.x0 + this.width - 6;
    }

    public class Entry extends ObjectSelectionList.Entry<Entry> {
        private final GunpackInfo pack;
        private long lastClickTime;

        public Entry(GunpackInfo pack) {
            this.pack = pack;
        }

        public GunpackInfo getPack() {
            return pack;
        }

        @Override
        public void render(
                GuiGraphics graphics,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovering,
                float partialTick
        ) {
            Font font = Minecraft.getInstance().font;
            int iconSize = 28;
            int iconX = left + 4;
            int iconY = top + (height - iconSize) / 2;

            ResourceLocation icon = PackIconCache.getOrLoadLocal(pack);
            if (icon != null) {
                graphics.blit(icon, iconX, iconY, 0, 0, iconSize, iconSize, iconSize, iconSize);
            } else {
                int bgColor = pack.enabled() ? 0xFF2B4C3B : 0xFF4A3232;
                graphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, bgColor);
                String initial = pack.displayName().isEmpty() ? "?" : pack.displayName().substring(0, 1).toUpperCase();
                graphics.drawCenteredString(font, initial, iconX + iconSize / 2, iconY + 10, 0xFFFFFFFF);
            }

            int textX = iconX + iconSize + 6;
            int maxTextWidth = Math.max(40, width - iconSize - 20);

            String resolvedName = resolveDisplayName(pack);
            String title = font.plainSubstrByWidth(resolvedName, maxTextWidth);
            graphics.drawString(font, title, textX, top + 3, 0xFFFFFF, false);

            String meta = String.format("v%s | G:%d A:%d Att:%d",
                    pack.version(), pack.gunCount(), pack.ammoCount(), pack.attachmentCount());
            graphics.drawString(font, font.plainSubstrByWidth(meta, maxTextWidth), textX, top + 14, 0xAAAAAA, false);

            GunpackUpdateChecker.PackUpdate update = GunpackUpdateChecker.getUpdateFor(pack);
            if (pack.pendingRestart()) {
                Component badge = Component.translatable("gui.gun_management.pending_restart")
                        .withStyle(ChatFormatting.GOLD);
                graphics.drawString(font, badge, textX, top + 24, 0xFFAA00, false);
            } else if (update != null) {
                Component badge = Component.translatable("gui.gun_management.update_badge",
                                update.latestVersion().versionNumber())
                        .withStyle(ChatFormatting.GOLD);
                graphics.drawString(font, badge, textX, top + 24, 0xFFAA00, false);
            } else if (!pack.authors().isEmpty()) {
                String authors = font.plainSubstrByWidth(String.join(", ", pack.authors()), maxTextWidth);
                graphics.drawString(font, authors, textX, top + 24, 0x888888, false);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                GunpackListWidget.this.setSelected(this);
                onSelect.accept(pack);
                long now = System.currentTimeMillis();
                if (now - lastClickTime < 300) {
                    onDoubleClick.accept(pack);
                }
                lastClickTime = now;
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal(pack.displayName());
        }
    }

    @Nullable
    public GunpackInfo getSelectedPack() {
        Entry entry = getSelected();
        return entry != null ? entry.getPack() : null;
    }

    @Nullable
    public GunpackInfo getHoveredPack() {
        Entry entry = getHovered();
        return entry != null ? entry.getPack() : null;
    }

    public static String resolveDisplayName(GunpackInfo info) {
        String raw = info.displayName();
        return I18n.exists(raw) ? I18n.get(raw) : raw;
    }

    public static List<Component> buildTooltip(GunpackInfo info) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(resolveDisplayName(info)).withStyle(ChatFormatting.BOLD, ChatFormatting.WHITE));
        lines.add(Component.literal("Namespace: " + info.namespace()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("File: " + info.fileName()).withStyle(ChatFormatting.GRAY));
        if (!info.description().isBlank()) {
            lines.add(Component.literal(info.description()).withStyle(ChatFormatting.AQUA));
        }
        if (!info.sha256().isBlank()) {
            String shortHash = info.sha256().substring(0, Math.min(12, info.sha256().length()));
            lines.add(Component.literal("SHA-256: " + shortHash + "...").withStyle(ChatFormatting.DARK_GRAY));
        }
        GunpackUpdateChecker.PackUpdate update = GunpackUpdateChecker.getUpdateFor(info);
        if (update != null) {
            lines.add(Component.translatable("gui.gun_management.update_tooltip",
                            update.currentVersionNumber(), update.latestVersion().versionNumber())
                    .withStyle(ChatFormatting.GOLD));
        }
        lines.add(Component.translatable("gui.gun_management.double_click_toggle")
                .withStyle(ChatFormatting.YELLOW));
        return lines;
    }
}
