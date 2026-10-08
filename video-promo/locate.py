"""从真机截图里检测目标控件的像素位置，输出相对 cam 可见区域的百分比。

cam: aspect-ratio 9/20, object-fit: cover, object-position: top center。
图比 9:20 更宽 → 按高度贴合、左右裁；更窄 → 按宽度贴合、上下裁（顶部对齐）。

用法:
  python locate.py <png> green            找绿色胶囊（「已开启」标签）
  python locate.py <png> blue             找蓝色块（按钮/图标）
  python locate.py <png> box x1 y1 x2 y2  手填像素框，换算成百分比
  python locate.py <png> probe x,y ...    采样若干点的颜色
"""
import sys
from PIL import Image

CAM_AR = 9 / 20


def visible_box(w, h):
    ar = w / h
    if abs(ar - CAM_AR) < 1e-6:
        return 0, 0, w, h
    if ar > CAM_AR:                      # 更宽：按高度贴合，左右裁
        sw = h * CAM_AR
        return (w - sw) / 2, 0, sw, h
    sh = w / CAM_AR                      # 更窄：按宽度贴合，上下裁（顶部对齐）
    return 0, 0, w, sh


def main():
    path, mode = sys.argv[1], sys.argv[2]
    img = Image.open(path).convert("RGB")
    w, h = img.size
    px = img.load()
    sx, sy, sw, sh = visible_box(w, h)

    def to_pct(x, y):
        return ((x - sx) / sw * 100, (y - sy) / sh * 100)

    if mode in ("green", "blue"):
        # 可选：再给 4 个参数限定搜索区域（像素），避免整图误命中
        rx1, ry1, rx2, ry2 = (0, 0, w, h)
        if len(sys.argv) >= 7:
            rx1, ry1, rx2, ry2 = (int(v) for v in sys.argv[3:7])
        minx, miny, maxx, maxy, n = 10**9, 10**9, -1, -1, 0
        for y in range(ry1, ry2):
            for x in range(rx1, rx2):
                r, g, b = px[x, y]
                if mode == "green":
                    hit = g > 150 and g - r > 45 and g - b > 45
                else:
                    hit = b > 170 and b - r > 55 and b - g > 35
                if hit:
                    n += 1
                    minx = min(minx, x); maxx = max(maxx, x)
                    miny = min(miny, y); maxy = max(maxy, y)
        if not n:
            print(f"no {mode} found")
            return
        ax, ay = to_pct(minx, miny)
        bx, by = to_pct(maxx, maxy)
        print(f"pixels: ({minx},{miny})-({maxx},{maxy})  n={n}")
        print(f"hlbox:  --tx:{ax:.1f}%;--ty:{ay:.1f}%;--tw:{bx-ax:.1f}%;--th:{by-ay:.1f}%")
        print(f"center: --tx:{(ax+bx)/2:.1f}%;--ty:{(ay+by)/2:.1f}%")

    elif mode == "box":
        x1, y1, x2, y2 = (int(v) for v in sys.argv[3:7])
        ax, ay = to_pct(x1, y1)
        bx, by = to_pct(x2, y2)
        print(f"hlbox:  --tx:{ax:.1f}%;--ty:{ay:.1f}%;--tw:{bx-ax:.1f}%;--th:{by-ay:.1f}%")
        print(f"center: --tx:{(ax+bx)/2:.1f}%;--ty:{(ay+by)/2:.1f}%")

    elif mode == "probe":
        for spec in sys.argv[3:]:
            x, y = (int(v) for v in spec.split(","))
            print(f"{x},{y} -> rgb{px[x, y]}")

    print(f"image: {w}x{h}  visible: ({sx:.0f},{sy:.0f}) {sw:.0f}x{sh:.0f}")


main()
