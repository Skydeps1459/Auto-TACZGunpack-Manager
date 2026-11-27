package com.liokasanovich.gun_management.client;

import com.liokasanovich.gun_management.GunManagement;
import com.liokasanovich.gun_management.client.gui.GunpackManagerScreen;
import com.liokasanovich.gun_management.core.GunpackUpdateChecker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Injects the Gunpack Manager entry button into the main menu and pause menu.
 */
@Mod.EventBusSubscriber(modid = GunManagement.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof TitleScreen) {
            GunpackUpdateChecker.checkOnceOnStartupAsync();
        }
        if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
            int updateCount = GunpackUpdateChecker.getUpdateCount();
            int width = updateCount > 0 ? 128 : 104;
            int height = 20;
            int x = screen.width - width - 8;
            int y = screen.height - height - 24;

            Component label = updateCount > 0
                    ? Component.translatable("gui.gun_management.menu_button_updates", updateCount)
                            .withStyle(ChatFormatting.GOLD)
                    : Component.translatable("gui.gun_management.menu_button");

            event.addListener(Button.builder(
                            label,
                            btn -> Minecraft.getInstance().setScreen(new GunpackManagerScreen(screen)))
                    .bounds(x, y, width, height)
                    .build());
        }
    }
}
