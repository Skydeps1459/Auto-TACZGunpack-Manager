package com.liokasanovich.gun_management;

import net.minecraftforge.common.ForgeConfigSpec;

public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.ConfigValue<String> CURSEFORGE_API_KEY = BUILDER
            .comment("Optional CurseForge Core API key (from https://console.curseforge.com/) for CurseForge gunpack search.")
            .define("curseforgeApiKey", "");

    public static final ForgeConfigSpec SPEC = BUILDER.build();
}
