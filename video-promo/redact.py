"""给真机截图里的「无关信息」打高斯模糊码，保留与演示相关的区域清晰。

做法：整图先做一次强高斯模糊，再把保留区从原图贴回去，接缝羽化。
模糊是**离线**做的，页面里零运行时开销（CSS filter:blur 会掉到 4 FPS，绝不能在页面里做）。

用法:
  python redact.py <in.png> <out.png> [radius] <x1,y1,x2,y2> [x1,y1,x2,y2 ...]
  保留区坐标是相对**整图**的百分比；可以给多个，逐个贴回清晰原图。
"""
import sys
from PIL import Image, ImageDraw, ImageFilter


def heavy_blur(img, radius):
    """缩到 1/4 再糊再放大 —— 等效大半径，但快得多、也更彻底。"""
    w, h = img.size
    small = img.resize((max(1, w // 4), max(1, h // 4)), Image.LANCZOS)
    small = small.filter(ImageFilter.GaussianBlur(radius / 4))
    return small.resize((w, h), Image.LANCZOS).filter(ImageFilter.GaussianBlur(radius / 3))


def main():
    src, dst = sys.argv[1], sys.argv[2]
    radius = float(sys.argv[3])
    keeps = [tuple(float(v) for v in spec.split(",")) for spec in sys.argv[4:]]
    if not keeps:
        raise SystemExit("need at least one keep region: x1,y1,x2,y2 (percent)")

    img = Image.open(src).convert("RGB")
    w, h = img.size
    blurred = heavy_blur(img, radius)

    # mask 白 = 用模糊版，黑 = 保留原图
    mask = Image.new("L", (w, h), 255)
    d = ImageDraw.Draw(mask)
    for x1, y1, x2, y2 in keeps:
        d.rounded_rectangle(
            [x1 / 100 * w, y1 / 100 * h, x2 / 100 * w, y2 / 100 * h],
            radius=int(0.016 * w), fill=0,
        )
    mask = mask.filter(ImageFilter.GaussianBlur(8))   # 羽化接缝

    Image.composite(blurred, img, mask).save(dst)
    print(f"{dst}  {w}x{h}  blur~{radius:.0f}px  keeps={len(keeps)}")
    for k in keeps:
        print(f"  keep ({k[0]},{k[1]})-({k[2]},{k[3]})%")


main()
