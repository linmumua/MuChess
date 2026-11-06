package linmumua.chess.interact

import java.util.UUID
import linmumua.chess.rule.core.Move
import linmumua.chess.rule.core.Square

/**
 * 某个玩家当前的选中态。
 *
 * 记"选了哪枚子"以及"算出来的合法落点"。落点在选中时算一次就缓存，
 * 空点据此生成白圈，可吃子据此设置红色描边；合法着法生成要对每个伪合法着法
 * 试走一遍，因此不能在每次世界渲染时重复计算。
 *
 * 局面一变（任意一方走子）缓存就失效，由 [SelectionRegistry.clearBoard] 负责清。
 *
 * @param square 选中的棋子所在交叉点
 * @param moves 该子的全部合法着法
 */
data class SelectionState(
    val square: Square,
    val moves: List<Move>,
) {

    /** 选中起点的紧凑坐标，用于动画开始前保留原始选中来源 */
    val sourcePacked: Int = square.packed

    /** 合法落点数量，供选中态与调试检查使用 */
    val destinationCount: Int get() = moves.size

    /** 是否有落点可走 */
    val hasMoves: Boolean get() = moves.isNotEmpty()

    /** 空落点，在世界里生成透明背景白圈 */
    val emptyDestinations: List<Square> get() = moves.filter { !it.isCapture }.map { it.to }

    /** 可吃子的落点，直接给目标敌方棋子红色描边 */
    val captureDestinations: List<Square> get() = moves.filter { it.isCapture }.map { it.to }

    /** 某点是否就是本次浮起的起点棋子 */
    fun isSelected(target: Square): Boolean = sourcePacked == target.packed

    /** 某点是否是本次选中的合法落点 */
    fun canMoveTo(target: Square): Boolean = moves.any { it.to == target }

    /** 取走到某点的那一步着法，不合法返回 null */
    fun moveTo(target: Square): Move? = moves.firstOrNull { it.to == target }
}

/**
 * 全服的选中态登记处。
 *
 * 按"玩家 + 棋盘"两级存：一个玩家同时只可能在一副棋盘上有选中态，
 * 但换棋盘时旧的必须失效，否则会出现在 A 盘选中、去 B 盘落子的错乱。
 *
 * 不是线程安全的，只在主线程访问。交互与渲染都在主线程，没有并发需求，
 * 加锁反而是无谓开销。
 */
class SelectionRegistry {

    /** 玩家 → (棋盘 id, 选中态) */
    private val selections = HashMap<UUID, BoardSelection>()

    /** 某玩家在指定棋盘上的选中态，棋盘不匹配时视为没选中 */
    fun get(player: UUID, boardId: UUID): SelectionState? =
        selections[player]?.takeIf { it.boardId == boardId }?.state

    /** 某玩家的选中态，不关心哪个棋盘 */
    fun any(player: UUID): SelectionState? = selections[player]?.state

    /** 记下选中态 */
    fun select(player: UUID, boardId: UUID, state: SelectionState) {
        selections[player] = BoardSelection(boardId, state)
    }

    /** 清掉某玩家的选中态，返回被清掉的那个（用于把浮起的棋子放回去） */
    fun clear(player: UUID): SelectionState? = selections.remove(player)?.state

    /**
     * 清掉某副棋盘上所有玩家的选中态。
     *
     * 局面一变就必须调用：缓存的合法落点是按旧局面算的，
     * 留着会让玩家按过期的高亮落子。
     *
     * @return 被清掉的玩家与其选中态，供渲染层把浮起的棋子放回去
     */
    fun clearBoard(boardId: UUID): Map<UUID, SelectionState> {
        val affected = selections.filterValues { it.boardId == boardId }
        affected.keys.forEach { selections.remove(it) }
        return affected.mapValues { it.value.state }
    }

    /** 当前有选中态的玩家数，供状态观察与测试使用 */
    fun activeCount(): Int = selections.size

    /** 是否没有任何选中态 */
    fun isEmpty(): Boolean = selections.isEmpty()

    /** 遍历所有选中态，供状态同步或诊断使用 */
    fun forEach(action: (UUID, UUID, SelectionState) -> Unit) {
        // 复制一份再遍历，避免回调里清选中态导致并发修改
        selections.entries.toList().forEach { (player, selection) ->
            action(player, selection.boardId, selection.state)
        }
    }

    /** 全清，关服与重载时用 */
    fun clearAll() {
        selections.clear()
    }

    /** 玩家的选中态连带它所属的棋盘 */
    private data class BoardSelection(val boardId: UUID, val state: SelectionState)
}
