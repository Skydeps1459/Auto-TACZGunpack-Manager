package com.skydeps.autotacz.scan;

import com.skydeps.autotacz.model.InstalledPack;
import com.skydeps.autotacz.model.PackState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫描器的端到端行为：用临时目录构造真实 TACZ 布局。
 *
 * <p>这些测试是「TACZ 包格式约定」的可执行文档：
 * 万一将来 TACZ 改了布局，这里会先失败，而不是等到线上才发现。
 */
class GunpackScannerTest {

    @Test
    @DisplayName("识别目录形态枪包并解析元数据")
    void readsDirectoryPack(@TempDir Path gameDir) throws IOException {
        Path tacz = gameDir.resolve("tacz");
        Path pack = tacz.resolve("my-rifles");
        writeDirPack(pack, "myrifles", "我的步枪", "1.2.0", "Shadow", "rifle", 3, 2, 1);

        GunpackScanner.ScanResult result = new GunpackScanner(tacz, false).scan();

        assertEquals(1, result.packs().size());
        InstalledPack installed = result.packs().get(0);
        assertEquals("myrifles", installed.namespace());
        assertEquals("我的步枪", installed.pack().displayName());
        assertEquals("1.2.0", installed.pack().version());
        assertEquals(PackState.ENABLED, installed.state());
        assertEquals(3, installed.pack().content().guns());
        assertEquals(2, installed.pack().content().ammo());
        assertEquals(1, installed.pack().content().attachments());
        assertTrue(installed.pack().weaponTypes().contains("rifle"));
        assertEquals(1, result.enabledCount());
    }

    @Test
    @DisplayName("识别 zip 形态枪包")
    void readsArchivePack(@TempDir Path gameDir) throws IOException {
        Path tacz = gameDir.resolve("tacz");
        Files.createDirectories(tacz);

        Path source = gameDir.resolve("src-pack");
        writeDirPack(source, "zipguns", "压缩包枪械", "1.0.0", "Nova", "pistol", 1, 1, 0);
        com.skydeps.autotacz.service.PackConverter.directoryToZip(source, tacz.resolve("zipguns.zip"));

        GunpackScanner.ScanResult result = new GunpackScanner(tacz, true).scan();

        assertEquals(1, result.packs().size());
        InstalledPack installed = result.packs().get(0);
        assertEquals("zipguns", installed.namespace());
        assertFalse(installed.pack().isDirectory());
        assertTrue(installed.pack().source().hasHash(), "开启哈希时应算出 SHA-256");
        assertEquals(64, installed.pack().source().sha256().length());
        assertTrue(installed.pack().source().sizeBytes() > 0);
    }

    @Test
    @DisplayName("禁用区内的枪包被标记为 DISABLED")
    void readsDisabledArea(@TempDir Path gameDir) throws IOException {
        Path tacz = gameDir.resolve("tacz");
        writeDirPack(tacz.resolve(".disabled").resolve("old-pack"),
                "oldpack", "旧枪包", "0.9.0", "Legacy", "smg", 1, 0, 0);

        GunpackScanner.ScanResult result = new GunpackScanner(tacz, false).scan();

        assertEquals(1, result.packs().size());
        assertEquals(PackState.DISABLED, result.packs().get(0).state());
        assertEquals(1, result.disabledCount());
        assertEquals(0, result.enabledCount());
    }

    @Test
    @DisplayName("缺少 gunpack.meta.json 的目录被静默跳过，不算失败")
    void skipsNonPackDirectories(@TempDir Path gameDir) throws IOException {
        Path tacz = gameDir.resolve("tacz");
        // 一个普通目录，只有资源没有 meta
        Path notAPack = tacz.resolve("just-assets");
        Files.createDirectories(notAPack.resolve("assets"));
        Files.writeString(notAPack.resolve("assets/readme.txt"), "hi", StandardCharsets.UTF_8);

        GunpackScanner.ScanResult result = new GunpackScanner(tacz, false).scan();

        assertEquals(0, result.packs().size());
        assertFalse(result.hasFailures(), "非枪包属于正常情况，不应记为失败");
    }

    @Test
    @DisplayName("损坏的 zip 记为失败但不影响其他包")
    void corruptArchiveIsReportedAsFailure(@TempDir Path gameDir) throws IOException {
        Path tacz = gameDir.resolve("tacz");
        Files.createDirectories(tacz);
        // 写入一个不是 zip 的 .zip 文件
        Files.writeString(tacz.resolve("broken.zip"), "this is not a zip", StandardCharsets.UTF_8);

        writeDirPack(tacz.resolve("good-pack"), "goodpack", "好枪包", "1.0.0", "A", "rifle", 1, 0, 0);

        GunpackScanner.ScanResult result = new GunpackScanner(tacz, false).scan();

        assertEquals(1, result.packs().size(), "好包仍应被解析");
        assertEquals(1, result.failures().size(), "坏包应被记录");
        assertTrue(result.hasFailures());
    }

    @Test
    @DisplayName("tacz 目录不存在时返回空结果而非异常")
    void missingRootYieldsEmptyResult(@TempDir Path gameDir) {
        GunpackScanner.ScanResult result =
                new GunpackScanner(gameDir.resolve("tacz"), false).scan();

        assertEquals(0, result.packs().size());
        assertFalse(result.hasFailures());
    }

    /**
     * 按 TACZ 布局造一个目录形态枪包。
     *
     * @param guns        生成多少个枪械条目
     * @param ammo        弹药条目数
     * @param attachments 配件条目数
     */
    private static void writeDirPack(Path root, String namespace, String name, String version,
                                     String author, String weaponType,
                                     int guns, int ammo, int attachments) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("gunpack.meta.json"),
                "{ \"namespace\": \"" + namespace + "\" }", StandardCharsets.UTF_8);

        Path assets = root.resolve("assets").resolve(namespace);
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("gunpack_info.json"),
                "{\n  // TACZ 的 info 文件允许行注释\n"
                        + "  \"name\": \"" + name + "\",\n"
                        + "  \"version\": \"" + version + "\",\n"
                        + "  \"desc\": \"测试用枪包\",\n"
                        + "  \"license\": \"MIT\",\n"
                        + "  \"authors\": [\"" + author + "\"]\n}",
                StandardCharsets.UTF_8);

        Path index = root.resolve("data").resolve(namespace).resolve("index");
        writeEntries(index.resolve("guns"), "gun", guns,
                "{\"type\": \"" + weaponType + "\"}");
        writeEntries(index.resolve("ammo"), "ammo", ammo, "{}");
        writeEntries(index.resolve("attachments"), "att", attachments, "{}");
    }

    private static void writeEntries(Path dir, String prefix, int count, String body)
            throws IOException {
        if (count <= 0) {
            return;
        }
        Files.createDirectories(dir);
        for (int i = 0; i < count; i++) {
            Files.writeString(dir.resolve(prefix + i + ".json"), body, StandardCharsets.UTF_8);
        }
    }
}