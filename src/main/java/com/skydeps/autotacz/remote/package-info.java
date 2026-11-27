/**
 * 远程源与下载：与外部（GitHub / CDN / 镜像）通信的全部代码。
 *
 * <h2>职责边界</h2>
 *
 * <p>本包只做「网络」这一件事，不关心本地枪包状态，也不决定要不要更新。
 * 判断「该不该更新」在 {@code starter} / {@code service} 层，"下载到哪、怎么装"
 * 在 {@code starter.UpdateApplier}。这样换源、加镜像、改协议都只影响本包。
 *
 * <h2>调用链路</h2>
 *
 * <pre>
 *   StarterConfig.manifestUrl / FALLBACK_MANIFESTS
 *            │
 *            ▼
 *   ProxySupport.resolve() ──► HttpFetcher（统一 UA / 超时 / 重定向 / 代理）
 *            │                        │
 *            │                        ├──► JsonManifestSource.load()
 *            │                        │      解析 packs.json → List&lt;RemotePack&gt;
 *            │                        │
 *            │                        └──► GunpackDownloader.download()
 *            │                               流式写 .part → 算 SHA-256
 *            │                               → 校验 → 原子改名
 *            ▼
 *   RemotePack（归一化的发布记录，与具体源无关）
 * </pre>
 *
 * <h2>关键类型</h2>
 * <ul>
 *   <li>{@link com.skydeps.autotacz.remote.GunpackRemoteSource} ——
 *       源接口。新增 GitHub API / 镜像站支持时只实现它</li>
 *   <li>{@link com.skydeps.autotacz.remote.JsonManifestSource} ——
 *       当前唯一实现，读静态 JSON 清单</li>
 *   <li>{@link com.skydeps.autotacz.remote.RemotePack} ——
 *       归一化后的发布记录，跨源通用</li>
 *   <li>{@link com.skydeps.autotacz.remote.HttpFetcher} ——
 *       HTTP 细节的唯一出口</li>
 *   <li>{@link com.skydeps.autotacz.remote.ProxySupport} ——
 *       代理自动探测</li>
 *   <li>{@link com.skydeps.autotacz.remote.GunpackDownloader} ——
 *       下载 + 校验</li>
 * </ul>
 *
 * <h2>网络现实（写代码前请先知道）</h2>
 *
 * <p>国内玩家直连 {@code raw.githubusercontent.com} 经常失败
 * （DNS 能解析，但 185.199.108/109.133 的 443 被阻断）。因此默认清单源用
 * jsDelivr CDN，raw 仅作回退。改动这里的地址前，请先确认目标域名在当前网络下可达。
 *
 * <p>另外 Java 的 {@code HttpClient} <b>既不读系统代理也不读 HTTP_PROXY 环境变量</b>，
 * 这正是 {@link com.skydeps.autotacz.remote.ProxySupport} 存在的原因。
 */
package com.skydeps.autotacz.remote;