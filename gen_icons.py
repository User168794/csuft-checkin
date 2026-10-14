# -*- coding: utf-8 -*-
"""生成「平安打卡」图标：米白底 + 橘色圆角方块 + 白色对勾（简笔画风）"""
import os
from PIL import Image, ImageDraw

PROJ = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(PROJ, "res")

CARD = (250, 246, 238, 255)     # #FAF6EE 米白
ORANGE = (228, 100, 47, 255)    # #E4642F 橘
WHITE = (255, 253, 247, 255)    # #FFFDF7


def _check(d, box, size, color, lw):
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    d.line([(x0 + w * 0.26, y0 + h * 0.52),
            (x0 + w * 0.44, y0 + h * 0.70),
            (x0 + w * 0.76, y0 + h * 0.30)], fill=color, width=lw, joint="curve")


def draw_badge(size):
    """橘色圆角方块 + 白勾（画在整幅 canvas 上，占 ~66%，留自适应安全边距）"""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    pad = size * 0.17
    box = (pad, pad, size - pad, size - pad)
    d.rounded_rectangle(box, radius=(size - 2 * pad) * 0.22, fill=ORANGE)
    _check(d, box, size, WHITE, max(2, int(size * 0.065)))
    return im


def draw_legacy(size):
    """传统图标：米白方块底 + 居中的橘色徽章"""
    im = Image.new("RGBA", (size, size), CARD)
    badge = draw_badge(int(size * 1.0))
    im.alpha_composite(badge)
    return im


def main():
    legacy_sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    fg_sizes = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    for dpi, size in legacy_sizes.items():
        d = os.path.join(RES, "mipmap-" + dpi)
        os.makedirs(d, exist_ok=True)
        draw_legacy(size).save(os.path.join(d, "ic_launcher.png"))
    for dpi, size in fg_sizes.items():
        d = os.path.join(RES, "mipmap-" + dpi)
        draw_badge(size).save(os.path.join(d, "ic_launcher_foreground.png"))
    prev = os.path.join(PROJ, "build", "icon_preview.png")
    os.makedirs(os.path.dirname(prev), exist_ok=True)
    draw_legacy(192).save(prev)
    print("图标已生成 →", RES, "| 预览:", prev)


if __name__ == "__main__":
    main()
