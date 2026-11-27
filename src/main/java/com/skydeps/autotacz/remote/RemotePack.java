package com.skydeps.autotacz.remote;

import com.skydeps.autotacz.model.Versions;

import java.net.URI;
import java.util.Objects;

/**
 * 远程枪包的一条发布记录。
 *
 * <p>不依赖具体的源实现：无论来自 GitHub Release、静态 JSON 清单还是镜像站，
 * 都先归一化成这个类型再做后续比较与下载。这样新增源时只需实现
 * {@link GunpackRemoteSource}，下载与安装逻辑完全不用动。
 *
 * <h2>「未知」的统一约定</h2>
 * <p>三个可选字段各有固定的「未知」表示，调用方据此判断，不要混用：
 * <table border="1">
 *   <caption>可选字段的未知值</caption>
 *   <tr><th>字段</th><th>未知值</th><th>判断方法</th></tr>
 *   <tr><td>{@code sizeBytes}</td><td>{@code -1}</td><td>{@code sizeBytes < 0}</td></tr>
 *   <tr><td>{@code sha256}</td><td>{@code null}</td><td>{@link #hasChecksum()}</td></tr>
 *   <tr><td>{@code version}</td><td>空串 {@code ""}</td><td>{@code version.isBlank()}</td></tr>
 * </table>
 *
 * @param namespace   枪包命名空间（用于与本地包匹配）。<b>必须与包内
 *                    {@code gunpack.meta.json} 的 namespace 完全一致</b>，
 *                    否则永远匹配不上本地包，更新检查会静默失效
 * @param displayName 展示名称；空白时自动回退为 {@code namespace}
 * @param version     版本号；未知时为空串而非 {@code null}
 * @param downloadUrl 下载地址（.zip）
 * @param pageUrl     详情页地址，无则为空串
 * @param sizeBytes   文件大小；未知为 -1
 * @param sha256      预期 SHA-256；未知为 {@code null}
 * @param sourceName  来源名称（用于界面显示与日志）
 */
public record RemotePack(
        String namespace,
        String displayName,
        String version,
        String downloadUrl,
        String pageUrl,
        long sizeBytes,
        String sha256,
        String sourceName
) {

    /**
     * 归一化可选字段，保证后续代码只需处理一套约定。
     *
     * <p>只对 {@code namespace} 与 {@code downloadUrl} 做非空校验——这两个缺了
     * 记录就没有意义。其余字段一律兜底成上面表格里的「未知值」，
     * 因为清单里漏填 name / version 是常见情况，不该导致整条记录不可用。
     */
    public RemotePack {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(downloadUrl, "downloadUrl");
        displayName = (displayName == null || displayName.isBlank()) ? namespace : displayName;
        version = version == null ? "" : version;
        pageUrl = pageUrl == null ? "" : pageUrl;
        sourceName = sourceName == null ? "" : sourceName;
    }

    /**
     * 是否提供了可校验的摘要。
     *
     * <p>{@link GunpackDownloader} 据此决定要不要强制比对；
     * 界面也可用它提示「此包未经校验」。
     */
    public boolean hasChecksum() {
        return sha256 != null && !sha256.isBlank();
    }

    /**
     * 下载地址转 URI。
     *
     * <p>不做校验：地址合法性由 {@code HttpFetcher} 在实际请求时暴露，
     * 提前解析失败反而会干扰清单的容错逻辑。
     */
    public URI downloadUri() {
        return URI.create(downloadUrl);
    }

    /**
     * 判断本记录是否比本地版本更新。
     *
     * <p>版本号无法比较出结果（例如两端都是空串）时按「不算更新」处理，
     * 避免对用户反复提示虚假更新。
     *
     * <p>比较规则见 {@link Versions#compare}：只比主版本数字，
     * 后缀（{@code -Release} 等）不参与。这一条很关键——MW4 的真实版本号就是
     * {@code 1.13.0-Release}，若把后缀当"更高"会每次启动都误报更新。
     */
    public boolean isNewerThan(String localVersion) {
        if (version.isBlank() && (localVersion == null || localVersion.isBlank())) {
            return false;
        }
        return Versions.isNewer(version, localVersion);
    }
}