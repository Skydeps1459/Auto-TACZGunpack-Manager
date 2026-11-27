package com.liokasanovich.gun_management.client;

import com.liokasanovich.gun_management.GunManagement;
import com.liokasanovich.gun_management.client.gui.GunpackManagerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Injects the Gunpack Manager entry button into the main menu and pause menu.
 */
@EventBusSubscriber(modid = GunManagement.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
            int width = 104;
            int height = 20;
            int x = screen.width - width - 8;
            int y = screen.height - height - 24;

            event.addListener(Button.builder(
                            Component.translatable("gui.gun_management.menu_button"),
                            btn -> Minecraft.getInstance().setScreen(new GunpackManagerScreen(screen)))
                    .bounds(x, y, width, height)
                    .build());
        }
    }
}
