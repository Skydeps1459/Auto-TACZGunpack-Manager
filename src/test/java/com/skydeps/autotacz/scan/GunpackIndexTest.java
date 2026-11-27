package com.skydeps.autotacz.scan;

import com.skydeps.autotacz.model.Gunpack;
import com.skydeps.autotacz.model.InstalledPack;
import com.skydeps.autotacz.model.PackSource;
import com.skydeps.autotacz.model.PackState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GunpackIndex} 的检索与排序行为。
 *
 * <p>重点是「多条件是 AND」以及「排序稳定」这两点，
 * 它们直接影响用户看到的列表是否符合预期。
 */
class GunpackIndexTest {

    private GunpackIndex index;

    @BeforeEach
    void setUp() {
        index = GunpackIndex.of(List.of(
                pack("apollo", "阿波罗步枪", "1.0.0", List.of("Shadow"), Set.of("rifle"),
                        new Gunpack.ContentStats(5, 3, 2), PackState.ENABLED, 1_000L),
                pack("beretta", "贝瑞塔手枪", "2.1.0", List.of("Nova"), Set.of("pistol"),
                        new Gunpack.ContentStats(1, 2, 0), PackState.ENABLED, 2_000L),
                pack("cryo", "冰霜狙击", "1.5.0", List.of("Shadow", "Nova"), Set.of("sniper"),
                        new Gunpack.ContentStats(2, 1, 4), PackState.DISABLED, 3_000L)
        ));
    }

    private static InstalledPack pack(String ns, String name, String version, List<String> authors,
                                      Set<String> types, Gunpack.ContentStats stats,
                                      PackState state, long mtime) {
        Gunpack gunpack = new Gunpack(ns, name, version, name + " 的描述",
                authors, "MIT", stats, types,
                PackSource.ofArchive(Path.of("tacz", ns + ".zip"), 1024, "", mtime));
        return new InstalledPack(gunpack, state);
    }

    @Test
    @DisplayName("空查询返回全部，按名称升序")
    void emptyQueryReturnsAllSortedByName() {
        List<InstalledPack> all = index.query(PackQuery.ALL);
        assertEquals(3, all.size());
        assertEquals("cryo", all.get(0).namespace(), "「冰霜狙击」按名称排序应靠前");
    }

    @Test
    @DisplayName("按武器类型筛选")
    void filtersByWeaponType() {
        List<InstalledPack> rifles = index.query(
                new PackQuery(null, null, "RIFLE", null, PackQuery.Sort.NAME, true));
        assertEquals(1, rifles.size());
        assertEquals("apollo", rifles.get(0).namespace(), "类型匹配应忽略大小写");
    }

    @Test
    @DisplayName("按作者筛选，多作者任一并列")
    void filtersByAuthor() {
        List<InstalledPack> byShadow = index.query(
                new PackQuery(null, "shadow", null, null, PackQuery.Sort.NAME, true));
        assertEquals(2, byShadow.size(), "Shadow 写了两个包");
    }

    @Test
    @DisplayName("全文关键字覆盖名称、namespace、作者、简介")
    void fullTextCoversMultipleFields() {
        // 命中作者
        assertEquals(2, index.query(text("nova")).size());
        // 命中 namespace
        assertEquals(1, index.query(text("apollo")).size());
        // 命中简介
        assertEquals(1, index.query(text("冰霜狙击 的描述")).size());
        // 无匹配
        assertEquals(0, index.query(text("不存在的词")).size());
    }

    @Test
    @DisplayName("多个条件之间是 AND")
    void conditionsAreConjunctive() {
        // 作者 Nova（2 个）+ 类型 sniper（1 个） → 只剩 cryo
        List<InstalledPack> result = index.query(
                new PackQuery(null, "nova", "sniper", null, PackQuery.Sort.NAME, true));
        assertEquals(1, result.size());
        assertEquals("cryo", result.get(0).namespace());
    }

    @Test
    @DisplayName("按启用状态筛选")
    void filtersByState() {
        assertEquals(1, index.query(
                new PackQuery(null, null, null, PackState.DISABLED, PackQuery.Sort.NAME, true)).size());
        assertEquals(2, index.enabledOnly().size());
    }

    @Test
    @DisplayName("按内容总量降序，稳定且带名称兜底")
    void sortsByContentDescending() {
        List<InstalledPack> byContent = index.query(
                new PackQuery(null, null, null, null, PackQuery.Sort.CONTENT, false));
        assertEquals("apollo", byContent.get(0).namespace(), "5+3+2=10 最多");
        assertEquals("cryo", byContent.get(1).namespace(), "2+1+4=7");
        assertEquals("beretta", byContent.get(2).namespace(), "1+2+0=3 最少");
    }

    @Test
    @DisplayName("find 忽略大小写，且找不到时为空")
    void findIsCaseInsensitive() {
        assertTrue(index.find("APOLLO").isPresent());
        assertFalse(index.find("nope").isPresent());
        assertFalse(index.find("").isPresent());
    }

    @Test
    @DisplayName("replace 返回新实例，原索引不变")
    void replaceProducesNewIndex() {
        InstalledPack cryo = index.find("cryo").orElseThrow();
        GunpackIndex updated = index.replace(cryo.withState(PackState.ENABLED));

        assertEquals(3, updated.enabledOnly().size(), "新索引中三个都启用");
        assertEquals(2, index.enabledOnly().size(), "原索引保持不变");
    }

    @Test
    @DisplayName("直方图统计可供界面提示")
    void histogramsSummarizeContent() {
        assertEquals(3, index.weaponTypeHistogram().size());
        assertEquals(3, index.stateHistogram().get(PackState.ENABLED)
                + index.stateHistogram().get(PackState.DISABLED));
    }

    private static PackQuery text(String keyword) {
        return new PackQuery(keyword, null, null, null, PackQuery.Sort.NAME, true);
    }
}