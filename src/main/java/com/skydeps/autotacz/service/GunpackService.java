package com.skydeps.autotacz.service;

import com.skydeps.autotacz.model.InstalledPack;
import com.skydeps.autotacz.model.PackState;
import com.skydeps.autotacz.remote.GunpackDownloader;
import com.skydeps.autotacz.remote.RemotePack;
import com.skydeps.autotacz.scan.GunpackIndex;
import com.skydeps.autotacz.scan.GunpackScanner;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

/**
 * 面向用例的编排层：把扫描、索引、下载、状态切换组合成完整操作。
 *
 * <p>设计原则：<b>每个公开方法对应一个用户可见的动作</b>
 * （扫描、启用、禁用、更新、安装）。命令入口只做参数解析并调用这里，
 * 因此业务逻辑不散落在命令类中，未来加 GUI 时可以原样复用。
 *
 * <p>本类持有「当前索引」这一可变量，是唯一有状态的服务。
 * 索引本身不可变，更新时整体替换，避免出现半更新状态。
 */
public final class GunpackService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GunpackService.class);

    private final Path taczRoot;
    private final GunpackDownloader downloader;

    private GunpackIndex index = GunpackIndex.empty();

    public GunpackService(Path taczRoot, GunpackDownloader downloader) {
        this.taczRoot = taczRoot;
        this.downloader = downloader;
    }

    public GunpackService(Path taczRoot) {
        this(taczRoot, new GunpackDownloader());
    }

    public GunpackIndex index() {
        return index;
    }

    public Path taczRoot() {
        return taczRoot;
    }

    // ---------------------------------------------------------------- 扫描

    /**
     * 重新扫描并替换索引。
     *
     * @param computeHashes 是否计算 SHA-256（首次扫描建议关闭，比对更新时再开）
     */
    public GunpackScanner.ScanResult rescan(boolean computeHashes) {
        GunpackScanner scanner = new GunpackScanner(taczRoot, computeHashes);
        GunpackScanner.ScanResult result = scanner.scan();
        this.index = GunpackIndex.from(result);
        LOGGER.info("扫描完成: 共 {} 个枪包（启用 {} / 禁用 {}），失败 {} 条",
                index.size(), result.enabledCount(), result.disabledCount(), result.failures().size());
        return result;
    }

    /** 确保索引非空；首次调用前自动扫描。 */
    public GunpackIndex requireIndex() {
        if (index.isEmpty()) {
            rescan(false);
        }
        return index;
    }

    // ------------------------------------------------------ 启用 / 禁用

    /**
     * 启用一个枪包：从 {@code .disabled/} 移回 {@code tacz/}。
     *
     * @return 操作后的安装包；找不到或无变化时返回 {@code null}
     */
    @Nullable
    public InstalledPack enable(String namespace) throws IOException {
        return moveState(namespace, PackState.ENABLED);
    }

    /**
     * 禁用一个枪包：移入 {@code .disabled/}。
     *
     * <p>选择「移动」而不是 TACZ 的配置项，是因为移动对所有 TACZ 版本都有效，
     * 且禁用后的包仍可被本模组扫描到（便于一键恢复）。
     */
    @Nullable
    public InstalledPack disable(String namespace) throws IOException {
        return moveState(namespace, PackState.DISABLED);
    }

    @Nullable
    private InstalledPack moveState(String namespace, PackState target) throws IOException {
        InstalledPack current = requireIndex().find(namespace).orElse(null);
        if (current == null) {
            return null;
        }
        if (current.state() == target) {
            return current;
        }

        Path from = current.pack().source().path();
        Path toDir = target == PackState.ENABLED
                ? taczRoot
                : taczRoot.resolve(GunpackScanner.DISABLED_DIR);
        Files.createDirectories(toDir);

        Path to = toDir.resolve(from.getFileName());
        if (Files.exists(to)) {
            throw new IOException("目标已存在同名枪包，拒绝覆盖: " + to);
        }

        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        LOGGER.info("{} -> {}", from, to);

        // 移动后路径变了，需要重新扫描该条目以得到正确的 source
        rescan(false);
        return requireIndex().find(namespace).orElse(null);
    }

    // ------------------------------------------------------------ 更新

    /**
     * 检查某个本地枪包是否有远程更新。
     *
     * @param source 远程源
     * @param namespace 本地枪包 namespace
     * @return 更新信息；无更新或本地不存在时为空
     */
    public Optional<UpdateInfo> checkUpdate(com.skydeps.autotacz.remote.GunpackRemoteSource source,
                                            String namespace) throws IOException {
        InstalledPack local = requireIndex().find(namespace).orElse(null);
        if (local == null) {
            return Optional.empty();
        }
        RemotePack remote = source.findByNamespace(local.namespace());
        if (remote == null) {
            return Optional.empty();
        }
        String localVersion = local.pack().version();
        if (!remote.isNewerThan(localVersion)) {
            return Optional.empty();
        }
        return Optional.of(new UpdateInfo(local, remote));
    }

    /**
     * 下载并替换本地枪包。
     *
     * <p>顺序很重要：先下载到临时名，校验通过后再替换旧包。
     * 若直接覆盖，下载失败会让用户既失去旧版本又没有新版本。
     *
     * @param update 更新信息
     * @return 更新后的安装包
     */
    public InstalledPack applyUpdate(UpdateInfo update) throws IOException {
        InstalledPack local = update.local();
        RemotePack remote = update.remote();

        Path targetDir = local.enabled() ? taczRoot : taczRoot.resolve(GunpackScanner.DISABLED_DIR);
        String fileName = safeZipName(local);

        Path oldPath = local.pack().source().path();
        Path backup = null;

        // 备份动作也放进 try：否则「旧包已移走、下载尚未开始」这段空窗一旦异常，
        // 旧包就永久丢失了（既不在原位也不触发回滚）。
        try {
            if (Files.exists(oldPath)) {
                backup = targetDir.resolve(oldPath.getFileName() + ".bak");
                deleteRecursively(backup);
                Files.move(oldPath, backup, StandardCopyOption.REPLACE_EXISTING);
            }

            GunpackDownloader.DownloadResult result = downloader.download(remote, targetDir, fileName);
            // 更新后需要重新记录新包的哈希，因此这里强制计算
            rescan(true);
            InstalledPack updated = requireIndex().find(local.namespace())
                    .orElseThrow(() -> new IOException("更新后重新扫描不到枪包: " + local.namespace()));

            deleteRecursively(backup);
            LOGGER.info("已更新 {} : {} -> {} ({})",
                    local.namespace(), local.pack().version(), remote.version(), result.path());
            return updated;
        } catch (IOException | RuntimeException e) {
            restoreBackup(backup, oldPath);
            throw e;
        }
    }

    /**
     * 递归删除，兼容目录形态的枪包。
     *
     * <p>{@code Files.deleteIfExists} 对非空目录会抛 {@code DirectoryNotEmptyException}，
     * 而 TACZ 枪包经常是未压缩目录，因此必须递归处理。
     */
    private static void deleteRecursively(@Nullable Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path);
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private static void restoreBackup(@Nullable Path backup, Path original) {
        if (backup == null || !Files.exists(backup)) {
            return;
        }
        try {
            // 新包可能已落地（目录形态会冲突），先清掉再恢复
            deleteRecursively(original);
            Files.move(backup, original, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("已回滚到更新前的文件: {}", original);
        } catch (IOException e) {
            LOGGER.error("回滚失败，备份文件保留在 {}: {}", backup, e.getMessage());
        }
    }

    /**
     * 安装一个远程枪包（本地没有时）。
     *
     * @return 安装后的条目
     */
    public InstalledPack install(RemotePack remote) throws IOException {
        String fileName = sanitize(remote.namespace()) + "-" + sanitize(remote.version()) + ".zip";
        downloader.download(remote, taczRoot, fileName);
        rescan(false);
        return requireIndex().find(remote.namespace())
                .orElseThrow(() -> new IOException(
                        "下载完成但扫描不到枪包，可能包内缺少 gunpack.meta.json: " + remote.namespace()));
    }

    private static String safeZipName(InstalledPack local) {
        String existing = local.pack().fileName();
        if (existing.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return existing;
        }
        return sanitize(local.namespace()) + ".zip";
    }

    private static String sanitize(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            boolean legal = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
            sb.append(legal ? c : '_');
        }
        return sb.length() == 0 ? "gunpack" : sb.toString();
    }

    /**
     * 待执行的更新。
     *
     * @param local  本地条目
     * @param remote 远程记录
     */
    public record UpdateInfo(InstalledPack local, RemotePack remote) {

        /** 一句话描述，供命令输出。 */
        public String describe() {
            return local.pack().displayName()
                    + " (" + local.namespace() + "): "
                    + local.pack().version() + " -> " + remote.version();
        }
    }
}