package linmumua.chess.command

import taboolib.common.platform.function.warning

/**
 * 资源包生成的接线点。
 *
 * 资源包产出属于 `pack/` 模块，由另一侧实现。本层只需要"触发一次生成、拿到结果"，
 * 所以在自己这边留一个可注入的钩子，而不是直接依赖 `pack/` 的具体类 ——
 * 那样两侧就得同时存在才能编译，并行开发会互相卡住。
 *
 * 接线方式：`pack/` 侧在启用阶段调 [install]，把真正的生成器塞进来。
 * 没装钩子时按"没有资源包能力"处理，走 pack-skipped 文案，不报错。
 */
object PackHook {

    /**
     * 生成结果。
     *
     * 三种结局各对应一条语言键：pack-done、pack-skipped、pack-failed。
     */
    sealed interface Outcome {

        /** 生成完成，[fileCount] 是写出的文件数 */
        data class Done(val fileCount: Int) : Outcome

        /** 跳过，通常是没装 CraftEngine 或配置关掉了自动生成 */
        data object Skipped : Outcome

        /** 生成失败，[reason] 是给服主看的原因 */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * 资源包生成器。
     *
     * 实现方要保证：耗时的文件遍历、贴图生成、压缩都在异步线程做，
     * 只有调 CraftEngine 的那一步回主线程。回调可以在任意线程触发，
     * 调用方（命令层）自己负责切回主线程发消息。
     */
    fun interface Generator {
        /** 生成一次，完成后用 [callback] 回报结果 */
        fun generate(callback: (Outcome) -> Unit)
    }

    /** 当前注入的生成器，未接线时为 null */
    @Volatile
    private var generator: Generator? = null

    /** 装上生成器，由 `pack/` 侧在启用阶段调用 */
    fun install(newGenerator: Generator) {
        generator = newGenerator
    }

    /** 卸掉生成器，CE 卸载时调用 */
    fun reset() {
        generator = null
    }

    /** 是否已经接上资源包能力 */
    fun isAvailable(): Boolean = generator != null

    /**
     * 触发一次生成。
     *
     * 没接线时直接回 [Outcome.Skipped]；实现方抛异常时翻成 [Outcome.Failed]
     * 并记日志，不允许把异常吞掉。
     */
    fun regenerate(callback: (Outcome) -> Unit) {
        val current = generator
        if (current == null) {
            callback(Outcome.Skipped)
            return
        }
        runCatching { current.generate(callback) }.onFailure { error ->
            warning("资源包生成失败：${error.message}")
            callback(Outcome.Failed(error.message ?: ""))
        }
    }
}
