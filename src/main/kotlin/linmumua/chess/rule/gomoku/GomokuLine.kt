package linmumua.chess.rule.gomoku

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square

/**
 * 五子棋的连子计数。
 *
 * 纯函数，不碰 Bukkit 也不碰对局状态 —— 连五判定错了不会抛异常，
 * 只会让该赢的一方没赢，或者让四连就被判胜，必须靠单测盯住。
 */
internal object GomokuLine {

    /**
     * 四条判定方向。
     *
     * 只取四条而不是八条：每条方向上都会同时往正反两边数，
     * 再列另外四条等于把每条线数两遍。
     */
    val DIRECTIONS = listOf(
        1 to 0,   // 横
        0 to 1,   // 竖
        1 to 1,   // 撇
        1 to -1,  // 捺
    )

    /**
     * 从 [origin] 出发，沿 [deltaFile]/[deltaRank] 正反两个方向数同色连续子数。
     *
     * 返回值含 [origin] 自己，所以最小是 1。
     */
    fun countThrough(
        board: Board,
        origin: Square,
        side: Side,
        deltaFile: Int,
        deltaRank: Int,
    ): Int {
        var total = 1
        total += runLength(board, origin, side, deltaFile, deltaRank)
        total += runLength(board, origin, side, -deltaFile, -deltaRank)
        return total
    }

    /**
     * 单向数同色连续子数，不含 [origin]。
     *
     * 走到盘外或遇到空点、异色子就停 —— [Board.get] 对盘外返回 null，
     * 与"遇到空点"同样收尾，所以这里不必另判边界。
     */
    fun runLength(
        board: Board,
        origin: Square,
        side: Side,
        deltaFile: Int,
        deltaRank: Int,
    ): Int {
        var count = 0
        var cursor = origin.offset(deltaFile, deltaRank)
        while (cursor != null && board[cursor]?.side == side) {
            count++
            cursor = cursor.offset(deltaFile, deltaRank)
        }
        return count
    }

    /**
     * [origin] 这一手是否成五。
     *
     * 用 `>=` 而不是 `==`：长连（六子及以上）在无禁手规则下同样算胜，
     * 写成 `== 5` 会让六连的一方赢不了。禁手开关另行处理。
     */
    fun isWin(board: Board, origin: Square, side: Side, needed: Int = WIN_LENGTH): Boolean =
        DIRECTIONS.any { (deltaFile, deltaRank) ->
            countThrough(board, origin, side, deltaFile, deltaRank) >= needed
        }

    /** 各方向的连子数，禁手判定要看它 */
    fun countsOf(board: Board, origin: Square, side: Side): List<Int> =
        DIRECTIONS.map { (deltaFile, deltaRank) ->
            countThrough(board, origin, side, deltaFile, deltaRank)
        }

    /** 连五取胜所需的子数 */
    const val WIN_LENGTH = 5
}
