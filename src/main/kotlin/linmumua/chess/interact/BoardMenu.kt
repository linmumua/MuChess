package linmumua.chess.interact

import java.util.UUID
import linmumua.chess.command.ChessCommandHandler
import linmumua.chess.config.ChessConfig
import linmumua.chess.config.Msg
import linmumua.chess.game.BotPlayer
import linmumua.chess.game.ChessGame
import linmumua.chess.game.GameManager
import linmumua.chess.game.GamePhase
import linmumua.chess.game.GameRequestFlow
import linmumua.chess.game.GameSession
import linmumua.chess.game.GameType
import linmumua.chess.game.GoBgmController
import linmumua.chess.game.TurnTimeoutOptions
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import taboolib.common.platform.event.SubscribeEvent

/**
 * 棋盘的九格交互菜单。
 *
 * 这里只需要九格菜单与几颗按钮。直接使用 Paper Inventory API 可以避免为这一处 UI
 * 引入 TabooLib bukkit-ui 及其 NMS 依赖；标题、按钮名与 Lore 仍全部走 [Msg] 的
 * MiniMessage 解析链路。
 *
 * 按钮**按对局阶段整套换掉**，不是固定一套然后灰掉几个：
 * - 等人阶段：主位是"加入对局"或"开始对局"（见 [ACTION_SLOT]），次位是每手限时设置
 * - 对局中：主位是"求和"，次位是"悔棋"，右侧是"认输"（见 [RESIGN_SLOT]）
 *
 * 因此本菜单是**入座与开局的完整入口**：`board-label.enabled` 关掉之后世界里没有牌子可点，
 * 有人类在局中时这个菜单就是把一副满座棋盘推进到开局的唯一地方。
 * （纯机器人对局例外，那种局由 `ChessCommandHandler.botAdd` 自动开，不经过这里。）
 *
 * 右端那颗是围棋开局背景音乐的开关（见 [BGM_SLOT]），只在围棋且总闸开着时出现，
 * 点它不会关菜单。
 */
object BoardMenu {

    /** 玩家当前打开的棋盘菜单；保存精确会话，点击时不再按距离猜棋盘。 */
    private val openMenus = HashMap<UUID, OpenBoardMenu>()

    /**
     * 打开指定棋盘的菜单。
     *
     * 一颗按钮都造不出来时不开菜单：那种情况下菜单是个纯粹的空盒子，
     * 打开它只会让玩家以为自己点错了地方。这条判断同时覆盖了"主按钮语言键缺失"——
     * 从前那是单独写的一次提前 return，现在按钮不止一颗，"主按钮缺了就整个不开"
     * 已经不成立了（对局中缺了求和文案，认输与悔棋照样该能点）。
     */
    fun open(player: Player, session: GameSession) {
        // 标题按棋种取键：三个棋种的名字不一样，写死一套会让围棋盘的菜单顶着"象棋棋盘"。
        // 键先落进变量再传，不要把 "menu-board-title-${...}" 直接写在调用参数里：
        // tools/lang-check.py 的字面量正则抓不到带模板的键，那处会被算成"未识别调用"，
        // 脚本随即宣布整轮核对不可信。而缺键的表现正是菜单静默不开，最需要被核对到。
        // 下面各个 buildXxx 里的 loreKey 也是同一个写法。
        val titleKey = "menu-board-title-${session.gameType.id}"
        val title = Msg.component(player, titleKey) ?: return
        val buttons = buildButtons(player, session)
        if (buttons.isEmpty()) {
            return
        }

        val inventory = Bukkit.createInventory(null, MENU_SIZE, title)
        buttons.forEach { (slot, item) -> inventory.setItem(slot, item) }

        // 先登记再打开：若玩家原本也开着本菜单，旧菜单的 close 事件不能删掉新记录。
        openMenus[player.uniqueId] = OpenBoardMenu(session.id, inventory)
        player.openInventory(inventory)
    }

    /**
     * 按当前阶段造出这一屏该有的全部按钮。
     *
     * 造不出来的按钮**不占格**（而不是摆个没文案的空壳），所以返回的映射可能只有一两项，
     * 也可能是空的。缺一颗按钮不影响其余按钮可用，理由见 [buildBgmButton] 的注释。
     */
    private fun buildButtons(player: Player, session: GameSession): Map<Int, ItemStack> {
        val buttons = LinkedHashMap<Int, ItemStack>()
        buildActionButton(player, session)?.let { buttons[ACTION_SLOT] = it }
        buildOptionButton(player, session)?.let { buttons[OPTION_SLOT] = it }
        buildResignButton(player, session)?.let { buttons[RESIGN_SLOT] = it }
        buildBgmButton(player, session)?.let { buttons[BGM_SLOT] = it }
        return buttons
    }

    /**
     * 主位按钮：等人阶段是入座或开局，对局中是求和。
     *
     * 只在**打开菜单**时决定长什么样，不决定点下去干什么 —— 后者在 [onClick] 里重新读一次
     * 状态。两处都判一遍是有意的：图标可以过时（玩家看着的是打开那一刻的快照），
     * 行为不能过时。
     *
     * 返回 null 表示语言键缺失，那一格空着。
     */
    private fun buildActionButton(player: Player, session: GameSession): ItemStack? {
        val game = session.game
        if (game.phase == GamePhase.PLAYING) {
            // 人机局这一格空着：机器人不会答复求和。与悔棋被关掉时的处理一致，
            // 见 [buildOptionButton] —— 不摆一颗必被拒的按钮。
            // 人机局想结束对局就点认输（那一格照常有）。
            val side = game.sideOf(player.uniqueId)
            if (side != null && BotPlayer.isBotSeat(session.id, side.opposite)) {
                return null
            }
            // 求和摆在正中央：这一格的含义一直是"这副棋盘接下来最可能的一步"，
            // 对局中最常点的正是求和。认输离它两格，见 [RESIGN_SLOT]。
            // 纸是"递张条子"的意思，与"提议"这个动作对得上，也明显不像方块那样有分量
            return button(player, Material.PAPER, "menu-draw-name", "menu-draw-lore")
        }
        val startable = isStartable(game)
        val nameKey = if (startable) "menu-start-name" else "menu-join-name"
        // 入座 lore 要说清会被分到哪一方，而阵营叫法随棋种变（象棋红黑、围棋与五子棋黑白），
        // 所以这条键带棋种后缀；开局 lore 讲的是"点了就摆子"，三个棋种一样，不分流。
        val loreKey = if (startable) "menu-start-lore" else "menu-join-lore-${session.gameType.id}"
        // 材质区分两种状态，让人不必读字就知道按钮变了：
        // 入座沿用 LIME_DYE（染料是"报名"这类轻动作），开局用 LIME_CONCRETE ——
        // 同色保持这是同一个按钮位，实心方块比染料更重，对应"按下去棋子就铺满了"这一步。
        val material = if (startable) Material.LIME_CONCRETE else Material.LIME_DYE
        return button(player, material, nameKey, loreKey)
    }

    /**
     * 次位按钮：等人阶段是每手限时设置，对局中是悔棋。
     *
     * 悔棋在 `config.yml` 关掉时这一格**空着**，不是摆出来点了再拒 ——
     * 服主关掉这个特性就是不想让人悔棋，摆一颗必被拒的按钮只会让玩家反复试。
     * 每手限时只在开局前可改（开局后这一格已经换成悔棋了），
     * 因为改完要立刻生效的话，正在读的那一秒该按新值还是旧值算没有好答案。
     */
    private fun buildOptionButton(player: Player, session: GameSession): ItemStack? {
        if (session.game.phase == GamePhase.PLAYING) {
            if (!ChessConfig.settings.game.allowUndo) {
                return null
            }
            // 结构方块的名字里带"撤销"意味不明显，但它是原版里唯一一眼看着像"存档回档"的方块
            return button(player, Material.STRUCTURE_BLOCK, "menu-undo-name", "menu-undo-lore")
        }
        if (session.game.phase != GamePhase.WAITING) {
            return null
        }
        // 名字里要带上当前档位，否则玩家点一下看不出变化 —— 图标是同一个钟
        val value = timeoutValueText(player, session) ?: return null
        val name = Msg.component(player, "menu-timeout-name", "value" to value) ?: return null
        val lore = Msg.component(player, "menu-timeout-lore") ?: return null
        return itemOf(Material.CLOCK, name, lore)
    }

    /**
     * 认输按钮，只在对局中出现。
     *
     * 点它不直接判负：走 [ChessCommandHandler.resign] 的二次确认，
     * 与命令入口用的是同一份确认状态。菜单绕过确认会让"点错一格就判负"变成可能。
     */
    private fun buildResignButton(player: Player, session: GameSession): ItemStack? {
        if (session.game.phase != GamePhase.PLAYING) {
            return null
        }
        // 红色染料：与主位那颗绿色染料同族不同色，红对应"这是要认输了"
        return button(player, Material.RED_DYE, "menu-resign-name", "menu-resign-lore")
    }

    /**
     * 当前每手限时档位的显示文本。
     *
     * 三档各一条文案而不是拿数字硬拼：`null`（跟随全局）与 `0`（这一局不限时）在玩家眼里
     * 是两回事，而"跟随全局"还得把服主设的实际秒数一起说出来，否则玩家不知道跟随的是多少。
     *
     * 返回的是 MiniMessage 片段，交给上层模板再解析一次 —— 与 [Msg.sideName] 同一套做法。
     */
    private fun timeoutValueText(player: Player, session: GameSession): String? {
        val perGame = session.game.turnTimeoutSeconds
        val global = ChessConfig.settings.game.turnTimeoutSeconds
        return when {
            perGame == null && global <= 0 -> Msg.text(player, "menu-timeout-value-global-none")
            perGame == null -> Msg.text(player, "menu-timeout-value-global", "seconds" to global)
            perGame <= 0 -> Msg.text(player, "menu-timeout-value-none")
            else -> Msg.text(player, "menu-timeout-value-seconds", "seconds" to perGame)
        }
    }

    /**
     * 造出围棋背景音乐的开关按钮。
     *
     * 三种情况返回 null，调用方据此让这一格空着：
     * - 不是围棋 —— 只有围棋有开局音乐（一局半小时以上才配得上长曲子），象棋与五子棋没这档设置
     * - `config.yml` 的总闸关着 —— 总闸关着时这颗按钮点了也不会响，摆出来只会让人以为插件坏了。
     *   默认就是关的，所以服主不开启这个特性时菜单与从前一模一样
     * - 语言键缺失 —— 与主按钮不同，这里不放弃整个菜单，理由见 [open] 里的注释
     *
     * 按钮名表达**当前状态**（"音乐已开/已关"），Lore 说清点下去会发生什么。
     * 反过来写（名字写动作）会在图标与文字之间造成歧义：玩家看到"关闭音乐"分不清
     * 这是现在的状态还是按下去的效果。
     */
    private fun buildBgmButton(player: Player, session: GameSession): ItemStack? {
        if (session.gameType != GameType.GO || !ChessConfig.settings.go.openingBgm.enabled) {
            return null
        }
        val on = session.bgmEnabled
        val nameKey = if (on) "menu-bgm-on-name" else "menu-bgm-off-name"
        val loreKey = if (on) "menu-bgm-on-lore" else "menu-bgm-off-lore"
        // 唱片机 = 正在放，音符盒 = 没在放。两个都是"发声的木头方块"，形状相近，
        // 一眼能看出是同一颗按钮的两个状态；换成禁止牌之类会显得像"这功能被封了"
        return button(player, if (on) Material.JUKEBOX else Material.NOTE_BLOCK, nameKey, loreKey)
    }

    /**
     * 按语言键造一颗按钮，任一条键缺失就返回 null。
     *
     * 名字与 Lore 都必须有：只有名字的按钮看不出点下去会发生什么，
     * 而这几颗按钮里有认输和悔棋这种点错了要付代价的。
     */
    private fun button(player: Player, material: Material, nameKey: String, loreKey: String): ItemStack? {
        val name = Msg.component(player, nameKey) ?: return null
        val lore = Msg.component(player, loreKey) ?: return null
        return itemOf(material, name, lore)
    }

    /** 组装物品。用 `itemName` 而不是 `displayName`，后者会给名字套上斜体 */
    private fun itemOf(material: Material, name: Component, lore: Component): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.itemName(name)
        meta.lore(listOf(lore))
        item.itemMeta = meta
        return item
    }

    /**
     * 这副棋盘现在是不是"等着有人点开始"。
     *
     * 与 `BoardLabelListener` 对悬浮字的判断保持同一个条件：等人阶段且双方坐满。
     * 两个入口指向同一套流程，判断不一致会让牌子和菜单给出不同的按钮。
     *
     * 判断留在本文件而不是抽到 [ChessGame]：那是对局流程层的类型，
     * "该显示哪个按钮"是交互层自己的问题。
     */
    private fun isStartable(game: ChessGame): Boolean =
        game.phase == GamePhase.WAITING && game.isSeatFull()

    /**
     * 菜单所有点击都锁住；四个格位各自分派。
     *
     * 分派**不看菜单打开时算好的状态**，而是重新取一次会话再判一次阶段：菜单是快照，
     * 打开到点下去之间对手可能刚坐满（图标还停在"加入对局"，实际已经该开局了），
     * 也可能刚开局（次位图标还是那口钟，实际已经该是悔棋了）。按快照分派会把点击送进
     * 错误的入口 —— 最坏的一种是等人阶段的图标配上对局中的行为，那等于点一下限时设置就认输。
     *
     * 各入口自己还会再校验一遍（座位满没满、是不是本局玩家、轮次、悔棋开关），
     * 所以竞态最坏结果是收到一条准确的拒绝文案，不会走错状态。
     */
    @SubscribeEvent
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val menu = openMenus[player.uniqueId] ?: return
        if (event.view.topInventory !== menu.inventory) {
            return
        }
        event.isCancelled = true
        val slot = event.rawSlot
        if (slot != ACTION_SLOT && slot != OPTION_SLOT && slot != RESIGN_SLOT && slot != BGM_SLOT) {
            return
        }
        val session = GameManager.session(menu.sessionId)
        if (session == null) {
            // 棋盘在菜单开着的时候被收掉了。这时菜单已经没有对应对象，关掉它
            openMenus.remove(player.uniqueId)
            player.closeInventory()
            Msg.send(player, "board-not-found")
            return
        }
        val playing = session.game.phase == GamePhase.PLAYING

        // 就地生效、不关菜单的两颗：音乐开关与限时设置。它们都不把对局推进一步，
        // 而且都要连点几下才调到想要的状态（开了又关试听、限时挨个档位看一遍），
        // 每点一次弹回世界会让人反复右键棋盘
        if (slot == BGM_SLOT) {
            toggleBgm(player, menu, session)
            return
        }
        if (slot == OPTION_SLOT && !playing) {
            cycleTimeout(player, menu, session)
            return
        }

        // 其余都会推进对局或弹出确认提示，先关菜单再执行：提示发在聊天栏与动作栏，
        // 菜单还开着的话玩家看不见刚发生了什么
        openMenus.remove(player.uniqueId)
        player.closeInventory()
        when {
            slot == RESIGN_SLOT -> {
                if (playing) {
                    // 走命令层的二次确认，不在这里直接判负
                    ChessCommandHandler.resign(player)
                }
            }
            slot == OPTION_SLOT -> GameRequestFlow.requestUndo(player)
            playing -> GameRequestFlow.requestDraw(player)
            isStartable(session.game) -> ChessCommandHandler.start(player, session)
            else -> ChessCommandHandler.join(player, session)
        }
    }

    /**
     * 翻转这一局的背景音乐开关并就地换掉图标。
     *
     * 关掉要**立刻**发停止包，不能等这一遍放完：玩家点的是"我现在不想听"，
     * 让他再等半分钟等于开关没用。开启不用在这里播，巡检下一秒会起播
     * （见 [linmumua.chess.game.GoBgmDecision.decide] 的 `!playing -> PLAY`），
     * 在这里也播一次反而会与巡检各起一遍，叠音。
     *
     * 开关随棋盘落盘，所以要标脏；下一次定时落盘写出去。
     */
    private fun toggleBgm(player: Player, menu: OpenBoardMenu, session: GameSession) {
        session.bgmEnabled = !session.bgmEnabled
        GameManager.markDirty()
        if (!session.bgmEnabled) {
            GoBgmController.stop(session)
        }
        // 图标换不了就让它停在旧状态：开关本身已经生效，没有文案的空按钮比过时图标更糟
        buildBgmButton(player, session)?.let { menu.inventory.setItem(BGM_SLOT, it) }
    }

    /**
     * 把这一局的每手限时调到下一档并就地换掉图标。
     *
     * 只改这一局的值（[ChessGame.turnTimeoutSeconds]），不动 `config.yml` 的全局值 ——
     * 一个玩家在自己这副棋盘上调快棋，不该让全服的棋盘跟着变。
     *
     * 换不出新图标时**不改值就退出**：玩家看不到变化却已经改了限时，
     * 是这颗按钮最坏的一种结果（他以为没改，实际下一手就开始读秒）。
     */
    private fun cycleTimeout(player: Player, menu: OpenBoardMenu, session: GameSession) {
        val current = session.game.turnTimeoutSeconds
        val next = TurnTimeoutOptions.next(current)
        session.game.turnTimeoutSeconds = next
        val button = buildOptionButton(player, session)
        if (button == null) {
            session.game.turnTimeoutSeconds = current
            return
        }
        menu.inventory.setItem(OPTION_SLOT, button)
        GameManager.markDirty()
    }

    /** 禁止把物品拖进菜单顶栏。 */
    @SubscribeEvent
    fun onDrag(event: InventoryDragEvent) {
        val menu = openMenus[event.whoClicked.uniqueId] ?: return
        if (event.view.topInventory !== menu.inventory) {
            return
        }
        if (event.rawSlots.any { it < menu.inventory.size }) {
            event.isCancelled = true
        }
    }

    /** 只清理与本次关闭库存相同的记录，避免快速重开时误删新菜单。 */
    @SubscribeEvent
    fun onClose(event: InventoryCloseEvent) {
        val uuid = event.player.uniqueId
        val menu = openMenus[uuid] ?: return
        if (event.inventory === menu.inventory) {
            openMenus.remove(uuid)
        }
    }

    private data class OpenBoardMenu(
        val sessionId: UUID,
        val inventory: Inventory,
    )

    private const val MENU_SIZE = 9

    /**
     * 主位按钮的格位，原来叫 JOIN_SLOT。
     *
     * 入座、开局、求和共用**一个**格位而不是各占一格：这三件事在时间上是先后的，
     * 同一时刻只有一个成立。分开摆的话，任何时候都有点了必被拒的按钮
     * （没坐满时点"开始"只会收到 start-not-full），玩家得靠试错才知道该点哪个。
     * 换按钮的做法与棋盘上方悬浮字一致 —— 那行字本来就是在"加入/开始"之间翻文案的。
     *
     * 中央这一格的含义始终是"这副棋盘接下来最可能的一步"：等人时是入座与开局，
     * 对局中是求和。
     */
    private const val ACTION_SLOT = 4

    /**
     * 次位按钮的格位：等人阶段的每手限时设置，对局中的悔棋。
     *
     * 摆在主位左边两格，与右边的认输对称。两颗都是"改变对局条件"的操作，
     * 但不像认输那样一按就可能结束这一局，所以放在离认输最远的那一侧。
     */
    private const val OPTION_SLOT = 2

    /**
     * 认输按钮的格位。
     *
     * 离中央两格，而且**只有这一格会出现认输** —— 其他阶段这里一直空着。
     * 认输是九格里唯一一按就可能判负的操作，不与任何别的按钮共用格位：
     * 共用会让玩家的肌肉记忆在阶段切换时失效，手滑点下去的代价是输一局。
     * 隔开一格还能挡住"想点求和结果点到认输"这类紧邻误触。
     * 即便点中了也还有 [ChessCommandHandler.resign] 的二次确认兜底。
     */
    private const val RESIGN_SLOT = 6

    /**
     * 围棋背景音乐开关所在的格位。
     *
     * 摆在最右端而不是紧挨主按钮：入座与开局是"这副棋盘接下来干什么"，音乐开关是
     * 一项与对局无关的表现设定，隔开摆能减少手滑点错。只有围棋且总闸开着时这一格才有东西，
     * 见 [buildBgmButton]。
     */
    private const val BGM_SLOT = 8
}
