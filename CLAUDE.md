# MuChess - 项目提示词

## 项目定位

MuChess 是一个 Paper 服务端的**棋类对局插件**。

本项目的唯一核心领域是**棋类对局**：棋盘、棋子、走法规则、对局流程、棋局表现。首期只做**中国象棋**，五子棋作为第二个棋种接入，两者共用同一套对局框架。

所有后续开发、重构、命名、配置、文档都必须围绕"棋类对局"展开。禁止引入与棋类无关的业务概念：不做经济系统、不做任务系统、不做称号、不做签到、不做与棋局无关的排行榜扩展。

棋盘以 CraftEngine 家具的形式实体化在世界里，占位 2x2 方块，棋子以显示实体呈现。插件启动时 hook CraftEngine 并自动生成自己的资源包，服主不需要手工拼资源包。

## 作者与归属

- 项目作者只有 `linmumua`。
- `plugin.yml`、`build.gradle.kts`、发布说明、资源包 `pack.yml` 的 author 字段只能出现 `linmumua`。
- 禁止出现其他作者名、示例作者名、占位作者名、AI 署名。
- 包名根为 `linmumua.chess`。

## 技术框架约束

本项目以 **TabooLib 6.3.0** 为核心框架。

- 插件入口是 TabooLib 的 `taboolib.platform.BukkitPlugin`，业务侧用 `@Awake(LifeCycle.*)` 挂生命周期，不写自己的 `JavaPlugin`。
- 优先使用 TabooLib 能力：`@Config` 配置、`@CommandHeader` 命令、`@SubscribeEvent` 事件、`submit/submitAsync` 调度、`openMenu` 菜单、`sendLang` 语言文件。
- 可以直接用 Bukkit / Paper API（`ItemDisplay`、`Interaction`、`Transformation`、粒子），但不能绕开 TabooLib 主架构另起一套生命周期。
- **TabooLib 与 Kotlin 标准库必须走运行期联网下载，禁止打包进 JAR。** 构建产物只允许包含 `taboolib.common` 引导层（约 30 个类）。判定标准：JAR 内 `kotlin/` 条目数为 0，`taboolib/` 条目数不超过 40。
- 第三方运行期库（SnakeYAML 等）用 TabooLib 的 `@RuntimeDependency` 声明下载，不用 shadow 打包。
- CraftEngine 为 `softdepend`，`compileOnly` 依赖。CraftEngine 缺失时插件必须能正常启动并降级，不能抛异常挂掉。

## 核心目标

1. **象棋规则引擎**：完整、可单测、不依赖 Bukkit 的纯逻辑层。走法生成、蹩马腿、塞象眼、飞将、送将、将死、困毙全部正确。
2. **CraftEngine 资源包自动生成**：插件自己产出棋盘与棋子的 CE 配置与贴图，写入 `plugins/CraftEngine/resources/muchess/`，并触发 CE 重载。
3. **世界内实体棋盘**：命令放置 2x2 棋盘家具，32 枚棋子以显示实体落在正确交叉点上。
4. **交互闭环**：右键选中棋子浮起放大 → 粒子标出所有合法落点 → 再次右键落点完成走子或吃子。
5. **对局流程**：开局、轮次、将军提示、终局判定、认输、悔棋（可配置）、观战。
6. **五子棋接入**：复用棋盘家具、交互层与对局框架，只替换规则实现。

## 版本与发布约定

- 版本号写在 `build.gradle.kts` 的 `version`，格式 `主.次.修`。
- 支持的 Paper 目标写在 `build.gradle.kts` 的 `supportedTargets`，一个目标绑定 paper-api 坐标、`api-version`、JDK 版本三样。新增目标必须三样同时给，不允许只加坐标。
- 切换目标用 `-PchessTarget=<id>`，产物分目录落在 `build/<目标 id>/`。对外发布要把受支持的每个目标都打一遍。
- 任何一次对外构建都必须先改版本号，禁止同版本号覆盖发布。
- 规则判定、配置结构、CE 资源 id 发生不兼容变化时必须升次版本号，并在发布说明写迁移方式。
- 资源包 `pack.yml` 的 `version` 跟随插件版本。

## 沟通与编码规则

- 沟通、注释、文档、提交说明使用简体中文。
- 类名、方法名、字段名、包名、配置键、CE 资源 id 使用英文。
- 玩家可见文本一律走语言文件，代码里只出现消息键。
- 控制台日志、调试日志可以直接写中文。
- 禁止用 Unicode 转义写中文或颜色符号。需要传统颜色码时直接写 `§`；富文本走 MiniMessage 标签。
- 禁止删除已有注释。注释过时就改写，不要整段删掉。
- 引用外部资源（贴图来源、音效 id、CE 模板 id、第三方 API 方法）必须在注释里写清来源与用途。
- 文案不要写成宣传语或 AI 腔。提示语只说清"发生了什么、下一步能做什么"，一句话讲完，不堆感叹号，不用"太棒了""恭喜您"这类客套。

## 玩家消息与文本规范

所有玩家可见文本放在 `lang/zh_CN.yml`，代码只允许通过键调用。

```yaml
prefix: "<gray>[</gray><gold>棋</gold><gray>]</gray> "
board-placed: "<white>棋盘已放到脚下，用 </white><aqua>/chess join</aqua><white> 入座</white>"
board-occupied: "<#fbbf24>这里放不下棋盘</#fbbf24> <gray>2x2 范围内有别的方块</gray>"
piece-selected: "<white>已选中 </white><gold><piece></gold><white>，可落点 </white><gold><count></gold><white> 处</white>"
move-illegal-blocked: "<#fda4af>这步走不了</#fda4af> <gray>马腿被别住了</gray>"
move-illegal-self-check: "<#fda4af>这步走不了</#fda4af> <gray>走完自己的将会被吃</gray>"
check-warning: "<#fbbf24><bold>将军</bold></#fbbf24> <gray><side>的将被叫了</gray>"
game-over-checkmate: "<gold><bold>将死</bold></gold> <white><winner> 胜</white>"
not-your-turn: "<gray>现在是 </gray><gold><side></gold><gray> 走棋</gray>"
```

MiniMessage 配色遵守固定角色分工：命令与关键名词用 `aqua`/`gold`，说明文字用 `gray`，警告用 `#fbbf24`，失败用 `#fda4af`，分隔符用 `dark_gray`。渐变只用于终局播报和菜单标题，普通提示不用渐变。

新增任何一条玩家可见文本，必须同步补进 `lang/zh_CN.yml`，禁止只改代码。

## 配置设计原则

**本项目所有人类可编辑文件只能是 YAML。** 禁止 JSON / TOML / properties / HOCON 作为配置入口。唯一例外是 CE 资源包内部必须的原版模型 JSON，那是资源包格式要求，由代码生成，不是给人手改的。

运行期 YAML 读写**只能用 `org.yaml:snakeyaml`**。禁止新增基于 Bukkit `YamlConfiguration` 的逻辑，禁止 Jackson YAML，禁止手拼 YAML 字符串。TabooLib 的 `@Config` 可以用于简单开关，涉及棋种定义、棋盘布局、资源映射这类结构化读取时走 SnakeYAML loader。

文件职责划分：

| 文件 | 职责 |
|---|---|
| `config.yml` | 全局开关、性能参数、粒子与动画参数、CE 资源包生成开关 |
| `lang/zh_CN.yml` | 玩家可见文本，唯一文案来源 |
| `games/xiangqi.yml` | 象棋棋种定义：初始局面、棋子到 CE id 的映射、坐标标定 |
| `games/gomoku.yml` | 五子棋棋种定义 |
| `boards.yml` | 已放置棋盘的运行时数据（位置、朝向、绑定对局） |

SnakeYAML 硬性要求：

- 读取任何服主可改的 YAML，必须显式构造 `LoaderOptions`，设置 `setAllowDuplicateKeys(false)`、`setMaxAliasesForCollections`、`setCodePointLimit`、`setNestingDepthLimit`。
- 用安全构造方式，禁止无约束反序列化任意对象图。
- 写出用块风格、UTF-8，键顺序用 `LinkedHashMap` 保序。
- 不承诺保留服主注释。默认值与注释靠模板资源文件释放，不靠 round-trip。
- YAML 读写收敛到专门的 loader / registry，禁止散落各处。

新增功能涉及配置时，必须同步更新默认配置模板与注释。禁止功能已实现但默认配置缺失。

## 领域模型约束

规则层是纯逻辑，不允许出现任何 Bukkit 类型。这是硬边界，保证规则可单测。

- `Board`：棋盘状态快照，只管哪个格子上有什么子。不知道世界坐标，不知道玩家。
- `Piece` / `PieceType` / `Side`：棋子定义。
- `Move`：一步棋，起点、终点、被吃子。
- `MoveGenerator`：伪合法走法生成，按棋子类型分发。
- `RuleValidator`：合法性裁定，负责蹩马腿、塞象眼、飞将、送将过滤。
- `CheckDetector`：将军检测，用反向探测法，不生成对方全部走法。
- `GameResult`：将死、困毙、限着、认输的终局判定。
- `ChessGame`：一局棋的运行时状态，轮次、历史、双方玩家。
- `GameSession`：把 `ChessGame` 和世界内棋盘、观战者绑起来。
- `BoardAnchor`：世界坐标标定，负责"交叉点 ↔ Location"双向换算。规则层之外的东西全在这一侧。
- `BoardRenderer`：显示实体的创建、更新、销毁。
- `SelectionState`：某个玩家当前选中了哪枚子、算出了哪些落点。
- `PackGenerator`：CE 资源包产出。
- `CraftEngineBridge`：CE API 的唯一入口，反射与版本兼容都关在这里。

禁止把职责堆进一个 God Class。配置读取、规则判定、运行时状态、世界渲染、CE 交互必须分离。规则层禁止反向依赖渲染层。

## 象棋规则基线

规则以象棋百科全书（xqbase）与亚洲象棋规例为准，实现必须满足：

- 棋盘 9 纵线 × 10 横线，棋子落在交叉点，共 90 点。河界在第 5 与第 6 横线之间。九宫为 3×3。
- 帥/將：九宫内上下左右一步。仕/士：九宫内斜走一步。
- 相/象：斜走两格，不可过河，阻挡点是**起终点的中点**（塞象眼）。
- 馬：走"日"字，阻挡点是**起点沿"走 2 格那个轴"方向的第一格**（蹩马腿），不是起终点中点。马的走法因此非对称。
- 車：直横任意距离，不可越子。
- 炮：不吃子时同車；吃子必须跨过**恰好一个**棋子，炮架不分敌我，跨 0 个或 2 个以上均非法。
- 兵/卒：过河前只能向前一步，过河后可前、左、右一步，永不后退，无升变。
- 蹩马腿与塞象眼的阻挡不分敌我。
- 飞将：两将同纵线且中间无子的局面非法。实现上把将视为車，能直接吃到对方将即视为被将军。
- 送将非法：走完后己方将处于被攻击状态的着法一律非法。这一条统一覆盖对脸、不应将、走入攻击范围。
- 将军检测用反向探测：从己方将出发，分别以車、炮、馬、过河兵的走法反查是否命中对应敌子。象与士永不能将军，不必检测。反向探测马时，马腿位置要用士的偏移方向组。
- 将死（被将军且无合法着法）判负；**困毙（未被将军但无合法着法）同样判负**，这点与国际象棋不同。
- 限着与循环判定（长将、长捉）属于裁判级逻辑，做成可配置模块，禁止混进走法生成器。

合法着法生成 = 生成伪合法着法 → 逐个试走 → 剔除使己方被将军的。

## 功能边界

### 1. 规则引擎模块

- 负责：走法生成、阻挡判定、将军检测、终局判定、局面表示与 FEN 式序列化。
- 不负责：世界坐标、玩家、消息发送、配置读取、任何 Bukkit 调用。

### 2. CraftEngine 资源包生成模块

- 负责：产出 `pack.yml`、`configuration/*.yml`、贴图与模型文件，写入 CE 的 `resources/muchess/`；产出内容变化时才重写；触发 CE 重载。
- 不负责：棋子摆放、走法、玩家交互。CE 缺失时直接跳过并记日志。

### 3. CraftEngine 桥接模块

- 负责：封装 CE API 调用（按 id 取物品、放置与移除家具、判断实体是否 CE 家具、监听 `FurnitureInteractEvent` 与 `CraftEngineReloadEvent`）；版本差异与反射兜底。
- 不负责：业务判断。桥接层不允许出现棋类概念之外的推理，也不允许直接改棋局状态。

### 4. 棋盘实体化模块

- 负责：2x2 占位校验、家具放置、交叉点与世界坐标换算、棋子显示实体的生成与销毁、朝向处理。
- 不负责：走法合法性。落点是否能走一律问规则层。

### 5. 交互模块

- 负责：右键命中解析、选中态管理、浮起放大动画、合法落点粒子提示、二次右键落子、非法操作反馈。
- 不负责：规则判定与资源生成。

### 6. 对局流程模块

- 负责：入座、开局、轮次推进、将军播报、终局结算、认输、观战、超时。
- 不负责：渲染细节。

### 7. 五子棋模块

- 负责：五子棋规则实现（连五判定、禁手可配置）。
- 不负责：另造一套棋盘或交互。必须复用象棋已有的棋盘、交互、对局框架。

## 推荐包结构

```text
linmumua.chess
├── ChessPlugin.kt              入口与生命周期挂载
├── command/                    /chess 命令树
├── config/                     SnakeYAML loader、配置模型、语言
├── rule/                       纯逻辑规则层，禁止 Bukkit 依赖
│   ├── core/                   Board、Piece、Move、Side 等共用模型
│   ├── xiangqi/                象棋走法生成、将军检测、终局判定
│   └── gomoku/                 五子棋规则
├── game/                       ChessGame、GameSession、轮次与结算
├── board/                      BoardAnchor 坐标换算、2x2 占位校验
├── render/                     BoardRenderer、显示实体、动画、粒子
├── interact/                   选中态、右键解析、落子入口
├── pack/                       PackGenerator，CE 资源包产出
├── compat/                     CraftEngineBridge 及版本兼容
├── listener/                   Bukkit 与 CE 事件监听
└── util/                       通用工具
```

规则层的目录纪律：`rule/` 下任何文件出现 `org.bukkit` 导入即视为违规。

## 生命周期建议

- `LifeCycle.LOAD`：释放默认配置模板，用 SnakeYAML 读取 `config.yml`、棋种定义、语言文件；构建规则层注册表。此阶段不碰 CraftEngine。
- `LifeCycle.ENABLE`：检测 CraftEngine 是否存在并启用，建立桥接；生成资源包并按需触发 CE 重载；注册命令与监听。
- CE 的 `CraftEngineReloadEvent`：此时 CE 的物品与家具注册表才可用，棋子物品的解析、已放置棋盘的重挂都放在这里。**禁止在 `onEnable` 阶段调用 CE 的 `loadedItems()` / `loadedFurniture()` / `loadedBlocks()`，那时是空的。**
- 世界加载完成后再恢复 `boards.yml` 里的棋盘，避免拿不到世界对象。
- `@Schedule`：动画插值推进、粒子刷新、对局超时检查。粒子与动画任务在没有活跃对局时必须自行停摆，不允许空转。
- `LifeCycle.DISABLE`：结束所有对局并落盘、取消全部调度任务、清理显示实体与交互实体、清空选中态。**关服后世界里不允许残留本插件生成的显示实体。**

## 当前技术栈

| 层 | 技术 | 备注 |
|---|---|---|
| 平台 | Paper 26.1.2 / 26.2 | 需要 `ItemDisplay` 与 `Interaction`；26.x 强制 JDK 25 |
| 主框架 | TabooLib 6.3.0 | 运行期联网下载，不打包 |
| 外观 | CraftEngine 26.7.4 | `softdepend`，`compileOnly` |
| 显示 | ItemDisplay + Transformation | 棋子渲染与浮起放大动画 |
| 命中 | CE `FurnitureInteractEvent` | 用 `interactionPoint()` 反算交叉点 |
| 配置 | SnakeYAML 2.5 | 运行期唯一 YAML 实现，`@RuntimeDependency` 下载 |
| 文案 | MiniMessage + TabooLib I18n | 语言文件唯一文案来源 |
| 构建 | Gradle 9 + Kotlin DSL | JDK 25 |
| 语言 | Kotlin | 规则层与业务层统一 Kotlin |
| 测试 | JUnit 5 | 规则层必须有单测 |

## 开发优先级建议

1. **规则引擎与单测**。规则不对，后面全是白做。先把象棋走法、阻挡、将军、送将、终局跑通。
2. **CE 资源包生成与桥接**。棋盘棋子能在世界里看见。
3. **坐标标定与棋子摆放**。32 枚子落在正确交叉点上。
4. **交互闭环**。选中、浮起、粒子提示、落子。
5. **对局流程与结算**。
6. **五子棋接入**。
7. **性能与清理**。实体数量控制、任务停摆、关服清场。
8. **文档与迁移说明**。

## 明确禁止事项

- 禁止偏离"棋类对局"这个核心领域。
- 禁止把 TabooLib 或 Kotlin 标准库打包进 JAR。构建后必须复核 JAR 内 `kotlin/` 条目为 0。
- 禁止硬编码玩家可见文本。
- 禁止作者字段出现 `linmumua` 以外的名字。
- 禁止用 JSON / TOML / properties 作为人类可编辑配置入口。
- 禁止用 Bukkit `YamlConfiguration` 或 Jackson YAML 处理运行期 YAML。
- 禁止在 `rule/` 包内导入任何 Bukkit 类型。
- 禁止在 `onEnable` 阶段读取 CE 的注册表。
- 禁止主线程做文件遍历、贴图下载、资源包压缩这类耗时 I/O。资源包生成必须异步，回主线程只做 CE 调用。
- 禁止吞异常不记日志。CE 反射失败必须降级并写清失败原因。
- 禁止用轮询扫描实体来找棋子。棋子与交叉点的对应关系由内存索引维护。
- 禁止新增功能不同步更新默认配置与语言文件。
- 禁止把所有职责堆进单个 God Class。
- 禁止无版本号变化地替换发布构建。
- 禁止在没有跑过单测的情况下声称规则已实现。

## 工作准则

- 改规则层必须同时补或改单测，测试要覆盖"为什么这条规则重要"，不是只覆盖"函数返回了什么"。蹩马腿、塞象眼、飞将、送将、困毙这五条必须各自有独立用例。
- 动手前先读相关代码。规则层与渲染层的边界不清楚就先问，不要猜。
- 只改必须改的地方。不顺手重构、不顺手调格式、不动无关注释。
- 两种写法冲突时选一种并说明理由，不要混着用。
- 每完成一个阶段就复述"做了什么、验证了什么、还剩什么"。
- 有跳过的步骤就明说。跳过了还报"已完成"是错的。

## AI / Codex 执行要求

- 参与开发前必须先完整读一遍本文件。
- 修改代码必须守住领域边界，不得主动扩展与棋类无关的功能。
- 新增玩家可见文本必须同步补语言文件。
- 涉及 TabooLib API 时优先查 TabooLib 文档或沿用本项目现有写法，不得凭空编造 API 签名。
- 涉及 CraftEngine API 时优先查 <https://ce.gtemc.cn/zh-Hans/> 或读 `compat/` 下已有实现，不得凭记忆写方法名。
- 拿不到的外部资料要明说拿不到，改用可核实的来源，禁止假装读过。
- 声称"实现完成"前必须跑构建与单测，并在结论里写清跑了什么、结果如何。
- 发现需求与项目核心冲突时先说明风险，再给收敛方案，不要默默照做。
