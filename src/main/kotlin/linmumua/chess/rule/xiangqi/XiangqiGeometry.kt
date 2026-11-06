package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square
import kotlin.math.abs

/**
 * 棋盘几何：方向偏移表与阻挡点算法。
 *
 * 只回答"某个方向的偏移是什么""马腿/象眼在哪一格""两点之间隔着几枚子"，
 * 不做任何合法性裁定 —— 裁定在 [RuleValidator]，反向探测在 [CheckDetector]。
 *
 * 偏移表集中在这里是为了让走法生成与反向探测用同一份数据：
 * 两处各写一份方向表，改了一处忘了另一处就会出现"能走但不算将军"这类错。
 */
internal object XiangqiGeometry {

    /** 四个正交方向：車、炮、帥/將 共用 */
    val ORTHOGONALS = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)

    /** 四个斜向单步：仕/士 走法 */
    val DIAGONALS = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)

    /** 相/象 的田字四个落点 */
    val ELEPHANT_STEPS = listOf(2 to 2, 2 to -2, -2 to 2, -2 to -2)

    /** 馬 的八个日字落点 */
    val HORSE_STEPS = listOf(
        1 to 2, -1 to 2, 1 to -2, -1 to -2,
        2 to 1, 2 to -1, -2 to 1, -2 to -1,
    )

    /** 兵/卒 的前进方向：先行方向 rank 递减，后行方向 rank 递增 */
    fun pawnForward(side: Side): Int = if (side == Side.FIRST) -1 else 1

    /**
     * 该点是否在指定一方的半场（未过河）。
     *
     * 河界在 rank 4 与 rank 5 之间：后行方半场 0..4，先行方半场 5..9。
     * 这一条与九宫都是象棋专有概念，所以放在象棋几何里而不是通用的 Square 上 ——
     * 五子棋与围棋没有河界也没有九宫。
     */
    fun isHomeSide(square: Square, side: Side): Boolean =
        if (side == Side.FIRST) square.rank >= 5 else square.rank <= 4

    /** 该点是否在指定一方的九宫内（3×3） */
    fun isInPalace(square: Square, side: Side): Boolean {
        if (square.file !in 3..5) {
            return false
        }
        return if (side == Side.FIRST) square.rank in 7..9 else square.rank in 0..2
    }

    /**
     * 蹩马腿的位置。
     *
     * 阻挡点是**起点沿"走 2 格那个轴"方向的第一格**，不是起终点中点。
     * 例如 (f, r) 走到 (f+1, r-2)，马腿在 (f, r-1)；走到 (f+2, r-1)，马腿在 (f+1, r)。
     * 正因为马腿贴着起点，马的走法是非对称的：A 能吃 B 不代表 B 能吃 A。
     */
    fun horseLeg(from: Square, deltaFile: Int, deltaRank: Int): Square? =
        if (abs(deltaRank) == 2) {
            from.offset(0, deltaRank / 2)
        } else {
            from.offset(deltaFile / 2, 0)
        }

    /** 塞象眼的位置：起终点的中点 */
    fun elephantEye(from: Square, deltaFile: Int, deltaRank: Int): Square? =
        from.offset(deltaFile / 2, deltaRank / 2)

    /**
     * 数一条直线上起终点之间（不含两端）的棋子数。
     *
     * 两点不共线时返回 -1，调用方应先判形状再取这个值。
     */
    fun countBetween(board: Board, from: Square, to: Square): Int {
        val stepFile = Integer.signum(to.file - from.file)
        val stepRank = Integer.signum(to.rank - from.rank)
        if (stepFile != 0 && stepRank != 0) {
            return -1
        }
        if (stepFile == 0 && stepRank == 0) {
            return -1
        }
        var count = 0
        var cursor = from.offset(stepFile, stepRank)
        while (cursor != null && cursor != to) {
            if (!board.isEmpty(cursor)) {
                count++
            }
            cursor = cursor.offset(stepFile, stepRank)
        }
        // cursor 为 null 说明走出棋盘也没碰到 to，两点不共线
        return if (cursor == null) -1 else count
    }
}
