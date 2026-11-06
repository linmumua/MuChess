package linmumua.chess.rule.core

import linmumua.chess.rule.xiangqi.GameOutcome
import linmumua.chess.rule.xiangqi.MoveRejection

/**
 * 棋种规则的统一入口。
 *
 * 上层（对局流程、交互、渲染）只认这个接口，不认具体棋种 ——
 * 早先 `ChessGame` 与 `GameManager` 的字段类型直接写成 `XiangqiRules`，
 * 加棋种就得改上层，所以先把这层抽出来。
 *
 * 实现类必须是纯逻辑，不含任何 Bukkit 类型，能脱离服务端跑单测。
 *
 * 状态管理约定：[Board] 是**可变**的，走子用 [apply]、撤销用 [undo]，两者严格配对。
 * 需要不影响原局面的推演时先 [Board.copy] 拿快照。
 */
interface GameRules {

    /** 盘面尺寸，渲染层与几何换算要按它铺格点 */
    val spec: BoardSpec

    /**
     * 这个棋种是不是"落子式"。
     *
     * true 表示棋子下到空点后不再移动（五子棋、围棋），交互层用单击直接落子；
     * false 表示要选中再移动（象棋），交互层走"选中 → 看落点 → 再点"那套。
     */
    val isPlacement: Boolean

    /** 摆一副开局 */
    fun initialBoard(): Board

    /** 某方全部合法着法 */
    fun legalMoves(board: Board, side: Side): List<Move>

    /** 某个交叉点上那枚子的合法落点，交互层选中后用它算高亮 */
    fun legalMovesFrom(board: Board, square: Square): List<Move>

    /**
     * 判定一步棋并给出否决原因，返回 null 表示合法。
     *
     * 只判规则本身，不校验轮次 —— 轮次是对局流程的状态。
     */
    fun rejectionOf(board: Board, from: Square, to: Square): MoveRejection?

    /** 带行棋方的判定，供"玩家真的落子"这条路径用，会校验归属与轮次 */
    fun rejectionOf(board: Board, from: Square, to: Square, mover: Side): MoveRejection?

    /** 按起终点补全着法，起点没子返回 null */
    fun moveAt(board: Board, from: Square, to: Square): Move?

    /** 走一步棋，不做校验；请先用 [rejectionOf] 过一遍 */
    fun apply(board: Board, move: Move)

    /** 撤销最近一步棋，必须与 [apply] 配对 */
    fun undo(board: Board, move: Move)

    /**
     * 最近一次 [apply] 提掉了哪些点。
     *
     * 只有围棋会返回非空：象棋一手最多吃一枚，那一枚已经记在 [Move.captured] 里，
     * 渲染层照终点覆盖即可；围棋一手可提一整块，[Move.captured] 装不下，
     * 而**世界里那些显示实体必须有人去删**（项目规则禁止轮询扫实体兜底），
     * 所以需要这条通路把点位交给渲染层。
     *
     * 默认实现返回空列表，象棋与五子棋不必覆写。
     *
     * 调用约定：必须在 [apply] 之后、下一次 [apply] 之前取，否则会被覆盖。
     * 上层实际是由 `ChessGame.move` 取走填进 `MoveAttempt.Success.captured`，
     * 交互层拿到的是那个不可变快照，不受后续着法影响。
     */
    fun lastCaptures(): List<Square> = emptyList()

    /**
     * 某方是否处于"要被提醒"的状态。
     *
     * 象棋是被将军。五子棋与围棋没有将军概念，恒为 false ——
     * 接口里没叫 isInCheck 就是为了不把象棋概念摊到另两个棋种上。
     */
    fun needsWarning(board: Board, side: Side): Boolean

    /** 判定终局 */
    fun outcome(board: Board, sideToMove: Side): GameOutcome

    /** 局面序列化，落盘用 */
    fun toFen(board: Board): String

    /** 局面反序列化，串不合法时抛 [IllegalArgumentException] */
    fun fromFen(fen: String): Board

    /**
     * 取一份供机器人搜索使用的规则实例。
     *
     * 机器人搜索跑在异步线程，传进去的棋盘是 `board.copy()` 快照，但**规则实例
     * 原先是直接共用的**。对无状态的规则（象棋、五子棋）没问题，对持有可变状态的
     * 规则（围棋的撤销栈、劫快照、虚着计数）会让两个线程互相踩：
     * 搜索展开虚着分支会自增虚着计数，主线程此刻判终局就可能在**无人虚着**的情况下
     * 直接数子结束对局。
     *
     * 默认实现返回 `this`，无状态规则零开销零改动。
     * 有状态的规则必须覆写，返回一份独立实例。
     *
     * 覆写时要注意：新实例应当**继承那些影响合法性判定的状态**（围棋的劫快照），
     * 否则搜索会选出一手在主线程被拒的着法，机器人白走一轮。
     */
    fun forSearch(): GameRules = this
}
