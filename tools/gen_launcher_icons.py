"""生成 Gaze 的启动图标。

设计：一只注视的眼睛（Gaze = 注视）。
- 背景墨黑，眼睛米白，瞳孔墨黑 + 一点高光。
- 眼形用上下两条抛物线构成**单一闭合环** —— PIL 的 polygon 对自相交多边形
  会漏填充，顶点必须按顺序绕一圈。
"""
import os

from PIL import Image, ImageDraw

INK = (18, 18, 18, 255)
PAPER = (245, 242, 236, 255)

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def make_icon(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), INK)
    draw = ImageDraw.Draw(img)
    cx = cy = size / 2.0
    a = size * 0.335   # 眼睛半宽
    b = size * 0.185   # 眼睛半高

    points = []
    steps = 96
    # 上眼睑：左 -> 右
    for i in range(steps + 1):
        x = -a + 2 * a * i / steps
        y = -b * (1.0 - (x / a) ** 2)
        points.append((cx + x, cy + y))
    # 下眼睑：右 -> 左
    for i in range(steps + 1):
        x = a - 2 * a * i / steps
        y = b * (1.0 - (x / a) ** 2)
        points.append((cx + x, cy + y))
    draw.polygon(points, fill=PAPER)

    # 瞳孔
    r = size * 0.105
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=INK)

    # 高光
    hr = size * 0.030
    hx, hy = cx + size * 0.038, cy - size * 0.038
    draw.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=PAPER)

    return img


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    res_root = os.path.join(here, "..", "app", "src", "main", "res")

    for density, size in DENSITIES.items():
        out_dir = os.path.join(res_root, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        icon = make_icon(size)
        path = os.path.join(out_dir, "ic_launcher.png")
        icon.save(path)
        print("wrote", os.path.relpath(path, here), icon.size)

    # 采样验证：别只靠肉眼看缩略图
    probe = make_icon(192)
    print("center  (expect INK  ):", probe.getpixel((96, 96)))
    print("eyewhite(expect PAPER):", probe.getpixel((96, 96 - 26)))
    print("corner  (expect INK  ):", probe.getpixel((10, 10)))

    # 预览拼图
    width = sum(DENSITIES.values()) + 10 * (len(DENSITIES) + 1)
    preview = Image.new("RGBA", (width, 200), (32, 32, 32, 255))
    x = 10
    for size in DENSITIES.values():
        preview.paste(make_icon(size), (x, 100 - size // 2))
        x += size + 10
    preview.save(os.path.join(here, "icon_preview.png"))
    print("preview ->", os.path.join(here, "icon_preview.png"))


if __name__ == "__main__":
    main()
