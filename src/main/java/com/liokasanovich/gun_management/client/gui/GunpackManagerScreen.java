package com.liokasanovich.gun_management.client.gui;

import com.liokasanovich.gun_management.client.gui.widget.GunpackListWidget;
import com.liokasanovich.gun_management.client.texture.PackIconCache;
import com.liokasanovich.gun_management.core.GunpackInfo;
import com.liokasanovich.gun_management.core.GunpackOperator;
import com.liokasanovich.gun_management.core.GunpackPaths;
import com.liokasanovich.gun_management.core.GunpackScanner;
import com.liokasanovich.gun_management.core.ModrinthClient;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Main management screen with two tabs:
 * 1) Local dual-column enable/disable manager
 * 2) Online Modrinth browser with one-click download and SHA-256 verification
 */
public class GunpackManagerScreen extends Screen {
    private enum Tab {
        LOCAL,
        ONLINE
    }

    private final Screen parent;
    private Tab currentTab = Tab.LOCAL;

    // Local tab state
    private List<GunpackInfo> allLocalPacks = List.of();
    private EditBox localSearchBox;
    private GunpackListWidget disabledList;
    private GunpackListWidget enabledList;
    private Button enableButton;
    private Button disableButton;
    private Button applyReloadButton;
    private Button openFolderButton;
    private Button refreshLocalButton;

    // Online tab state
    private EditBox onlineSearchBox;
    private Button onlineSearchButton;
    private ModrinthListWidget onlineList;
    private Button installButton;
    private List<ModrinthClient.ModrinthProject> onlineProjects = List.of();
    @Nullable
    private ModrinthClient.ModrinthProject selectedProject;
    private boolean onlineSearching = false;
    private boolean downloading = false;
    private String statusMessage = "";

    public GunpackManagerScreen(Screen parent) {
        super(Component.translatable("gui.gun_management.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();

        int tabWidth = 110;
        int tabY = 8;
        int centerX = this.width / 2;

        Button localTabBtn = Button.builder(Component.translatable("gui.gun_management.tab.local"), b -> switchTab(Tab.LOCAL))
                .bounds(centerX - tabWidth - 4, tabY, tabWidth, 20)
                .build();
        Button onlineTabBtn = Button.builder(Component.translatable("gui.gun_management.tab.online"), b -> switchTab(Tab.ONLINE))
                .bounds(centerX + 4, tabY, tabWidth, 20)
                .build();
        localTabBtn.active = (currentTab != Tab.LOCAL);
        onlineTabBtn.active = (currentTab != Tab.ONLINE);
        addRenderableWidget(localTabBtn);
        addRenderableWidget(onlineTabBtn);

        if (currentTab == Tab.LOCAL) {
            initLocalTab();
        } else {
            initOnlineTab();
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20)
                .build());
    }

    private void initLocalTab() {
        int topY = 34;
        int bottomMargin = 34;
        int listTop = topY + 26;
        int listHeight = Math.max(80, this.height - listTop - bottomMargin);

        localSearchBox = new EditBox(this.font, 16, topY, Math.min(260, this.width - 32), 20,
                Component.translatable("gui.gun_management.search"));
        localSearchBox.setHint(Component.translatable("gui.gun_management.search_local_hint"));
        localSearchBox.setResponder(text -> filterLocalLists());
        addRenderableWidget(localSearchBox);

        int gap = 36;
        int colWidth = Math.max(120, (this.width - 32 - gap) / 2);
        int leftX = 16;
        int rightX = leftX + colWidth + gap;

        disabledList = new GunpackListWidget(this.minecraft, colWidth, listHeight, listTop, 38,
                pack -> updateTransferButtons(),
                pack -> togglePack(pack, true));
        disabledList.setX(leftX);
        addRenderableWidget(disabledList);

        enabledList = new GunpackListWidget(this.minecraft, colWidth, listHeight, listTop, 38,
                pack -> updateTransferButtons(),
                pack -> togglePack(pack, false));
        enabledList.setX(rightX);
        addRenderableWidget(enabledList);

        int midX = leftX + colWidth + 6;
        int midY = listTop + listHeight / 2;
        enableButton = Button.builder(Component.literal("->"), b -> {
            GunpackInfo selected = disabledList.getSelectedPack();
            if (selected != null) {
                togglePack(selected, true);
            }
        }).bounds(midX, midY - 22, 24, 20).build();
        disableButton = Button.builder(Component.literal("<-"), b -> {
            GunpackInfo selected = enabledList.getSelectedPack();
            if (selected != null) {
                togglePack(selected, false);
            }
        }).bounds(midX, midY + 2, 24, 20).build();
        addRenderableWidget(enableButton);
        addRenderableWidget(disableButton);

        int btnY = this.height - 28;
        applyReloadButton = Button.builder(Component.translatable("gui.gun_management.apply_reload"), b -> triggerReload())
                .bounds(16, btnY, 120, 20).build();
        openFolderButton = Button.builder(Component.translatable("gui.gun_management.open_folder"),
                b -> Util.getPlatform().openFile(GunpackPaths.getTaczDir().toFile()))
                .bounds(142, btnY, 96, 20).build();
        refreshLocalButton = Button.builder(Component.translatable("gui.gun_management.refresh"), b -> refreshLocalPacks())
                .bounds(244, btnY, 70, 20).build();

        addRenderableWidget(applyReloadButton);
        addRenderableWidget(openFolderButton);
        addRenderableWidget(refreshLocalButton);

        refreshLocalPacks();
        updateTransferButtons();
    }

    private void initOnlineTab() {
        int topY = 34;
        int bottomMargin = 34;
        int listTop = topY + 26;
        int listHeight = Math.max(80, this.height - listTop - bottomMargin);

        int searchWidth = Math.min(260, this.width - 110);
        onlineSearchBox = new EditBox(this.font, 16, topY, searchWidth, 20,
                Component.translatable("gui.gun_management.search"));
        onlineSearchBox.setHint(Component.translatable("gui.gun_management.search_online_hint"));
        addRenderableWidget(onlineSearchBox);

        onlineSearchButton = Button.builder(Component.translatable("gui.gun_management.search_btn"), b -> searchOnline())
                .bounds(16 + searchWidth + 6, topY, 70, 20)
                .build();
        addRenderableWidget(onlineSearchButton);

        int leftWidth = Math.max(150, (int) (this.width * 0.52));
        onlineList = new ModrinthListWidget(this.minecraft, leftWidth, listHeight, listTop, 38);
        onlineList.setX(16);
        addRenderableWidget(onlineList);
        onlineList.setProjects(onlineProjects);

        int drawerX = 16 + leftWidth + 12;
        int drawerW = Math.max(120, this.width - drawerX - 16);
        installButton = Button.builder(Component.translatable("gui.gun_management.install"), b -> installSelectedProject())
                .bounds(drawerX, this.height - 28, Math.min(160, drawerW), 20)
                .build();
        installButton.active = (selectedProject != null && !downloading);
        addRenderableWidget(installButton);

        if (onlineProjects.isEmpty() && !onlineSearching) {
            searchOnline();
        }
    }

    private void switchTab(Tab tab) {
        if (this.currentTab != tab) {
            this.currentTab = tab;
            this.statusMessage = "";
            init();
        }
    }

    private void refreshLocalPacks() {
        Path taczDir = GunpackPaths.getTaczDir();
        Set<Path> pending = GunpackOperator.getPendingRestartPaths(taczDir);
        GunpackScanner.scanAllAsync(taczDir, pending).thenAccept(packs ->
                Minecraft.getInstance().execute(() -> {
                    this.allLocalPacks = packs;
                    if (currentTab == Tab.LOCAL) {
                        filterLocalLists();
                    }
                })
        );
    }

    private void filterLocalLists() {
        if (disabledList == null || enabledList == null) {
            return;
        }
        String query = localSearchBox != null ? localSearchBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        List<GunpackInfo> disabled = new ArrayList<>();
        List<GunpackInfo> enabled = new ArrayList<>();
        for (GunpackInfo pack : allLocalPacks) {
            if (!query.isEmpty()
                    && !pack.displayName().toLowerCase(Locale.ROOT).contains(query)
                    && !pack.namespace().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            if (pack.enabled()) {
                enabled.add(pack);
            } else {
                disabled.add(pack);
            }
        }
        disabledList.setPacks(disabled);
        enabledList.setPacks(enabled);
        updateTransferButtons();
    }

    private void updateTransferButtons() {
        if (enableButton != null && disabledList != null) {
            enableButton.active = disabledList.getSelectedPack() != null;
        }
        if (disableButton != null && enabledList != null) {
            disableButton.active = enabledList.getSelectedPack() != null;
        }
    }

    private void togglePack(GunpackInfo pack, boolean enable) {
        GunpackOperator.OperationResult result = GunpackOperator.setEnabled(pack, enable);
        this.statusMessage = result.message();
        refreshLocalPacks();
    }

    private void triggerReload() {
        this.statusMessage = Component.translatable("gui.gun_management.reloading").getString();
        GunpackOperator.reloadAllPacks().thenRun(() ->
                Minecraft.getInstance().execute(() -> {
                    this.statusMessage = Component.translatable("gui.gun_management.reloaded").getString();
                    refreshLocalPacks();
                })
        );
    }

    private void searchOnline() {
        if (onlineSearching) {
            return;
        }
        onlineSearching = true;
        String query = onlineSearchBox != null ? onlineSearchBox.getValue() : "tacz";
        this.statusMessage = Component.translatable("gui.gun_management.searching").getString();

        ModrinthClient.searchGunpacksAsync(query, 25, 0).whenComplete((projects, err) ->
                Minecraft.getInstance().execute(() -> {
                    onlineSearching = false;
                    if (err != null) {
                        this.statusMessage = "Search error: " + err.getMessage();
                        return;
                    }
                    this.onlineProjects = projects != null ? projects : List.of();
                    this.statusMessage = Component.translatable("gui.gun_management.found_count",
                            this.onlineProjects.size()).getString();
                    if (onlineList != null) {
                        onlineList.setProjects(this.onlineProjects);
                    }
                })
        );
    }

    private void installSelectedProject() {
        if (selectedProject == null || downloading) {
            return;
        }
        ModrinthClient.ModrinthProject project = selectedProject;
        downloading = true;
        if (installButton != null) {
            installButton.active = false;
        }
        this.statusMessage = Component.translatable("gui.gun_management.resolving", project.title()).getString();

        ModrinthClient.getLatestCompatibleVersionAsync(project.projectId())
                .thenCompose(optVersion -> {
                    if (optVersion.isEmpty()) {
                        throw new RuntimeException("No compatible 1.21.1 file found for " + project.title());
                    }
                    ModrinthClient.ModrinthVersion version = optVersion.get();
                    return ModrinthClient.downloadAndInstallAsync(version, GunpackPaths.getTaczDir(),
                            (done, total) -> Minecraft.getInstance().execute(() -> {
                                if (total > 0) {
                                    int pct = (int) Math.min(100, (done * 100) / total);
                                    this.statusMessage = String.format("Downloading %s... %d%%", project.title(), pct);
                                } else {
                                    this.statusMessage = String.format("Downloading %s... %d KB", project.title(), done / 1024);
                                }
                            }));
                })
                .whenComplete((path, err) -> Minecraft.getInstance().execute(() -> {
                    downloading = false;
                    if (installButton != null) {
                        installButton.active = (selectedProject != null);
                    }
                    if (err != null) {
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        this.statusMessage = "Install failed: " + cause.getMessage();
                        return;
                    }
                    this.statusMessage = Component.translatable("gui.gun_management.installed_reloading",
                            path.getFileName().toString()).getString();
                    GunpackOperator.reloadAllPacks().thenRun(() ->
                            Minecraft.getInstance().execute(this::refreshLocalPacks));
                }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        if (currentTab == Tab.LOCAL && disabledList != null && enabledList != null) {
            graphics.drawString(this.font,
                    Component.translatable("gui.gun_management.disabled_header"),
                    disabledList.getX(), disabledList.getY() - 10, 0xFFAAAA, false);
            graphics.drawString(this.font,
                    Component.translatable("gui.gun_management.enabled_header"),
                    enabledList.getX(), enabledList.getY() - 10, 0xAAFFAA, false);
        } else if (currentTab == Tab.ONLINE && onlineList != null) {
            renderOnlineDrawer(graphics);
        }

        if (!statusMessage.isBlank()) {
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(statusMessage, this.width - 420),
                    322, this.height - 22, 0xDDDD88, false);
        }
    }

    private void renderOnlineDrawer(GuiGraphics graphics) {
        int drawerX = onlineList.getX() + onlineList.getWidth() + 12;
        int drawerY = onlineList.getY();
        int drawerW = Math.max(120, this.width - drawerX - 16);
        int drawerH = onlineList.getHeight();

        graphics.fill(drawerX, drawerY, drawerX + drawerW, drawerY + drawerH, 0x66000000);
        if (selectedProject == null) {
            graphics.drawString(this.font,
                    Component.translatable("gui.gun_management.select_project"),
                    drawerX + 8, drawerY + 10, 0xAAAAAA, false);
            return;
        }

        int y = drawerY + 8;
        graphics.drawString(this.font,
                this.font.plainSubstrByWidth(selectedProject.title(), drawerW - 16),
                drawerX + 8, y, 0xFFFFFF, false);
        y += 14;
        graphics.drawString(this.font,
                "Author: " + selectedProject.author(),
                drawerX + 8, y, 0xAAAAAA, false);
        y += 12;
        graphics.drawString(this.font,
                "Downloads: " + selectedProject.downloads(),
                drawerX + 8, y, 0xAAAAAA, false);
        y += 16;
        graphics.drawWordWrap(this.font,
                Component.literal(selectedProject.description()),
                drawerX + 8, y, drawerW - 16, 0xCCCCCC);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    private class ModrinthListWidget extends ObjectSelectionList<ModrinthListWidget.ProjectEntry> {
        public ModrinthListWidget(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        public void setProjects(List<ModrinthClient.ModrinthProject> projects) {
            clearEntries();
            for (ModrinthClient.ModrinthProject project : projects) {
                addEntry(new ProjectEntry(project));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.max(100, this.width - 18);
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getX() + this.width - 6;
        }

        private class ProjectEntry extends ObjectSelectionList.Entry<ProjectEntry> {
            private final ModrinthClient.ModrinthProject project;

            private ProjectEntry(ModrinthClient.ModrinthProject project) {
                this.project = project;
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
                int iconSize = 28;
                int iconX = left + 4;
                int iconY = top + (height - iconSize) / 2;

                ResourceLocation icon = PackIconCache.getOrLoadRemote(project.iconUrl());
                if (icon != null) {
                    graphics.blit(icon, iconX, iconY, 0, 0, iconSize, iconSize, iconSize, iconSize);
                } else {
                    graphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, 0xFF2D3E50);
                    String initial = project.title().isEmpty() ? "M" : project.title().substring(0, 1).toUpperCase();
                    graphics.drawCenteredString(font, initial, iconX + iconSize / 2, iconY + 10, 0xFFFFFFFF);
                }

                int textX = iconX + iconSize + 6;
                int maxW = Math.max(40, width - iconSize - 16);
                graphics.drawString(font, font.plainSubstrByWidth(project.title(), maxW), textX, top + 4, 0xFFFFFF, false);
                String sub = project.author() + " | " + project.downloads() + " DL";
                graphics.drawString(font, font.plainSubstrByWidth(sub, maxW), textX, top + 18, 0xAAAAAA, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    ModrinthListWidget.this.setSelected(this);
                    selectedProject = project;
                    if (installButton != null) {
                        installButton.active = !downloading;
                    }
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal(project.title());
            }
        }
    }
}
