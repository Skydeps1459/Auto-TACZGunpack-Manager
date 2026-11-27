package com.liokasanovich.gun_management.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.liokasanovich.gun_management.core.GunpackInfo;
import com.liokasanovich.gun_management.core.GunpackOperator;
import com.liokasanovich.gun_management.core.GunpackPaths;
import com.liokasanovich.gun_management.core.GunpackScanner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Utility command {@code /gunmanager} for testing local gunpack scanning, toggling, and TaCZ reload.
 */
public final class GunManagerCommand {
    private GunManagerCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gunmanager")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list").executes(GunManagerCommand::listPacks))
                .then(Commands.literal("reload").executes(GunManagerCommand::reloadPacks))
                .then(Commands.literal("enable")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(ctx -> togglePack(ctx, true))))
                .then(Commands.literal("disable")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(ctx -> togglePack(ctx, false)))));
    }

    private static int listPacks(CommandContext<CommandSourceStack> ctx) {
        Path taczDir = GunpackPaths.getTaczDir();
        Set<Path> pending = GunpackOperator.getPendingRestartPaths(taczDir);
        List<GunpackInfo> packs = GunpackScanner.scanAll(taczDir, pending);
        if (packs.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("[GunManager] No gunpacks found in " + taczDir), false);
            return Command.SINGLE_SUCCESS;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[GunManager] Found " + packs.size() + " gunpack(s):"), false);
        for (GunpackInfo pack : packs) {
            String state = pack.pendingRestart() ? "PENDING_RESTART" : (pack.enabled() ? "ENABLED" : "DISABLED");
            String line = String.format(" - %s (%s) [%s] v%s | guns=%d ammo=%d att=%d",
                    pack.namespace(), pack.displayName(), state, pack.version(),
                    pack.gunCount(), pack.ammoCount(), pack.attachmentCount());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int togglePack(CommandContext<CommandSourceStack> ctx, boolean enable) {
        if (!enable) {
            ctx.getSource().sendFailure(Component.translatable("gui.gun_management.disable_in_world_blocked"));
            return 0;
        }
        String namespace = StringArgumentType.getString(ctx, "namespace");
        Path taczDir = GunpackPaths.getTaczDir();
        Set<Path> pending = GunpackOperator.getPendingRestartPaths(taczDir);
        List<GunpackInfo> packs = GunpackScanner.scanAll(taczDir, pending);

        Optional<GunpackInfo> match = packs.stream()
                .filter(p -> p.namespace().equalsIgnoreCase(namespace))
                .findFirst();

        if (match.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("[GunManager] Gunpack not found: " + namespace));
            return 0;
        }

        GunpackOperator.OperationResult result = GunpackOperator.setEnabled(taczDir, match.get(), enable);
        if (result.status() == GunpackOperator.Status.FAILED) {
            ctx.getSource().sendFailure(Component.literal("[GunManager] " + result.message()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("[GunManager] " + result.message()), false);
        if (result.status() == GunpackOperator.Status.SUCCESS) {
            GunpackOperator.reloadAllPacks();
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int reloadPacks(CommandContext<CommandSourceStack> ctx) {
        GunpackOperator.reloadAllPacks();
        ctx.getSource().sendSuccess(() -> Component.literal("[GunManager] Triggered TaCZ gunpack reload."), false);
        return Command.SINGLE_SUCCESS;
    }
}
