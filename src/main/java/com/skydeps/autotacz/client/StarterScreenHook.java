package com.skydeps.autotacz.client;

import com.skydeps.autotacz.AutoTaczGunpackManager;
import com.skydeps.autotacz.config.StarterConfig;
import com.skydeps.autotacz.starter.UpdateApplier;
import com.skydeps.autotacz.starter.UpdateChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * 启动检查的客户端接线。
 *
 * <p>为什么挂在 {@link ScreenEvent.Init.Post}（主菜单初始化完成）而不是
 * 更早的加载阶段：
 * <ul>
 *   <li>此时资源、字体、音效系统都已就绪，窗口能正常渲染</li>
 *   <li>Minecraft 实例可安全调用 {@code setScreen}</li>
 *   <li>比模组构造期晚，不拖慢加载</li>
 * </ul>
 *
 * <p>只对「本次启动的第一次主菜单」生效。判断依据是 {@link #worldJoined}：
 * 玩家进过世界后再退回主菜单不应再弹窗，否则每次退出存档都会被拦一次。
 * 用「是否进过世界」而不是检查 {@code minecraft.level}，是因为退回主菜单时
 * level 已被清空，那时无法区分「首次进入」与「从世界返回」。
 */
@Mod.EventBusSubscriber(
        modid = AutoTaczGunpackManager.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class StarterScreenHook {

    /** 本次进程是否已经处理过（弹过窗或判定无需弹窗）。 */
    private static boolean handledThisLaunch;

    /** 是否进入过世界。一旦为 true，后续主菜单不再弹窗。 */
    private static boolean worldJoined;

    private StarterScreenHook() {
    }

    /** 进入世界（单人/多人）时置位。 */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        worldJoined = true;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (handledThisLaunch || worldJoined) {
            return;
        }
        if (!(event.getScreen() instanceof TitleScreen)) {
            return;
        }
        handledThisLaunch = true;

        Path gameDir = FMLPaths.GAMEDIR.get();
        StarterConfig config = StarterConfig.load(
                gameDir.resolve("config").resolve("autotacz-starter.json"));
        if (!config.enabled()) {
            AutoTaczGunpackManager.LOGGER.info("启动更新检查已关闭，直接进入主菜单");
            return;
        }

        Path taczRoot = gameDir.resolve("tacz");
        UpdateChecker checker = new UpdateChecker(config, taczRoot);
        UpdateApplier applier = new UpdateApplier(taczRoot);

        AutoTaczGunpackManager.LOGGER.info("开始启动前更新检查，清单: {}", config.manifestUrl());

        // 先挂界面再开始检查：即使网络立刻失败，界面也已就位
        Minecraft.getInstance().setScreen(new StarterScreen(checker, applier));
        checker.startAsync(null);
    }
}