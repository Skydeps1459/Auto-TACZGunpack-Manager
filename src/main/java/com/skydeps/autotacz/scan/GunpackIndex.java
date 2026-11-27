package com.skydeps.autotacz.scan;

import com.skydeps.autotacz.model.Gunpack;
import com.skydeps.autotacz.model.InstalledPack;
import com.skydeps.autotacz.model.PackState;
import com.skydeps.autotacz.model.Versions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 内存中的枪包索引。
 *
 * <p>不可变：一次扫描产出一个索引，查询不会修改索引。
 * 这让「并发查询」自然安全，也让索引可以放心被多个命令共享。
 * 需要更新时用 {@link #of} 或 {@link #replace} 生成新实例。
 *
 * <p>数据量预期：几十到几百个枪包。因此不预建倒排索引，
 * 直接线性扫描 + 子串匹配即可，避免为过早优化引入复杂度。
 */
public final class GunpackIndex {

    private final List<InstalledPack> packs;
    private final Map<String, InstalledPack> byNamespace;

    private GunpackIndex(List<InstalledPack> packs) {
        this.packs = List.copyOf(packs);
        Map<String, InstalledPack> map = new LinkedHashMap<>();
        for (InstalledPack pack : this.packs) {
            map.putIfAbsent(pack.namespace().toLowerCase(Locale.ROOT), pack);
        }
        this.byNamespace = Map.copyOf(map);
    }

    public static GunpackIndex empty() {
        return new GunpackIndex(List.of());
    }

    public static GunpackIndex of(List<InstalledPack> packs) {
        return new GunpackIndex(packs);
    }

    /** 从扫描结果构建索引。 */
    public static GunpackIndex from(GunpackScanner.ScanResult result) {
        return new GunpackIndex(result.packs());
    }

    /**
     * 返回替换了单个条目的新索引（按 namespace 匹配）。
     *
     * <p>启用/禁用操作只改变一个包的状态，重建整个索引没必要，
     * 但为保证不可变性仍返回新实例。
     */
    public GunpackIndex replace(InstalledPack updated) {
        List<InstalledPack> copy = new ArrayList<>(packs.size());
        String target = updated.namespace().toLowerCase(Locale.ROOT);
        for (InstalledPack pack : packs) {
            copy.add(pack.namespace().toLowerCase(Locale.ROOT).equals(target) ? updated : pack);
        }
        return new GunpackIndex(copy);
    }

    /** 全部枪包（只读）。 */
    public List<InstalledPack> all() {
        return packs;
    }

    public int size() {
        return packs.size();
    }

    public boolean isEmpty() {
        return packs.isEmpty();
    }

    /** 按 namespace 精确查找（忽略大小写）。 */
    public Optional<InstalledPack> find(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byNamespace.get(namespace.trim().toLowerCase(Locale.ROOT)));
    }

    /** 是否存在指定 namespace 的枪包。 */
    public boolean contains(String namespace) {
        return find(namespace).isPresent();
    }

    /**
     * 执行查询。
     *
     * @param query 检索条件，{@code null} 视为 {@link PackQuery#ALL}
     * @return 匹配的枪包，已按条件排序
     */
    public List<InstalledPack> query(PackQuery query) {
        PackQuery q = query == null ? PackQuery.ALL : query;
        List<InstalledPack> matched = new ArrayList<>();
        for (InstalledPack pack : packs) {
            if (matches(pack, q)) {
                matched.add(pack);
            }
        }
        matched.sort(comparatorFor(q));
        return List.copyOf(matched);
    }

    private static boolean matches(InstalledPack installed, PackQuery q) {
        Gunpack pack = installed.pack();

        if (q.state() != null && installed.state() != q.state()) {
            return false;
        }
        if (q.weaponType() != null && !pack.hasWeaponType(q.weaponType())) {
            return false;
        }
        if (q.author() != null && !pack.hasAuthor(q.author())) {
            return false;
        }
        return q.text() == null || matchesText(pack, q.text());
    }

    /** 全文匹配：名称 / namespace / 简介 / 作者。 */
    private static boolean matchesText(Gunpack pack, String needle) {
        if (containsIgnoreCase(pack.displayName(), needle)
                || containsIgnoreCase(pack.namespace(), needle)
                || containsIgnoreCase(pack.description(), needle)
                || containsIgnoreCase(pack.fileName(), needle)) {
            return true;
        }
        for (String author : pack.authors()) {
            if (containsIgnoreCase(author, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Comparator<InstalledPack> comparatorFor(PackQuery q) {
        Comparator<InstalledPack> base = switch (q.sort()) {
            case NAME -> Comparator.comparing(p -> p.pack().displayName(), String.CASE_INSENSITIVE_ORDER);
            case VERSION -> (a, b) -> Versions.compare(a.pack().version(), b.pack().version());
            case CONTENT -> Comparator.comparingInt(p -> p.pack().content().total());
            case TIME -> Comparator.comparingLong(p -> p.pack().source().lastModified());
        };
        // 名称作为稳定的次级排序键，避免同值条目顺序随扫描结果抖动
        Comparator<InstalledPack> withTieBreak = base.thenComparing(
                p -> p.pack().displayName(), String.CASE_INSENSITIVE_ORDER);
        return q.ascending() ? withTieBreak : withTieBreak.reversed();
    }

    /** 统计各武器类型的包数量，用于界面提示可用筛选值。 */
    public Map<String, Integer> weaponTypeHistogram() {
        Map<String, Integer> histogram = new HashMap<>();
        for (InstalledPack pack : packs) {
            for (String type : pack.pack().weaponTypes()) {
                histogram.merge(type, 1, Integer::sum);
            }
        }
        return Map.copyOf(histogram);
    }

    /** 统计启用 / 禁用数量。 */
    public Map<PackState, Integer> stateHistogram() {
        Map<PackState, Integer> histogram = new LinkedHashMap<>();
        for (PackState state : PackState.values()) {
            histogram.put(state, 0);
        }
        for (InstalledPack pack : packs) {
            histogram.merge(pack.state(), 1, Integer::sum);
        }
        return Map.copyOf(histogram);
    }

    /**
     * 返回只包含启用态包的新索引，便于「导出所有启用枪包」之类用例。
     */
    public GunpackIndex enabledOnly() {
        List<InstalledPack> filtered = new ArrayList<>();
        for (InstalledPack pack : packs) {
            if (pack.enabled()) {
                filtered.add(pack);
            }
        }
        return new GunpackIndex(filtered);
    }
}