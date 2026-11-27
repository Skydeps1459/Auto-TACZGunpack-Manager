package com.skydeps.autotacz.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时更新检查的配置。
 *
 * <p>刻意不使用 Forge 的 {@code ForgeConfigSpec}：本功能在客户端启动早期就要读取配置，
 * 而 {@code ModConfigEvent} 的加载时序依赖模组加载顺序，容易出现「配置还没加载完就被读」。
 * 一份独立的 JSON 文件读取时机完全可控，也便于用户手工编辑。
 *
 * <p>文件位置：{@code config/autotacz-starter.json}。文件不存在时会写入默认值，
 * 让用户知道有这个开关。
 *
 * @param enabled        是否在启动时自动检查
 * @param manifestUrl    远程清单地址
 * @param targetPatterns 需要检查的枪包匹配模式（namespace 或名称包含即算命中，忽略大小写）
 * @param timeoutSeconds 单个网络请求的超时秒数，超时即视为网络异常
 * @param autoContinue   无可用更新时是否自动进入游戏
 */
public record StarterConfig(
        boolean enabled,
        String manifestUrl,
        List<String> targetPatterns,
        int timeoutSeconds,
        boolean autoContinue
) {

    private static final Logger LOGGER = LoggerFactory.getLogger(StarterConfig.class);

    /** 默认只关心 MW4 枪包。 */
    public static final List<String> DEFAULT_PATTERNS = List.of("mw4");

    /**
     * 默认清单地址：走 jsDelivr CDN。
     *
     * <p>选 CDN 而不是 {@code raw.githubusercontent.com}，是因为后者的若干 IP
     * 在国内网络下会被阻断（实测 185.199.108/109 不可达，仅 110 可用），
     * 命中不可达 IP 时玩家会直接看到「网络异常」。
     * jsDelivr 对 GitHub 仓库有稳定镜像，实测可用，且自带多节点回退。
     */
    public static final String DEFAULT_MANIFEST =
            "https://cdn.jsdelivr.net/gh/Skydeps1459/Auto-TACZGunpack-Manager@main/packs.json";

    /**
     * 备用清单地址。主地址失败时依次尝试。
     *
     * <p>CDN 也有偶发故障，因此保留 raw 作为兜底——
     * 两个都失败才判定为网络异常并让玩家跳过。
     */
    public static final List<String> FALLBACK_MANIFESTS = List.of(
            "https://raw.githubusercontent.com/Skydeps1459/Auto-TACZGunpack-Manager/main/packs.json");

    /**
     * 默认配置。
     *
     * <p>超时选 8 秒：过短会在慢网下误判为异常，过长则让玩家干等。
     */
    public static StarterConfig defaults() {
        return new StarterConfig(true, DEFAULT_MANIFEST, DEFAULT_PATTERNS, 8, true);
    }

    public StarterConfig {
        manifestUrl = (manifestUrl == null || manifestUrl.isBlank()) ? DEFAULT_MANIFEST : manifestUrl.trim();
        targetPatterns = (targetPatterns == null || targetPatterns.isEmpty())
                ? DEFAULT_PATTERNS
                : List.copyOf(targetPatterns);
        // 夹在合理区间，避免用户把超时设成 0 或 9999 造成卡死或误判
        timeoutSeconds = Math.min(60, Math.max(2, timeoutSeconds));
    }

    /** 返回仅修改启用开关的副本。 */
    public StarterConfig withEnabled(boolean value) {
        return new StarterConfig(value, manifestUrl, targetPatterns, timeoutSeconds, autoContinue);
    }

    /**
     * 从文件读取配置；文件缺失或损坏时回退默认值（并尝试写入默认文件）。
     *
     * @param file 配置文件路径
     */
    public static StarterConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            StarterConfig created = defaults();
            created.save(file);
            return created;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Dto dto = GSON.fromJson(reader, Dto.class);
            if (dto == null) {
                return defaults();
            }
            return new StarterConfig(
                    dto.enabled == null || dto.enabled,
                    dto.manifestUrl,
                    dto.targetPatterns,
                    dto.timeoutSeconds == null ? 8 : dto.timeoutSeconds,
                    dto.autoContinue == null || dto.autoContinue
            );
        } catch (IOException | JsonParseException e) {
            // 配置坏了不应该妨碍启动，直接用默认值
            LOGGER.warn("读取启动配置失败，使用默认值: {} ({})", file, e.getMessage());
            return defaults();
        }
    }

    /** 保存配置；失败只记录日志，不抛给调用方。 */
    public void save(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, PRETTY_GSON.toJson(new Dto(this)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("保存启动配置失败: {} ({})", file, e.getMessage());
        }
    }

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    /**
     * 可空字段的传输对象。
     *
     * <p>不用 record 直接反序列化，是为了在字段缺失时能区分「未设置」与「设为 false」，
     * 从而让默认值（enabled=true）真正生效。
     */
    private static final class Dto {
        Boolean enabled;
        String manifestUrl;
        List<String> targetPatterns;
        Integer timeoutSeconds;
        Boolean autoContinue;

        Dto() {
        }

        Dto(StarterConfig config) {
            this.enabled = config.enabled();
            this.manifestUrl = config.manifestUrl();
            this.targetPatterns = new ArrayList<>(config.targetPatterns());
            this.timeoutSeconds = config.timeoutSeconds();
            this.autoContinue = config.autoContinue();
        }
    }
}