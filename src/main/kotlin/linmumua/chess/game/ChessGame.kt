package linmumua.chess.game

import java.util.UUID
import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.go.GoRules
import linmumua.chess.rule.xiangqi.GameOutcome
import linmumua.chess.rule.xiangqi.MoveRejection
import linmumua.chess.rule.core.GameRules

/**
 * 一局棋的运行时状态。
 *
 * 只管"局面、轮次、双方是谁、走过哪些步"，不管渲染也不管消息。
 * 玩家用 [UUID] 表示而不是 Bukkit 的 Player，因此这个类**不依赖 Bukkit**，
 * 轮次与结算逻辑可以脱离服务端单测。
 *
 * @param rules 规则门面，由上层按配置构造好传进来
 */
class ChessGame(
    val rules: GameRules,
    /** 起始局面，默认摆一副开局 */
    board: Board = rules.initialBoard(),
) {

    /** 当前局面，走子会原地修改 */
    var board: Board = board
        private set

    /** 红方玩家，null 表示这个座位还空着 */
    var redPlayer: UUID? = null
        private set

    /** 黑方玩家，null 表示这个座位还空着 */
    var blackPlayer: UUID? = null
        private set

    /** 已走过的着法，供悔棋与棋谱使用 */
    private val history = ArrayList<Move>()

    /** 对局阶段 */
    var phase: GamePhase = GamePhase.WAITING
        private set

    /** 终局结果，未终局时为 null。认输、超时这类由流程层给出的结局也记在这里 */
    var result: GameResult? = null
        private set

    /**
     * 上一次走子的时间戳，单位毫秒。
     *
     * 由调用方传入而不是这里取 `System.currentTimeMillis()`，
     * 这样超时判定能在单测里喂假时间。
     */
    var lastMoveAt: Long = 0L
        private set

    /** 该走棋的一方 */
    val sideToMove: Side get() = board.sideToMove

    /**
     * 本局单独的每步时限，单位秒；null 表示跟随 config.yml 的全局值。
     *
     * 加这一层覆盖而不是只有全局配置：同一个服务器上"新手慢慢下"和"快棋"要共存，
     * 改全局配置会影响所有正在进行的对局。0 是**有意义的显式值**（这局不限时），
     * 与 null（没设过，听全局的）不是一回事，所以类型是 Int? 而不是 Int。
     *
     * 由棋盘菜单的超时设置按钮改写，随 `boards.yml` 落盘；旧存档没有这个字段，
     * 读回来是 null，行为与升级前完全一致。
     */
    var turnTimeoutSeconds: Int? = null

    /**
     * 本局实际生效的每步时限。
     *
     * 收成一个函数供超时判定与倒计时显示共用：这两处必须看到同一个数，
     * 各自写一遍 `?:` 早晚会分叉，分叉的表现是"倒计时读到 0 了却不判负"。
     *
     * @param globalSeconds config.yml 的 `game.turn-timeout-seconds`
     */
    fun effectiveTimeoutSeconds(globalSeconds: Int): Int = turnTimeoutSeconds ?: globalSeconds

    /** 走过的步数 */
    val moveCount: Int get() = history.size

    /** 最近一步棋，没走过则为 null */
    val lastMove: Move? get() = history.lastOrNull()

    /** 只读的着法历史 */
    fun historyView(): List<Move> = history.toList()

    /** 某玩家在这局里的阵营，不在局中返回 null */
    fun sideOf(player: UUID): Side? = when (player) {
        redPlayer -> Side.FIRST
        blackPlayer -> Side.SECOND
        else -> null
    }

    /** 某阵营的玩家 */
    fun playerOf(side: Side): UUID? = if (side == Side.FIRST) redPlayer else blackPlayer

    /** 该玩家是否为对局双方之一 */
    fun isPlayer(player: UUID): Boolean = sideOf(player) != null

    /** 双方是否都已入座 */
    fun isSeatFull(): Boolean = redPlayer != null && blackPlayer != null

    /**
     * 已入座人数，取值 0..2。
     *
     * 棋盘上方的"加入对局 1/2"和入座回执都要报这个数，共用一个口径，
     * 免得两处各数一遍数出不一样的结果。
     */
    fun seatedCount(): Int = Side.entries.count { playerOf(it) != null }

    /**
     * 这局是否已经开过。
     *
     * 用来回答"棋盘上该不该有棋子"这一个问题，所以 FINISHED 也算已开过 ——
     * 终局后棋盘要留着让人复盘，棋子不能凭空消失。只有 WAITING 是空盘。
     *
     * 判断收在这里而不是让渲染侧各自比 phase：放置、存档恢复、区块重载三条路径
     * 都要问同一件事，散在三处早晚会漏一处，漏了的表现就是"空棋盘凭空长出棋子"。
     */
    fun hasStarted(): Boolean = phase != GamePhase.WAITING

    /**
     * 当前空着的阵营。
     *
     * 保持红、黑的稳定顺序，流程层只在两边都空时随机挑一个；只剩一边时必须补到剩余座位。
     */
    fun availableSides(): List<Side> = buildList(2) {
        if (redPlayer == null) {
            add(Side.FIRST)
        }
        if (blackPlayer == null) {
            add(Side.SECOND)
        }
    }

    /**
     * 入座。
     *
     * 座位被占或该玩家已在局中都返回失败原因，由流程层挑对应文案。
     *
     * 坐满**不再自动开局**：开局要有人显式点"开始"（见 [start]）。
     * 原因是开局那一刻才摆棋子，而"最后一个人刚坐下"和"双方都准备好了"
     * 不是一回事 —— 自动开局会让人还在调整视角时棋子就已经铺完、计时也开始跑了。
     */
    fun join(player: UUID, side: Side): JoinResult {
        if (phase == GamePhase.FINISHED) {
            return JoinResult.GAME_FINISHED
        }
        if (isPlayer(player)) {
            return JoinResult.ALREADY_JOINED
        }
        if (playerOf(side) != null) {
            return JoinResult.SEAT_TAKEN
        }
        if (side == Side.FIRST) {
            redPlayer = player
        } else {
            blackPlayer = player
        }
        // 坐满只是"可以开始了"，phase 仍留在 WAITING，由 start() 推进
        return if (isSeatFull()) JoinResult.SEAT_FULL else JoinResult.WAITING
    }

    /**
     * 显式开局。
     *
     * 这是 phase 从 WAITING 走到 PLAYING 的**唯一**入口（存档恢复的
     * [restoreSeats] 不算，那是照抄旧状态而不是开新局）。开局与入座分开之后，
     * 摆棋子、启动计时、踢机器人第一手这三件事有了一个确定的时刻可挂。
     *
     * 座位没坐满不许开：一个人开局等于自己跟自己下。
     *
     * @param now 当前时间戳，用于起算超时；与 [move] 一样由调用方给，便于单测喂假时间
     */
    fun start(now: Long): StartResult {
        if (phase == GamePhase.FINISHED) {
            return StartResult.GAME_FINISHED
        }
        if (phase == GamePhase.PLAYING) {
            return StartResult.ALREADY_PLAYING
        }
        if (!isSeatFull()) {
            return StartResult.SEAT_NOT_FULL
        }
        phase = GamePhase.PLAYING
        markStarted(now)
        return StartResult.STARTED
    }

    /**
     * 离座。
     *
     * 已经开局后离座等于弃局，由流程层决定是否判负；这里只负责清座位与改阶段。
     *
     * **调用约定：PLAYING 阶段的对局必须先 [finishWith] 结算再调本方法。**
     * 直接对在下的局调 leave 会落到下面那条 PLAYING → WAITING 分支，造出
     * "phase 是 WAITING 但盘上还摆着棋子"的坏状态：[hasStarted] 的定义是
     * `phase != WAITING`，它会回答"这局没开过"，于是区块重载或重启后
     * 渲染侧按空盘处理，整副棋子凭空消失。
     *
     * 现有三个调用点都守住了这条约定，那条分支目前不可达：
     * `ChessCommandHandler.leave` 与 `ChessCommandHandler.botRemove` 都先判
     * `phase == PLAYING` 再 finishWith（phase 变成 FINISHED），
     * `SessionListener.onQuit` 只在 WAITING 分支里调。
     * 新增调用点时要么先结算，要么确认自己只在 WAITING 阶段调。
     */
    fun leave(player: UUID): Boolean {
        val side = sideOf(player) ?: return false
        if (side == Side.FIRST) {
            redPlayer = null
        } else {
            blackPlayer = null
        }
        // 兜底分支：按上面的调用约定，走到这里说明调用方漏了 finishWith。
        // 保留退回 WAITING 而不是抛异常，是因为改行为会影响别的路径，超出当前范围。
        if (phase == GamePhase.PLAYING) {
            phase = GamePhase.WAITING
        }
        return true
    }

    /**
     * 玩家落子。
     *
     * 这是**唯一**能改动局面的入口，非法着法一律不落地。
     * 判定顺序：阶段 → 归属与轮次与规则（交给规则层的 4 参 rejectionOf）。
     *
     * @param now 当前时间戳，用于刷新超时计时；由调用方给，便于单测
     */
    fun move(player: UUID, from: Square, to: Square, now: Long): MoveAttempt {
        if (phase != GamePhase.PLAYING) {
            return MoveAttempt.NotPlaying(phase)
        }
        val mover = sideOf(player) ?: return MoveAttempt.NotAPlayer
        // 归属、轮次、规则全交给规则层判，交互层不自己复制一份判断
        val rejection = rules.rejectionOf(board, from, to, mover)
        if (rejection != null) {
            return MoveAttempt.Rejected(rejection)
        }
        val move = rules.moveAt(board, from, to)
            ?: return MoveAttempt.Rejected(MoveRejection.EMPTY_ORIGIN)
        rules.apply(board, move)
        history += move
        lastMoveAt = now

        // 提子点位必须在 apply 之后、下一手之前取：规则层那份是可变状态，会被下一手覆盖。
        // 取出来放进不可变的 Success 里交给渲染层，让它知道该删哪些显示实体。
        val captured = rules.lastCaptures()

        // 走完之后轮到对方，终局判定必须按对方来算
        val opponent = mover.opposite
        val outcome = rules.outcome(board, opponent)
        val inCheck = rules.needsWarning(board, opponent)
        if (outcome.isFinished) {
            phase = GamePhase.FINISHED
            result = GameResult.fromOutcome(outcome)
        }
        return MoveAttempt.Success(move, outcome, inCheck, captured)
    }

    /**
     * 虚着（围棋 pass）。
     *
     * 只有围棋支持，其余棋种返回 [PassAttempt.NotSupported]。
     * 判定顺序与 [move] 保持一致：阶段 → 归属 → 轮次 → 棋种能力。
     *
     * 连续虚着数**不由本类持有**，而在 [GoRules] 里。原因是终局要靠
     * `rules.outcome(board, side)` 产出 [GameOutcome.ScoreWin]，而那个签名看不到
     * 本类的字段 —— 计数放这里的话，规则层永远算不出双方已连续虚着，双 pass 终局走不通。
     *
     * **虚着计数不持久化**：它只在 [GoRules] 内存里，`BoardRecord` 不存。所以
     * 「一方虚着 → 关服重启 → 另一方虚着」不会终局，计数从 0 重新起算。这是显式接受的
     * 取舍而不是待修的 bug：虚着只在紧接着的下一手这个窗口内有意义，跨重启的半个虚着
     * 本身就是异常序列；玩家再各虚着一次即可终局。详见 [GoRules] 的类注释。
     *
     * @param now 当前时间戳，用于刷新超时计时；与 [move] 一样由调用方给
     */
    fun pass(player: UUID, now: Long): PassAttempt {
        if (phase != GamePhase.PLAYING) {
            return PassAttempt.NotPlaying(phase)
        }
        val mover = sideOf(player) ?: return PassAttempt.NotAPlayer
        val goRules = rules as? GoRules ?: return PassAttempt.NotSupported
        if (mover != board.sideToMove) {
            return PassAttempt.WrongTurn
        }

        val move = goRules.passMove(mover)
        goRules.apply(board, move)
        history += move
        lastMoveAt = now

        // 与 move() 同构：走完轮到对方，终局按对方算
        val outcome = goRules.outcome(board, mover.opposite)
        if (outcome.isFinished) {
            phase = GamePhase.FINISHED
            result = GameResult.fromOutcome(outcome)
        }
        return PassAttempt.Passed(outcome)
    }

    /**
     * 认输。
     *
     * 未开局时认输没有意义，返回 false 让流程层给提示。
     */
    fun resign(player: UUID): Side? {
        if (phase != GamePhase.PLAYING) {
            return null
        }
        val side = sideOf(player) ?: return null
        phase = GamePhase.FINISHED
        result = GameResult.Resign(side)
        return side
    }

    /** 判负收场，供超时与离线弃局用 */
    fun finishWith(newResult: GameResult) {
        phase = GamePhase.FINISHED
        result = newResult
    }

    /**
     * 悔棋，撤销最近一步。
     *
     * 是否允许悔棋由配置决定，这里只提供能力；对手是否同意由流程层管。
     */
    fun undoLast(): Move? {
        if (phase != GamePhase.PLAYING) {
            return null
        }
        val move = history.removeLastOrNull() ?: return null
        rules.undo(board, move)
        return move
    }

    /**
     * 当前一步是否已超时。
     *
     * [timeoutSeconds] 为 0 表示不限时。开局后还没走过第一步时不计时，
     * 避免刚坐下就被判超时。
     */
    fun isTimedOut(now: Long, timeoutSeconds: Int): Boolean {
        if (timeoutSeconds <= 0 || phase != GamePhase.PLAYING || lastMoveAt == 0L) {
            return false
        }
        return now - lastMoveAt >= timeoutSeconds * 1000L
    }

    /**
     * 当前一步还剩多少秒，供动作栏读秒用。
     *
     * 返回 null 表示"这局现在没有读秒这回事"，与返回 0 严格区分：
     * null 是不限时、未开局、或开局后还没走第一步（此时 [isTimedOut] 也不计时），
     * 调用方据此**什么都不显示**；0 表示时间已经耗尽，该判负了。
     *
     * 秒数向上取整，因此 0 出现的时刻与 [isTimedOut] 转 true 的时刻严格一致 ——
     * 这条不变式是有意维护的：读秒显示 1 却已经被判负，或读到 0 还能继续走，
     * 两种都会让玩家认为计时是坏的。取整方向反了就会二者之一。
     * 同理，刚落子那一瞬（now == lastMoveAt）显示满值而不是少一秒。
     *
     * 纯函数，时间由调用方传，沿用 [isTimedOut] 的约定，可脱离服务端单测。
     */
    fun remainingSeconds(now: Long, timeoutSeconds: Int): Int? {
        if (timeoutSeconds <= 0 || phase != GamePhase.PLAYING || lastMoveAt == 0L) {
            return null
        }
        val remainingMillis = lastMoveAt + timeoutSeconds * 1000L - now
        if (remainingMillis <= 0L) {
            return 0
        }
        // 向上取整：还剩 1 毫秒也算"还有 1 秒可走"，与上面那条不变式对应
        val seconds = (remainingMillis + 999L) / 1000L
        // 服主把时限调小、或系统时间回跳时算出的值可能超过时限本身，夹一下免得显示比设置更大的数
        return seconds.coerceAtMost(timeoutSeconds.toLong()).toInt()
    }

    /**
     * 开局计时起点。
     *
     * 原先由流程层在"双方坐满"时调用，现在开局收进了 [start]，这里由它调。
     * 仍保留为公开方法：存档恢复与测试需要单独校准计时起点，那两处不该走开局流程。
     */
    fun markStarted(now: Long) {
        lastMoveAt = now
    }

    /** 换一个局面，仅用于从存档恢复 */
    fun restoreBoard(restored: Board) {
        board = restored
    }

    /** 恢复存档时重挂座位，不走 [join] 的开局逻辑 */
    fun restoreSeats(red: UUID?, black: UUID?, restoredPhase: GamePhase) {
        redPlayer = red
        blackPlayer = black
        phase = restoredPhase
    }
}

/** 对局阶段 */
enum class GamePhase {
    /** 等人入座 */
    WAITING,

    /** 正在下 */
    PLAYING,

    /** 已终局 */
    FINISHED,
}

/** 入座结果 */
enum class JoinResult {
    /** 坐下了，还差一个人 */
    WAITING,

    /**
     * 坐下了并且坐满了，但**还没开局**。
     *
     * 从 `STARTED` 改名而来：旧名字读起来像"已经开始下了"，而现在坐满只是
     * 一个可以按"开始"的状态，phase 仍是 WAITING。名字与事实不符的枚举比
     * 注释过时更危险 —— 调用方会照名字写分支，于是又在入座时摆上棋子。
     */
    SEAT_FULL,

    /** 这个座位有人了 */
    SEAT_TAKEN,

    /** 你已经在这局里 */
    ALREADY_JOINED,

    /** 这局已经结束 */
    GAME_FINISHED,
}

/** 开局结果 */
enum class StartResult {
    /** 开局了，从这一刻起棋盘上才有棋子 */
    STARTED,

    /** 座位没坐满，开不了 */
    SEAT_NOT_FULL,

    /** 已经在下了，重复点"开始"不该重摆棋子 */
    ALREADY_PLAYING,

    /** 这局已经结束 */
    GAME_FINISHED,
}
