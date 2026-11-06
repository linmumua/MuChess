package linmumua.chess

import linmumua.chess.command.ChessCommandHandler
import linmumua.chess.command.DebugBench
import linmumua.chess.command.PackHook
import linmumua.chess.compat.CraftEngineBridge
import linmumua.chess.compat.CraftEngineInteractListener
import linmumua.chess.compat.CraftEngineLifecycle
import linmumua.chess.compat.CraftEnginePlaceListener
import linmumua.chess.config.ChessConfig
import linmumua.chess.config.LangMigrator
import linmumua.chess.game.BoardStorage
import linmumua.chess.game.BotPlayer
import linmumua.chess.game.GameManager
import linmumua.chess.game.GoBgmController
import linmumua.chess.game.TurnCountdown
import linmumua.chess.game.request.GameRequests
import linmumua.chess.listener.SessionListener
import linmumua.chess.pack.PackService
import taboolib.common.LifeCycle
import taboolib.common.env.RuntimeDependency
import taboolib.common.platform.Awake
import taboolib.common.platform.Schedule
import taboolib.common.platform.function.info
import taboolib.common.platform.function.submitAsync

/**
 * 插件入口。
 *
 * 主类是 TabooLib 的 `taboolib.platform.BukkitPlugin`（由构建插件写进 plugin.yml），
 * 业务侧只用 `@Awake(LifeCycle.*)` 挂生命周期，不自己写 JavaPlugin。
 *
 * SnakeYAML 用 `@RuntimeDependency` 声明运行期下载，不打包进 JAR ——
 * 项目要求构建产物里不含第三方库与 Kotlin 标准库。
 */
@RuntimeDependency(
    value = "!org.yaml:snakeyaml:2.5",
    test = "!org.yaml.snakeyaml.Yaml",
)
object ChessPlugin {

    /**
     * 加载阶段：释放模板、读配置、准备语言文件。
     *
     * 这一阶段**不碰 CraftEngine**：CE 的注册表要等它自己的重载事件才可用，
     * 在这里读一定是空的。
     */
    @Awake(LifeCycle.LOAD)
    fun onLoad() {
        // 这里刻意不设 Language.enableSimpleComponent：
        // TabooLib 的 SimpleComponent 不是 MiniMessage（它用 [文字](color=x) 语法），
        // 打开它对 <gray> 这类标签没有任何作用。
        // 本项目的 MiniMessage 解析统一在 Msg 里用 Adventure 做，详见 Msg 的说明。
        ChessConfig.reload()
        // 语言文件按版本迁移：首次释放模板，文案版本变动时备份旧文件再换上新模板
        LangMigrator.migrate()
        GameManager.applySettings()
        info("配置与规则注册表已就绪")
    }

    /**
     * 启用阶段：读棋盘存档、接线 CraftEngine。
     *
     * 存档读取放异步，恢复棋盘等世界加载事件。
     */
    @Awake(LifeCycle.ENABLE)
    fun onEnable() {
        submitAsync {
            SessionListener.loadPending()
        }
        hookCraftEngine()
        info("MuChess 已启用")
    }

    /**
     * CraftEngine 接线。
     *
     * 时序是这里最要紧的事：
     * 1. 现在（ENABLE）只能确认 CE 插件在不在，以及把资源包导出去。
     *    导出是纯文件拷贝，不碰 CE 的注册表，所以这个阶段可以做。
     * 2. CE 的物品与家具注册表要等 `CraftEngineReloadEvent` 才有内容，
     *    所以外观实现的注入挂在那个事件里（见 [CraftEngineLifecycle]）。
     * 3. 资源包写完要让 CE 重载一次，重载又会触发上面那个事件，顺带把外观接上。
     *
     * CE 不在时这里全程静默降级：棋子走原版兜底外观，棋盘没有家具外观，
     * 规则与对局照常。
     */
    private fun hookCraftEngine() {
        CraftEngineBridge.logStatus()
        PackHook.install(PackService)

        if (!CraftEngineBridge.isAvailable) {
            return
        }
        CraftEngineLifecycle.register()
        CraftEngineInteractListener.register()
        // 放下家具即建棋局，拆掉任意 hitbox 都收整副；没有这一层 give 出来的家具只是空壳
        CraftEnginePlaceListener.register()

        if (ChessConfig.settings.craftEngine.autoGeneratePack) {
            // 自动检查只认 muchess 目录：存在就尊重服主资源，不比较也不覆盖；缺失才异步导出。
            PackService.ensureDirectory()
        }
    }

    /**
     * 关服阶段：清场。
     *
     * 项目硬要求：关服后世界里不允许残留本插件生成的显示实体。
     * 顺序是先落盘（这时会话还在，能导出记录），再清实体与选中态。
     *
     * 这里的落盘是**同步**的：关服阶段异步任务不保证还能跑完，
     * 存档丢了比多花几十毫秒严重。这是关服路径唯一允许的主线程 I/O。
     */
    @Awake(LifeCycle.DISABLE)
    fun onDisable() {
        val records = GameManager.exportRecords()
        BoardStorage.flush(records)
        GameManager.clearDirty()
        ChessCommandHandler.clearResignPending()
        // 压测登记也是纯运行时状态。棋盘本身已经落盘，重启后按普通棋盘处理，
        // 攥着一批旧 id 只会让下次 debug remove 去找一堆不存在的会话
        DebugBench.clear()
        // 机器人是纯运行时状态，不落盘：重启后座位上留着一个不存在的"玩家"比没有机器人更糟
        BotPlayer.clearAll()
        // 求和与悔棋请求同理，而且它们只有三十秒有效期，重启后一条都不该留
        GameRequests.clearAll()
        GameManager.shutdown()
        CraftEngineLifecycle.shutdown()
        PackHook.reset()
        info("MuChess 已停用，棋盘实体已清理")
    }

    /**
     * 对局超时检查、读秒动作栏、背景音乐巡检、过期请求清理与存档落盘。
     *
     * 每秒跑一次。五件事在没有棋盘（或没有限时对局、没有围棋在下、没有请求挂着）时
     * 都会自己短路返回。
     */
    @Schedule(period = 20L, async = false)
    fun tickSessions() {
        SessionListener.checkTimeouts()
        // 读秒必须排在超时判定之后：同一 tick 内先判负让对局进入 FINISHED，
        // TurnCountdown 随即读到"不该读秒"而跳过，避免终局播报旁边还挂着一行 0 秒
        TurnCountdown.tick()
        // 背景音乐要靠巡检重播：MC 客户端不能让一个声音事件自己循环，
        // 播一次只有一遍，一局围棋比曲子长得多。不空转的保证见 GoBgmController.tick
        GoBgmController.tick()
        // 过期的求和与悔棋请求。判定本身已经把过期请求当作不存在，
        // 所以这一步只是回收内存，早一秒晚一秒都不影响谁能答复什么
        GameRequests.purgeExpired(System.currentTimeMillis())
        maybeSave()
    }

    /**
     * 未开局棋盘的悬浮字巡检。
     *
     * 独立于 [tickSessions] 是因为两者的频率诉求不同：超时判定要每秒一次才够准，
     * 而牌子只需要在被外力删掉后能自己回来。5 秒（period = 100 ticks）是恢复延迟与
     * 开销之间的取舍 —— 服主 `/kill @e[type=text_display]` 清场之后最多五秒牌子就回来，
     * 玩家几乎察觉不到；再密没有意义，因为正常情况下这个巡检什么也不做。
     *
     * 不空转的保证在 [GameManager.refreshWaitingLabels] 里：开关关掉直接返回，
     * 只看 WAITING 的会话，活着的牌子在渲染器里立刻短路。判断依据见那个方法的说明。
     */
    @Schedule(period = 100L, async = false)
    fun tickBoardLabels() {
        GameManager.refreshWaitingLabels()
    }

    /** 距上次落盘过了多少秒 */
    private var saveCounter = 0

    /**
     * 按配置间隔落盘。
     *
     * 只有存档有改动时才写。导出记录要在主线程做（要读会话状态），
     * 真正的文件写入丢给异步线程。
     */
    private fun maybeSave() {
        if (!GameManager.dirty) {
            return
        }
        saveCounter++
        if (saveCounter < ChessConfig.settings.storage.saveIntervalSeconds) {
            return
        }
        saveCounter = 0
        // 主线程导出快照，异步写文件：禁止主线程做文件 I/O
        val records = GameManager.exportRecords()
        GameManager.clearDirty()
        submitAsync {
            BoardStorage.save(records)
        }
    }
}
