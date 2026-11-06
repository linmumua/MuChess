package linmumua.chess.listener

import linmumua.chess.config.ChessConfig
import linmumua.chess.game.BoardStorage
import linmumua.chess.game.GameManager
import linmumua.chess.game.GamePhase
import linmumua.chess.game.GameResult
import linmumua.chess.interact.MoveExecutor
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.WorldLoadEvent
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info
import taboolib.common.platform.function.submit

/**
 * 对局相关的世界与玩家事件。
 *
 * 负责两件事：世界加载后恢复棋盘、玩家退出时处理未完的对局。
 * 不处理右键，那在 [BoardInteractListener]。
 */
object SessionListener {

    /** 还没恢复的存档记录，按世界名分组。世界一加载就取出对应的那批 */
    private val pending = HashMap<String, MutableList<linmumua.chess.game.BoardRecord>>()

    /**
     * 读存档并按世界分组。
     *
     * 在启用阶段异步调用：读文件属于耗时 I/O，不能放主线程。
     * 分组之后等各世界的 [WorldLoadEvent] 再恢复，
     * 因为启用阶段拿不到还没加载的世界对象。
     */
    fun loadPending() {
        val records = BoardStorage.load()
        if (records.isEmpty()) {
            return
        }
        synchronized(pending) {
            records.forEach { record ->
                pending.getOrPut(record.world) { ArrayList() } += record
            }
        }
        info("已读出 ${records.size} 副棋盘存档，等世界加载后恢复")
        // 已经加载好的世界不会再触发 WorldLoadEvent，要主动补一次
        submit {
            org.bukkit.Bukkit.getWorlds().forEach { restoreWorld(it) }
        }
    }

    /** 世界加载完成后恢复这个世界里的棋盘 */
    @SubscribeEvent
    fun onWorldLoad(event: WorldLoadEvent) {
        restoreWorld(event.world)
    }

    /**
     * 区块加载完成后补回非持久化棋子实体，并清掉其中的无主残留。
     *
     * 延后一 tick，避开 CE 家具与区块存档实体仍在恢复的阶段；只匹配家具 base entity
     * 所在的中心区块，因此一副跨区块棋盘不会被相邻区块重复重铺。
     */
    @SubscribeEvent
    fun onChunkLoad(event: ChunkLoadEvent) {
        val chunk = event.chunk
        submit(delay = 1L) {
            if (chunk.isLoaded) {
                GameManager.resyncSessionsIn(chunk)
                // 必须排在 resync 之后：resync 会重铺棋子并把新实体登记进索引，
                // 先扫会把它们当成无主的删掉，然后这个区块的棋盘就空了。
                GameManager.sweepChunk(chunk)
            }
        }
    }

    /**
     * 恢复某个世界里的棋盘。
     *
     * 实体操作必须在主线程，调用方要保证这一点。
     */
    private fun restoreWorld(world: org.bukkit.World) {
        val records = synchronized(pending) { pending.remove(world.name) } ?: return
        var restored = 0
        records.forEach { record ->
            if (GameManager.restore(record, world) != null) {
                restored++
            }
        }
        if (restored > 0) {
            info("世界 ${world.name} 恢复了 $restored 副棋盘")
        }
    }

    /**
     * 玩家退出。
     *
     * 清掉选中态并把浮起的棋子放回去，否则那枚子会一直悬在空中。
     * 对局进行中退出算弃局，按语言文件的 game-over-quit 播报。
     *
     * 退出的人分两类，用 [linmumua.chess.game.ChessGame.sideOf] 区分：返回 null 的是观战者
     * （观战不占座位，移出名单即可），非 null 的是已入座的一方，要按 phase 分别处理。
     * 这个区分不能省 —— [linmumua.chess.game.GameSession.removeSpectator] 对已入座的玩家
     * 是空操作，靠它兜住座位等于座位永远不腾。
     */
    @SubscribeEvent
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val session = GameManager.sessionOf(uuid) ?: return

        // 先清掉这副棋盘的白圈、发光与浮起状态
        MoveExecutor.releaseAllSelections(session)

        val side = session.game.sideOf(uuid)
        if (side == null) {
            // 掉线的是观战者：观战席不占座位，从名单里移掉就完事，不碰对局状态
            session.removeSpectator(uuid)
            return
        }

        when (session.game.phase) {
            // 进行中掉线算弃局，按 game-over-quit 播报后座位随终局一起冻结
            GamePhase.PLAYING -> {
                val result = GameResult.Quit(side)
                session.game.finishWith(result)
                MoveExecutor.announceResult(session, result)
                GameManager.markDirty()
            }

            // 还没开局就掉线：必须腾出座位，否则会开出一局没人能走的死棋。
            //
            // 开局改成"入座 → 有人点开始"两步之后，"满座但没开局"从几毫秒的瞬态变成了
            // 会一直等下去的持久态。座位不腾的话：A 入座后掉线，B 坐进另一边，牌子翻成
            // "开始对局"，B 一点就真的开了局；轮到 A 时无人能走，而默认不判超时
            // （turn-timeout-seconds: 0），PLAYING 阶段又拒绝 /chess remove 与挖家具，
            // 这副棋盘就只剩 B 认输能解开。
            //
            // 取舍：重连后不会自动坐回原位，要重新点牌子入座。腾座位本来就是为了不占着
            // 位子卡住别人，"替掉线的人留座"和"别人能正常开局"二者只能选一个，选后者。
            //
            // 不给还在线的另一方发消息：这一步没有新文案键。此时还没开局，没有"对局被中断"
            // 这件事可播报，而牌子从"开始对局"翻回"加入对局"本身就是看得见的反馈；复用
            // game-over-quit 更糟，那句写的是"对局作废"，可这局压根没开始过。
            GamePhase.WAITING -> {
                session.game.leave(uuid)
                // 座位空出来了，牌子要从"开始对局"翻回"加入对局"
                session.refreshLabel()
                // 座位会落进 boards.yml（见 GameManager.recordOf），不标脏的话重启后
                // 掉线者的座位又回来了，死局链路照样能复现
                GameManager.markDirty()
            }

            // 终局盘留着复盘，座位不动：结果已经记在 result 里，腾座位改不了胜负，
            // 反而会让复盘时看不出这局是谁跟谁下的
            GamePhase.FINISHED -> Unit
        }
    }

    /**
     * 检查对局超时。
     *
     * 由调度任务每秒调一次。没有棋盘时直接返回，不做任何遍历。
     *
     * 短路条件从"全局时限为 0 就返回"改成了"没有棋盘就返回"：每局可以单独设时限
     * （见 [linmumua.chess.game.ChessGame.turnTimeoutSeconds]）之后，全局为 0
     * 不再等于"全服都不限时"，照旧短路会让菜单里设过时限的那副棋盘永远不判超时。
     * 遍历成本仍然可控 —— 棋盘数量本来就受 `board.max-boards` 限制，且每条会话在
     * 有效时限为 0 时立刻跳过，不会去读时间以外的任何状态。
     */
    fun checkTimeouts() {
        if (GameManager.isEmpty()) {
            return
        }
        val globalTimeout = ChessConfig.settings.game.turnTimeoutSeconds
        val now = System.currentTimeMillis()
        GameManager.allSessions().forEach { session ->
            val game = session.game
            val timeout = game.effectiveTimeoutSeconds(globalTimeout)
            if (timeout <= 0) {
                return@forEach
            }
            if (game.isTimedOut(now, timeout)) {
                // 超时判负的是当前该走棋的一方
                val result = GameResult.Timeout(game.sideToMove)
                game.finishWith(result)
                MoveExecutor.announceResult(session, result)
                GameManager.markDirty()
            }
        }
    }
}
