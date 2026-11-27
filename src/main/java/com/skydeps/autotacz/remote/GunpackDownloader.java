package com.skydeps.autotacz.remote;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 枪包下载器。
 *
 * <p>两个关键行为：
 * <ol>
 *   <li><b>先写临时文件，再原子改名</b>。下载中断不会在枪包目录留下半个 zip
 *       —— TACZ 扫描到损坏的 zip 会报错，用户体验很差。</li>
 *   <li><b>下载即校验</b>。边写边算 SHA-256，远端提供了摘要就比对，
 *       不一致则删除临时文件并报错。</li>
 * </ol>
 *
 * <h2>完整的下载流程</h2>
 * <pre>
 *   tacz/foo.zip.part   ← 流式写入（16 KiB 缓冲），同时累计字节数
 *          │
 *          ├─ 计算 SHA-256
 *          ├─ 若清单给了 sha256 → 比对，不符则删掉 .part 并抛异常
 *          └─ 校验通过 → 删除旧 tacz/foo.zip → 原子改名 .part → foo.zip
 * </pre>
 *
 * <p>注意本类<b>只负责下载到目标目录</b>，不碰"替换已安装枪包"。
 * 已安装包可能是目录形态、可能有备份与回滚需求，那套逻辑在
 * {@code starter.UpdateApplier}。把两件事分开，才能各自测试。
 */
public final class GunpackDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(GunpackDownloader.class);

    /** 临时文件后缀。以 {@code .part} 结尾，且放在目标目录的同级，保证同盘改名是原子操作。 */
    private static final String TEMP_SUFFIX = ".part";

    private final HttpFetcher fetcher;

    /**
     * @param fetcher 已配置好代理与超时的 HTTP 客户端。
     *                通常复用上层那一个，避免每次下载都新建连接池。
     */
    public GunpackDownloader(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** 使用默认配置（自动探测代理、20 秒元数据超时）的便捷构造。 */
    public GunpackDownloader() {
        this(new HttpFetcher());
    }

    /**
     * 下载到目标路径。
     *
     * <p>任何一步失败都会删掉 {@code .part} 临时文件，目标位置保持原样
     * （不会出现「旧的没了、新的没下完」）。
     *
     * @param pack           远程记录，提供下载地址与（可选的）预期摘要
     * @param targetDirectory 目标目录（通常是 {@code tacz/}）
     * @param fileName       保存的文件名，必须以 {@code .zip} 结尾
     * @return 下载结果（含实际哈希与字节数）
     * @throws IOException             网络或磁盘错误
     * @throws ChecksumMismatchException 摘要不匹配
     * @throws IllegalArgumentException  文件名不是 .zip —— 提前拦住，
     *                                   否则 TACZ 根本不会扫描这个文件
     */
    public DownloadResult download(RemotePack pack, Path targetDirectory, String fileName)
            throws IOException {
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("枪包文件名必须是 .zip: " + fileName);
        }
        Files.createDirectories(targetDirectory);

        Path target = targetDirectory.resolve(fileName);
        Path temp = targetDirectory.resolve(fileName + TEMP_SUFFIX);

        // 清理上一次失败留下的临时文件（进程被强杀时会残留）
        Files.deleteIfExists(temp);

        long written;
        String actualHash;
        try {
            written = streamToFile(pack.downloadUri(), temp);
            actualHash = sha256(temp);
            verifyChecksum(pack, actualHash);
        } catch (IOException | RuntimeException e) {
            // 下载或校验失败：立刻清掉 .part，别让它被下次扫描当成枪包
            deleteQuietly(temp);
            throw e;
        }

        // 原子替换：先删旧文件再改名，避免 Windows 上 target 存在时 move 失败
        Files.deleteIfExists(target);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // 某些文件系统（网络盘、部分 FAT）不支持原子改名，退化为普通移动。
            // 这里不抛异常：走到这一步说明内容已校验通过，能装上比"严格原子"更重要。
            LOGGER.warn("原子改名失败，降级为普通移动: {}", atomicFailed.getMessage());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }

        LOGGER.info("下载完成: {} ({} 字节, sha256={})", target, written, shortHash(actualHash));
        return new DownloadResult(target, written, actualHash);
    }

    /**
     * 把响应体流式写入临时文件，返回写入的字节数。
     *
     * <p>用 16 KiB 缓冲而非一次性读入内存：真实枪包可达上百 MB，
     * 整个读进堆里会让客户端在低配机器上卡死甚至 OOM。
     */
    private long streamToFile(URI uri, Path temp) throws IOException {
        HttpResponse<InputStream> response = fetcher.openStream(uri);
        int status = response.statusCode();
        if (status / 100 != 2) {
            closeQuietly(response.body());
            throw new IOException("下载失败，HTTP " + status + ": " + uri);
        }

        long total = 0;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
        }
        if (total == 0) {
            throw new IOException("下载内容为空: " + uri);
        }
        return total;
    }

    /**
     * 校验下载内容的 SHA-256。
     *
     * <p>远端没提供摘要时<b>放行</b>而不是拒绝——很多镜像站没有 sha256 字段，
     * 一律拒绝会让它们完全不可用。此时只记 debug 日志，把风险交给调用方判断
     * （界面可据此提示"该包未经校验"）。
     *
     * @throws ChecksumMismatchException 提供了摘要但不匹配
     */
    private static void verifyChecksum(RemotePack pack, String actual) throws IOException {
        if (!pack.hasChecksum()) {
            // 远端没提供摘要，无法校验；记录以便排查
            LOGGER.debug("{} 未提供 SHA-256，跳过校验", pack.namespace());
            return;
        }
        // 大小写不敏感比对：清单里可能贴的是大写十六进制
        String expected = pack.sha256().trim().toLowerCase(java.util.Locale.ROOT);
        if (!expected.equals(actual)) {
            throw new ChecksumMismatchException(pack.namespace(), expected, actual);
        }
    }

    /**
     * 计算文件 SHA-256。
     *
     * <p>流式分块计算（8 KiB），不把整个文件读进内存。
     */
    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("运行环境缺少 SHA-256 实现", e);
        }
    }

    /**
     * 删除临时文件，失败不抛异常。
     *
     * <p>清理是尽力而为：文件被其他进程占用（杀毒软件扫描）时删不掉也无所谓，
     * 下次下载开始时会再试一次。为了清理失败而掩盖真正的下载错误不划算。
     */
    private static void deleteQuietly(@Nullable Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("清理临时文件失败: {} ({})", path, e.getMessage());
        }
    }

    /**
     * 关闭流，失败不抛异常。用于非 2xx 分支丢弃响应体。
     */
    private static void closeQuietly(@Nullable InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }

    /**
     * 摘要只取前 12 位用于日志，避免刷屏。
     */
    private static String shortHash(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "…";
    }

    /**
     * 下载结果。
     *
     * @param path    最终文件路径
     * @param bytes   实际字节数
     * @param sha256  实际 SHA-256（小写十六进制）；即使清单未提供摘要，这里也一定有值
     */
    public record DownloadResult(Path path, long bytes, String sha256) {
    }

    /**
     * 摘要与远端声明不一致。
     *
     * <p>单独设一个异常类型（而不是复用 {@link IOException}）是为了让上层能区分
     * 「网络问题是暂时性的，可重试」与「内容不符，重试也没用」——
     * 后者通常意味着清单写错了或文件被篡改，应提示用户而不是默默重下。
     */
    public static final class ChecksumMismatchException extends IOException {
        private final String expected;
        private final String actual;

        ChecksumMismatchException(String namespace, String expected, String actual) {
            super("枪包 " + namespace + " 校验失败：期望 " + expected + "，实际 " + actual);
            this.expected = expected;
            this.actual = actual;
        }

        /** 清单声明的摘要。 */
        public String expected() {
            return expected;
        }

        /** 实际下载内容的摘要。 */
        public String actual() {
            return actual;
        }
    }
}