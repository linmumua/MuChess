package linmumua.chess.render

import linmumua.chess.board.BoardFacing
import org.bukkit.Location

/**
 * 棋盘本体的外观。
 *
 * 棋盘按项目定位是 CraftEngine 家具，但摆放与移除家具属于桥接层职责，
 * 渲染层只声明"我需要在这个位置放一块棋盘、结束时收掉"。
 *
 * 接线方式：`compat/` 的桥接实现本接口，在启用阶段调 [BoardVisualRegistry.install]。
 * CE 缺失时用 [NoopBoardVisual]，棋盘没有外观但棋子照常渲染、规则照常能下，
 * 符合"CE 缺失必须降级而不是挂掉"的约定。
 */
interface BoardVisual {

    /** 外观是否可用，CE 未就绪时返回 false */
    fun isAvailable(): Boolean

    /**
     * 在指定位置放一块棋盘外观。
     *
     * @param origin 占位区域的最小角，与 `BoardAnchor.origin` 同一含义
     * @param facing 棋盘朝向
     * @param boardId 要放哪种棋盘的外观标记（各棋种一个）。渲染层不解释它的含义，
     *   由实现方映射到自己的资源 id；调用方传 `GameType.furnitureId`。
     * @return 外观句柄，用于之后移除；放置失败返回 null
     */
    fun place(origin: Location, facing: BoardFacing, boardId: String): BoardVisualHandle?

    /** 收掉之前放下的棋盘外观 */
    fun remove(handle: BoardVisualHandle)

    /**
     * 把已放下的棋盘外观挪到新位置。
     *
     * 玩家手动放置家具时，CE 的落点可能落在草层上方；棋盘要落回实体方块顶面，
     * 家具实体就得跟着挪一格，否则棋盘与棋子错开。
     *
     * @return 是否挪成功；实现不支持或找不到实体时返回 false，调用方记日志降级
     */
    fun moveTo(handle: BoardVisualHandle, target: Location): Boolean

    /**
     * 这个句柄对应的棋盘外观是否还在世界里。
     *
     * 用来识别"会话还在、外观已经没了"的错位：外观被清场命令删掉之后，会话仍持有句柄，
     * 于是棋子与悬浮字照常渲染在一片空地上，而且没有外观可拆就再也触发不了回收事件。
     *
     * **调用约束：只能在外观所在区块已加载时调。** 区块未加载时实现方查不到实体，
     * 会把活着的外观误判为已死，调用方补建就会放出第二副棋盘。
     *
     * 拿不准时必须返回 true（保守），让调用方这次不补建 ——
     * 误判已死会多一副棋盘，误判活着只是少补一次，代价不对等。
     */
    fun isAlive(handle: BoardVisualHandle): Boolean
}

/**
 * 棋盘外观的句柄。
 *
 * 只存一个字符串 id，具体含义由实现方决定（CE 家具的 uuid 等）。
 * 渲染层不解释它的内容，只负责原样传回给实现方。
 * 落盘时会把 [id] 写进 boards.yml，重启后交还给实现方去认领。
 */
data class BoardVisualHandle(val id: String)

/**
 * 无外观实现。
 *
 * CraftEngine 缺失时使用。什么都不做，但不报错 —— 棋盘的位置信息由
 * `BoardAnchor` 管着，没有家具只是看不见棋盘格，棋子仍然落在正确的交叉点上。
 */
object NoopBoardVisual : BoardVisual {
    override fun isAvailable(): Boolean = false
    override fun place(origin: Location, facing: BoardFacing, boardId: String): BoardVisualHandle? = null
    override fun remove(handle: BoardVisualHandle) = Unit

    /** 没有外观实体可挪，返回 true 表示"无需处理"，避免调用方误报失败 */
    override fun moveTo(handle: BoardVisualHandle, target: Location): Boolean = true

    /**
     * 降级模式下本来就没有外观实体，返回 true 表示"无需补建"。
     *
     * 返回 false 会让调用方反复尝试补建一个永远建不出来的东西，每次区块加载刷一条日志。
     */
    override fun isAlive(handle: BoardVisualHandle): Boolean = true
}

/**
 * 棋盘外观实现的注册点。
 *
 * 与 [PieceAppearanceRegistry] 同理，用可替换引用而不是构造注入，
 * 因为 CE 就绪时机晚于渲染层创建时机。
 */
object BoardVisualRegistry {

    /** 当前生效的棋盘外观实现 */
    @Volatile
    var current: BoardVisual = NoopBoardVisual
        private set

    /** 装上实现，由桥接层在 CE 就绪后调用 */
    fun install(visual: BoardVisual) {
        current = visual
    }

    /** 退回无外观，CE 卸载或反射失败时调用 */
    fun reset() {
        current = NoopBoardVisual
    }
}
