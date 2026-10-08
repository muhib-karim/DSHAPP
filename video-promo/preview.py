"""把 cam 可见区域裁出来，画上候选的触点/高亮框，输出预览图供人工核对。

用法: python preview.py <out.png> <img> <tx%> <ty%> <tw%> <th%>
tx/ty 是触点中心百分比，tw/th 是高亮框尺寸百分比（框以触点为中心不成立，
本脚本把框左上角放在 (tx-tw/2, ty-th/2)，即触点=框中心）。
"""
import sys
from PIL import Image, ImageDraw

CAM_AR = 9 / 20


def visible_box(w, h):
    ar = w / h
    if abs(ar - CAM_AR) < 1e-6:
        return 0, 0, w, h
    if ar > CAM_AR:
        sw = h * CAM_AR
        return (w - sw) / 2, 0, sw, h
    sh = w / CAM_AR
    return 0, 0, w, sh


def main():
    out, path = sys.argv[1], sys.argv[2]
    img = Image.open(path).convert("RGB")
    w, h = img.size
    sx, sy, sw, sh = visible_box(w, h)
    crop = img.crop((int(sx), int(sy), int(sx + sw), int(sy + sh)))

    # 缩到 540 宽便于查看
    scale = 540 / crop.width
    crop = crop.resize((540, int(crop.height * scale)))
    d = ImageDraw.Draw(crop)

    for spec in sys.argv[3:]:
        # 格式: tx,ty,bx,by,bw,bh  （触点中心 + 框中心 + 框尺寸，单位 %）
        tx, ty, bx, by, bw, bh = (float(v) for v in spec.split(","))
        cx, cy = tx / 100 * crop.width, ty / 100 * crop.height
        fx, fy = bx / 100 * crop.width, by / 100 * crop.height
        w2, h2 = bw / 100 * crop.width, bh / 100 * crop.height
        d.rectangle([fx - w2 / 2, fy - h2 / 2, fx + w2 / 2, fy + h2 / 2],
                    outline=(90, 107, 255), width=2)
        r = 11
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(90, 107, 255))
        d.ellipse([cx - 4, cy - 4, cx + 4, cy + 4], fill=(255, 255, 255))

    crop.save(out)
    print(f"{out}  {crop.width}x{crop.height}  (cam visible = {sw:.0f}x{sh:.0f} of {w}x{h})")


main()
