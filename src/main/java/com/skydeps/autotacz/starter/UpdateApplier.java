package com.skydeps.autotacz.starter;

import com.skydeps.autotacz.model.InstalledPack;
import com.skydeps.autotacz.remote.GunpackDownloader;
import com.skydeps.autotacz.remote.RemotePack;
import com.skydeps.autotacz.scan.GunpackScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用更新（下载并替换）。
 *
 * <p>与 {@code GunpackService.applyUpdate} 的区别：本类服务于「启动界面」场景，
 * 需要在下载过程中持续汇报进度给界面，且失败不能中断其余条目。
 * 因此它不做整体重扫描，而是逐条独立失败、最后统一汇报。
 *
 * <p>安全顺序：先下载到 {@code .part}（由 {@link GunpackDownloader} 保证），
 * 校验通过才替换旧文件；替换前把旧文件改名备份，失败则回滚。
 */
public final class UpdateApplier {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateApplier.class);

    private final Path taczRoot;
    private final GunpackDownloader downloader;

    public UpdateApplier(Path taczRoot) {
        this.taczRoot = taczRoot;
        this.downloader = new GunpackDownloader();
    }

    public Path taczRoot() {
        return taczRoot;
    }

    /**
     * 下载并替换单个候选。
     *
     * @return 结果（成功或失败都返回，不抛异常）
     */
    public ApplyResult apply(UpdateChecker.Candidate candidate) {
        InstalledPack local = candidate.local();
        RemotePack remote = candidate.remote();

        Path targetDir = local.enabled()
                ? taczRoot
                : taczRoot.resolve(GunpackScanner.DISABLED_DIR);

        String fileName = targetFileName(local);
        Path oldFile = local.pack().source().path();
        Path backup = null;

        try {
            Files.createDirectories(targetDir);

            if (Files.exists(oldFile)) {
                backup = targetDir.resolve(oldFile.getFileName() + ".bak");
                deleteRecursively(backup);
                moveRecursively(oldFile, backup);
            }

            GunpackDownloader.DownloadResult result = downloader.download(remote, targetDir, fileName);

            // 下载与校验都通过，才真正丢弃备份
            deleteRecursively(backup);
            LOGGER.info("启动时更新完成: {} {} -> {} ({})",
                    local.namespace(), local.pack().version(), remote.version(), result.path());
            return ApplyResult.ok(local.namespace(), remote.version());
        } catch (IOException | RuntimeException e) {
            rollback(backup, oldFile);
            LOGGER.warn("启动时更新失败: {} ({})", local.namespace(), describe(e));
            return ApplyResult.fail(local.namespace(), describe(e));
        }
    }

    /**
     * 移动文件或目录。
     *
     * <p>目录形态的枪包很常见（TACZ 两种都支持），而对非空目录使用
     * {@code REPLACE_EXISTING} 会抛 {@link java.nio.file.DirectoryNotEmptyException}，
     * 因此统一按「先清空目标再移动」处理。
     */
    private static void moveRecursively(Path source, Path target) throws IOException {
        deleteRecursively(target);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 递归删除文件或目录，不存在时静默返回。
     *
     * <p>{@code Files.deleteIfExists} 对非空目录会抛 {@code DirectoryNotEmptyException}，
     * 这是更新目录形态枪包时必须处理的情况——否则备份清不掉，
     * 回滚时又会把 .bak 误当成原包。
     */
    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path);
            return;
        }
        try (var stream = Files.walk(path)) {
            // 逆序保证先删子项再删父目录
            for (Path entry : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    /**
     * 批量应用。
     *
     * @param candidates 用户勾选的候选
     * @param progress   每条开始前的回调，参数为「已完成数 / 总数 / 当前名称」
     */
    public List<ApplyResult> applyAll(List<UpdateChecker.Candidate> candidates, ProgressListener progress) {
        List<ApplyResult> results = new ArrayList<>();
        int total = candidates.size();
        for (int i = 0; i < total; i++) {
            UpdateChecker.Candidate candidate = candidates.get(i);
            if (progress != null) {
                progress.onProgress(i, total, candidate.local().pack().displayName());
            }
            results.add(apply(candidate));
        }
        if (progress != null) {
            progress.onProgress(total, total, "");
        }
        return List.copyOf(results);
    }

    /**
     * 决定保存的文件名。
     *
     * <p>尽量保留本地原有的文件名（用户可能手动改过），
     * 非 zip 形态则用 namespace 生成。
     */
    private static String targetFileName(InstalledPack local) {
        String existing = local.pack().fileName();
        if (existing.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            return existing;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : local.namespace().toCharArray()) {
            boolean legal = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
            sb.append(legal ? c : '_');
        }
        return (sb.length() == 0 ? "gunpack" : sb.toString()) + ".zip";
    }

    private static void rollback(Path backup, Path original) {
        if (backup == null || !Files.exists(backup)) {
            return;
        }
        try {
            // 新包可能已落地，先清掉再恢复旧包，否则目录形态会冲突
            deleteRecursively(original);
            Files.move(backup, original, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("已回滚: {}", original);
        } catch (IOException e) {
            LOGGER.error("回滚失败，备份保留在 {}: {}", backup, e.getMessage());
        }
    }

    private static String describe(Throwable error) {
        StringBuilder sb = new StringBuilder(error.getClass().getSimpleName());
        Throwable cursor = error;
        while (cursor != null) {
            String msg = cursor.getMessage();
            if (msg != null && !msg.isBlank()) {
                return sb.append(": ").append(msg).toString();
            }
            cursor = cursor.getCause();
        }
        return sb.toString();
    }

    /** 进度回调。 */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int done, int total, String currentName);
    }

    /**
     * 单条更新结果。
     *
     * @param namespace 枪包 namespace
     * @param version   更新后的版本（失败时为空）
     * @param error     错误信息（成功时为 {@code null}）
     */
    public record ApplyResult(String namespace, String version, String error) {

        static ApplyResult ok(String namespace, String version) {
            return new ApplyResult(namespace, version, null);
        }

        static ApplyResult fail(String namespace, String error) {
            return new ApplyResult(namespace, "", error);
        }

        public boolean ok() {
            return error == null;
        }
    }
}