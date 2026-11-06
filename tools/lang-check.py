#!/usr/bin/env python3
"""核对代码里引用的语言键与 lang/zh_CN.yml 是否一致。

用法：python tools/lang-check.py（在项目根目录执行）
缺键时退出码非 0。

为什么需要这个：缺键只会让 Msg.text() 打一行 warning 然后静默不发消息，
构建和单测都发现不了，装服启动也只有一行容易被忽略的 warning。
项目规范把"新增玩家可见文本必须同步补语言文件"列为硬要求，
靠肉眼核对不可靠，所以留一个机械检查。

新增玩家可见文本后请跑一次这个脚本。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "lang", "zh_CN.yml")
SRC = os.path.join(ROOT, "src", "main", "kotlin")

# yml 顶层键（本文件全是顶层键，没有嵌套）
defined = set()
with io.open(LANG, encoding="utf-8") as f:
    for line in f:
        m = re.match(r"^([A-Za-z][A-Za-z0-9-]*):", line)
        if m:
            defined.add(m.group(1))

used = {}
dynamic_refs = {}
total_calls = 0
literal_hits = 0
dynamic_hits = 0


def note(key, where):
    used.setdefault(key, set()).add(where)


# 上面 METHODS 里那几个方法的第一个字符串字面量参数。
# 必须支持跨行写法：项目里有不少调用把参数拆成多行，
# 只匹配单行会漏掉它们，从而给出"差集为空"的假安全感。
# re.S 让 . 匹配换行；[^"]*? 非贪婪跨过 receiver 参数直到第一个字符串字面量。
#
# 第二个参数是变量（如 result.messageKey）时不能当字面量键，否则会跨过它
# 匹配到后面的占位符名，误报成缺键。所以这里要求键参数后面紧跟 ) 或 ,
# 且前面那段不含 "." —— 有点是变量取值，不是字面量。
#
# receiver 段允许成对括号：像 Msg.sendAll(session.audience(), "game-started")
# 这种写法，receiver 自带 ()，若禁掉括号整条调用会被跳过，
# 给出"差集为空"的假安全感。(?:[^\"(),]|\([^()]*\))*? 允许跨过一层成对括号。
RECEIVER = r"(?:[^\"(),]|\([^()]*\))*?"
# 要跟踪的 Msg 方法，必须与 config/Msg.kt 里的公开方法一一对上。
#
# 早先只列了 send / sendRaw / text / sendAll，漏掉三个：
#   component   —— 悬浮字（label-join / label-start）与箱子菜单（menu-*）只走它
#   actionBar   —— 将军提示走它
#   actionBarAll
# 这些键因此从来没被核对过，而它们缺键的表现同样是静默不显示。
# 往 Msg 里加新方法时记得同步这里。
METHODS = r"(?:send|sendRaw|text|sendAll|component|actionBar|actionBarAll)"
call = re.compile(
    r"Msg\." + METHODS + r"\s*\(\s*" + RECEIVER + r",\s*\"([a-z][a-z0-9-]*)\"\s*[,)]",
    re.S,
)
# 键名来自变量的调用，单独统计，人工核对来源。
# 变量名可能就叫 key（转发函数的形参），所以不要求前面还有别的字符。
KEYVAR = r"[A-Za-z_][A-Za-z0-9_.]*"
dynamic = re.compile(
    r"Msg\." + METHODS + r"\s*\(\s*" + RECEIVER +
    r",\s*(" + KEYVAR + r")\s*(?:[,)]|,\s*\*)",
    re.S,
)
# @CommandBody(description = "help-xxx")
desc = re.compile(r"description\s*=\s*\"([a-z][a-z0-9-]*)\"")
# 只数调用点，用于和上面两个正则的捕获数对账
anycall = re.compile(r"Msg\." + METHODS + r"\s*\(")

# 行注释与块注释。对账用的调用点计数要先剥掉它们：
# 注释里提到 Msg.send(...) 是常事（本项目就有一处），那种"调用"永远匹配不上键正则，
# 会让脚本报"有 1 处未识别、本次结果不可信"。真有漏匹配时这句警告就被淹掉了。
#
# 只剥注释、不动字符串字面量：本项目的语言键里不含 // 或 /*，
# 所以不必为"字符串里的 //"做例外处理。
comment = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)

for dirpath, _, files in os.walk(SRC):
    for name in files:
        if not name.endswith(".kt"):
            continue
        path = os.path.join(dirpath, name)
        rel = os.path.relpath(path, ROOT)
        with io.open(path, encoding="utf-8") as f:
            text = f.read()
        # 对账只数真实代码里的调用，注释里提到的不算
        total_calls += len(anycall.findall(comment.sub("", text)))
        for m in call.finditer(text):
            note(m.group(1), rel)
            literal_hits += 1
        for m in desc.finditer(text):
            note(m.group(1), rel)
        for m in dynamic.finditer(text):
            dynamic_refs.setdefault(m.group(1), set()).add(rel)
            dynamic_hits += 1

missing = sorted(k for k in used if k not in defined)

# 自报覆盖率：捕获点数要对得上实际调用点数。
# 不做这个对账，正则漏匹配时会安静地给出"差集为空"，
# 反而比直接报错更危险 —— 这个坑本项目已经踩过一次。
uncovered = total_calls - literal_hits - dynamic_hits
print("yml 定义键数: %d" % len(defined))
print("Msg 调用点: %d（字面量 %d + 变量键 %d + 未识别 %d）"
      % (total_calls, literal_hits, dynamic_hits, uncovered))
if uncovered != 0:
    print("!! 有 %d 处 Msg 调用没被识别，正则需要修，本次结果不可信" % uncovered)
print("代码引用键数（字面量去重）: %d" % len(used))
if dynamic_refs:
    print("键名来自变量的调用（需人工核对来源）:")
    for k in sorted(dynamic_refs):
        print("   %s <- %s" % (k, ", ".join(sorted(dynamic_refs[k]))))
print()
if missing:
    print("!! 代码引用但 yml 缺失（会静默不发消息）:")
    for k in missing:
        print("   %-26s <- %s" % (k, ", ".join(sorted(used[k]))))
    sys.exit(1)
print("差集为空：代码引用的键 yml 全都有")
