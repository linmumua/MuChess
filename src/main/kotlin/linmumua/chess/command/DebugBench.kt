package linmumua.chess.command

import java.util.UUID
import linmumua.chess.board.BoardFacing
import linmumua.chess.board.BoardGrid
import linmumua.chess.config.Msg
import linmumua.chess.game.GameManager
import linmumua.chess.game.GameType
import linmumua.chess.game.PlaceResult
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import taboolib.common.platform.function.submit

/**
 * `/chess debug test` 与 `/chess debug remove` 的压测实现。
 *
 * 用途是量出"一台服务器摆得下多少副棋盘"：棋子是显示实体，一副象棋 32 枚，
 * 64 副就是 2048 个 ItemDisplay，加上棋盘家具本身的 base + hitbox 实体。
 * 这个量级的实体在客户端和服务端各自会有什么表现，只能实测。
 *
 * 与正常放置路径的区别只有"谁来放家具"：这里自己算网格并调
 * [GameManager.placeForDebug]，其余校验、渲染、落盘全部走同一套代码 ——
 * 压测数据才代表真实放置的开销，而不是另一条捷径的开销。
 */
object DebugBench {

    /**
     * 单次压测的棋盘数上限。
     *
     * 拦在这里而不是只靠 `board.max-per-world`：那个上限服主可以配大，
     * 而一条命令要求生成上万副棋盘会把服务端卡死到没人能再执行命令。
     * 512 已经远超实用范围（象棋 512 副 = 16384 个显示实体）。
     */
    const val MAX_COUNT = 512

    /**
     * 每 tick 最多放几副。
     *
     * 不能一 tick 全放完：放家具与摆棋子都是实体操作，64 副象棋一次性铺完
     * 是 2000+ 次 spawn，主线程会明显卡一下，测出来的数字也只反映"卡了多久"。
     * 分 tick 之后每 tick 的工作量可控，累加的净耗时才是有意义的开销。
     */
    private const val PER_TICK = 8

    /**
     * 本次会话由压测建出来的棋盘 id。
     *
     * 只存在内存里，重启后清空 —— 这是刻意的：`debug remove` 的语义是
     * "撤掉我刚测出来的那批"，重启之后那批棋盘已经变成 boards.yml 里的普通棋盘，
     * 该用 `/chess remove` 单独收。压测棋盘也会正常落盘，不做特殊标记，
     * 否则存档格式要为一个调试命令加字段。
     */
    private val benchSessions = LinkedHashSet<UUID>()

    /** 是否正在跑压测，防止两条命令同时铺网格互相抢位置 */
    private var running = false

    /**
     * 铺 [count] 副 [gameType] 棋盘并报性能数据。
     *
     * 网格以玩家脚下为中心铺开，Y 取玩家所在格 —— 压测要求玩家站在平地上，
     * 地形不平时会有一部分棋盘因为占位不通过而落不下，汇总里会算进失败数。
     */
    fun test(player: Player, count: Int, gameType: GameType) {
        if (running) {
            Msg.send(player, "debug-busy")
            return
        }
        if (count <= 0 || count > MAX_COUNT) {
            Msg.send(player, "debug-count-invalid", "max" to MAX_COUNT)
            return
        }

        val world = player.world
        val offsets = BoardGrid.offsets(count, gameType.footprintSize)
        val (width, depth) = BoardGrid.extent(count, gameType.footprintSize)
        // 网格居中摆在玩家脚下，免得整片棋盘全铺在身后看不见
        val baseX = player.location.blockX - width / 2
        val baseZ = player.location.blockZ - depth / 2
        val baseY = player.location.blockY

        running = true
        val stats = Stats()
        val startedAt = System.currentTimeMillis()
        Msg.send(player, "debug-test-start", "count" to count, "type" to gameType.id)

        placeBatch(player, world, offsets, baseX, baseY, baseZ, gameType, 0, stats, startedAt)
    }

    /**
     * 分 tick 放置一批棋盘，放完自己排下一批。
     *
     * 递归安排而不是一个循环里 sleep：主线程不能阻塞，而 `submit` 的下一 tick
     * 回调是 TabooLib 里最直接的让路方式。
     */
    private fun placeBatch(
        player: Player,
        world: org.bukkit.World,
        offsets: List<Pair<Int, Int>>,
        baseX: Int,
        baseY: Int,
        baseZ: Int,
        gameType: GameType,
        from: Int,
        stats: Stats,
        startedAt: Long,
    ) {
        val until = Math.min(from + PER_TICK, offsets.size)
        // 只计本 tick 内实际花在放置与渲染上的纳秒，不含等待下一 tick 的时间
        val tickStart = System.nanoTime()

        for (index in from until until) {
            val (dx, dz) = offsets[index]
            val origin = Vector(
                (baseX + dx).toDouble(),
                baseY.toDouble(),
                (baseZ + dz).toDouble(),
            )
            when (val result = GameManager.placeForDebug(world, origin, BoardFacing.NORTH, gameType)) {
                is PlaceResult.Success -> {
                    benchSessions += result.session.id
                    stats.placed++
                    // 压测的重点是显示实体的量，所以直接铺棋子，不等入座开局。
                    // 象棋会摆出 32 枚，五子棋初始局面是空盘（落子才有子），
                    // 两者的实体数差异本身就是要测的信息。
                    val renderStart = System.nanoTime()
                    result.session.renderer.renderAll(result.session.game.board)
                    stats.renderNanos += System.nanoTime() - renderStart
                    stats.pieces += result.session.game.board.spec.squares()
                        .count { result.session.game.board[it] != null }
                }
                PlaceResult.NoSpace -> stats.noSpace++
                is PlaceResult.LimitReached -> stats.limitReached++
                is PlaceResult.FurnitureOverlap -> stats.furnitureBlocked++
            }
        }

        stats.totalNanos += System.nanoTime() - tickStart
        stats.ticks++

        if (until < offsets.size) {
            submit {
                placeBatch(player, world, offsets, baseX, baseY, baseZ, gameType, until, stats, startedAt)
            }
            return
        }

        running = false
        report(player, stats, System.currentTimeMillis() - startedAt, gameType)
    }

    /**
     * 撤掉本次会话压测建出来的全部棋盘。
     *
     * 逐副走 [GameManager.remove]，与玩家手动收棋盘同一条路径：会清棋子实体、
     * 选中态、机器人登记与家具本体。
     */
    fun remove(player: Player) {
        if (running) {
            Msg.send(player, "debug-busy")
            return
        }
        if (benchSessions.isEmpty()) {
            Msg.send(player, "debug-remove-none")
            return
        }

        val startedAt = System.nanoTime()
        var removed = 0
        var missing = 0
        // 复制一份再遍历：remove 会改 GameManager 的会话表，而这里的 id 集合也要清
        for (id in benchSessions.toList()) {
            val session = GameManager.session(id)
            if (session == null) {
                // 已经被别的路径收掉了（玩家手动拆、家具被挖），不算失败
                missing++
                continue
            }
            GameManager.remove(session)
            removed++
        }
        benchSessions.clear()

        val ms = (System.nanoTime() - startedAt) / 1_000_000.0
        Msg.send(
            player,
            "debug-remove-done",
            "removed" to removed,
            "missing" to missing,
            "ms" to format(ms),
            "left" to GameManager.sessionCount,
        )
    }

    /** 关服与重载时清掉登记，避免握着已经不存在的会话 id */
    fun clear() {
        benchSessions.clear()
        running = false
    }

    /**
     * 把统计结果翻成玩家可见的几条消息。
     *
     * 报四组数：成功/失败数、净耗时与均摊、实体量、失败原因分布。
     * 失败原因单独报是因为"放不下"和"撞上家具"要采取的动作完全不同。
     */
    private fun report(player: Player, stats: Stats, wallMs: Long, gameType: GameType) {
        val totalMs = stats.totalNanos / 1_000_000.0
        val renderMs = stats.renderNanos / 1_000_000.0
        val perBoard = if (stats.placed > 0) totalMs / stats.placed else 0.0

        Msg.send(
            player,
            "debug-test-done",
            "placed" to stats.placed,
            "failed" to stats.failed(),
            "type" to gameType.id,
        )
        Msg.send(
            player,
            "debug-test-timing",
            "total" to format(totalMs),
            "render" to format(renderMs),
            "per" to format(perBoard),
            "wall" to wallMs,
            "ticks" to stats.ticks,
        )
        Msg.send(
            player,
            "debug-test-entities",
            "pieces" to stats.pieces,
            "boards" to GameManager.sessionCount,
        )
        if (stats.failed() > 0) {
            Msg.send(
                player,
                "debug-test-failures",
                "nospace" to stats.noSpace,
                "limit" to stats.limitReached,
                "furniture" to stats.furnitureBlocked,
            )
        }
    }

    /** 固定两位小数，避免消息里出现一长串浮点尾数 */
    private fun format(value: Double): String = String.format("%.2f", value)

    /**
     * 一次压测的累计数据。
     *
     * 用可变类而不是每 tick 传新的 data class：分 tick 递归时要一路带着累加，
     * 复制一份只会让"哪个是最新的"更难看清。
     */
    private class Stats {
        /** 成功放下的棋盘数 */
        var placed = 0

        /** 实际生成的棋子显示实体数 */
        var pieces = 0

        /** 放置与渲染的净耗时，不含跨 tick 等待 */
        var totalNanos = 0L

        /** 其中花在铺棋子上的部分 */
        var renderNanos = 0L

        /** 用掉的 tick 数 */
        var ticks = 0

        /** 因 2x2 占位不通过或棋盘重叠而失败 */
        var noSpace = 0

        /** 因世界棋盘数上限而失败 */
        var limitReached = 0

        /** 因 CE 家具阻挡而失败 */
        var furnitureBlocked = 0

        fun failed(): Int = noSpace + limitReached + furnitureBlocked
    }
}
