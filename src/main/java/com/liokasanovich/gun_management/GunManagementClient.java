package com.liokasanovich.gun_management;

import com.liokasanovich.gun_management.client.gui.GunpackManagerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = GunManagement.MODID, dist = Dist.CLIENT)
public class GunManagementClient {
    public GunManagementClient(ModContainer container) {
        // Clicking "Config" in the Mods list opens the Gunpack Manager directly.
        container.registerExtensionPoint(IConfigScreenFactory.class, (mc, parent) -> new GunpackManagerScreen(parent));
    }
}
