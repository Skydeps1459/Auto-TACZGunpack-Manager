package com.liokasanovich.gun_management;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> CURSEFORGE_API_KEY = BUILDER
            .comment("Optional CurseForge Core API key (from https://console.curseforge.com/) for CurseForge gunpack search.")
            .define("curseforgeApiKey", "");

    public static final ModConfigSpec SPEC = BUILDER.build();
}
