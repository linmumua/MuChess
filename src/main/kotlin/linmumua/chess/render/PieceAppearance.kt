package linmumua.chess.render

import linmumua.chess.board.BoardFacing
import linmumua.chess.rule.core.Piece
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * 棋子外观来源。
 *
 * 这是渲染层对"棋子长什么样"的**唯一依赖**，故意做成接口：
 * CraftEngine 的物品 id 解析属于桥接层职责（`compat/`），渲染层不该直接调 CE API，
 * 否则 CE 缺失或换版本时渲染层要跟着改。
 *
 * 接线方式：由 `compat/` 的桥接实现本接口并在启用阶段注入 [PieceAppearanceRegistry]。
 * CE 的物品注册表只有在 `CraftEngineReloadEvent` 之后才可用，
 * 所以实现方的 [isAvailable] 在重载完成前应返回 false，渲染层会自动退到原版兜底。
 */
interface PieceAppearance {

    /**
     * 外观是否已就绪。
     *
     * 返回 false 时渲染层不会调 [itemFor]，直接用兜底外观。
     * CE 未安装、资源包还没生成、CE 尚未重载完成都应返回 false。
     */
    fun isAvailable(): Boolean

    /**
     * 取某枚棋子在指定棋盘朝向下的展示物品。
     *
     * 红黑方向与四种棋盘方向都由资源模型的 FIXED 变换决定，故实现方必须把 [facing]
     * 纳入物品选择；返回 null 时调用方会退到原版兜底，不能让缺一个模型拖垮整副棋。
     */
    fun itemFor(piece: Piece, facing: BoardFacing): ItemStack?
}

/**
 * 原版兜底外观。
 *
 * CraftEngine 缺失时棋子仍要能看见、能区分红黑与兵种，否则对局根本没法下。
 * 用染色陶瓦区分阵营（红方红色、黑方黑色），棋子种类靠物品显示名区分。
 *
 * 显示名这里**不走语言文件**是有意的：ItemDisplay 的物品名在游戏里几乎不可见
 * （显示实体不显示悬浮文字），它只是给服主用 F3+I 排查时看的调试标识，
 * 属于开发者可见信息而不是玩家可见文案。真正给玩家看的棋子名走 `Msg.pieceName`。
 */
object VanillaPieceAppearance : PieceAppearance {

    /** 即使是调试物品名也统一从 MiniMessage 生成 Adventure 组件 */
    private val miniMessage by lazy { MiniMessage.miniMessage() }

    /** 兜底外观永远可用，这是它存在的意义 */
    override fun isAvailable(): Boolean = true

    override fun itemFor(piece: Piece, facing: BoardFacing): ItemStack {
        val material = if (piece.side == linmumua.chess.rule.core.Side.FIRST) {
            Material.RED_GLAZED_TERRACOTTA
        } else {
            Material.BLACK_GLAZED_TERRACOTTA
        }
        return ItemStack(material).also { stack ->
            val meta = stack.itemMeta ?: return@also
            // assetKey 形如 red_horse，与 CE 资源 id 同源，便于排查对不上的情况。
            // Paper 26.x 已弃用 setItemName(String)，改用 Adventure 组件版 itemName(Component)；
            // 调试标识虽然不走语言文件，也统一用 MiniMessage 生成组件，避免项目里出现第二套文本链路。
            meta.itemName(miniMessage.deserialize("<dark_gray>${piece.assetKey}</dark_gray>"))
            stack.itemMeta = meta
        }
    }
}

/**
 * 外观实现的注册点。
 *
 * 渲染层只认这里拿到的实现。默认是原版兜底，桥接层就绪后调 [install] 换成 CE 版本。
 * 用一个可替换的引用而不是构造参数注入，是因为 CE 的可用时机晚于渲染层的创建时机
 * （CE 注册表要等 `CraftEngineReloadEvent`），构造期拿不到最终实现。
 */
object PieceAppearanceRegistry {

    /** 当前生效的外观来源 */
    @Volatile
    var current: PieceAppearance = VanillaPieceAppearance
        private set

    /** 装上外观实现，通常由桥接层在 CE 重载完成后调用 */
    fun install(appearance: PieceAppearance) {
        current = appearance
    }

    /** 退回原版兜底，CE 卸载或反射失败时调用 */
    fun reset() {
        current = VanillaPieceAppearance
    }

    /**
     * 取展示物品，自动处理不可用与缺资源两种降级。
     *
     * 这是渲染层实际调用的方法，保证永远拿得到一个能显示的物品。
     */
    fun resolve(piece: Piece, facing: BoardFacing): ItemStack {
        val appearance = current
        if (appearance.isAvailable()) {
            appearance.itemFor(piece, facing)?.let { return it }
        }
        return VanillaPieceAppearance.itemFor(piece, facing)
    }
}

/** 空合法落点白圈的外观来源，职责与 [PieceAppearance] 相同，但只解析一个内部物品。 */
interface MoveMarkerAppearance {

    /** 当前外观来源是否已经就绪 */
    fun isAvailable(): Boolean

    /** 取白圈标记使用的展示物品，资源缺失时返回 null */
    fun item(): ItemStack?
}

/** CraftEngine 缺失时的可见兜底，至少保证合法空点仍有圆形提示。 */
object VanillaMoveMarkerAppearance : MoveMarkerAppearance {

    override fun isAvailable(): Boolean = true

    override fun item(): ItemStack = ItemStack(Material.SNOWBALL)
}

/** 白圈外观注册点，CE 重载完成后由 compat 层安装真正实现。 */
object MoveMarkerAppearanceRegistry {

    @Volatile
    private var current: MoveMarkerAppearance = VanillaMoveMarkerAppearance

    /** 安装 CraftEngine 外观实现 */
    fun install(appearance: MoveMarkerAppearance) {
        current = appearance
    }

    /** CE 关闭或不可用时退回原版兜底 */
    fun reset() {
        current = VanillaMoveMarkerAppearance
    }

    /** 始终返回可显示的 marker ItemStack */
    fun resolve(): ItemStack {
        val appearance = current
        if (appearance.isAvailable()) {
            appearance.item()?.let { return it }
        }
        return VanillaMoveMarkerAppearance.item()
    }
}
