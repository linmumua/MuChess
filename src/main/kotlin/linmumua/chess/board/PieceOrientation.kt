package linmumua.chess.board

/**
 * 棋子显示模型选择。
 *
 * 红黑相对方向与四种棋盘方向全部固化在资源包 JSON 的 `display.fixed` 中；运行期不再计算
 * 字面角度，只按棋盘朝向选对应内部物品，并保留把模型平放到棋盘面的公共俯仰角。
 */
object PieceOrientation {

    /**
     * ItemDisplay 铺平角度。
     *
     * 自定义双面模型仍位于 XY 平面，绕 X 轴 +90° 后正反两个字面分别朝上、朝下。
     * 这一步只决定棋子平放，不参与红黑或棋盘方向选择。
     */
    const val FLAT_PITCH_DEGREES = 90f

    /** 只供棋盘 ItemDisplay 使用的 CE 物品 id；朝向名称与构建期模型目录保持一致。 */
    fun displayItemKey(assetKey: String, facing: BoardFacing): String =
        "$assetKey$DISPLAY_ITEM_SEGMENT${facing.name.lowercase()}"

    private const val DISPLAY_ITEM_SEGMENT = "_display_"
}
