package linmumua.chess.game

import linmumua.chess.rule.core.BoardSpec
import linmumua.chess.rule.core.GameRules
import linmumua.chess.rule.core.Side
import linmumua.chess.rule.go.GoRules
import linmumua.chess.rule.gomoku.GomokuRules
import linmumua.chess.rule.xiangqi.XiangqiRules

/**
 * 棋种。
 *
 * 把"一个棋种"需要的东西绑在一起：CE 家具 id、规则实现、盘面尺寸、占位边长、棋子缩放。
 * 这些值必须成套出现 —— 只改家具 id 不改规则，或者改了尺寸忘了缩放，
 * 都会让棋盘看着对但棋子落错位置或互相压边。
 *
 * 新增棋种要在这里加一项，并同步在构建期 `PackSpec` 里加对应的家具与物品定义。
 *
 * [id] 同时是 debug 命令的类型参数（英文），也是 `boards.yml` 里存的棋种标记。
 */
enum class GameType(
    /** 命令参数与存档标记用的英文 id */
    val id: String,
    /** CE 家具与物品 id */
    val furnitureId: String,
    /** 盘面尺寸 */
    val spec: BoardSpec,
    /** 占位边长（方块数） */
    val footprintSize: Int,
) {

    /** 中国象棋：9×10 铺 2x2 */
    XIANGQI("xiangqi", "xiangqi_board", BoardSpec.XIANGQI, footprintSize = 2),

    /** 五子棋：15×15 铺 2x2 */
    GOMOKU("gomoku", "gomoku_board", BoardSpec.GOMOKU, footprintSize = 2),

    /**
     * 围棋：19×19 铺 3x3。
     *
     * 占位边长是奇数，家具绕方块**中心**旋转（CE `alignment: center`，由构建期
     * `PackSpec.BoardSpec.alignment` 按奇偶自动给出），与 2x2 那两副绕方块角不同。
     *
     * 3 也是这套薄板模型能支持的最大占位：模型顶点按 `8 ± 8×size` 算，size=3 得到
     * -16..32，正好压在 Minecraft JSON 模型坐标的合法边界上。
     */
    GO("go", "go_board", BoardSpec.GO, footprintSize = 3),
    ;

    /** 占位边长的浮点形式，几何换算用 */
    val span: Double get() = footprintSize.toDouble()

    /**
     * 阵营名在语言键里的后缀。
     *
     * [Side] 只按行棋先后命名（FIRST/SECOND），颜色留给各棋种自己的显示层映射，
     * 这里就是那层映射：象棋是红先黑后，五子棋与围棋是黑先白后。
     *
     * 这个映射必须按棋种分流。早先它写死在 `MsgTemplate` 里，恒为 FIRST→red，
     * 于是围棋的黑方在提示与棋盘悬浮字上一律显示成"红方" —— 围棋盘上没有红子。
     *
     * 注意棋子名的键不走这里：五子棋与围棋有自己的黑白子命名，见 `MsgTemplate.pieceKey`。
     */
    fun sideSlug(side: Side): String = when (this) {
        XIANGQI -> if (side == Side.FIRST) "red" else "black"
        GOMOKU, GO -> if (side == Side.FIRST) "stone-black" else "stone-white"
    }

    /**
     * 建一份该棋种的规则实例。
     *
     * 每次建新对局都取一份而不是共用单例：规则实例带可调项（象棋的限着回合数、
     * 五子棋的禁手开关），将来允许按棋盘配置时不必再改这里的结构。
     */
    fun createRules(): GameRules = when (this) {
        XIANGQI -> XiangqiRules(GameManager.xiangqiOptions)
        GOMOKU -> GomokuRules(GameManager.gomokuOptions)
        // 围棋规则实例持有每局独占的可变状态（撤销栈、劫快照、虚着计数），
        // 绝不能改成共享单例，详见 GoRules 类注释
        GO -> GoRules(GameManager.goOptions)
    }

    companion object {
        /** 按英文 id 查棋种，查不到返回 null（命令层据此给提示） */
        fun byId(id: String): GameType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

        /** 按 CE 家具 id 查棋种，查不到返回 null（说明点的不是本插件的棋盘） */
        fun byFurnitureId(furnitureId: String): GameType? =
            entries.firstOrNull { it.furnitureId == furnitureId }
    }
}
