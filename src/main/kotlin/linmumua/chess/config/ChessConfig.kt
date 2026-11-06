package linmumua.chess.config

import java.io.File
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.common.platform.function.warning

/**
 * 运行期配置的持有者。
 *
 * 全插件只从这里拿 [ChessSettings]，不允许各处自己开文件读 YAML。
 * 读取走 [Yaml]（SnakeYAML），不使用 Bukkit YamlConfiguration。
 *
 * [settings] 用 `@Volatile` 是因为 `/chess reload` 可能在异步线程里重载，
 * 而渲染与交互在主线程读；这里只需要"读到的要么是旧的整份、要么是新的整份"，
 * 引用整体替换即可满足，不需要加锁。
 */
object ChessConfig {

    @Volatile
    var settings: ChessSettings = ChessSettings.DEFAULT
        private set

    /** 配置文件在数据目录下的相对路径 */
    private const val FILE_NAME = "config.yml"

    /**
     * 读取配置。磁盘文件是唯一真源。
     *
     * 文件不存在时才释放模板（`replace = false`），**已存在的文件一个字节都不动**。
     * 插件不做默认值迁移、不改写、不备份 —— 服主写在文件里的就是最终生效的值。
     *
     * 曾经有一版在这里加过"把仍是旧默认值的项升级到新默认"的自动迁移。那是错的：
     * 它会改写服主的文件，而且判定方式是"磁盘值等于某个历史默认值就算没动过"，
     * 于是刻意设成恰好等于旧默认的值也会被改掉。配置归服主，插件不替他做决定。
     *
     * 这一步有磁盘 I/O，只在 LifeCycle.LOAD 与 `/chess reload` 的异步线程里调用。
     */
    fun reload() {
        val file = releaseResourceFile(FILE_NAME, replace = false)
        settings = read(file)
    }

    /**
     * 读一份配置出来。
     *
     * 文件坏掉时**不抛异常**：配置只影响表现参数，让整个插件启动失败不合理。
     * 但也**不退回默认值** —— 而是保留上一次成功读到的那份。
     *
     * 为什么不退默认：`/chess reload` 时配置写错一个冒号，退默认就等于把服主调好的
     * 每一项都悄悄换成插件默认值，表现全变而文件看着没动，排查方向会被完全带偏。
     * 保留上次生效值则只是"这次重载没生效"，语义清楚得多。
     *
     * 首次启动就读失败时 [settings] 还是初始的 [ChessSettings.DEFAULT]，
     * 这时保留上次值等价于用默认值，日志会写清是哪种情况。
     */
    private fun read(file: File): ChessSettings {
        if (!file.exists()) {
            // 正常情况下 releaseResourceFile 已经释放过模板，走到这里说明文件被删或释放失败
            warning("配置文件 $FILE_NAME 不存在，保留当前生效的配置")
            return settings
        }
        val root = try {
            file.inputStream().use { Yaml.loadMap(it) }
        } catch (error: Exception) {
            // 不允许吞异常：配置写坏了服主必须知道是哪一行的问题
            warning(
                "配置文件 $FILE_NAME 解析失败，本次重载未生效，仍用上一次读到的配置：" +
                    "${error.javaClass.simpleName} ${error.message}",
            )
            null
        }
        val parsed = resolve(root) ?: return settings
        // 解析器逐项容错，写错或超范围会静默退默认/钳边界。
        // 这里把"没按你写的生效"的项逐条报出来，免得服主对着文件排查半天。
        for (ignored in ConfigDiagnostics.inspect(root ?: emptyMap(), parsed)) {
            warning(
                "配置项 ${ignored.key} 写的是 ${ignored.written}，" +
                    "实际生效 ${ignored.effective}：${ignored.reason}",
            )
        }
        return parsed
    }

    /**
     * 决定这次读取该用哪份配置。
     *
     * 抽成纯函数是为了能单测"读失败时保留上次生效值"这条 —— [reload] 依赖
     * TabooLib 的平台函数，测不到；而这条正是服主明确要求的行为，不能只靠注释保证。
     *
     * @param root 解析出的原始映射；读失败传 null
     * @return 读成功返回新解析的配置；读失败返回 null，调用方保留当前生效值
     */
    internal fun resolve(root: Map<String, Any?>?): ChessSettings? {
        // 读失败才保留旧值。读成功就完全按文件来 —— 文件里没写的项用默认值是应有语义，
        // 那和"把服主写的值换成默认值"是两件事。
        return if (root == null) null else ChessConfigLoader.parse(root)
    }

    /** 数据目录下的子文件，供 boards.yml 这类运行时数据定位 */
    fun dataFile(name: String): File = File(getDataFolder(), name)
}
