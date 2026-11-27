package com.skydeps.autotacz;

import com.mojang.logging.LogUtils;
import com.skydeps.autotacz.command.TaczPackCommand;
import com.skydeps.autotacz.service.GunpackService;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Auto TACZ Gunpack Manager — 模组入口。
 *
 * <p>本类只负责装配（wiring）：把各个服务组件注册到对应的生命周期事件上。
 * 具体业务逻辑分布在下列包中：
 * <ul>
 *   <li>{@code model}   —— 不可变领域模型</li>
 *   <li>{@code scan}    —— 本地枪包扫描与索引</li>
 *   <li>{@code remote}  —— 远程源检索与下载</li>
 *   <li>{@code service} —— 面向用例的编排层</li>
 *   <li>{@code command} —— 用户入口</li>
 * </ul>
 */
@Mod(AutoTaczGunpackManager.MOD_ID)
public final class AutoTaczGunpackManager {

    public static final String MOD_ID = "autotaczgunpackmanager";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 全局服务实例。命令层与未来的 GUI 共用同一份索引。 */
    private final GunpackService service;

    public AutoTaczGunpackManager() {
        Path taczRoot = FMLPaths.GAMEDIR.get().resolve("tacz");
        this.service = new GunpackService(taczRoot);

        // 用注解总线注册自身，避免额外的 EventBusSubscriber 类
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("Auto TACZ Gunpack Manager 已加载，枪包目录: {}", taczRoot.toAbsolutePath());
    }

    /**
     * 服务端启动时做一次预热扫描。
     *
     * <p>放在这里而不是构造函数里，是因为构造期游戏目录可能尚未准备好；
     * 且扫描是 IO 操作，不宜阻塞模组加载。
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        try {
            var result = service.rescan(false);
            LOGGER.info("初始扫描: {} 个枪包（启用 {} / 禁用 {}），{} 条读取失败",
                    result.packs().size(), result.enabledCount(),
                    result.disabledCount(), result.failures().size());
        } catch (RuntimeException e) {
            // 扫描失败不应阻止服务端启动
            LOGGER.error("初始扫描失败: {}", e.getMessage(), e);
        }
    }

    /** 注册 {@code /taczpack} 命令。 */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        new TaczPackCommand(service).register(event.getDispatcher());
        LOGGER.debug("已注册 /taczpack 命令");
    }
}