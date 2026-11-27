package com.skydeps.autotacz.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.skydeps.autotacz.model.InstalledPack;
import com.skydeps.autotacz.model.PackState;
import com.skydeps.autotacz.remote.JsonManifestSource;
import com.skydeps.autotacz.remote.RemotePack;
import com.skydeps.autotacz.scan.GunpackIndex;
import com.skydeps.autotacz.scan.GunpackScanner;
import com.skydeps.autotacz.scan.PackQuery;
import com.skydeps.autotacz.service.GunpackService;
import com.skydeps.autotacz.service.PackExporter;
import com.skydeps.autotacz.service.PackConverter;
import com.skydeps.autotacz.service.ExportSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /taczpack} 命令入口。
 *
 * <p>本类只做三件事：解析参数、调用 {@link GunpackService}、把结果转成聊天消息。
 * 不含任何业务规则——这类逻辑全部在 service 层，
 * 因此命令层可以随便重写（换成 GUI 或 HTTP 接口）而不影响核心功能。
 *
 * <p>子命令一览：
 * <pre>
 * /taczpack list [关键字]              列出已安装枪包
 * /taczpack info &lt;namespace&gt;          查看详情
 * /taczpack search &lt;关键字&gt;           搜索（名称/作者/类型/简介）
 * /taczpack type &lt;类型&gt;               按武器类型筛选
 * /taczpack enable|disable &lt;ns&gt;       启用/禁用
 * /taczpack export &lt;输出目录&gt; [格式]   批量导出
 * /taczpack check [清单地址]            检查远程更新
 * /taczpack update &lt;ns&gt; [清单地址]     下载更新
 * /taczpack install &lt;ns&gt; [清单地址]    安装远程枪包
 * /taczpack rescan                     重新扫描
 * </pre>
 */
public final class TaczPackCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaczPackCommand.class);

    /** 一次聊天输出的条目上限，避免刷屏。 */
    private static final int MAX_LINES = 20;

    private final GunpackService service;

    public TaczPackCommand(GunpackService service) {
        this.service = service;
    }

    // ------------------------------------------------------------ 注册

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("taczpack")
                .requires(source -> source.hasPermission(0))
                .executes(ctx -> help(ctx))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx, null))
                        .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "keyword")))))
                .then(Commands.literal("search")
                        .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                .executes(ctx -> search(ctx, StringArgumentType.getString(ctx, "keyword")))))
                .then(Commands.literal("type")
                        .executes(ctx -> typeHistogram(ctx))
                        .then(Commands.argument("weaponType", StringArgumentType.word())
                                .suggests(TYPE_SUGGESTIONS)
                                .executes(ctx -> byType(ctx, StringArgumentType.getString(ctx, "weaponType")))))
                .then(Commands.literal("info")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .suggests(NAMESPACE_SUGGESTIONS)
                                .executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "namespace")))))
                .then(Commands.literal("enable")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .suggests(NAMESPACE_SUGGESTIONS)
                                .executes(ctx -> toggle(ctx, StringArgumentType.getString(ctx, "namespace"), true))))
                .then(Commands.literal("disable")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .suggests(NAMESPACE_SUGGESTIONS)
                                .executes(ctx -> toggle(ctx, StringArgumentType.getString(ctx, "namespace"), false))))
                .then(Commands.literal("export")
                        // 路径用 string() 而非 greedyString()，否则会连同格式参数一起吞掉。
                        // string() 支持引号包裹，因此含空格的路径仍可正常传入。
                        .then(Commands.argument("target", StringArgumentType.string())
                                .executes(ctx -> export(ctx,
                                        StringArgumentType.getString(ctx, "target"), "copy"))
                                .then(Commands.argument("format", StringArgumentType.word())
                                        .suggests(EXPORT_FORMATS)
                                        .executes(ctx -> export(ctx,
                                                StringArgumentType.getString(ctx, "target"),
                                                StringArgumentType.getString(ctx, "format"))))))
                .then(Commands.literal("check")
                        .executes(ctx -> check(ctx, null))
                        .then(Commands.argument("manifest", StringArgumentType.greedyString())
                                .executes(ctx -> check(ctx, StringArgumentType.getString(ctx, "manifest")))))
                .then(Commands.literal("update")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .suggests(NAMESPACE_SUGGESTIONS)
                                .executes(ctx -> update(ctx,
                                        StringArgumentType.getString(ctx, "namespace"), null))))
                .then(Commands.literal("install")
                        .then(Commands.argument("namespace", StringArgumentType.word())
                                .executes(ctx -> install(ctx,
                                        StringArgumentType.getString(ctx, "namespace"), null))))
                .then(Commands.literal("rescan")
                        .executes(ctx -> rescan(ctx)))
        );
    }

    /** namespace 补全：从当前索引取。 */
    private final SuggestionProvider<CommandSourceStack> NAMESPACE_SUGGESTIONS = (ctx, builder) -> {
        for (InstalledPack pack : safeIndex().all()) {
            builder.suggest(pack.namespace());
        }
        return builder.buildFuture();
    };

    /** 武器类型补全：从索引的直方图取。 */
    private final SuggestionProvider<CommandSourceStack> TYPE_SUGGESTIONS = (ctx, builder) -> {
        for (String type : safeIndex().weaponTypeHistogram().keySet()) {
            builder.suggest(type);
        }
        return builder.buildFuture();
    };

    /** 导出格式补全。 */
    private final SuggestionProvider<CommandSourceStack> EXPORT_FORMATS = (ctx, builder) -> {
        builder.suggest("copy");
        builder.suggest("zip");
        builder.suggest("dir");
        return builder.buildFuture();
    };

    private GunpackIndex safeIndex() {
        try {
            return service.requireIndex();
        } catch (RuntimeException e) {
            LOGGER.warn("获取索引失败: {}", e.getMessage());
            return GunpackIndex.empty();
        }
    }

    // ------------------------------------------------------------ 各子命令

    private int help(CommandContext<CommandSourceStack> ctx) {
        send(ctx, Component.literal("Auto TACZ Gunpack Manager").withStyle(ChatFormatting.GOLD));
        line(ctx, "list [关键字]", "列出已安装枪包");
        line(ctx, "search <关键字>", "按名称/作者/类型/简介搜索");
        line(ctx, "type [类型]", "查看或按武器类型筛选");
        line(ctx, "info <namespace>", "查看枪包详情");
        line(ctx, "enable|disable <ns>", "启用或禁用枪包");
        line(ctx, "export <目录>", "批量导出（copy/zip/dir）");
        line(ctx, "check [清单地址]", "检查远程更新");
        line(ctx, "update <ns> [清单]", "下载并更新单个枪包");
        line(ctx, "install <ns> [清单]", "安装远程枪包");
        line(ctx, "rescan", "重新扫描本地枪包");
        return 1;
    }

    private int list(CommandContext<CommandSourceStack> ctx, @Nullable String keyword) {
        PackQuery query = new PackQuery(keyword, null, null, null, PackQuery.Sort.NAME, true);
        return printPacks(ctx, query, "枪包列表");
    }

    private int search(CommandContext<CommandSourceStack> ctx, String keyword) {
        PackQuery query = new PackQuery(keyword, null, null, null, PackQuery.Sort.NAME, true);
        return printPacks(ctx, query, "搜索「" + keyword + "」");
    }

    private int byType(CommandContext<CommandSourceStack> ctx, String type) {
        PackQuery query = new PackQuery(null, null, type, null, PackQuery.Sort.NAME, true);
        return printPacks(ctx, query, "类型 " + type);
    }

    private int typeHistogram(CommandContext<CommandSourceStack> ctx) {
        var histogram = safeIndex().weaponTypeHistogram();
        if (histogram.isEmpty()) {
            send(ctx, Component.literal("包内没有可识别的武器类型信息").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        send(ctx, Component.literal("可用武器类型（包数）:").withStyle(ChatFormatting.GOLD));
        histogram.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> line(ctx, e.getKey(), e.getValue() + " 个包"));
        return histogram.size();
    }

    private int printPacks(CommandContext<CommandSourceStack> ctx, PackQuery query, String title) {
        List<InstalledPack> found = safeIndex().query(query);
        if (found.isEmpty()) {
            send(ctx, Component.literal(title + "：没有匹配的枪包").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        send(ctx, Component.literal(title + "（" + found.size() + " 个）").withStyle(ChatFormatting.GOLD));

        int shown = 0;
        for (InstalledPack pack : found) {
            if (shown++ >= MAX_LINES) {
                send(ctx, Component.literal("…… 还有 " + (found.size() - MAX_LINES) + " 个，请用关键字缩小范围")
                        .withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            send(ctx, Component.literal(formatLine(pack)));
        }
        return found.size();
    }

    /** 单行展示：状态标记 + 名称 + namespace/版本 + 内容统计。 */
    private static String formatLine(InstalledPack pack) {
        String state = pack.enabled() ? "[启用]" : "[禁用]";
        var c = pack.pack().content();
        String types = pack.pack().weaponTypes().isEmpty()
                ? ""
                : " {" + String.join(",", pack.pack().weaponTypes()) + "}";
        return state + " " + pack.pack().displayName()
                + " (" + pack.namespace() + " v" + pack.pack().version() + ")"
                + " 枪" + c.guns() + "/弹" + c.ammo() + "/配" + c.attachments()
                + types;
    }

    private int info(CommandContext<CommandSourceStack> ctx, String namespace) {
        Optional<InstalledPack> found = safeIndex().find(namespace);
        if (found.isEmpty()) {
            sendError(ctx, "未找到枪包: " + namespace);
            return 0;
        }
        InstalledPack installed = found.get();
        var pack = installed.pack();

        send(ctx, Component.literal(pack.displayName()).withStyle(ChatFormatting.GOLD));
        line(ctx, "namespace", pack.namespace());
        line(ctx, "版本", pack.version());
        line(ctx, "状态", installed.state() == PackState.ENABLED ? "启用" : "禁用");
        line(ctx, "作者", pack.authors().isEmpty() ? "(未声明)" : String.join(", ", pack.authors()));
        line(ctx, "许可", pack.license().isBlank() ? "(未声明)" : pack.license());
        line(ctx, "内容", "枪 " + pack.content().guns()
                + " / 弹 " + pack.content().ammo()
                + " / 配件 " + pack.content().attachments());
        line(ctx, "武器类型", pack.weaponTypes().isEmpty() ? "(未声明)" : String.join(", ", pack.weaponTypes()));
        line(ctx, "文件", pack.fileName());
        if (pack.source().hasHash()) {
            line(ctx, "SHA-256", pack.source().sha256());
        }
        if (!pack.description().isBlank()) {
            line(ctx, "简介", pack.description());
        }
        return 1;
    }

    private int toggle(CommandContext<CommandSourceStack> ctx, String namespace, boolean enable) {
        try {
            InstalledPack result = enable ? service.enable(namespace) : service.disable(namespace);
            if (result == null) {
                sendError(ctx, "操作未生效：找不到枪包，或它已处于目标状态: " + namespace);
                return 0;
            }
            send(ctx, Component.literal((enable ? "已启用 " : "已禁用 ") + result.pack().displayName())
                    .withStyle(ChatFormatting.GREEN));
            return 1;
        } catch (IOException e) {
            sendError(ctx, "操作失败: " + describe(e));
            return 0;
        }
    }

    private int export(CommandContext<CommandSourceStack> ctx, String target, String formatName) {
        Path dest = resolvePath(target);
        PackExporter.Format format;
        switch (formatName.toLowerCase(Locale.ROOT)) {
            case "copy" -> format = PackExporter.Format.COPY;
            case "zip", "archive" -> format = PackExporter.Format.AS_ARCHIVE;
            case "dir", "directory" -> format = PackExporter.Format.AS_DIRECTORY;
            default -> {
                // 不静默降级：静默用错格式会让用户以为导出成功
                sendError(ctx, "未知导出格式: " + formatName + "（可用 copy / zip / dir）");
                return 0;
            }
        }

        List<InstalledPack> enabled = safeIndex().enabledOnly().all();
        if (enabled.isEmpty()) {
            sendError(ctx, "没有可导出的启用枪包");
            return 0;
        }
        try {
            PackExporter exporter = new PackExporter(safeIndex());
            var outcomes = exporter.export(enabled, dest, format);
            long ok = outcomes.stream().filter(PackExporter.ExportOutcome::ok).count();
            send(ctx, Component.literal("已导出 " + ok + "/" + outcomes.size() + " 个枪包 -> " + dest)
                    .withStyle(ChatFormatting.GREEN));
            outcomes.stream().filter(o -> !o.ok()).forEach(o ->
                    line(ctx, "失败 " + o.namespace(), String.valueOf(o.error())));
            return (int) ok;
        } catch (IOException e) {
            sendError(ctx, "导出失败: " + describe(e));
            return 0;
        }
    }

    private int check(CommandContext<CommandSourceStack> ctx, @Nullable String manifest) {
        try {
            JsonManifestSource source = source(manifest);
            List<GunpackService.UpdateInfo> updates = new ArrayList<>();
            for (InstalledPack pack : safeIndex().all()) {
                Optional<GunpackService.UpdateInfo> info = service.checkUpdate(source, pack.namespace());
                info.ifPresent(updates::add);
            }
            if (updates.isEmpty()) {
                send(ctx, Component.literal("所有枪包均为最新").withStyle(ChatFormatting.GREEN));
                return 0;
            }
            send(ctx, Component.literal("发现 " + updates.size() + " 个可更新:").withStyle(ChatFormatting.GOLD));
            updates.forEach(u -> send(ctx, Component.literal(u.describe())));
            return updates.size();
        } catch (IOException e) {
            sendError(ctx, "检查更新失败: " + describe(e));
            return 0;
        }
    }

    private int update(CommandContext<CommandSourceStack> ctx, String namespace, @Nullable String manifest) {
        try {
            JsonManifestSource source = source(manifest);
            Optional<GunpackService.UpdateInfo> info = service.checkUpdate(source, namespace);
            if (info.isEmpty()) {
                send(ctx, Component.literal(namespace + " 已是最新或不在远程源中").withStyle(ChatFormatting.GRAY));
                return 0;
            }
            InstalledPack updated = service.applyUpdate(info.get());
            send(ctx, Component.literal("已更新 " + updated.pack().displayName()
                    + " -> v" + updated.pack().version()).withStyle(ChatFormatting.GREEN));
            return 1;
        } catch (IOException e) {
            sendError(ctx, "更新失败: " + describe(e));
            return 0;
        }
    }

    private int install(CommandContext<CommandSourceStack> ctx, String namespace, @Nullable String manifest) {
        try {
            JsonManifestSource source = source(manifest);
            RemotePack remote = source.findByNamespace(namespace);
            if (remote == null) {
                sendError(ctx, "远程源中没有 " + namespace);
                return 0;
            }
            InstalledPack installed = service.install(remote);
            send(ctx, Component.literal("已安装 " + installed.pack().displayName()
                    + " v" + installed.pack().version()).withStyle(ChatFormatting.GREEN));
            return 1;
        } catch (IOException e) {
            sendError(ctx, "安装失败: " + describe(e));
            return 0;
        }
    }

    private int rescan(CommandContext<CommandSourceStack> ctx) {
        GunpackScanner.ScanResult result = service.rescan(true);
        send(ctx, Component.literal("扫描完成：启用 " + result.enabledCount()
                + " / 禁用 " + result.disabledCount()
                + "，失败 " + result.failures().size()).withStyle(ChatFormatting.GREEN));
        result.failures().forEach(f -> line(ctx, "失败 " + f.path().getFileName(), f.reason()));
        return result.packs().size();
    }

    // ------------------------------------------------------------ 工具

    /**
     * 构造远程源。
     *
     * <p>未指定清单时使用仓库内的默认清单地址；
     * 允许玩家传自定义 URL，便于自建镜像。
     */
    private static JsonManifestSource source(@Nullable String manifest) throws IOException {
        URI uri = (manifest == null || manifest.isBlank())
                ? ExportSupport.DEFAULT_MANIFEST
                : URI.create(manifest.trim());
        return new JsonManifestSource("manifest", uri, new com.skydeps.autotacz.remote.HttpFetcher());
    }

    /**
     * 把用户输入的路径解析为绝对路径。
     *
     * <p>相对路径按当前工作目录解析——服务端即游戏目录，符合直觉。
     */
    private static Path resolvePath(String input) {
        Path path = Path.of(input.trim());
        return path.isAbsolute() ? path : Path.of("").toAbsolutePath().resolve(path).normalize();
    }

    private static void send(CommandContext<CommandSourceStack> ctx, Component message) {
        ctx.getSource().sendSuccess(() -> message, false);
    }

    private static void sendError(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    /**
     * 把异常转成可读文本。
     *
     * <p>{@code getMessage()} 在网络异常上经常是 {@code null}
     * （例如 {@code ConnectException} 只带类名），直接打印会变成「失败: null」，
     * 对排查毫无帮助，因此这里始终带上异常类名，并沿 cause 链取第一个有消息的。
     */
    private static String describe(Throwable error) {
        StringBuilder sb = new StringBuilder(error.getClass().getSimpleName());
        Throwable cursor = error;
        while (cursor != null) {
            String msg = cursor.getMessage();
            if (msg != null && !msg.isBlank()) {
                sb.append(": ").append(msg);
                break;
            }
            cursor = cursor.getCause();
        }
        return sb.toString();
    }

    private static void line(CommandContext<CommandSourceStack> ctx, String key, String value) {
        send(ctx, Component.literal("  " + key + ": ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
    }
}