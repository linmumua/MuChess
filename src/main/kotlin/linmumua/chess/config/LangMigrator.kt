package linmumua.chess.config

import java.io.File
import java.io.InputStream
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.info
import taboolib.common.platform.function.releaseResourceFile
import taboolib.common.platform.function.warning

/**
 * 语言文件的版本化迁移。
 *
 * ## 为什么 lang 要迁移，而 config.yml 明确不迁移
 *
 * [ChessConfig] 那边写着"已存在的文件一个字节都不动，配置归服主"，这里却会备份加覆盖，
 * 两处策略相反是**刻意**的，不是漏改：
 *
 * - `config.yml` 装的是服主调出来的参数（开关、时长、粒子密度）。那些值就是服主的最终意图，
 *   插件没有立场替他改，所以那边连备份都不做。
 * - `lang/zh_CN.yml` 装的是**插件功能的一部分**。插件加了子命令、删了子命令、改了交互方式，
 *   文案必须跟着走。旧文案留在盘上不是"服主的选择"，而是功能坏掉：
 *   实际发生过的例子是文案还在教玩家打一个已经删掉的子命令，玩家照着打、失败、再打，
 *   最后刷了一屏红字。这种情况下"尊重磁盘文件"等于放着 bug 不修。
 *
 * 所以这里的取舍是：**覆盖，但绝不静默丢东西**。覆盖前先把旧文件备份成
 * `zh_CN.yml.bak-<旧版本>`，服主自定义过的句子都能从备份里捞回来，日志也会写清备份文件名。
 * 备份一旦失败就放弃覆盖 —— 宁可让新文案不生效，也不能拿服主的改动去赌。
 *
 * ## 版本怎么判定
 *
 * 比的是 `lang-version` 键：内置模板里的值与磁盘文件里的值不相等就迁移，相等就一个字节都不动。
 * 只做字符串相等判断，不做语义版本大小比较 —— 服主手动降级插件时同样需要把文案换回去，
 * "不相等即迁移"正好覆盖这个方向，而"新版本才覆盖"会漏掉它。
 *
 * 这个键只在文案真正变动的版本里才需要动，跟插件版本号不必逐版对齐。
 */
object LangMigrator {

    /** 语言文件在数据目录与 jar 内的相对路径，两边同名 */
    private const val LANG_PATH = "lang/zh_CN.yml"

    /** 版本标记键。服主不需要手改，改了只会导致一次多余的备份与覆盖 */
    private const val VERSION_KEY = "lang-version"

    /** 磁盘文件缺版本键或读不出来时用的占位，会出现在备份文件名里 */
    private const val UNKNOWN_VERSION = "unknown"

    /**
     * 释放并按需迁移语言文件。
     *
     * 有磁盘 I/O，只在 `LifeCycle.LOAD` 里调用一次。读的是几十 KB 的小文件，
     * 与原本就在这一阶段做的模板释放同量级。
     */
    fun migrate() {
        val target = File(getDataFolder(), LANG_PATH)
        if (!target.exists()) {
            // 首次安装：没有旧文件，也就没有需要保护的服主改动
            releaseResourceFile(LANG_PATH, replace = false)
            info("语言文件已释放：$LANG_PATH")
            return
        }

        val bundled = readBundledVersion()
        if (bundled == null) {
            // 读不到内置模板的版本就没有比对基准。这时覆盖等于盲操作，宁可不动
            warning("读不到内置语言模板的 $VERSION_KEY，本次不迁移语言文件，磁盘文件保持原样")
            return
        }

        val current = readDiskVersion(target)
        if (current == bundled) return

        val backup = backup(target, current)
        if (backup == null) {
            warning("语言文件备份失败，为避免丢失服主改动，本次不覆盖 $LANG_PATH")
            return
        }

        releaseResourceFile(LANG_PATH, replace = true)
        info("语言文件已从 $current 更新到 $bundled，旧文件备份为 ${backup.name}")
    }

    /**
     * 读 jar 内模板的版本。
     *
     * 走 classLoader 而不是 [releaseResourceFile]：后者返回的是磁盘上那份，
     * 而这里要的恰好是"插件带来的新版本"，两者在迁移场景下必须区分开。
     */
    private fun readBundledVersion(): String? {
        val stream = openBundled() ?: run {
            warning("jar 内缺少语言模板 $LANG_PATH")
            return null
        }
        return try {
            stream.use { Yaml.loadMap(it) }[VERSION_KEY]?.toString()
        } catch (error: Exception) {
            // 不吞异常：模板是插件自己带的，解析失败属于打包问题，必须让人看见
            warning("内置语言模板解析失败：${error.javaClass.simpleName} ${error.message}")
            null
        }
    }

    private fun openBundled(): InputStream? =
        LangMigrator::class.java.classLoader.getResourceAsStream(LANG_PATH)

    /**
     * 读磁盘文件的版本。
     *
     * 解析失败按 [UNKNOWN_VERSION] 处理，于是必然与模板版本不等，进而触发备份加覆盖。
     * 这是有意的：一个语法坏掉的语言文件本来就没法用，换成好的模板同时把坏文件留在
     * `.bak-unknown` 里，比让玩家一直看到键名更合理。
     */
    private fun readDiskVersion(file: File): String =
        try {
            file.inputStream().use { Yaml.loadMap(it) }[VERSION_KEY]?.toString() ?: UNKNOWN_VERSION
        } catch (error: Exception) {
            warning("磁盘语言文件解析失败，按未知版本处理：${error.javaClass.simpleName} ${error.message}")
            UNKNOWN_VERSION
        }

    /**
     * 把旧语言文件复制成备份。
     *
     * 同名备份已存在时往后加序号，绝不覆盖上一次的备份 —— 连续升级两个版本时，
     * 最早那份服主改动同样有价值。
     */
    private fun backup(target: File, version: String): File? {
        val base = "${target.name}.bak-$version"
        var candidate = File(target.parentFile, base)
        var index = 2
        while (candidate.exists()) {
            candidate = File(target.parentFile, "$base-$index")
            index++
        }
        return try {
            target.copyTo(candidate)
        } catch (error: Exception) {
            warning("语言文件备份写入失败：${error.javaClass.simpleName} ${error.message}")
            null
        }
    }
}
