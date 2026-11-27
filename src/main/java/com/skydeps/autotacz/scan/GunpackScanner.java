package com.skydeps.autotacz.scan;

import com.skydeps.autotacz.model.Gunpack;
import com.skydeps.autotacz.model.InstalledPack;
import com.skydeps.autotacz.model.PackSource;
import com.skydeps.autotacz.model.PackState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 本地枪包扫描器。
 *
 * <p>职责边界：<b>只负责发现</b>——遍历目录、判断形态、交给
 * {@link GunpackMetadataReader} 解析，然后把结果组装成 {@link InstalledPack}。
 * 不做缓存、不做搜索、不做文件移动，因此可以被反复调用而不产生副作用。
 *
 * <p>目录约定（与 TACZ 本体一致）：
 * <pre>
 * &lt;gameDir&gt;/tacz/              已启用的枪包（目录或 .zip）
 * &lt;gameDir&gt;/tacz/.disabled/    已禁用的枪包
 * </pre>
 *
 * <p>扫描是容错的：单个包损坏只记录到 {@link ScanResult#failures()}，
 * 不影响其余包。
 */
public final class GunpackScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(GunpackScanner.class);

    /** TACZ 的枪包根目录名。 */
    public static final String TACZ_DIR = "tacz";

    /** 禁用区目录名。前缀 {@code .} 让 TACZ 原生扫描器忽略它。 */
    public static final String DISABLED_DIR = ".disabled";

    private final Path taczRoot;
    private final boolean computeHashes;

    /**
     * @param taczRoot      枪包根目录（通常是 {@code <gameDir>/tacz}）
     * @param computeHashes 是否计算 SHA-256。扫描大目录时哈希是主要开销，
     *                      索引展示可以关掉，需要「比对远程版本」时再开
     */
    public GunpackScanner(Path taczRoot, boolean computeHashes) {
        this.taczRoot = taczRoot;
        this.computeHashes = computeHashes;
    }

    /** 便捷构造：从游戏根目录定位到 {@code tacz/}。 */
    public static GunpackScanner forGameDirectory(Path gameDir, boolean computeHashes) {
        return new GunpackScanner(gameDir.resolve(TACZ_DIR), computeHashes);
    }

    public Path taczRoot() {
        return taczRoot;
    }

    public Path enabledRoot() {
        return taczRoot;
    }

    public Path disabledRoot() {
        return taczRoot.resolve(DISABLED_DIR);
    }

    /**
     * 执行一次完整扫描（启用区 + 禁用区）。
     *
     * <p>结果按 namespace 排序，保证多次扫描输出顺序稳定，便于 diff 与测试。
     */
    public ScanResult scan() {
        List<InstalledPack> packs = new ArrayList<>();
        List<ScanFailure> failures = new ArrayList<>();

        scanArea(enabledRoot(), PackState.ENABLED, packs, failures);
        scanArea(disabledRoot(), PackState.DISABLED, packs, failures);

        packs.sort(Comparator.comparing(InstalledPack::namespace, String.CASE_INSENSITIVE_ORDER));
        return new ScanResult(List.copyOf(packs), List.copyOf(failures));
    }

    /**
     * 扫描单个区域。
     *
     * @param area     要扫描的目录
     * @param state    该区域内枪包的安装状态
     */
    private void scanArea(Path area, PackState state,
                          List<InstalledPack> out, List<ScanFailure> failures) {
        if (!Files.isDirectory(area)) {
            return;
        }

        List<Path> candidates = listCandidates(area, failures);
        for (Path candidate : candidates) {
            try {
                InstalledPack pack = readOne(candidate, state);
                if (pack != null) {
                    out.add(pack);
                } else {
                    // 缺少 gunpack.meta.json —— 不是枪包，属于正常情况（如附带资源）
                    LOGGER.debug("跳过非枪包条目: {}", candidate);
                }
            } catch (IOException e) {
                failures.add(new ScanFailure(candidate, describe(e)));
                LOGGER.warn("读取枪包失败: {} ({})", candidate, describe(e));
            }
        }
    }

    /**
     * 列出区域内的候选条目：目录或 .zip 文件。
     *
     * <p>跳过以 {@code .} 开头的条目，避免把 {@code .disabled} 当成枪包递归读入。
     */
    private List<Path> listCandidates(Path area, List<ScanFailure> failures) {
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> stream = Files.list(area)) {
            stream.filter(p -> !isHidden(p))
                    .filter(p -> Files.isDirectory(p) || isZip(p))
                    .sorted()
                    .forEach(candidates::add);
        } catch (IOException e) {
            failures.add(new ScanFailure(area, describe(e)));
            LOGGER.warn("无法列出目录 {}: {}", area, describe(e));
        }
        return candidates;
    }

    /**
     * 异常转可读文本。
     *
     * <p>{@code getMessage()} 可能为 {@code null}（如部分 NIO 异常），
     * 直接拼接会产生「失败: null」这种无用信息。
     */
    private static String describe(IOException e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank())
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + msg;
    }

    private static boolean isHidden(Path path) {
        Path name = path.getFileName();
        return name == null || name.toString().startsWith(".");
    }

    private static boolean isZip(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".zip");
    }

    /**
     * 解析单个候选条目。
     *
     * @return 解析出的安装包；非枪包时返回 {@code null}
     */
    @Nullable
    private InstalledPack readOne(Path candidate, PackState state) throws IOException {
        PackSource source = buildSource(candidate);
        try (PackContent content = openContent(candidate)) {
            Gunpack pack = GunpackMetadataReader.read(content, source);
            return pack == null ? null : new InstalledPack(pack, state);
        }
    }

    private PackContent openContent(Path candidate) throws IOException {
        return Files.isDirectory(candidate)
                ? new DirectoryPackContent(candidate)
                : new ZipPackContent(candidate);
    }

    private PackSource buildSource(Path candidate) throws IOException {
        long lastModified = Files.getLastModifiedTime(candidate).toMillis();
        if (Files.isDirectory(candidate)) {
            return PackSource.ofDirectory(candidate, lastModified);
        }
        long size = Files.size(candidate);
        String hash = computeHashes ? sha256(candidate) : PackSource.UNKNOWN_HASH;
        return PackSource.ofArchive(candidate, size, hash, lastModified);
    }

    /** 计算文件 SHA-256；失败时返回 {@link PackSource#UNKNOWN_HASH} 而非抛异常。 */
    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            LOGGER.debug("计算哈希失败: {} ({})", file, e.getMessage());
            return PackSource.UNKNOWN_HASH;
        }
    }

    /**
     * 一次扫描的完整结果。
     *
     * @param packs    成功解析的枪包（已按 namespace 排序）
     * @param failures 无法读取的条目
     */
    public record ScanResult(List<InstalledPack> packs, List<ScanFailure> failures) {

        public static final ScanResult EMPTY = new ScanResult(List.of(), List.of());

        public ScanResult {
            packs = List.copyOf(packs);
            failures = List.copyOf(failures);
        }

        public int enabledCount() {
            return (int) packs.stream().filter(InstalledPack::enabled).count();
        }

        public int disabledCount() {
            return packs.size() - enabledCount();
        }

        /** 是否需要向用户提示部分失败。 */
        public boolean hasFailures() {
            return !failures.isEmpty();
        }
    }

    /**
     * 单条扫描失败记录。
     *
     * @param path   出问题的路径
     * @param reason 原因（用于日志与界面提示）
     */
    public record ScanFailure(Path path, String reason) {
    }
}