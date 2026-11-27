package com.skydeps.autotacz.remote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基于静态 JSON 清单的远程源。
 *
 * <p>选择它作为默认源，是因为静态清单：
 * <ul>
 *   <li>不需要 API 鉴权，也不会被限流</li>
 *   <li>可以由任何人托管，天然支持自建镜像</li>
 *   <li>格式由我们自己定义，升级时向后兼容容易</li>
 * </ul>
 *
 * <p>线上清单由仓库根目录的 {@code packs.json} 提供，默认地址见
 * {@code StarterConfig.DEFAULT_MANIFEST}（jsDelivr CDN），
 * 回退地址见 {@code StarterConfig.FALLBACK_MANIFESTS}（raw.githubusercontent）。
 * 改清单后推送到 {@code main} 即可生效，jsDelivr 有几分钟缓存。
 *
 * <p>清单格式（顶层为对象，{@code packs} 数组；也接受顶层直接是数组）：
 * <pre>
 * {
 *   "_comment": "可选，给维护者看的说明",
 *   "_updated": "2026-11-27",
 *   "packs": [
 *     {
 *       "namespace": "mw4",                    // 必需，须与包内 gunpack.meta.json 一致
 *       "name": "Modern Warfare 4",            // 可选，缺省用 namespace
 *       "version": "1.13.0-Release",           // 可选，缺省视为空串（不会触发更新）
 *       "url": "https://.../pkg.zip",          // 必需，直链；GitHub Release 的 302 会被自动跟随
 *       "page": "https://...",                 // 可选，详情页，仅供界面跳转
 *       "size": 86812599,                      // 可选，字节数；未知写 -1
 *       "sha256": "d58a3408..."                // 可选，但强烈建议填：填了就强制校验
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>{@code namespace} 与 {@code url} 为必需字段。缺任一字段的条目会被
 * <b>整条跳过</b>而不是让整份清单失败——一份清单里混入坏条目是常态，
 * 不能因此让所有人的检查都挂掉。{@code _} 开头的键是纯注释，会被忽略。
 */
public final class JsonManifestSource implements GunpackRemoteSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonManifestSource.class);

    /**
     * 清单允许含 {@code //} 注释（方便维护者标注来源），因此使用 lenient 模式。
     *
     * <p>注意：lenient 只放宽语法，不改变类型校验；字段类型不对仍会走
     * {@link #str} / {@link #num} 的兜底分支。
     */
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    /** 来源名称，会写进每个 {@link RemotePack#sourceName()}，用于日志区分多源。 */
    private final String sourceName;

    /** 清单地址。每次 {@link #load()} 都重新请求，不做缓存（缓存由调用方负责）。 */
    private final URI manifestUri;

    /** 统一的 HTTP 客户端，承载超时、UA、代理等全部网络细节。 */
    private final HttpFetcher fetcher;

    /**
     * @param sourceName  源名称，写入每个 {@link RemotePack#sourceName()}，便于日志区分多源
     * @param manifestUri 清单地址（支持 https；GitHub Release 的 302 由
     *                    {@link HttpFetcher} 自动跟随）
     * @param fetcher     HTTP 客户端，已配置好代理与超时
     */
    public JsonManifestSource(String sourceName, URI manifestUri, HttpFetcher fetcher) {
        this.sourceName = sourceName;
        this.manifestUri = manifestUri;
        this.fetcher = fetcher;
    }

    @Override
    public String name() {
        return sourceName;
    }

    /**
     * 按关键字检索。
     *
     * <p>只匹配 {@code namespace} 与展示名，<b>不</b>匹配版本号或作者——
     * 作者信息在清单里没有，需要靠 {@code PackQuery} 在本地索引上查。
     * 关键字为空或全空白时返回全部条目。
     */
    @Override
    public List<RemotePack> search(String keyword) throws IOException {
        List<RemotePack> all = load();
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return all;
        }
        List<RemotePack> matched = new ArrayList<>();
        for (RemotePack pack : all) {
            if (pack.namespace().toLowerCase(Locale.ROOT).contains(needle)
                    || pack.displayName().toLowerCase(Locale.ROOT).contains(needle)) {
                matched.add(pack);
            }
        }
        return List.copyOf(matched);
    }

    /**
     * 按 namespace 精确查找（忽略大小写）。
     *
     * <p>这是更新检查的主路径：本地扫描出 namespace 后，来这里找对应的发布记录。
     * 同一 namespace 在清单里出现多次时返回<b>第一条</b>，所以清单里应保持唯一。
     *
     * @return 未找到、或 {@code namespace} 为空白时返回 {@code null}
     */
    @Override
    public RemotePack findByNamespace(String namespace) throws IOException {
        if (namespace == null || namespace.isBlank()) {
            return null;
        }
        String target = namespace.trim().toLowerCase(Locale.ROOT);
        for (RemotePack pack : load()) {
            if (pack.namespace().toLowerCase(Locale.ROOT).equals(target)) {
                return pack;
            }
        }
        return null;
    }

    /**
     * 拉取并解析清单。每次调用都会重新请求，缓存交给上层。
     *
     * <p>注意：清单源切换（主地址 → 备用地址）不在这里做，而在
     * {@code starter.UpdateChecker.loadManifestWithFallback()}——
     * 那属于"编排"决策，本类只负责"把给定地址读下来"。
     *
     * @throws IOException 网络失败、非 2xx、或 JSON 结构不合法
     */
    public List<RemotePack> load() throws IOException {
        String body = fetcher.fetchText(manifestUri)
                .orElseThrow(() -> new IOException("无法获取清单: " + manifestUri));
        return parse(body, sourceName);
    }

    /**
     * 解析清单文本。
     *
     * <p>抽成包可见的静态方法，便于在不联网的情况下做单元测试。
     *
     * <p>容错策略分两层，都是刻意的：
     * <ul>
     *   <li><b>整份失败</b>：JSON 语法错误、顶层为空、找不到 {@code packs} 数组 ——
     *       这些说明清单本身坏了，继续下去没有意义</li>
     *   <li><b>单条跳过</b>：某个元素的 {@code namespace} 或 {@code url} 缺失 ——
     *       只丢这一条，其余条目照常可用</li>
     * </ul>
     *
     * @param sourceName 解析结果统一标注的来源名
     */
    static List<RemotePack> parse(String body, String sourceName) throws IOException {
        JsonElement root;
        try {
            root = GSON.fromJson(body, JsonElement.class);
        } catch (JsonParseException e) {
            throw new IOException("清单 JSON 解析失败: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new IOException("清单为空");
        }

        JsonArray array = extractArray(root);
        if (array == null) {
            throw new IOException("清单缺少 packs 数组");
        }

        List<RemotePack> packs = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            RemotePack pack = toPack(element.getAsJsonObject(), sourceName);
            if (pack != null) {
                packs.add(pack);
            }
        }
        return List.copyOf(packs);
    }

    /**
     * 取出 packs 数组，兼容「顶层是数组」和「顶层是对象含 packs 键」两种写法。
     *
     * @return 找不到时返回 {@code null}
     */
    @Nullable
    private static JsonArray extractArray(JsonElement root) {
        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }
        if (root.isJsonObject()) {
            JsonElement packs = root.getAsJsonObject().get("packs");
            if (packs != null && packs.isJsonArray()) {
                return packs.getAsJsonArray();
            }
        }
        return null;
    }

    /**
     * 把单个 JSON 对象转成 {@link RemotePack}。
     *
     * <p>{@code namespace} 或 {@code url} 缺失时返回 {@code null}，
     * 由调用方跳过——单条坏数据不应污染整份清单。
     */
    @Nullable
    private static RemotePack toPack(JsonObject obj, String sourceName) {
        String namespace = str(obj, "namespace");
        String url = str(obj, "url");
        if (namespace == null || namespace.isBlank() || url == null || url.isBlank()) {
            return null;
        }
        return new RemotePack(
                namespace.trim(),
                str(obj, "name"),
                str(obj, "version"),
                url.trim(),
                str(obj, "page"),
                num(obj, "size"),
                str(obj, "sha256"),
                sourceName
        );
    }

    /**
     * 安全读取字符串字段。
     *
     * <p>三种异常都吞掉返回 {@code null}，而不是让整份清单失败：
     * 字段不存在、值是 {@code null}、值是对象/数组而非原始类型。
     */
    @Nullable
    private static String str(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            return el.getAsString();
        } catch (ClassCastException | IllegalStateException e) {
            return null;
        }
    }

    /**
     * 安全读取数字字段。
     *
     * @return 缺失或非数字时返回 {@code -1}，与 {@link RemotePack#sizeBytes()} 的
     *         「未知」约定保持一致，避免用 0 误导界面显示成「0 字节」
     */
    private static long num(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return -1L;
        }
        try {
            return el.getAsLong();
        } catch (ClassCastException | NumberFormatException e) {
            return -1L;
        }
    }
}