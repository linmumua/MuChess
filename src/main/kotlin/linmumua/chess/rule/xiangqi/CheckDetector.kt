package linmumua.chess.rule.xiangqi

import linmumua.chess.rule.core.Board
import linmumua.chess.rule.core.PieceType
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.core.Square

/**
 * 将军检测。
 *
 * 用**反向探测**：从己方将所在点出发，按車、炮、馬、兵的走法反查有没有对应敌子命中，
 * 而不是生成对方全部着法再看有没有吃到将。后者要跑一遍完整走法生成，
 * 合法着法过滤本身又要对每个伪合法着法调一次将军检测，那就是平方级开销。
 *
 * 相/象 与 仕/士 永远不可能将军（象走田、士走斜，都到不了对方九宫），不必探测。
 */
object CheckDetector {

    /** 某方是否正被将军。局面里找不到该方的将时返回 false，由上层判定摆局面出错 */
    fun isInCheck(board: Board, side: Side): Boolean {
        val king = board.kingSquare(side) ?: return false
        return isAttacked(board, king, side)
    }

    /**
     * 指定交叉点是否被 [side] 的对手攻击。
     *
     * 参数是"被攻击方"而不是"进攻方"，因为兵的方向要按被攻击方推算。
     */
    fun isAttacked(board: Board, target: Square, side: Side): Boolean {
        val enemy = side.opposite
        return attackedByChariotOrKing(board, target, enemy) ||
            attackedByCannon(board, target, enemy) ||
            attackedByHorse(board, target, enemy) ||
            attackedByPawn(board, target, side)
    }

    /**
     * 車 与 飞将 一起查。
     *
     * 沿四个正交方向找到第一枚子：是敌車则被将军；
     * 是敌将则命中**飞将（白脸将）** —— 两将同线中间无子的局面本身非法，
     * 实现上把将当車看，能直接吃到对方将就算被将军。
     * 这样"走成对脸"自然被送将规则挡掉，不需要单独写一条对脸校验。
     */
    private fun attackedByChariotOrKing(board: Board, target: Square, enemy: Side): Boolean {
        for ((deltaFile, deltaRank) in XiangqiGeometry.ORTHOGONALS) {
            var cursor = target.offset(deltaFile, deltaRank)
            while (cursor != null) {
                val piece = board[cursor]
                if (piece != null) {
                    if (piece.side == enemy &&
                        (piece.type == PieceType.CHARIOT || piece.type == PieceType.KING)
                    ) {
                        return true
                    }
                    break
                }
                cursor = cursor.offset(deltaFile, deltaRank)
            }
        }
        return false
    }

    /**
     * 炮。
     *
     * 沿四个方向先找到第一枚子当炮架（不分敌我），再继续找炮架后的第一枚子，
     * 若是敌炮则被将军。跨 0 枚或 2 枚以上都不成立，这里的两段扫描正好只认"恰好一枚"。
     */
    private fun attackedByCannon(board: Board, target: Square, enemy: Side): Boolean {
        for ((deltaFile, deltaRank) in XiangqiGeometry.ORTHOGONALS) {
            var cursor = target.offset(deltaFile, deltaRank)
            var screenFound = false
            while (cursor != null) {
                val piece = board[cursor]
                if (piece != null) {
                    if (!screenFound) {
                        // 第一枚子只当炮架，本身不构成炮的攻击
                        screenFound = true
                    } else {
                        if (piece.side == enemy && piece.type == PieceType.CANNON) {
                            return true
                        }
                        break
                    }
                }
                cursor = cursor.offset(deltaFile, deltaRank)
            }
        }
        return false
    }

    /**
     * 馬。
     *
     * 反向探测的关键点：从被攻击点看，敌马在 target + (df, dr) 处，
     * 它要走的是 (-df, -dr)，马腿贴在**马自己**那一侧，
     * 所以马腿 = 马的位置 + 沿"走 2 格那个轴"的半步 = target + (df, dr) + (-df/2, -dr/2)。
     * 把它化简到 target 上，八组偏移算出来的马腿恰好落在 target 的四个斜向邻格，
     * 也就是**仕/士 的偏移方向组**。这就是"反查马腿要用士的偏移"这条规矩的由来。
     * 直接把马的正向偏移照抄一份取反是错的，那样算出的马腿贴在将这一侧。
     */
    private fun attackedByHorse(board: Board, target: Square, enemy: Side): Boolean {
        for ((deltaFile, deltaRank) in XiangqiGeometry.HORSE_STEPS) {
            val horseSquare = target.offset(deltaFile, deltaRank) ?: continue
            val piece = board[horseSquare] ?: continue
            if (piece.side != enemy || piece.type != PieceType.HORSE) {
                continue
            }
            // 马腿按马自己的走法算：从 horseSquare 走 (-deltaFile, -deltaRank)
            val leg = XiangqiGeometry.horseLeg(horseSquare, -deltaFile, -deltaRank) ?: continue
            if (board.isEmpty(leg)) {
                return true
            }
        }
        return false
    }

    /**
     * 兵/卒。
     *
     * [side] 是被攻击方。敌兵能吃到 target 的位置只有三处：
     * target 正后方（按敌兵前进方向算）、以及左右两侧 —— 侧向吃子要求敌兵已过河。
     */
    private fun attackedByPawn(board: Board, target: Square, side: Side): Boolean {
        val enemy = side.opposite
        val enemyForward = XiangqiGeometry.pawnForward(enemy)
        // 正面：敌兵在 target 的"后方"，往前一步正好踩到 target
        target.offset(0, -enemyForward)?.let { square ->
            val piece = board[square]
            if (piece != null && piece.side == enemy && piece.type == PieceType.PAWN) {
                return true
            }
        }
        // 侧面：只有过河的敌兵才能横吃
        for (deltaFile in intArrayOf(-1, 1)) {
            val square = target.offset(deltaFile, 0) ?: continue
            val piece = board[square] ?: continue
            if (piece.side == enemy && piece.type == PieceType.PAWN && !XiangqiGeometry.isHomeSide(square, enemy)) {
                return true
            }
        }
        return false
    }
}
