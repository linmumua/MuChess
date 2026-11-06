import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    java
    kotlin("jvm") version "2.3.20"
    id("io.izzel.taboolib") version "2.0.38"
}

group = "linmumua"
version = "1.12.0"

/**
 * 一个受支持的 Paper 目标。
 *
 * Paper 从 26.x 起改用日期式版本号，API 坐标与 api-version 都跟着变，
 * 而且 26.x 要求 JDK 25。所以把这三样绑在一起，避免改了一处漏另一处。
 */
data class PaperTarget(
    /** Gradle 属性里用的短名 */
    val id: String,
    /** paper-api 的完整版本坐标 */
    val apiDependency: String,
    /** 写进 plugin.yml 的 api-version */
    val apiVersion: String,
    /** 编译与运行需要的 JDK 主版本 */
    val javaVersion: Int,
)

val supportedTargets = listOf(
    PaperTarget("paper-26.1.2", "26.1.2.build.74-stable", "26.1.2", 25),
    PaperTarget("paper-26.2", "26.2.build.84-stable", "26.2", 25),
).associateBy(PaperTarget::id)

// 默认打 26.2。要打另一个目标：./gradlew build -PchessTarget=paper-26.1.2
val chessTargetId = providers.gradleProperty("chessTarget").orElse("paper-26.2").get()
val chessTarget = supportedTargets[chessTargetId]
    ?: throw GradleException(
        "不认识的 chessTarget '$chessTargetId'，可选：${supportedTargets.keys.joinToString()}",
    )

// 每个目标各自一个构建目录，来回切换时不会互相覆盖产物
layout.buildDirectory.set(layout.projectDirectory.dir("build/${chessTarget.id}"))

// 是否打开 TabooLib 的调试输出：./gradlew build -PchessDebug=true
//
// 注意：改了 taboolib { } 里的任何 env / version 配置后，必须 clean 再构建。
// taboolibMainTask 不把这些配置声明为 task input，只改配置不会让它重跑，
// JAR 里的 META-INF/taboolib/env.properties 会留着上一次的旧值，
// 表现为"改了开关但完全没生效"。
val chessDebug = providers.gradleProperty("chessDebug").orElse("false").get().toBoolean()

// ============================================================================
// 【为什么不再把 jar-relocator 与 ASM 打进 JAR】
//
// 曾经打过。那是 Paper 装载格式时期的必需品：
// `PaperSimplePluginClassLoader.findClass` 只从插件 JAR 自身读字节码，找不到直接抛
// ClassNotFoundException，不查 URLClassLoader 的 URL 搜索路径。而 TabooLib 的
// `PrimitiveLoader` 下载完 jar-relocator 后是靠 `ClassAppender.addPath` 注入、紧接着就
// `new JarRelocator(...)` 去重定位下一个依赖 —— 那次注入在 Paper 加载器上不可见，于是崩在：
//
//     ClassNotFoundException: me.lucko.jarrelocator.Relocation
//       at PaperSimplePluginClassLoader.findClass
//       at PrimitiveLoader.loadFile
//
// 现已退回传统 Bukkit 装载（理由见下面"为什么本项目不用 Paper 装载格式"那段）。
// Bukkit 的 `PluginClassLoader` 会查 ucp，TabooLib 的原生注入本来就有效，
// 这三个库由 TabooLib 在运行期自己下载即可，打进 JAR 是多余的 220KB。
//
// 移除前做过针对性实测，不是想当然。光在本机跑通不算证据 —— 本机 libraries/ 里早就有
// 这三个库（几轮排查时手工铺进去的），不清掉的话测出来的只是缓存命中。
// 实验步骤：把测试服 libraries/ 下的 jar-relocator-1.7、asm-9.8、asm-commons-9.8 连同
// 各自 .sha1 移走，cache/taboolib 里对应的三个重定位产物也移走，保留 Paper 自己要用的
// asm-9.9.1（版本不同，别一起清），造出"从没有过这三个库"的环境，再用不含它们的产物冷启动。
//
// 实测结果（boot18）：
//   日志出现 正在下载依赖 me.lucko:jar-relocator:1.7 / org.ow2.asm:asm:9.8 /
//     org.ow2.asm:asm-commons:9.8  —— TabooLib 自己把它们下回来了
//   下载后落盘：jar-relocator-1.7.jar 19126 字节、asm-9.8.jar 126113 字节
//   plugins/MuChess/config.yml 与 lang/zh_CN.yml 正常释放，零插件异常
//   产物体积 1771020 → 1552907 字节，省 213KB
// 将来若要改回打包，先复现这个实验，别只看"本机能启动"。
// ============================================================================

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://repo.tabooproject.org/repository/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${chessTarget.apiDependency}")
    compileOnly("net.momirealms:craft-engine-core:26.7.4")
    compileOnly("net.momirealms:craft-engine-bukkit:26.7.4")

    compileOnly(kotlin("stdlib"))
    // SnakeYAML 只在编译期可见，运行期由 TabooLib 的 @RuntimeDependency 联网下载
    compileOnly("org.yaml:snakeyaml:2.5")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:2.0.0")
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

taboolib {
    version {
        taboolib = "6.3.0-75b18a2"
        // 必须让 TabooLib 重定位到 linmumua.chess.taboolib，不能设 skipTabooLibRelocate。
        //
        // TabooLib 靠重定位来认自己的 groupId：ProjectInfoKt 里写的是
        //   groupId = "taboolib".substringBefore(".$taboolibId")
        // 重定位后那个字面量会变成 "linmumua.chess.taboolib"，截出 groupId = "linmumua.chess"，
        // 项目类扫描才认得 linmumua.chess.* 是"本项目的类"。
        //
        // 一旦跳过重定位，字面量还是 "taboolib"，截出来的 groupId 也是 "taboolib"，
        // 于是本插件所有类都被判成"外部类"，@Awake / @SubscribeEvent / @Schedule 一个都不触发：
        // 插件能 Enabling 但什么都不做，config.yml 都不会释放。
        //
        // 重定位与"模块运行期联网下载"是两件独立的事，下载由下面 env 的 install 控制，
        // 不需要靠跳过重定位来实现。
    }

    env {
        // 不开隔离类加载器。
        // 试过 enableIsolatedClassloader = true，结果 IsolatedClassLoader 自己找不到
        // 重定位后的 Kotlin，启动直接挂在 "无法启动 Kotlin 环境。(未能找到 kotlin2320.Lazy)"，
        // 比不开更糟。非隔离模式下 ClassAppender 的私有 addURL 同样会回调
        // ProjectScannerKt 的注册钩子，模块类照样能进 extraLoadedClasses。

        // 调试输出默认关闭，需要时用 -PchessDebug=true 打开。
        // TabooLib 只在这个开关下打印模块加载明细、RunningClasses 计数与
        // 平台服务注册表内容，排查启动期问题离不开它；但正式产物不该带着它发布，
        // 否则每次启动都往控制台刷几十行内部日志。
        debug = chessDebug

        // install 声明的是"运行时要下载哪些模块"，不是"打包进 JAR"。
        // 构建产物里只保留 taboolib.common 的引导层（约 24 个类），
        // 其余模块与 Kotlin 标准库在首次启动时由 loader 从远程仓库拉取到本地缓存。
        // 只装真正用到的模块。
        //
        // 刻意不装 bukkit-ui 与 bukkit-nms：本插件的棋子是 ItemDisplay，
        // 没有箱子菜单，也不碰 NMS，而 bukkit-ui 会连带拉进 bukkit-nms。
        // 少装一个模块就少一份运行期下载与版本适配风险，所以这里按需最小化。
        // 注意：serviceMap 为空的那个故障与本列表无关，根因是上面的隔离类加载器开关。
        install(
            "platform-bukkit",
            "platform-bukkit-impl",
            "basic-configuration",
            "basic-submit-chain",
            "minecraft-chat",
            "minecraft-i18n",
            "minecraft-command-helper",
            "bukkit-util",
            "bukkit-xseries",
        )
    }

    description {
        name = "MuChess"
        contributors {
            name("linmumua")
        }
        // softdepend：CraftEngine 存在时先于本插件加载，缺失时本插件仍可启动（降级为无外观模式）
        dependencies {
            name("CraftEngine").optional(true)
        }
    }
}

// paper-api 26.x 的 Gradle 元数据声明了"只兼容 JVM 25 及以上"，
// 所以字节码不能降级到 21，工具链与目标版本必须一致。
kotlin {
    jvmToolchain(chessTarget.javaVersion)
}

java {
    withSourcesJar()
}

// ---- CraftEngine 资源包生成 ----
// 贴图与 CE 配置在构建期产出，随 JAR 一起发布，运行期再导出到 CE 的 resources 目录。
// 放在构建期而不是运行期，是因为画贴图属于耗时 CPU 工作，不该让服务端启动时去做。
val packOutputDir = layout.buildDirectory.dir("generated/craftengine")

val generateCraftEnginePack by tasks.registering {
    description = "生成 MuChess 的 CraftEngine 资源包（贴图 + 配置 + 音频）"
    val outputDir = packOutputDir
    val packVersion = version.toString()
    val opacityTolerance = providers.systemProperty("verifyTextureOpaque").orElse("0.02")
    // 音频素材目录：里面是预先用 ffmpeg 转好的 ogg，构建期只复制。
    // 声明成输入目录，换了素材才会重跑本任务；不声明的话改了音频构建缓存照旧命中，
    // 产出的包里还是旧曲子。
    val audioSourceDir = layout.projectDirectory.dir("assets-src/audio")
    outputs.dir(outputDir)
    inputs.dir(audioSourceDir)
    inputs.property("packVersion", packVersion)
    inputs.property("verifyTextureOpaque", opacityTolerance)

    doLast {
        val root = outputDir.get().asFile.resolve("muchess")
        root.deleteRecursively()
        root.mkdirs()
        PackWriter.writeAll(root, packVersion, audioSourceDir.asFile)

        // 运行期导出用的清单：列出包内所有文件的相对路径。
        // 有清单才能做增量比对和清理陈旧文件，不必在运行期遍历 JAR。
        val entries = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
        root.resolve("_bundle_index.txt").writeText(entries.joinToString("\n") + "\n", Charsets.UTF_8)

        // 自检：字体没加载上时贴图仍然是有效 PNG，只是没有字，
        // 必须靠像素统计把这种"静默画错"拦下来。
        val channelTolerance = opacityTolerance.get().toDoubleOrNull()
            ?: error("verifyTextureOpaque 必须是数字：${opacityTolerance.get()}")
        VerifyTextures.verifyAll(root, channelTolerance = channelTolerance)
        // 音频同理：复制成 0 字节或换了曲子都不会让构建失败，只会让服主进服听出问题。
        VerifyAudio.verifyAll(root)
        logger.lifecycle("[MuChess] 资源包已生成：${entries.size} 个文件")
    }
}

sourceSets.main {
    resources.srcDir(packOutputDir)
}

// ---- 修正 TabooLib 生成的 plugin.yml ----
// TabooLib 的 Bukkit 描述构建器把 api-version 与 folia-supported 写死了，
// DSL 里没有对应开关，所以在 JAR 打好之后改这两行：
//   api-version: 取当前构建目标的版本。写死的 1.13 会让服主以为能装在老版本上，
//                而本项目需要 ItemDisplay / Interaction。
//   folia-supported: 没有在 Folia 上验证过，不能声明支持。声明了但实际不兼容，
//                    比不声明更糟。
// ============================================================================
// 【为什么本项目不用 Paper 装载格式（paper-plugin.yml）】
//
// 曾经尝试过：写 paper-plugin.yml + 一个 PluginLoader（PaperClasspath），由它把
// Kotlin 与 TabooLib 各模块装进 Paper 的 libraryLoader。动机是 Paper 插件的类加载器
// 重写了 findClass、只读插件 JAR 自身，TabooLib 靠 ClassAppender.addPath 往 ucp 注入
// 的 jar 不会被查到（注入静默成功、类却找不到）。
//
// 那条路实测走不通，根因用 -Xlog:class+init=info 抓到：TabooLib 引导层被 define 三次，
// 三个互不相同的类镜像地址（同一份字节码被不同加载器各自 define，就是不同的类）：
//
//   3.560s   0x...05391438   ServerMain     ← PaperClasspath 里跑下载用的临时 URLClassLoader
//   17.851s  0x...062d06b8   Server thread  ← BukkitPlugin 引用的那份（062d 段）
//   17.930s  0x...062d8908   Server thread  ← IsolatedClassLoader.init 里又一份
//
// TabooLib.lifeCycleTask 是静态表。实测注册侧（ClassVisitorHandler 0x...062e88a8、
// TabooLib$2 0x...062e95b8）落在 062e 段，跑批侧（BukkitPlugin + 其 TabooLib）落在
// 062d 段 —— 注册与跑批分属两个类、两张表，跑批读到空表。表现是：
//   生命周期各阶段全部 0 毫秒 → 永不打印 RunningClasses → ClassVisitorAwake 一次都不初始化
//   → 类扫描不发生 → ChessPlugin 从未加载 → 插件 Loading/Enabling 全过、零报错、
//     plugins/MuChess 下什么都不释放。
// 这种"启动成功但什么都不做且不报错"的形态极难排查，前后耗了十几轮冷启动。
//
// 退回传统 Bukkit 装载（只留 plugin.yml）后，同样的 class+init 取证：
//   TabooLib 初始化次数 1（Paper 格式下是 3）
//   ChessPlugin 初始化次数 1（Paper 格式下是 0）
//   生命周期 INIT/LOAD/ENABLE/ACTIVE = 214/107/139/3 毫秒（不再是 0）
//   RunningClasses (Jar) = 366、(Included) = 283
//   plugins/MuChess/config.yml 与 lang/zh_CN.yml 正常释放，零报错
// 原因很简单：Bukkit 的 PluginClassLoader 会查 ucp，TabooLib 的原生注入本来就有效，
// 整条链上只有一个类加载器，不存在多份静态状态。
//
// 结论：Paper 装载格式与 TabooLib 的隔离加载器机制存在结构性冲突。TabooLib 6.3.0
// 没有为 Paper 格式做适配（Gradle 插件 2.0.38 的平台枚举里根本没有 Paper）。
// 在 TabooLib 官方支持之前，本项目只用 plugin.yml。
//
// 若将来要重试 Paper 格式，先验这一条：class+init 下 TabooLib 只能初始化一次。
// 达不到就不要往下走，中间任何"看起来启动成功"都是假象。
//
// 那次尝试的完整实现连同踩坑记录留在 docs/PaperClasspath.java.txt（纯文本，不参与编译）。
// 里面还记着几条与装载格式无关、将来仍可能踩到的实测细节：TabooLib 重定位产物写回原
// Maven 布局时带 _r<N>_<hash> 后缀、PrimitiveLoader.getCacheFile 的落盘位置约定等。
//
// 【与装载格式无关的一条事实，单独拎出来免得再踩】
// 本项目的 TabooLib 重定位前缀是 **包名首段 + ".taboolib"**，即 linmumua.taboolib，
// 不是"业务包名去掉最后一段再加 .taboolib"。实测依据：unzip -l 产物可见引导层落在
// linmumua/taboolib/common/TabooLib.class，而业务代码在 linmumua.chess.*。
// 早先按"去掉最后一段"推成 linmumua.chess.taboolib，于是 TabooLib 的系统属性键
// （linmumua.taboolib.main 等）全拼错，设了等于没设，且没有任何报错指向那里。
// 凡是要拼 TabooLib 系统属性键或反射引导层类名的地方，都按首段推。
// ============================================================================

val fixPluginDescription by tasks.registering {
    description = "修正 plugin.yml 的 api-version 与 folia-supported，并写入 paper-plugin.yml"
    dependsOn("taboolibMainTask")
    val pluginJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val targetApiVersion = chessTarget.apiVersion
    val pluginVersion = version.toString()
    inputs.file(pluginJar)
    inputs.property("apiVersion", targetApiVersion)
    inputs.property("pluginVersion", pluginVersion)

    doLast {
        val jarFile = pluginJar.get().asFile
        val temp = File(jarFile.parentFile, "${jarFile.name}.rewrite")
        ZipFile(jarFile).use { zip ->
            ZipOutputStream(temp.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    // 历史产物里可能残留 paper-plugin.yml（本项目曾尝试 Paper 装载格式，
                    // 实测与 TabooLib 的隔离加载器结构性冲突，已放弃，理由见上面那段说明）。
                    // 只要它还在 JAR 里，Paper 就会优先按 Paper 插件装载，于是类扫描不发生、
                    // 插件启动成功却什么都不做。所以这里必须主动剔除，不能只是"不再生成"：
                    // jar 任务判定 UP-TO-DATE 时输入 JAR 是上一次的产物，带着那个文件。
                    if (entry.name == "paper-plugin.yml") {
                        continue
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "plugin.yml") {
                        val text = zip.getInputStream(entry).reader(Charsets.UTF_8).readText()
                        val fixed = text
                            .replace("api-version: 1.13", "api-version: '$targetApiVersion'")
                            .replace("folia-supported: true", "folia-supported: false")
                        out.write(fixed.toByteArray(Charsets.UTF_8))
                    } else {
                        zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
                // 这里曾经写 paper-plugin.yml，现已移除：本项目只用传统 Bukkit 装载。

                // 这里曾经把 jar-relocator 与 ASM 原样解包进 JAR，现已移除。
                // 那是 Paper 装载格式时期的必需品，Bukkit 装载下由 TabooLib 运行期自行下载。
                // 完整理由与移除前的实测方法见文件开头那段说明。
            }
        }
        temp.copyTo(jarFile, overwrite = true)
        temp.delete()

        // 改完复核一遍，避免上游改了字面量后这里静默失效
        ZipFile(jarFile).use { zip ->
            val entry = zip.getEntry("plugin.yml") ?: error("JAR 内没有 plugin.yml")
            val text = zip.getInputStream(entry).reader(Charsets.UTF_8).readText()
            check(text.contains("api-version: '$targetApiVersion'")) {
                "api-version 没改成 $targetApiVersion，实际内容：\n$text"
            }
            check(text.contains("folia-supported: false")) { "folia-supported 没改成 false，实际内容：\n$text" }

            // 断言 paper-plugin.yml **不在** JAR 里。
            //
            // 这条是防回退护栏，不是多余检查。只要它出现，Paper 就会优先按 Paper 插件装载，
            // 于是 TabooLib 引导层被 define 多份、类扫描不发生 —— 插件照样 Loading/Enabling
            // 全过、零报错，但 plugins/MuChess 下什么都不释放，命令也不注册。
            // 这种失败形态没有任何报错指向成因，实测耗了十几轮才定位（详见上面那段说明）。
            // 宁可在构建期直接失败。
            check(zip.getEntry("paper-plugin.yml") == null) {
                "JAR 内出现了 paper-plugin.yml。本项目只用传统 Bukkit 装载：" +
                    "Paper 装载格式与 TabooLib 的隔离加载器结构性冲突，" +
                    "会导致插件启动成功但类扫描不发生、配置不释放且无任何报错。"
            }
        }
        logger.lifecycle(
            "[MuChess] plugin.yml 已修正 api-version=$targetApiVersion、folia-supported=false；" +
                "已确认 JAR 内无 paper-plugin.yml（传统 Bukkit 装载）",
        )
    }
}

tasks.named("assemble") {
    dependsOn(fixPluginDescription)
}

// 所有会读 main resources 的任务都得等资源包生成完。
// sourcesJar 也在其中：它会把 resources 目录打进源码包，漏了会报隐式依赖错误。
tasks.named("processResources") {
    dependsOn(generateCraftEnginePack)
    doFirst {
        // Gradle 的增量资源复制不会可靠删除生成目录里已经移除的文件。
        // 先清空本包目录，避免 1.1.x 的 blocks.yml 与四象限贴图混进当前 JAR。
        layout.buildDirectory.dir("resources/main/muchess").get().asFile.deleteRecursively()
    }
}

tasks.named("sourcesJar") {
    dependsOn(generateCraftEnginePack)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
