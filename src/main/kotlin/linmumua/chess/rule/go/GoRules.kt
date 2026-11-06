package linmumua.chess.rule.go

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.GameRules
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Piece
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import linmumua.chess.rule.xiangqi.GameOutcome
import linmumua.chess.rule.xiangqi.MoveRejection

/**
 * 围棋规则门面。
 *
 * 19×19，先行方执黑（[Side.FIRST] → `black_stone`，与五子棋一致）。
 * 全类不含任何 Bukkit 类型，可脱离服务端跑单测。
 *
 * ## 为什么不走 [Board.apply] / [Board.undo]
 *
 * [Move.captured] 只能装**一枚**被吃子，而围棋一手可提一整块（最多 361 枚）。
 * 所以本类直接用 `board[square] = piece` 改盘，并自己维护撤销栈。
 * `board.sideToMove` 与 `board.halfMovesSinceCapture` 都是公开可写字段，够用；
 * `Board` 内部的 `clockHistory` 因为全程没被碰过而始终为空，不存在错配。
 *
 * ## 状态与限制（重要）
 *
 * 本类持有三份**每局独占**的可变状态：撤销栈、劫快照、连续虚着计数。
 * 因此**同一个实例不能同时服务多个 [Board]**。
 * `GameType.createRules()` 每局建一份新实例，人对人对局没问题。
 *
 * 机器人搜索跑在异步线程，棋盘是 `board.copy()` 快照，但规则实例原先是共用的 ——
 * 那会让搜索与主局互相踩状态，最糟的表现是搜索展开虚着分支后主线程读到
 * `consecutivePasses == 2`，在无人虚着的情况下直接判终局数子。
 *
 * 解法是 [forSearch]：搜索前取一份独立实例，继承劫快照与虚着计数、但不带撤销栈。
 * `MoveExecutor.moveAsBot` 已按此调用，所以围棋现在可以加机器人。
 *
 * ## 不持久化的两项状态
 *
 * 连续虚着计数与劫快照都只在内存里，`BoardRecord` 只存 `fen`/`phase`/双方。所以重启后：
 * - 「一方虚着 → 重启 → 另一方虚着」不会终局，计数归零
 * - 劫快照丢失，重启后「立刻提回」会变成合法着法
 *
 * 这是**显式接受**的取舍，不是待修的 bug：两者都只在「紧接着的下一手」这个窗口内有意义，
 * 跨重启的半个虚着、跨重启的劫争本身就是异常序列；而加存档字段要同时改 `BoardRecord`、
 * 编解码与老档兼容三处，代价大于收益。玩家的补救也很直接 —— 再各虚着一次即可终局。
 *
 * @param options 规则可调项，默认贴 3.75 子
 */
class GoRules(val options: GoRuleOptions = GoRuleOptions.DEFAULT) : GameRules {

    override val spec: BoardSpec get() = BoardSpec.GO

    /** 围棋是落子式，交互层用单击直接落子 */
    override val isPlacement: Boolean get() = true

    /**
     * 撤销栈，与上层的着法历史一一对应。
     *
     * 每次 [apply] 压一帧，[undo] 弹一帧。存的是"这一手之前长什么样"所需的最小信息。
     */
    private val undoStack = ArrayDeque<UndoFrame>()

    /**
     * 上一手之前的局面快照，用于单步劫判定。
     *
     * 存局面串而不是哈希：19×19 一次 361 字符的比较在落子这种低频操作上开销可忽略，
     * 而哈希要引库或自己实现 Zobrist，还得处理碰撞。
     *
     * null 表示还没有可比对的历史局面（开局或刚撤销到底）。
     */
    private var previousPosition: String? = null

    /** 连续虚着数，落子清零，达到 2 触发数子终局 */
    private var consecutivePasses = 0

    /** 最近一次 [apply] 提掉的点位，供 [lastCaptures] 取用 */
    private var lastCapturedSquares: List<Square> = emptyList()

    /**
     * 一帧撤销信息。
     *
     * @param captured 被提掉的子及其原位置，撤销时按原样放回
     * @param previousClock 这一手之前的 `halfMovesSinceCapture`
     * @param previousKoPosition 这一手之前的劫快照
     * @param previousPasses 这一手之前的连续虚着数
     */
    private data class UndoFrame(
        val captured: List<Pair<Square, Piece>>,
        val previousClock: Int,
        val previousKoPosition: String?,
        val previousPasses: Int,
    )

    /** 空盘开局，先行方（黑）先下 */
    override fun initialBoard(): Board = Board(BoardSpec.GO, Side.FIRST)

    /**
     * 某方全部合法着法 = 全部能下的空点。
     *
     * **不含虚着。** `legalMoves` 的既有语义是"盘上哪些点能下"，交互层用它算高亮；
     * 把一个没有坐标的虚着混进来会让白圈逻辑必须特判。虚着由 `/chess pass`
     * 这条独立入口经 [passMove] 产出。
     *
     * 阶段6 的搜索若要考虑虚着（终局收官阶段必须考虑，否则机器人会在该停手时
     * 继续往自己阵地里填子），得自己把 [passMove] 追加到候选列表。
     *
     * 19×19 空盘有 361 个着法，且每个都要试走判自杀，机器人不能直接对这个列表搜索。
     */
    override fun legalMoves(board: Board, side: Side): List<Move> =
        board.emptySquares()
            .filter { rejectionOf(board, it, it, side) == null }
            .map { placementAt(it, side) }

    /**
     * 落子式棋种没有"某枚子能走哪"这回事。
     *
     * 交互层靠 [isPlacement] 分流，不会走到这里；真被调到就返回空。
     */
    override fun legalMovesFrom(board: Board, square: Square): List<Move> = emptyList()

    /**
     * 判定一手落子，不校验轮次。
     *
     * 判定顺序是规则本身要求的，不能调换：
     * 1. 在盘内、是空点 —— 基础前提
     * 2. 试走：放上子，先提掉对方气尽的块
     * 3. 再看自己这块有没有气 —— **必须在提子之后判**，否则"紧气自救"会被误判成自杀
     * 4. 最后比劫快照
     */
    override fun rejectionOf(board: Board, from: Square, to: Square): MoveRejection? =
        rejectionOf(board, from, to, board.sideToMove)

    /**
     * 带行棋方的判定。
     *
     * 落子式没有"这不是你的子"的情况（下的是自己的新子），所以只判轮次再走规则本身。
     */
    override fun rejectionOf(board: Board, from: Square, to: Square, mover: Side): MoveRejection? {
        if (mover != board.sideToMove) {
            return MoveRejection.WRONG_TURN
        }
        if (!board.contains(to)) {
            return MoveRejection.OFF_BOARD
        }
        if (!board.isEmpty(to)) {
            return MoveRejection.SQUARE_OCCUPIED
        }
        return probe(board, to, mover)
    }

    /**
     * 在盘上试走一手，判自杀与劫，然后原样还原。
     *
     * 直接在真盘上试走再还原，而不是 `board.copy()`：19×19 拷贝 595 长的数组，
     * 而 [legalMoves] 要对 361 个空点各试一次，拷贝会成为主要开销。
     * 还原路径很短（放回被提子、清掉试走子），出错风险可控。
     */
    private fun probe(board: Board, target: Square, mover: Side): MoveRejection? {
        val stone = Piece(PieceType.STONE, mover)
        board[target] = stone

        // 先提对方：这一步的顺序决定了"紧气自救"是否成立
        val captured = GoBoardOps.capturesAfterPlacing(board, target, mover.opposite)
        val capturedPieces = captured.map { square -> square to board[square]!! }
        capturedPieces.forEach { (square, _) -> board[square] = null }

        // 提完之后自己这块还有没有气
        val suicide = GoBoardOps.groupAt(board, target).liberties.isEmpty()
        // 劫：这一手落完的局面与上一手之前完全相同，说明是立刻提回
        val ko = !suicide && previousPosition != null && positionOf(board) == previousPosition

        // 还原试走
        capturedPieces.forEach { (square, piece) -> board[square] = piece }
        board[target] = null

        return when {
            suicide -> MoveRejection.GO_SUICIDE
            ko -> MoveRejection.GO_KO
            else -> null
        }
    }

    /**
     * 按落点补出一手棋。
     *
     * 与象棋相反：象棋要求起点有子，围棋要求落点**没有**子。
     */
    override fun moveAt(board: Board, from: Square, to: Square): Move? {
        if (!board.contains(to) || !board.isEmpty(to)) {
            return null
        }
        return placementAt(to, board.sideToMove)
    }

    /**
     * 落一手子或虚着。
     *
     * 不做校验，请先用 [rejectionOf] 过一遍。落子会把连续虚着数清零 ——
     * 漏了这一步会让"虚着 → 落子 → 虚着"误判成双方连续虚着，对局在中盘莫名结束。
     */
    override fun apply(board: Board, move: Move) {
        val frame = UndoFrame(
            captured = emptyList(),
            previousClock = board.halfMovesSinceCapture,
            previousKoPosition = previousPosition,
            previousPasses = consecutivePasses,
        )

        if (move.pass) {
            consecutivePasses++
            lastCapturedSquares = emptyList()
            // 虚着不改变盘面，所以劫快照要清掉：虚着之后没有"立刻提回"可言
            previousPosition = null
            board.halfMovesSinceCapture++
            board.sideToMove = board.sideToMove.opposite
            undoStack.addLast(frame)
            return
        }

        // 劫快照记的是**这一手落子之前**的局面，下一手用它判"是否还原成了这个样子"
        val beforePosition = positionOf(board)

        board[move.to] = move.piece
        val captured = GoBoardOps.capturesAfterPlacing(board, move.to, move.piece.side.opposite)
        val capturedPieces = captured.map { square -> square to board[square]!! }
        capturedPieces.forEach { (square, _) -> board[square] = null }

        lastCapturedSquares = capturedPieces.map { it.first }
        consecutivePasses = 0
        previousPosition = beforePosition
        board.halfMovesSinceCapture = if (captured.isEmpty()) board.halfMovesSinceCapture + 1 else 0
        board.sideToMove = board.sideToMove.opposite
        undoStack.addLast(frame.copy(captured = capturedPieces))
    }

    /**
     * 撤销最近一手，必须与 [apply] 严格配对。
     *
     * 栈空时什么都不做：调用方（`ChessGame.undoLast`）自己也会先判历史是否为空，
     * 这里不抛异常是为了让"多撤一次"退化成无操作而不是崩掉整局。
     */
    override fun undo(board: Board, move: Move) {
        val frame = undoStack.removeLastOrNull() ?: return

        if (!move.pass) {
            // 先放回被提的子，再清掉落下的那颗：两者位置不会重合，顺序其实无关，
            // 但按"逆着 apply 来"写更容易核对
            frame.captured.forEach { (square, piece) -> board[square] = piece }
            board[move.to] = null
        }

        board.halfMovesSinceCapture = frame.previousClock
        previousPosition = frame.previousKoPosition
        consecutivePasses = frame.previousPasses
        lastCapturedSquares = emptyList()
        board.sideToMove = board.sideToMove.opposite
    }

    /** 围棋没有将军，恒为 false */
    override fun needsWarning(board: Board, side: Side): Boolean = false

    /**
     * 判定终局。
     *
     * 围棋只有一种规则层能算出的终局：**双方连续虚着**，然后数子。
     *
     * 注意这里**不能**像象棋那样用"无合法着法"判负 —— 围棋无处可下时还能虚着，
     * 而且盘面填满在围棋里是极罕见的收官结果，不代表任何一方输。
     *
     * [sideToMove] 参数没用到：双方连续虚着与轮到谁无关。保留是为了实现 [GameRules] 接口。
     */
    override fun outcome(board: Board, sideToMove: Side): GameOutcome {
        if (consecutivePasses < PASSES_TO_END) {
            return GameOutcome.Ongoing
        }
        val score = GoScoring.score(board, options.komi)
        return GameOutcome.ScoreWin(
            winner = score.winner,
            firstScore = score.firstScore,
            secondScore = score.secondScore,
        )
    }

    override fun lastCaptures(): List<Square> = lastCapturedSquares

    override fun toFen(board: Board): String = board.toFen()

    override fun fromFen(fen: String): Board = Board.fromFen(fen, BoardSpec.GO)

    /** 当前连续虚着数，供上层播报与测试核对 */
    val passCount: Int get() = consecutivePasses

    /**
     * 取一份供机器人搜索用的独立实例。
     *
     * 这是本类三份可变状态能与异步搜索共存的关键。不做隔离的话，搜索线程与主线程
     * 会共用同一个 [undoStack]、[previousPosition] 与 [consecutivePasses]，
     * 最糟的表现是搜索展开虚着分支后主线程读到 `consecutivePasses == 2`，
     * 在**无人虚着**的情况下直接判终局数子。
     *
     * 继承 [previousPosition] 而不是留空：劫快照决定"哪个点现在不能下"。
     * 不继承的话搜索会选出一手劫争点，回主线程被 [rejectionOf] 拒掉，
     * 机器人白走一轮（`applyBotMove` 只会记一条 warning，玩家看到的是机器人卡住）。
     *
     * 继承 [consecutivePasses] 是为了让搜索知道"对方刚虚着，我再虚着就终局了"。
     *
     * **不**继承 [undoStack]：那是配对用的临时栈，搜索有自己的一套 apply/undo 序列，
     * 带过去只会让它撤销到主局的历史里。新实例的栈为空正是想要的。
     */
    override fun forSearch(): GameRules {
        val copy = GoRules(options)
        copy.previousPosition = previousPosition
        copy.consecutivePasses = consecutivePasses
        return copy
    }

    /**
     * 造一手虚着。
     *
     * 虚着的 from/to 填哪里都无意义（见 [Move.pass] 的说明），统一填 (0,0)。
     */
    fun passMove(side: Side): Move = Move(
        from = Square(0, 0),
        to = Square(0, 0),
        piece = Piece(PieceType.STONE, side),
        captured = null,
        pass = true,
    )

    /**
     * 数出当前盘面的得分，供终局播报与测试用。
     *
     * 公开是因为它不改状态，且"现在数一下谁多"是个合理的只读问题。
     */
    fun scoreOf(board: Board): GoScore = GoScoring.score(board, options.komi)

    /**
     * 取局面串用于劫比对。
     *
     * 只取 [Board.toFen] 的第一段（子力分布），不含走棋方与计时器：
     * 劫判定问的是"棋子摆得是否一模一样"，把走棋方带进来会让永远比不相等
     * （一手之后走棋方必然已经换过），劫就永远判不出来。
     */
    private fun positionOf(board: Board): String = board.toFen().substringBefore(' ')

    companion object {
        /** 连续几手虚着算终局 */
        const val PASSES_TO_END = 2

        /**
         * 构造一手落子。
         *
         * 落子式棋种没有起点，用 from == to 表达；[Move.captured] 恒为 null ——
         * 围棋的提子装不进那个单枚字段，由 [GoRules.lastCaptures] 单独给出。
         */
        fun placementAt(square: Square, side: Side): Move =
            Move(square, square, Piece(PieceType.STONE, side), null)
    }
}

/**
 * 围棋规则可调项。
 *
 * [komi] 默认 3.75 子，即中国数子法的原生贴子值（等价于数目法的 7.5 目）。
 * 带 .75 尾数可以顺带消除平局。
 */
data class GoRuleOptions(
    /**
     * 贴给后行方（白）的子数。
     *
     * 单位是**子**而不是目 —— 本项目用中国数子法（见 [GoScoring]），
     * 两者不可混用：3.75 子 ≈ 7.5 目，把数目法的 6.5 填进来等于贴子几乎翻倍。
     */
    val komi: Double = DEFAULT_KOMI,
) {
    init {
        require(komi >= 0.0) { "贴子不能为负，实际 $komi" }
    }

    companion object {
        /** 中国规则标准贴子：3¾ 子 */
        const val DEFAULT_KOMI = 3.75

        val DEFAULT = GoRuleOptions()
    }
}
