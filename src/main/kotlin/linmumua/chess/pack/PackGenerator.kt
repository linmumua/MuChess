package linmumua.chess.pack

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning

/**
 * 把随 JAR 发布的 CraftEngine 资源包导出到 CE 的 resources 目录。
 *
 * CraftEngine 没有提供"注册外部资源包目录"的编程接口，第三方包只能落到
 * `plugins/CraftEngine/resources/<包名>/`，所以这里做的是文件导出而不是 API 注册。
 *
 * 贴图与配置在构建期就已产出（见 buildSrc 的 PackWriter），运行期只做拷贝，
 * 不在服务端上画图。拷贝本身也是 I/O，必须在异步线程调用。
 */
object PackGenerator {

    /** JAR 内资源包根路径，与构建期 generateCraftEnginePack 的输出对应 */
    private const val BUNDLE_ROOT = "muchess"

    /** 文件清单，构建期生成，用于增量比对与清理陈旧文件 */
    private const val BUNDLE_INDEX = "$BUNDLE_ROOT/_bundle_index.txt"

    /** 资源包元信息文件名，`version:` 行是判断磁盘资源是否过期的依据 */
    private const val PACK_META = "pack.yml"

    /** 自动检查与手动重生成共用一把锁，避免 CE 重载和插件重载同时写同一目录 */
    private val exportLock = Any()

    /** 导出结果 */
    sealed interface Result {
        /** CE 不在，跳过 */
        data class Skipped(val reason: String) : Result

        /** 内容没变化，没动磁盘 */
        data class UpToDate(val fileCount: Int) : Result

        /** 实际写入了文件 */
        data class Exported(val fileCount: Int) : Result

        /** 失败 */
        data class Failed(val reason: String) : Result
    }

    /**
     * 执行导出。
     *
     * @param craftEngineDataFolder CraftEngine 的插件数据目录
     * @param force 忽略内容比对，强制重写
     * @param checkDirectory 为 true 时先看 muchess 目录：目录存在**且版本一致**才跳过。
     *   版本不一致说明插件升过级，磁盘上的模型与物品定义已经过期，必须重写，
     *   否则新加的资源永远进不去，棋子会一直退回原版外观。
     */
    fun export(
        craftEngineDataFolder: Path,
        force: Boolean,
        checkDirectory: Boolean = false,
    ): Result = synchronized(exportLock) {
        val target = packDirectory(craftEngineDataFolder)
        if (checkDirectory && Files.isDirectory(target) && !versionChanged(target)) {
            return@synchronized Result.Skipped("muchess 目录已是当前版本")
        }
        val entries = readIndex() ?: return@synchronized Result.Failed("JAR 内缺少资源包清单 $BUNDLE_INDEX")

        try {
            if (!force && isUpToDate(target, entries)) {
                Result.UpToDate(entries.size)
            } else {
                removeStaleFiles(target, entries)
                for (entry in entries) {
                    copyEntry(entry, target.resolve(entry))
                }
                Result.Exported(entries.size)
            }
        } catch (error: IOException) {
            // 不吞异常：写不进去要让服主看到原因，通常是权限或磁盘满
            warning("资源包导出失败：${error.message}")
            Result.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** `plugins/CraftEngine/resources/muchess/` 的统一定位，避免各调用点自行拼路径。 */
    fun packDirectory(craftEngineDataFolder: Path): Path =
        craftEngineDataFolder.resolve("resources").resolve(BUNDLE_ROOT)

    /**
     * 是否需要导出。
     *
     * 给 [PackService] 的自动检查做预筛，避免每次 CE 重载都进同步块。
     * 目录不存在、任一侧读不到版本、两边版本不同都要导出。
     */
    fun needsExport(craftEngineDataFolder: Path): Boolean {
        val target = packDirectory(craftEngineDataFolder)
        return !Files.isDirectory(target) || versionChanged(target)
    }

    /** 磁盘资源与 JAR 内资源的版本是否不一致；任一侧读不到就当作需要重写 */
    private fun versionChanged(target: Path): Boolean {
        val bundled = bundledPackVersion() ?: return true
        val installed = installedPackVersion(target) ?: return true
        return bundled != installed
    }

    /** 读 JAR 内 `muchess/pack.yml` 的版本 */
    private fun bundledPackVersion(): String? {
        val stream = resource("$BUNDLE_ROOT/$PACK_META") ?: return null
        return stream.use { input ->
            readPackVersion(
                String(input.readAllBytes(), StandardCharsets.UTF_8).lineSequence(),
            )
        }
    }

    /** 读磁盘上已导出的 `pack.yml` 版本，文件不存在或读失败都返回 null */
    private fun installedPackVersion(target: Path): String? {
        val file = target.resolve(PACK_META)
        if (!Files.isRegularFile(file)) {
            return null
        }
        return try {
            readPackVersion(Files.readAllLines(file, StandardCharsets.UTF_8).asSequence())
        } catch (error: IOException) {
            // 读不到就当版本未知，交给调用方重写；但不能静默，否则排查不到权限问题
            warning("读取已导出的 $PACK_META 失败，将按需要重新导出：${error.message}")
            null
        }
    }

    /**
     * 只取 `pack.yml` 的 `version:` 行。
     *
     * 这里**故意不走** SnakeYAML：该库在本项目是 `compileOnly` + `@RuntimeDependency`
     * 运行期下载，测试 classpath 上没有它。而且 `pack.yml` 是构建期生成、运行期只拷贝的
     * 产物文件，结构固定，既不是服主配置入口也不接受不可信输入，用不上 LoaderOptions
     * 那套安全限制。项目"运行期 YAML 只用 SnakeYAML"的约束针对的是 config.yml、
     * 棋种定义、语言文件、boards.yml 这些配置入口，本方法不构成第二套解析链路。
     *
     * 实现刻意留在 PackGenerator 私有作用域，防止被误用去读真正的配置文件。
     */
    private fun readPackVersion(lines: Sequence<String>): String? = lines
        .map(String::trim)
        .firstOrNull { it.startsWith("version:") }
        ?.removePrefix("version:")
        ?.trim()
        ?.trim('"', '\'')
        ?.takeIf { it.isNotEmpty() }

    /** 读取 JAR 内的文件清单 */
    private fun readIndex(): List<String>? {
        val stream = resource(BUNDLE_INDEX) ?: return null
        return stream.use { input ->
            String(input.readAllBytes(), StandardCharsets.UTF_8)
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it != "_bundle_index.txt" }
                .toList()
        }
    }

    /**
     * 判断目标目录内容是否已经是最新。
     *
     * 不能只比较文件长度：模型缩放、配置值或同长度文本变更都会让旧资源被误判为最新。
     * 资源文件数量有限，这里用分块逐字节比较，避免一次性把贴图全部读进内存。
     */
    private fun isUpToDate(target: Path, entries: List<String>): Boolean {
        for (entry in entries) {
            val file = target.resolve(entry)
            if (!Files.isRegularFile(file) || !sameContent(file, "$BUNDLE_ROOT/$entry")) {
                return false
            }
        }
        return true
    }

    /** 分块比较目标文件与 JAR 内资源，内容完全一致才认为无需导出。 */
    private fun sameContent(target: Path, resourcePath: String): Boolean {
        val bundled = resource(resourcePath) ?: return false
        bundled.use { bundledInput ->
            Files.newInputStream(target).use { targetInput ->
                val bundledBuffer = ByteArray(8192)
                val targetBuffer = ByteArray(8192)
                while (true) {
                    val bundledRead = bundledInput.read(bundledBuffer)
                    val targetRead = targetInput.read(targetBuffer)
                    if (bundledRead != targetRead) {
                        return false
                    }
                    if (bundledRead < 0) {
                        return true
                    }
                    for (index in 0 until bundledRead) {
                        if (bundledBuffer[index] != targetBuffer[index]) {
                            return false
                        }
                    }
                }
            }
        }
    }

    /** 清掉目标目录里不在清单内的残留文件，避免旧版本资源留着干扰 CE */
    private fun removeStaleFiles(target: Path, entries: List<String>) {
        if (!Files.isDirectory(target)) {
            return
        }
        val expected = entries.map { target.resolve(it).normalize() }.toSet()
        Files.walk(target).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { it.normalize() !in expected }
                .forEach { stale ->
                    try {
                        Files.deleteIfExists(stale)
                    } catch (error: IOException) {
                        warning("清理陈旧资源文件失败 $stale：${error.message}")
                    }
                }
        }
    }

    /** 从 JAR 拷一个文件出来 */
    private fun copyEntry(entry: String, target: Path) {
        val stream = resource("$BUNDLE_ROOT/$entry")
            ?: throw IOException("JAR 内缺少资源文件：$entry")
        stream.use { input ->
            Files.createDirectories(target.parent)
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 读取 JAR 内资源，读不到返回 null */
    private fun resource(path: String) = PackGenerator::class.java.classLoader.getResourceAsStream(path)

    /** 把导出结果写到控制台，方便服主确认 */
    fun logResult(result: Result) {
        when (result) {
            is Result.Skipped -> info("跳过资源包导出：${result.reason}")
            is Result.UpToDate -> info("资源包已是最新，共 ${result.fileCount} 个文件")
            is Result.Exported -> info("资源包已写入 CraftEngine，共 ${result.fileCount} 个文件")
            is Result.Failed -> warning("资源包导出失败：${result.reason}")
        }
    }
}
