#!/usr/bin/env python3
"""复核构建产物是否满足项目的 JAR 约束。

用法：python tools/jar-check.py <jar 路径> <期望 api-version> <期望版本号>
任一约束不满足时退出码非 0。

为什么需要这个：项目规范把"TabooLib 与 Kotlin 标准库不打包"列为硬约束，
判定标准是 JAR 内 kotlin/ 条目为 0、taboolib/ 条目不超过 40。
但这两条都容易查错：

- TabooLib 被重定位到 linmumua.taboolib.*，按 "taboolib/" 前缀查会得到 0，
  看着像"完全没打包"，其实是查漏了。反过来若引导层真的缺失，插件根本起不来。
  所以这里既查上限也查下限。
- plugin.yml 的 api-version 与 folia-supported 由 fixPluginDescription 事后改写，
  pack.yml 的 version 由构建期生成，三者都可能因为增量构建残留旧值。
"""
import sys
import zipfile

# 引导层类数的上下限。
# 上限 40 是项目规范给的判定标准；下限 20 是"引导层确实在里面"的兜底 ——
# 实测 6.3.0 装 9 个模块时是 26 个类，掉到 20 以下说明重定位或打包出了问题。
TABOOLIB_MAX = 40
TABOOLIB_MIN = 20


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    jar_path, want_api, want_version = sys.argv[1], sys.argv[2], sys.argv[3]
    failures = []

    with zipfile.ZipFile(jar_path) as z:
        names = z.namelist()

        # 1. Kotlin 标准库必须为 0（运行期由 TabooLib loader 下载）
        kotlin = [e for e in names if e.startswith("kotlin/") or e.startswith("kotlin2")]
        if kotlin:
            failures.append("kotlin/ 条目应为 0，实际 %d，前几个：%s" % (len(kotlin), kotlin[:3]))

        # 2. SnakeYAML 必须为 0（走 @RuntimeDependency）
        yaml = [e for e in names if e.startswith("org/yaml/") or "snakeyaml" in e.lower()]
        if yaml:
            failures.append("snakeyaml 条目应为 0，实际 %d" % len(yaml))

        # 3. TabooLib 引导层：既不能超上限，也不能缺失。
        #    重定位后路径是 linmumua/taboolib/，所以不能只按 "taboolib/" 前缀找。
        boot = [e for e in names
                if e.endswith(".class") and ("/taboolib/" in e or e.startswith("taboolib/"))]
        if len(boot) > TABOOLIB_MAX:
            failures.append("taboolib 引导类 %d 个，超过上限 %d" % (len(boot), TABOOLIB_MAX))
        if len(boot) < TABOOLIB_MIN:
            failures.append(
                "taboolib 引导类只有 %d 个（下限 %d），引导层可能没打进去，插件会起不来"
                % (len(boot), TABOOLIB_MIN))

        # 4. 未重定位的 taboolib/ 顶层包：出现说明重定位失效，
        #    那会让 groupId 截错、所有 @Awake / @SubscribeEvent 都不触发
        unrelocated = [e for e in names if e.startswith("taboolib/")]
        if unrelocated:
            failures.append("存在未重定位的 taboolib/ 条目 %d 个，重定位失效" % len(unrelocated))

        # 5. plugin.yml 的三个字段
        plugin_yml = z.read("plugin.yml").decode("utf-8")
        for want, label in (
            ("version: %s" % want_version, "版本号"),
            ("api-version: '%s'" % want_api, "api-version"),
            ("folia-supported: false", "folia-supported"),
        ):
            if want not in plugin_yml:
                failures.append("plugin.yml 的%s不对，期望包含 %r" % (label, want))

        # 6. pack.yml 版本必须跟随插件版本（项目规范要求），作者只能是 linmumua
        pack_yml = z.read("muchess/pack.yml").decode("utf-8")
        if "version: %s" % want_version not in pack_yml:
            failures.append("pack.yml 版本没跟上插件版本 %s" % want_version)
        if "author: linmumua" not in pack_yml:
            failures.append("pack.yml 的 author 必须是 linmumua")

        print("%s" % jar_path)
        print("  总条目 %d，taboolib 引导类 %d，kotlin %d，snakeyaml %d"
              % (len(names), len(boot), len(kotlin), len(yaml)))

    if failures:
        print("\n!! %d 项不满足：" % len(failures))
        for f in failures:
            print("   -", f)
        return 1
    print("  约束全部满足")
    return 0


if __name__ == "__main__":
    sys.exit(main())
