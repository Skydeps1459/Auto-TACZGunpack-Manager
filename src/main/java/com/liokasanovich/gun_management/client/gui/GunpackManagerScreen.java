package com.liokasanovich.gun_management.client.gui;

import com.liokasanovich.gun_management.client.gui.widget.GunpackListWidget;
import com.liokasanovich.gun_management.client.texture.PackIconCache;
import com.liokasanovich.gun_management.core.GitHubClient;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main management screen with two tabs (MC 1.20.1 Forge):
 * 1) Local dual-column enable/disable manager
 * 2) Online browser supporting Modrinth and GitHub (topic:tacz + acceleration mirrors)
 */
public class GunpackManagerScreen extends Screen {
    private enum Tab {
        LOCAL,
        ONLINE
    }

    private enum OnlineSource {
        MODRINTH,
        GITHUB
    }

    private record OnlineEntry(
            @Nullable ModrinthClient.ModrinthProject modrinth,
            @Nullable GitHubClient.GitHubRepo github
    ) {
        String title() {
            return modrinth != null ? modrinth.title() : github.name();
        }

        String subtitle() {
            return modrinth != null
                    ? modrinth.author() + " | " + modrinth.downloads() + " DL"
                    : github.owner() + " | ★ " + github.stars();
        }

        @Nullable
        String iconUrl() {
            return modrinth != null ? modrinth.iconUrl() : github.avatarUrl();
        }
    }

    private final Screen parent;
    private Tab currentTab = Tab.LOCAL;
    private OnlineSource onlineSource = OnlineSource.MODRINTH;
    private GitHubClient.MirrorNode githubMirror = GitHubClient.MirrorNode.GHFAST;

    // Local tab state
    private List<GunpackInfo> allLocalPacks = List.of();
    private EditBox localSearchBox;
    private GunpackListWidget disabledList;
    private GunpackListWidget enabledList;
    private Button enableButton;
    private Button disableButton;

    // Online tab state
    private EditBox onlineSearchBox;
    private OnlineListWidget onlineList;
    private Button sourceToggleButton;
    private Button mirrorToggleButton;
    private Button installButton;
    private List<OnlineEntry> onlineEntries = List.of();
    @Nullable
    private OnlineEntry selectedOnline;
    private final Map<String, GitHubClient.RepoDetails> githubDetailsCache = new ConcurrentHashMap<>();
    private boolean onlineSearching = false;
    private int searchGeneration = 0;
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
        int topY = 32;
        int bottomMargin = 46;
        int listTop = 70;
        int listHeight = Math.max(70, this.height - listTop - bottomMargin);

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
        disabledList.setLeftPos(leftX);
        addRenderableWidget(disabledList);

        enabledList = new GunpackListWidget(this.minecraft, colWidth, listHeight, listTop, 38,
                pack -> updateTransferButtons(),
                pack -> togglePack(pack, false));
        enabledList.setLeftPos(rightX);
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
        addRenderableWidget(Button.builder(Component.translatable("gui.gun_management.apply_reload"), b -> triggerReload())
                .bounds(16, btnY, 120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.gun_management.open_folder"),
                b -> Util.getPlatform().openFile(GunpackPaths.getTaczDir().toFile()))
                .bounds(142, btnY, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.gun_management.refresh"), b -> refreshLocalPacks())
                .bounds(244, btnY, 70, 20).build());

        refreshLocalPacks();
        updateTransferButtons();
    }

    private void initOnlineTab() {
        int topY = 32;
        int bottomMargin = 46;
        int listTop = 60;
        int listHeight = Math.max(70, this.height - listTop - bottomMargin);

        sourceToggleButton = Button.builder(sourceLabel(), b -> toggleOnlineSource())
                .bounds(16, topY, 96, 20).build();
        addRenderableWidget(sourceToggleButton);

        int searchX = 116;
        int rightReserve = onlineSource == OnlineSource.GITHUB ? 190 : 72;
        int searchWidth = Math.max(80, this.width - searchX - rightReserve);
        onlineSearchBox = new EditBox(this.font, searchX, topY, searchWidth, 20,
                Component.translatable("gui.gun_management.search"));
        onlineSearchBox.setHint(onlineSource == OnlineSource.GITHUB
                ? Component.translatable("gui.gun_management.search_github_hint")
                : Component.translatable("gui.gun_management.search_online_hint"));
        addRenderableWidget(onlineSearchBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.gun_management.search_btn"), b -> searchOnline())
                .bounds(searchX + searchWidth + 4, topY, 48, 20).build());

        if (onlineSource == OnlineSource.GITHUB) {
            mirrorToggleButton = Button.builder(Component.translatable(githubMirror.translationKey()), b -> cycleMirror())
                    .bounds(searchX + searchWidth + 56, topY, 118, 20).build();
            addRenderableWidget(mirrorToggleButton);
        } else {
            mirrorToggleButton = null;
        }

        int leftWidth = Math.max(150, (int) (this.width * 0.50));
        onlineList = new OnlineListWidget(this.minecraft, leftWidth, listHeight, listTop, 38);
        onlineList.setLeftPos(16);
        addRenderableWidget(onlineList);
        onlineList.setEntries(onlineEntries);

        int drawerX = 16 + leftWidth + 12;
        int drawerW = Math.max(120, this.width - drawerX - 16);
        installButton = Button.builder(Component.translatable("gui.gun_management.install"), b -> installSelected())
                .bounds(drawerX, this.height - 28, Math.min(160, drawerW), 20)
                .build();
        updateInstallButton();
        addRenderableWidget(installButton);

        if (onlineEntries.isEmpty() && !onlineSearching) {
            searchOnline();
        }
    }

    private Component sourceLabel() {
        return onlineSource == OnlineSource.MODRINTH
                ? Component.translatable("gui.gun_management.source.modrinth")
                : Component.translatable("gui.gun_management.source.github");
    }

    private void toggleOnlineSource() {
        onlineSource = (onlineSource == OnlineSource.MODRINTH) ? OnlineSource.GITHUB : OnlineSource.MODRINTH;
        searchGeneration++;
        onlineSearching = false;
        onlineEntries = List.of();
        selectedOnline = null;
        statusMessage = "";
        init();
    }

    private void cycleMirror() {
        githubMirror = githubMirror.next();
        if (mirrorToggleButton != null) {
            mirrorToggleButton.setMessage(Component.translatable(githubMirror.translationKey()));
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

    private boolean isInWorld() {
        return this.minecraft != null && this.minecraft.level != null;
    }

    private void updateTransferButtons() {
        if (enableButton != null && disabledList != null) {
            enableButton.active = disabledList.getSelectedPack() != null;
        }
        if (disableButton != null && enabledList != null) {
            disableButton.active = !isInWorld() && enabledList.getSelectedPack() != null;
        }
    }

    private void updateInstallButton() {
        if (installButton == null) {
            return;
        }
        if (downloading || selectedOnline == null) {
            installButton.active = false;
            return;
        }
        if (selectedOnline.github() != null) {
            GitHubClient.RepoDetails details = githubDetailsCache.get(selectedOnline.github().fullName());
            installButton.active = (details != null && !details.zipAssets().isEmpty());
        } else {
            installButton.active = true;
        }
    }

    private void togglePack(GunpackInfo pack, boolean enable) {
        if (!enable && isInWorld()) {
            this.statusMessage = Component.translatable("gui.gun_management.disable_in_world_blocked").getString();
            return;
        }
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
        final int gen = ++searchGeneration;
        final OnlineSource activeSource = onlineSource;
        String query = onlineSearchBox != null ? onlineSearchBox.getValue() : "";
        this.statusMessage = Component.translatable("gui.gun_management.searching").getString();

        if (activeSource == OnlineSource.MODRINTH) {
            ModrinthClient.searchGunpacksAsync(query, 100, 0).whenComplete((projects, err) ->
                    Minecraft.getInstance().execute(() -> {
                        if (gen != searchGeneration) {
                            return;
                        }
                        handleSearchResult(
                                projects == null ? List.of() : projects.stream()
                                        .map(p -> new OnlineEntry(p, null)).toList(),
                                err);
                    }));
        } else {
            GitHubClient.searchTaczReposAsync(query, 50).whenComplete((repos, err) ->
                    Minecraft.getInstance().execute(() -> {
                        if (gen != searchGeneration) {
                            return;
                        }
                        handleSearchResult(
                                repos == null ? List.of() : repos.stream()
                                        .map(r -> new OnlineEntry(null, r)).toList(),
                                err);
                    }));
        }
    }

    private void handleSearchResult(List<OnlineEntry> entries, @Nullable Throwable err) {
        onlineSearching = false;
        if (err != null) {
            Throwable cause = err.getCause() != null ? err.getCause() : err;
            this.statusMessage = "Search error: " + cause.getMessage();
            return;
        }
        this.onlineEntries = entries;
        this.selectedOnline = null;
        this.statusMessage = Component.translatable("gui.gun_management.found_count", entries.size()).getString();
        if (onlineList != null) {
            onlineList.setEntries(entries);
        }
        updateInstallButton();
    }

    private void onSelectOnlineEntry(OnlineEntry entry) {
        this.selectedOnline = entry;
        if (entry.github() != null) {
            String fullName = entry.github().fullName();
            if (!githubDetailsCache.containsKey(fullName)) {
                GitHubClient.fetchRepoDetailsAsync(entry.github()).thenAccept(details ->
                        Minecraft.getInstance().execute(() -> {
                            githubDetailsCache.put(fullName, details);
                            updateInstallButton();
                        }));
            }
        }
        updateInstallButton();
    }

    private void installSelected() {
        if (selectedOnline == null || downloading) {
            return;
        }
        if (selectedOnline.modrinth() != null) {
            installModrinthProject(selectedOnline.modrinth());
        } else if (selectedOnline.github() != null) {
            installGitHubRepo(selectedOnline.github());
        }
    }

    private void installModrinthProject(ModrinthClient.ModrinthProject project) {
        downloading = true;
        updateInstallButton();
        this.statusMessage = Component.translatable("gui.gun_management.resolving", project.title()).getString();

        ModrinthClient.getLatestCompatibleVersionAsync(project.projectId())
                .thenCompose(optVersion -> {
                    if (optVersion.isEmpty()) {
                        throw new RuntimeException("No compatible .zip gunpack found for " + project.title());
                    }
                    return ModrinthClient.downloadAndInstallAsync(optVersion.get(), GunpackPaths.getTaczDir(),
                            (done, total) -> updateDownloadProgress(project.title(), done, total));
                })
                .whenComplete((path, err) -> finishInstall(path, err));
    }

    private void installGitHubRepo(GitHubClient.GitHubRepo repo) {
        GitHubClient.RepoDetails details = githubDetailsCache.get(repo.fullName());
        if (details == null || details.zipAssets().isEmpty()) {
            this.statusMessage = Component.translatable("gui.gun_management.github.no_zip").getString();
            return;
        }
        GitHubClient.GunpackZipAsset asset = details.zipAssets().get(0);
        downloading = true;
        updateInstallButton();
        this.statusMessage = Component.translatable("gui.gun_management.resolving", asset.fileName()).getString();

        GitHubClient.downloadAndInstallAsync(asset, githubMirror, GunpackPaths.getTaczDir(),
                        (done, total) -> updateDownloadProgress(asset.fileName(), done, total))
                .whenComplete((path, err) -> finishInstall(path, err));
    }

    private void updateDownloadProgress(String name, long done, long total) {
        Minecraft.getInstance().execute(() -> {
            if (total > 0) {
                int pct = (int) Math.min(100, (done * 100) / total);
                this.statusMessage = String.format("Downloading %s... %d%%", name, pct);
            } else {
                this.statusMessage = String.format("Downloading %s... %d KB", name, done / 1024);
            }
        });
    }

    private void finishInstall(@Nullable Path path, @Nullable Throwable err) {
        Minecraft.getInstance().execute(() -> {
            downloading = false;
            updateInstallButton();
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                this.statusMessage = "Install failed: " + cause.getMessage();
                return;
            }
            if (path != null) {
                this.statusMessage = Component.translatable("gui.gun_management.installed_reloading",
                        path.getFileName().toString()).getString();
            }
            GunpackOperator.reloadAllPacks().thenRun(() ->
                    Minecraft.getInstance().execute(this::refreshLocalPacks));
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        if (currentTab == Tab.LOCAL && disabledList != null && enabledList != null) {
            graphics.fill(disabledList.getLeft(), disabledList.getTop(),
                    disabledList.getRight(), disabledList.getBottom(), 0x55000000);
            graphics.fill(enabledList.getLeft(), enabledList.getTop(),
                    enabledList.getRight(), enabledList.getBottom(), 0x55000000);
        } else if (currentTab == Tab.ONLINE && onlineList != null) {
            graphics.fill(onlineList.getLeft(), onlineList.getTop(),
                    onlineList.getRight(), onlineList.getBottom(), 0x55000000);
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        if (currentTab == Tab.LOCAL && disabledList != null && enabledList != null) {
            graphics.drawString(this.font,
                    Component.translatable("gui.gun_management.disabled_header"),
                    disabledList.getLeft(), disabledList.getTop() - 12, 0xFFAAAA, false);
            graphics.drawString(this.font,
                    Component.translatable("gui.gun_management.enabled_header"),
                    enabledList.getLeft(), enabledList.getTop() - 12, 0xAAFFAA, false);
        } else if (currentTab == Tab.ONLINE && onlineList != null) {
            renderOnlineDrawer(graphics);
        }

        if (!statusMessage.isBlank()) {
            int maxW = Math.max(100, this.width - 32);
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(statusMessage, maxW),
                    16, this.height - 40, 0xDDDD88, false);
        }

        if (currentTab == Tab.LOCAL && disabledList != null && enabledList != null) {
            GunpackInfo hovered = disabledList.getHoveredPack();
            if (hovered == null) {
                hovered = enabledList.getHoveredPack();
            }
            if (hovered != null) {
                graphics.renderComponentTooltip(this.font, GunpackListWidget.buildTooltip(hovered), mouseX, mouseY);
            }
        }
    }

    private void renderOnlineDrawer(GuiGraphics graphics) {
        int drawerX = onlineList.getLeft() + onlineList.getWidth() + 12;
        int drawerY = onlineList.getTop();
        int drawerW = Math.max(120, this.width - drawerX - 16);
        int drawerH = onlineList.getBottom() - onlineList.getTop();

        graphics.fill(drawerX, drawerY, drawerX + drawerW, drawerY + drawerH, 0x66000000);
        graphics.enableScissor(drawerX, drawerY, drawerX + drawerW, drawerY + drawerH);
        try {
            if (selectedOnline == null) {
                graphics.drawString(this.font,
                        Component.translatable("gui.gun_management.select_project"),
                        drawerX + 8, drawerY + 10, 0xAAAAAA, false);
                return;
            }

            int y = drawerY + 8;
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(selectedOnline.title(), drawerW - 16),
                    drawerX + 8, y, 0xFFFFFF, false);
            y += 14;
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(selectedOnline.subtitle(), drawerW - 16),
                    drawerX + 8, y, 0xAAAAAA, false);
            y += 14;

            if (selectedOnline.modrinth() != null) {
                graphics.drawWordWrap(this.font,
                        Component.literal(selectedOnline.modrinth().description()),
                        drawerX + 8, y, drawerW - 16, 0xCCCCCC);
            } else if (selectedOnline.github() != null) {
                GitHubClient.RepoDetails details = githubDetailsCache.get(selectedOnline.github().fullName());
                if (details == null) {
                    graphics.drawString(this.font,
                            Component.translatable("gui.gun_management.github.loading_details"),
                            drawerX + 8, y, 0xDDDD88, false);
                    return;
                }
                if (!details.zipAssets().isEmpty()) {
                    GitHubClient.GunpackZipAsset zip = details.zipAssets().get(0);
                    String zipLine = "ZIP: " + zip.fileName() + " (" + zip.sourceLabel() + ")";
                    graphics.drawString(this.font,
                            this.font.plainSubstrByWidth(zipLine, drawerW - 16),
                            drawerX + 8, y, 0x88FF88, false);
                } else {
                    graphics.drawString(this.font,
                            Component.translatable("gui.gun_management.github.no_zip"),
                            drawerX + 8, y, 0xFF8888, false);
                }
                y += 14;
                String body = !details.filteredReadme().isBlank()
                        ? details.filteredReadme()
                        : selectedOnline.github().description();
                if (!body.isBlank()) {
                    graphics.drawWordWrap(this.font,
                            Component.literal(body),
                            drawerX + 8, y, drawerW - 16, 0xCCCCCC);
                }
            }
        } finally {
            graphics.disableScissor();
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    private class OnlineListWidget extends ObjectSelectionList<OnlineListWidget.Entry> {
        public OnlineListWidget(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, y + height, itemHeight);
            this.setRenderBackground(false);
            this.setRenderTopAndBottom(false);
        }

        public void setEntries(List<OnlineEntry> items) {
            clearEntries();
            for (OnlineEntry item : items) {
                addEntry(new Entry(item));
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

        private class Entry extends ObjectSelectionList.Entry<Entry> {
            private final OnlineEntry item;

            private Entry(OnlineEntry item) {
                this.item = item;
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

                ResourceLocation icon = PackIconCache.getOrLoadRemote(item.iconUrl());
                if (icon != null) {
                    graphics.blit(icon, iconX, iconY, 0, 0, iconSize, iconSize, iconSize, iconSize);
                } else {
                    int bg = item.github() != null ? 0xFF24292E : 0xFF2D3E50;
                    graphics.fill(iconX, iconY, iconX + iconSize, iconY + iconSize, bg);
                    String initial = item.title().isEmpty() ? "?" : item.title().substring(0, 1).toUpperCase();
                    graphics.drawCenteredString(font, initial, iconX + iconSize / 2, iconY + 10, 0xFFFFFFFF);
                }

                int textX = iconX + iconSize + 6;
                int maxW = Math.max(40, width - iconSize - 16);
                graphics.drawString(font, font.plainSubstrByWidth(item.title(), maxW), textX, top + 4, 0xFFFFFF, false);
                graphics.drawString(font, font.plainSubstrByWidth(item.subtitle(), maxW), textX, top + 18, 0xAAAAAA, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    OnlineListWidget.this.setSelected(this);
                    onSelectOnlineEntry(item);
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal(item.title());
            }
        }
    }
}
