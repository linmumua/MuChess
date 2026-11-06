package linmumua.chess.pack

import linmumua.chess.command.PackHook
import linmumua.chess.compat.CraftEngineBridge
import linmumua.chess.config.ChessConfig
import taboolib.common.platform.function.info
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.function.warning

/**
 * 资源包生成的对外服务。
 *
 * 把 [PackGenerator] 的文件导出和 CraftEngine 的重载串起来，并实现命令层的
 * [PackHook.Generator] 接口，让 `/chess pack` 能触发一次重新生成。
 *
 * 线程约定：文件导出在异步线程，CE 重载回主线程。这是项目硬要求 ——
 * 主线程不做文件遍历，CE API 调用不在异步线程。
 */
object PackService : PackHook.Generator {

    /**
     * 执行一次生成。
     *
     * @param force 忽略内容比对，强制重写
     * @param checkDirectory 是否优先按 muchess 目录存在性跳过
     * @return 给命令层用的结果
     */
    fun generate(force: Boolean, checkDirectory: Boolean = false): PackHook.Outcome {
        if (!CraftEngineBridge.isAvailable) {
            val result = PackGenerator.Result.Skipped("没有可用的 CraftEngine")
            PackGenerator.logResult(result)
            return PackHook.Outcome.Skipped
        }

        val dataFolder = CraftEngineBridge.dataFolder()
        if (dataFolder == null) {
            warning("拿不到 CraftEngine 的数据目录，资源包没法导出")
            return PackHook.Outcome.Failed("CraftEngine 数据目录不可用")
        }

        val skipUnchanged = ChessConfig.settings.craftEngine.skipIfUnchanged
        val result = PackGenerator.export(
            craftEngineDataFolder = dataFolder,
            force = force || !skipUnchanged,
            checkDirectory = checkDirectory,
        )
        PackGenerator.logResult(result)

        return when (result) {
            is PackGenerator.Result.Exported -> {
                maybeReload()
                PackHook.Outcome.Done(result.fileCount)
            }
            // 内容没变也算成功，服主执行命令时应该看到"已经是最新"而不是失败
            is PackGenerator.Result.UpToDate -> PackHook.Outcome.Done(result.fileCount)
            is PackGenerator.Result.Skipped -> PackHook.Outcome.Skipped
            is PackGenerator.Result.Failed -> PackHook.Outcome.Failed(result.reason)
        }
    }

    /**
     * 插件重载与 CE 重载共用的自动检查。
     *
     * 判定依据是 `plugins/CraftEngine/resources/muchess/pack.yml` 的版本：与 JAR 内一致就
     * 完全不处理；目录缺失或版本对不上才异步重写完整资源。按版本判定而不是只看目录存在，
     * 是因为插件升级后磁盘上是旧模型与旧物品定义，只看目录会让新资源永远进不去，
     * 棋子就一直退回原版釉陶外观。
     *
     * 导出后按配置补一次 CE 重载；下一次事件会因版本已对齐而直接结束，不会形成重载循环。
     */
    fun ensureDirectory() {
        if (!CraftEngineBridge.isAvailable || !ChessConfig.settings.craftEngine.autoGeneratePack) {
            return
        }
        val dataFolder = CraftEngineBridge.dataFolder()
        if (dataFolder == null) {
            warning("拿不到 CraftEngine 的数据目录，无法检查 muchess 资源文件夹")
            return
        }
        submitAsync {
            if (!PackGenerator.needsExport(dataFolder)) {
                return@submitAsync
            }
            // 版本对不上时必须强制重写：旧文件里可能有同名但内容过期的模型与 items.yml。
            // 导出层再用 checkDirectory 复核一次版本，防住 CE 与插件重载并发竞争。
            generate(force = true, checkDirectory = true)
        }
    }

    /**
     * 按配置触发 CE 重载。
     *
     * CE 的重载要在主线程调。关掉自动重载时只提示服主自己执行 `/ce reload all`，
     * 不悄悄跳过 —— 不然服主会以为资源包没生效是插件的问题。
     */
    private fun maybeReload() {
        if (!ChessConfig.settings.craftEngine.autoReload) {
            info("资源包已写入，但自动重载已关闭，需要手动执行 /ce reload all 才会生效")
            return
        }
        submit {
            val reloaded = CraftEngineBridge.reload()
            if (reloaded) {
                info("已让 CraftEngine 重新加载资源包")
            } else {
                info("CraftEngine 自动重载没成功，请手动执行 /ce reload all")
            }
        }
    }

    /** 命令层入口：异步生成，完成后回报结果 */
    override fun generate(callback: (PackHook.Outcome) -> Unit) {
        submitAsync {
            val outcome = try {
                generate(force = true)
            } catch (error: Throwable) {
                warning("资源包生成过程抛异常：${error.javaClass.simpleName} ${error.message}")
                PackHook.Outcome.Failed(error.message ?: error.javaClass.simpleName)
            }
            callback(outcome)
        }
    }
}
