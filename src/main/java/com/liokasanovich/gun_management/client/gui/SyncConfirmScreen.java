package com.liokasanovich.gun_management.client.gui;

import com.liokasanovich.gun_management.core.GunpackOperator;
import com.liokasanovich.gun_management.core.GunpackPaths;
import com.liokasanovich.gun_management.core.ModrinthClient;
import com.liokasanovich.gun_management.network.PacketSyncGunpacks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Displays missing or mismatched server gunpacks upon login and offers one-click Modrinth resolution by SHA-256.
 */
public class SyncConfirmScreen extends Screen {
    @Nullable
    private final Screen parent;
    private final List<PacketSyncGunpacks.MismatchEntry> mismatches;
    private Button installAllButton;
    private boolean installing = false;
    private String statusText = "";

    public SyncConfirmScreen(@Nullable Screen parent, List<PacketSyncGunpacks.MismatchEntry> mismatches) {
        super(Component.translatable("gui.gun_management.sync.title"));
        this.parent = parent;
        this.mismatches = mismatches;
    }

    @Override
    protected void init() {
        int btnY = this.height - 32;
        int centerX = this.width / 2;

        installAllButton = Button.builder(
                        Component.translatable("gui.gun_management.sync.install_all"),
                        b -> installMissingFromModrinth())
                .bounds(centerX - 165, btnY, 180, 20)
                .build();
        installAllButton.active = !installing;
        addRenderableWidget(installAllButton);

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.gun_management.sync.ignore"),
                        b -> onClose())
                .bounds(centerX + 25, btnY, 140, 20)
                .build());
    }

    private void installMissingFromModrinth() {
        if (installing) {
            return;
        }
        installing = true;
        if (installAllButton != null) {
            installAllButton.active = false;
        }
        statusText = Component.translatable("gui.gun_management.sync.resolving").getString();

        List<CompletableFuture<Boolean>> tasks = new ArrayList<>();
        for (PacketSyncGunpacks.MismatchEntry entry : mismatches) {
            String sha256 = entry.serverPack().sha256();
            if (sha256 == null || sha256.isBlank()) {
                tasks.add(CompletableFuture.completedFuture(false));
                continue;
            }
            CompletableFuture<Boolean> task = ModrinthClient.findVersionBySha256Async(sha256)
                    .thenCompose(optVersion -> {
                        if (optVersion.isEmpty()) {
                            return CompletableFuture.completedFuture(false);
                        }
                        return ModrinthClient.downloadAndInstallAsync(optVersion.get(), GunpackPaths.getTaczDir(), null)
                                .thenApply(path -> true);
                    })
                    .exceptionally(ex -> false);
            tasks.add(task);
        }

        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).thenRun(() -> {
            long installedCount = tasks.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count();
            Minecraft.getInstance().execute(() -> {
                installing = false;
                if (installedCount > 0) {
                    statusText = Component.translatable("gui.gun_management.sync.installed_count",
                            installedCount, mismatches.size()).getString();
                    GunpackOperator.reloadAllPacks();
                } else {
                    statusText = Component.translatable("gui.gun_management.sync.none_found").getString();
                }
                if (installAllButton != null) {
                    installAllButton.active = true;
                }
            });
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, this.title, centerX, 20, 0xFFAA00);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.gun_management.sync.subtitle", mismatches.size()),
                centerX, 36, 0xCCCCCC);

        int y = 58;
        int maxVisible = Math.max(1, (this.height - 110) / 14);
        for (int i = 0; i < Math.min(mismatches.size(), maxVisible); i++) {
            PacketSyncGunpacks.MismatchEntry entry = mismatches.get(i);
            Component state = entry.hashMismatch()
                    ? Component.translatable("gui.gun_management.sync.mismatch").withStyle(ChatFormatting.RED)
                    : Component.translatable("gui.gun_management.sync.missing").withStyle(ChatFormatting.YELLOW);
            Component line = Component.literal("• " + entry.serverPack().displayName()
                    + " (" + entry.serverPack().namespace() + ") - ").append(state);
            graphics.drawCenteredString(this.font, line, centerX, y, 0xFFFFFF);
            y += 14;
        }

        if (!statusText.isBlank()) {
            graphics.drawCenteredString(this.font, statusText, centerX, this.height - 52, 0x88FF88);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
