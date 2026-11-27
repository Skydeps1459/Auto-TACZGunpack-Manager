package com.skydeps.autotacz.scan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.skydeps.autotacz.model.Gunpack;
import com.skydeps.autotacz.model.PackSource;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从 {@link PackContent} 解析 TACZ 枪包元数据。
 *
 * <p>这是整个项目中<b>唯一</b>了解 TACZ 包内部文件布局的地方。
 * TACZ 版本升级导致格式变化时，只需要修改本类。
 *
 * <p>包布局约定：
 * <pre>
 * gunpack.meta.json                       → 声明 namespace（必需，缺失则不是合法枪包）
 * assets/&lt;namespace&gt;/gunpack_info.json    → 名称 / 版本 / 作者 / 简介（可选）
 * data/&lt;namespace&gt;/index/guns/*.json      → 枪械条目
 * data/&lt;namespace&gt;/index/ammo/*.json      → 弹药条目
 * data/&lt;namespace&gt;/index/attachments/     → 配件条目
 * </pre>
 *
 * <p>武器类型（rifle / pistol / sniper …）优先从枪械条目自身的 {@code type} 字段读取；
 * 部分枪包把条目放在按类型命名的子目录里（{@code index/guns/rifle/ak47.json}），
 * 因此子目录名作为兜底来源。
 */
public final class GunpackMetadataReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(GunpackMetadataReader.class);

    private static final String META_FILE = "gunpack.meta.json";
    private static final String INFO_FILE = "gunpack_info.json";

    /**
     * TACZ 的 gunpack_info.json 含 {@code //} 注释，
     * 因此使用 lenient 模式解析。
     */
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    /** 抽样解析武器类型时的条目上限，避免超大包拖慢索引。 */
    private static final int MAX_TYPE_SAMPLE = 32;

    private GunpackMetadataReader() {
    }

    /**
     * 解析枪包。
     *
     * @param content 内容视图
     * @param source  物理来源
     * @return 解析结果；若缺少必需的 {@code gunpack.meta.json} 或 namespace 非法，
     *         返回 {@code null}（调用方应视为「不是枪包」并跳过）
     */
    @Nullable
    public static Gunpack read(PackContent content, PackSource source) {
        String namespace = readNamespace(content, source.fileName());
        if (namespace == null || namespace.isBlank()) {
            return null;
        }

        Info info = readInfo(content, namespace);
        Gunpack.ContentStats stats = readStats(content, namespace);
        Set<String> weaponTypes = readWeaponTypes(content, namespace);

        return new Gunpack(
                namespace,
                info.name(),
                info.version(),
                info.description(),
                info.authors(),
                info.license(),
                stats,
                weaponTypes,
                source
        );
    }

    @Nullable
    private static String readNamespace(PackContent content, String label) {
        try (InputStream in = content.open(META_FILE)) {
            if (in == null) {
                return null;
            }
            JsonObject obj = parseObject(in, label + ":" + META_FILE);
            if (obj == null) {
                return null;
            }
            return stringOrNull(obj, "namespace");
        } catch (IOException e) {
            LOGGER.debug("读取 {} 的 {} 失败: {}", label, META_FILE, e.getMessage());
            return null;
        }
    }

    private static Info readInfo(PackContent content, String namespace) {
        String path = "assets/" + namespace + "/" + INFO_FILE;
        try (InputStream in = content.open(path)) {
            if (in == null) {
                return Info.EMPTY;
            }
            JsonObject obj = parseObject(in, namespace + ":" + INFO_FILE);
            if (obj == null) {
                return Info.EMPTY;
            }
            return new Info(
                    stringOrNull(obj, "name"),
                    defaultValue(stringOrNull(obj, "version"), "1.0.0"),
                    defaultValue(stringOrNull(obj, "desc"), ""),
                    defaultValue(stringOrNull(obj, "license"), ""),
                    readAuthors(obj)
            );
        } catch (IOException e) {
            LOGGER.debug("读取 {} 的 {} 失败: {}", namespace, INFO_FILE, e.getMessage());
            return Info.EMPTY;
        }
    }

    private static Gunpack.ContentStats readStats(PackContent content, String namespace) {
        String base = "data/" + namespace + "/index/";
        return new Gunpack.ContentStats(
                content.countJsonUnder(base + "guns/"),
                content.countJsonUnder(base + "ammo/"),
                content.countJsonUnder(base + "attachments/")
        );
    }

    /**
     * 收集包内出现过的武器类型。
     *
     * <p>为避免大包拖慢索引（某些整合包内含上千个枪械条目），
     * 只抽样前 {@link #MAX_TYPE_SAMPLE} 个条目；这足以覆盖一个包的武器类型集合，
     * 因为同一包内的类型高度重复。
     *
     * <p>类型来源有两条，按优先级：
     * <ol>
     *   <li>枪械条目的 {@code type} 字段（TACZ 的规范位置）</li>
     *   <li>条目所在子目录名，形如 {@code index/guns/rifle/ak47.json} → {@code rifle}</li>
     * </ol>
     */
    private static Set<String> readWeaponTypes(PackContent content, String namespace) {
        String base = "data/" + namespace + "/index/guns/";
        List<String> names = content.listJsonNamesUnder(base);
        if (names.isEmpty()) {
            return Set.of();
        }

        Set<String> types = new TreeSet<>();
        int sampled = 0;
        for (String name : names) {
            if (sampled >= MAX_TYPE_SAMPLE) {
                break;
            }
            sampled++;

            String folderType = folderTypeOf(name);
            if (folderType != null) {
                types.add(folderType);
            }

            String declared = readDeclaredType(content, base + name);
            if (declared != null) {
                types.add(declared);
            }
        }
        return types;
    }

    /**
     * 从条目相对路径里取出「类型目录」名。
     *
     * @param relativeName 形如 {@code rifle/ak47.json} 或 {@code ak47.json}
     * @return 类型名；条目直接放在 guns/ 下时返回 {@code null}
     */
    @Nullable
    private static String folderTypeOf(String relativeName) {
        int slash = relativeName.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String folder = relativeName.substring(0, slash);
        // 排除 guns/ 下常见的非类型目录
        if (folder.equalsIgnoreCase("index") || folder.equalsIgnoreCase("data")) {
            return null;
        }
        return folder.toLowerCase(java.util.Locale.ROOT);
    }

    @Nullable
    private static String readDeclaredType(PackContent content, String fullPath) {
        try (InputStream in = content.open(fullPath)) {
            if (in == null) {
                return null;
            }
            JsonObject obj = parseObject(in, fullPath);
            if (obj == null) {
                return null;
            }
            String type = stringOrNull(obj, "type");
            return (type == null || type.isBlank()) ? null : type.trim().toLowerCase(java.util.Locale.ROOT);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> readAuthors(JsonObject obj) {
        JsonElement el = obj.get("authors");
        if (el == null || !el.isJsonArray()) {
            return List.of();
        }
        List<String> authors = new ArrayList<>();
        for (JsonElement entry : el.getAsJsonArray()) {
            if (entry != null && entry.isJsonPrimitive()) {
                String name = entry.getAsString();
                if (!name.isBlank()) {
                    authors.add(name);
                }
            }
        }
        return authors;
    }

    @Nullable
    private static JsonObject parseObject(InputStream in, String label) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, JsonObject.class);
        } catch (JsonParseException | IllegalStateException | IOException e) {
            LOGGER.debug("解析 {} 失败: {}", label, e.getMessage());
            return null;
        }
    }

    @Nullable
    private static String stringOrNull(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        return el.getAsString();
    }

    private static String defaultValue(@Nullable String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** 解析出的可选元信息。 */
    private record Info(@Nullable String name, String version, String description,
                        String license, List<String> authors) {
        static final Info EMPTY = new Info(null, "1.0.0", "", "", List.of());
    }

    /** 便捷方法：从路径判断形态。 */
    public static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".zip");
    }
}