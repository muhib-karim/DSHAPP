# analyze-motion.py — 读 probe-motion.mjs 落盘的采样帧，量化「每屏到底动得够不够」。
#
# 为什么不能只看「帧哈希是否相同」：背景那层 26s 的 drift 漂移会让每帧像素都不同，
# 于是纯静态画面也能骗过哈希检查（实测 s7 就是这种情况）。必须量**幅度**。
#
# 指标：相邻采样帧的灰度平均绝对差（MAD）的中位数。
#   - 相邻帧才能反映「持续运动」；首末帧会因为相位巧合而误判。
#   - 中位数而不是均值：滤掉某一对恰好同相位的偶发 0。
#
# 门槛（浅色系、以真实截图为主的片子，数值偏小是正常的）：
#   MAD >= 1.5  实质动效
#   MAD >= 0.6  有可见动效
#   MAD >= 0.25 偏弱
#   否则        基本静止（只剩背景漂移）
import glob
import os
import re
import sys

from PIL import Image, ImageChops

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp", "motion")

if not os.path.isdir(D):
    sys.exit("找不到 tmp/motion —— 先跑 node probe-motion.mjs")


def frames(sid):
    fs = glob.glob(os.path.join(D, f"{sid}-f*-t*.png"))
    return sorted(fs, key=lambda p: int(re.search(r"-f(\d+)-", p).group(1)))


def mad(a, b):
    """灰度平均绝对差，范围 0–255。"""
    da = ImageChops.difference(a.convert("L"), b.convert("L"))
    h = da.histogram()
    total = sum(h)
    return sum(i * c for i, c in enumerate(h)) / total


def changed_ratio(a, b, thr=6):
    """差 > thr 的像素占比 —— 反映「动的地方有多大一片」。"""
    da = ImageChops.difference(a.convert("L"), b.convert("L"))
    h = da.histogram()
    return sum(h[thr + 1:]) / sum(h) * 100


print()
print("  屏     相邻帧MAD中位  变化像素占比  最大单对MAD   判定")
print("  " + "─" * 60)

worst = []
for i in range(1, 14):
    sid = f"s{i}"
    fs = frames(sid)
    if len(fs) < 3:
        print(f"  {sid:<5} 样张不足（{len(fs)}）")
        continue
    ims = [Image.open(f) for f in fs]
    pairs = list(zip(ims, ims[1:]))
    mads = sorted(mad(a, b) for a, b in pairs)
    med = mads[len(mads) // 2]
    chg = sum(changed_ratio(a, b) for a, b in pairs) / len(pairs)
    mx = mads[-1]

    if med >= 1.5:
        verdict = "实质动效"
    elif med >= 0.6:
        verdict = "有动效"
    elif med >= 0.25:
        verdict = "偏弱"
    else:
        verdict = "★ 基本静止"

    if med < 0.6:
        worst.append((sid, med))
    print(f"  {sid:<5} {med:>11.3f} {chg:>12.2f}% {mx:>11.3f}   {verdict}")

print()
if worst:
    print("  需要加强的屏：" + "、".join(f"{s}({m:.2f})" for s, m in worst))
    sys.exit(1)
print("  全部通过（相邻帧 MAD 中位数 ≥ 0.6）")
