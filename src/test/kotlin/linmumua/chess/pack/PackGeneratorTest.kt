package linmumua.chess.pack

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 自动资源检查按 pack.yml 的版本决定要不要重导。
 *
 * 只看目录存在与否是不够的：插件升级后磁盘上是旧模型与旧 items.yml，
 * 新增的朝向物品永远进不去，棋子会一直退回原版釉陶外观。
 * 所以这里的核心约束是"同版本不动、跨版本重写"。
 */
class PackGeneratorTest {

    @TempDir
    lateinit var craftEngineDataFolder: Path

    /**
     * 当前插件版本，从 JAR 内真实的 `muchess/pack.yml` 读。
     *
     * 不能写成字面量：那样每次改 `build.gradle.kts` 的 version 都要同步改这里，
     * 忘了改就是四条测试一起红，而失败原因和被测逻辑毫无关系。
     * 这里读的正是 [PackGenerator] 判断"磁盘是否过期"所依据的同一份资源。
     */
    private val currentVersion: String =
        requireNotNull(
            PackGeneratorTest::class.java.classLoader
                .getResourceAsStream("muchess/pack.yml")
                ?.use { stream ->
                    stream.readBytes().toString(StandardCharsets.UTF_8)
                        .lineSequence()
                        .firstOrNull { it.startsWith("version:") }
                        ?.substringAfter("version:")
                        ?.trim()
                        ?.trim('"', '\'')
                },
        ) { "JAR 内 muchess/pack.yml 缺少 version 行，构建期资源生成可能没跑" }

    /** 造一份指定版本的 pack.yml，模拟磁盘上已有的导出结果 */
    private fun writeInstalledPack(target: Path, version: String) {
        Files.createDirectories(target)
        Files.writeString(
            target.resolve("pack.yml"),
            "author: linmumua\nversion: $version\nnamespace: muchess\n",
            StandardCharsets.UTF_8,
        )
    }

    /**
     * 版本一致时必须一个文件都不动。
     *
     * 这是允许服主在同一版本内替换自己的贴图与模型的前提；
     * 如果每次 CE 重载都重写一遍，服主的美术资源会被反复覆盖。
     */
    @Test
    fun `muchess目录版本一致时自动检查完全跳过`() {
        val target = PackGenerator.packDirectory(craftEngineDataFolder)
        writeInstalledPack(target, currentVersion)
        val custom = target.resolve("custom-model.yml")
        Files.writeString(custom, "owner: server\n", StandardCharsets.UTF_8)

        val result = assertIs<PackGenerator.Result.Skipped>(
            PackGenerator.export(
                craftEngineDataFolder = craftEngineDataFolder,
                force = true,
                checkDirectory = true,
            ),
        )

        assertEquals("muchess 目录已是当前版本", result.reason)
        assertEquals("owner: server\n", custom.readText())
        assertTrue(!PackGenerator.needsExport(craftEngineDataFolder), "版本一致时不该判为需要导出")
        assertEquals(2L, Files.walk(target).use { stream -> stream.filter(Files::isRegularFile).count() })
    }

    /**
     * 版本不一致说明插件升过级，必须重写并清掉旧残留。
     *
     * 这条正是本次要修的线上问题：旧目录留在那里，56 个朝向内部物品进不去，
     * 棋子全部退回釉陶方块。
     */
    @Test
    fun `muchess目录版本不一致时重新导出完整资源`() {
        val target = PackGenerator.packDirectory(craftEngineDataFolder)
        writeInstalledPack(target, "0.0.1")
        val stale = target.resolve("configuration/old-items.yml")
        Files.createDirectories(stale.parent)
        Files.writeString(stale, "outdated: true\n", StandardCharsets.UTF_8)

        assertTrue(PackGenerator.needsExport(craftEngineDataFolder), "版本不一致必须判为需要导出")

        val result = assertIs<PackGenerator.Result.Exported>(
            PackGenerator.export(
                craftEngineDataFolder = craftEngineDataFolder,
                force = true,
                checkDirectory = true,
            ),
        )

        assertTrue(result.fileCount > 0)
        assertTrue(
            target.resolve("pack.yml").readText().contains("version: $currentVersion"),
            "重导后 pack.yml 版本必须刷新，否则下次还会被判成过期",
        )
        assertTrue(Files.notExists(stale), "旧版残留文件必须被清掉")
        // 棋子退回原版外观的根因就是这些朝向物品缺失，重导后必须齐全
        val items = target.resolve("configuration/items.yml").readText()
        assertTrue(items.contains("muchess:red_king_display_north"))
        assertTrue(items.contains("muchess:black_king_display_south"))
    }

    /**
     * 缺 pack.yml 时读不到版本，必须按需要导出处理。
     *
     * 服主手工删过文件、或上一次导出被中断，都会出现目录在但 pack.yml 不在的情况。
     * 这时候宁可重写一遍，也不能默认它是最新的。
     */
    @Test
    fun `缺少pack文件时按需要导出处理`() {
        val target = PackGenerator.packDirectory(craftEngineDataFolder)
        Files.createDirectories(target)

        assertTrue(PackGenerator.needsExport(craftEngineDataFolder), "读不到版本必须判为需要导出")

        val result = assertIs<PackGenerator.Result.Exported>(
            PackGenerator.export(
                craftEngineDataFolder = craftEngineDataFolder,
                force = false,
                checkDirectory = true,
            ),
        )

        assertTrue(result.fileCount > 0)
        assertTrue(target.resolve("pack.yml").readText().contains("version: $currentVersion"))
    }

    /** 目录缺失时要一次导出完整清单，不能只建空文件夹导致 CE 仍找不到模型。 */
    @Test
    fun `muchess目录缺失时导出完整资源`() {
        val target = PackGenerator.packDirectory(craftEngineDataFolder)
        assertTrue(Files.notExists(target))
        assertTrue(PackGenerator.needsExport(craftEngineDataFolder), "目录不存在必须判为需要导出")

        val result = assertIs<PackGenerator.Result.Exported>(
            PackGenerator.export(
                craftEngineDataFolder = craftEngineDataFolder,
                force = false,
                checkDirectory = true,
            ),
        )

        assertTrue(result.fileCount > 0)
        assertTrue(Files.isRegularFile(target.resolve("pack.yml")))
        assertTrue(Files.isRegularFile(target.resolve("configuration/items.yml")))
        assertTrue(
            Files.isRegularFile(
                target.resolve("resourcepack/assets/muchess/models/item/board/xiangqi_board_model.json"),
            ),
        )
        assertTrue(
            Files.isRegularFile(
                target.resolve("resourcepack/assets/muchess/models/item/piece/xiangqi_piece_base.json"),
            ),
        )
    }

    /** 手动重生成关闭目录检查后，即使 muchess 已存在也必须真正覆盖内置资源。 */
    @Test
    fun `关闭目录检查时已有目录仍然导出`() {
        val target = PackGenerator.packDirectory(craftEngineDataFolder)
        Files.createDirectories(target)
        val packFile = target.resolve("pack.yml")
        Files.writeString(packFile, "owner: server\n", StandardCharsets.UTF_8)

        val result = assertIs<PackGenerator.Result.Exported>(
            PackGenerator.export(
                craftEngineDataFolder = craftEngineDataFolder,
                force = true,
                checkDirectory = false,
            ),
        )

        assertTrue(result.fileCount > 0)
        assertTrue(packFile.readText().contains("version: $currentVersion"))
    }
}
