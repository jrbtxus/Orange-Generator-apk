// SPDX-License-Identifier: MIT
/**
 * 由仓库里已有的品牌贴纸生成安卓图标 / 启动图。
 *
 *   node scripts/generate-android-icons.mjs
 *
 * 输出（全部是生成物，可直接覆盖，不要手改）：
 *   android/app/src/main/res/mipmap-<density>/ic_launcher.png
 *   android/app/src/main/res/mipmap-<density>/ic_launcher_round.png
 *   android/app/src/main/res/mipmap-<density>/ic_launcher_foreground.png
 *   android/app/src/main/res/mipmap-xxxhdpi/playstore_icon.png
 *   android/app/src/main/res/drawable-nodpi/splash_art.png（启动图主视觉）
 *
 * 依赖 python3 + Pillow。
 *
 * 主视觉来源：public/assets/stickers/orange-4.png（站点吉祥物的正面半身像）
 * —— 橙色头发 + 深色描边，在浅蓝底上对比度足够；图标里绝不要用整张带字的
 * 横幅图，缩到 48px 会糊成一团。
 */

import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const RES = path.join(ROOT, "android/app/src/main/res");

const script = `
from PIL import Image, ImageDraw
import os

ROOT = ${JSON.stringify(ROOT)}
RES = ${JSON.stringify(RES)}

# 品牌色：站点 src/styles.css 的 --sky 系列
CENTER = (0xD9, 0xF2, 0xFF)   # 渐变中心（更亮）
MID    = (0x92, 0xD2, 0xF6)   # 主题色 #92d2f6
EDGE   = (0x4F, 0xBE, 0xF2)   # 渐变边缘（更深）

SOURCE = os.path.join(ROOT, 'public/assets/stickers/orange-4.png')

def radial_gradient(size):
    """与 drawable/ic_launcher_background.xml 同一套配色的径向渐变。"""
    base = Image.new('RGB', (size, size), EDGE)
    d = ImageDraw.Draw(base)
    maxr = (2 ** 0.5) * size / 2
    steps = 220
    for i in range(steps, 0, -1):
        t = i / steps
        r = maxr * t
        # 0 -> 中心色，1 -> 边缘色（中间经过主题色）
        if t <= 0.5:
            k = t / 0.5
            col = tuple(round(CENTER[j] + (MID[j] - CENTER[j]) * k) for j in range(3))
        else:
            k = (t - 0.5) / 0.5
            col = tuple(round(MID[j] + (EDGE[j] - MID[j]) * k) for j in range(3))
        d.ellipse((size / 2 - r, size / 2 - r, size / 2 + r, size / 2 + r), fill=col)
    return base

src = Image.open(SOURCE).convert('RGBA')
# 去掉四周全透明的空白，保证图形在图标里真的居中、占满
bbox = src.getbbox()
if bbox:
    src = src.crop(bbox)

DENSITIES = {
    'mdpi': 1.0,
    'hdpi': 1.5,
    'xhdpi': 2.0,
    'xxhdpi': 3.0,
    'xxxhdpi': 4.0,
}

def fit_contain(img, size, pad_ratio=0.0):
    """等比缩放到不超过 size，可选留白，返回居中绘制好的 RGBA 图。"""
    target = int(size * (1.0 - 2 * pad_ratio))
    scale = min(target / img.width, target / img.height)
    new = img.resize(
        (max(1, round(img.width * scale)), max(1, round(img.height * scale))),
        Image.LANCZOS,
    )
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(new, ((size - new.width) // 2, (size - new.height) // 2))
    return canvas

def legacy(dp):
    base = radial_gradient(dp).convert('RGBA')
    base.alpha_composite(fit_contain(src, dp, pad_ratio=0.10))
    return base

def round_icon(dp):
    icon = legacy(dp)
    mask = Image.new('L', (dp * 4, dp * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, dp * 4 - 1, dp * 4 - 1), fill=255)
    mask = mask.resize((dp, dp), Image.LANCZOS)
    out = Image.new('RGBA', (dp, dp), (0, 0, 0, 0))
    out.paste(icon, (0, 0), mask)
    return out

for name, scale in DENSITIES.items():
    d = os.path.join(RES, 'mipmap-' + name)
    os.makedirs(d, exist_ok=True)
    dp48 = round(48 * scale)    # 传统图标 48dp
    dp108 = round(108 * scale)  # 自适应图标 108dp
    legacy(dp48).save(os.path.join(d, 'ic_launcher.png'))
    round_icon(dp48).save(os.path.join(d, 'ic_launcher_round.png'))
    # 自适应图标前景：图形限制在中心 66dp 安全区内（108dp 画布的 61%），
    # 再留一点余量，避免圆形/方形遮罩把头发切掉。
    fit_contain(src, dp108, pad_ratio=0.20).save(
        os.path.join(d, 'ic_launcher_foreground.png')
    )
    print('mipmap-%-8s legacy=%dpx adaptive=%dpx' % (name, dp48, dp108))

# Play 商店用 512x512 图标
playstore = radial_gradient(512).convert('RGBA')
playstore.alpha_composite(fit_contain(src, 512, pad_ratio=0.10))
playstore.convert('RGB').save(os.path.join(RES, 'mipmap-xxxhdpi/playstore_icon.png'))

# 启动图主视觉：固定像素、居中显示（背景交给 drawable/splash.xml 的渐变）
splash_art = fit_contain(src, 360).convert('RGBA')
nodpi = os.path.join(RES, 'drawable-nodpi')
os.makedirs(nodpi, exist_ok=True)
splash_art.save(os.path.join(nodpi, 'splash_art.png'))
print('drawable-nodpi/splash_art.png 360x360（主视觉）')

# 清掉 Capacitor 模板留下的整屏方图（不同屏幕比例下会被裁切/过小）
STALE = [
    'drawable',
    'drawable-port-mdpi', 'drawable-port-hdpi', 'drawable-port-xhdpi',
    'drawable-port-xxhdpi', 'drawable-port-xxxhdpi',
    'drawable-land-mdpi', 'drawable-land-hdpi', 'drawable-land-xhdpi',
    'drawable-land-xxhdpi', 'drawable-land-xxxhdpi',
]
removed = 0
for folder in STALE:
    stale = os.path.join(RES, folder, 'splash.png')
    if os.path.exists(stale):
        os.remove(stale)
        removed += 1
print('清理模板 splash.png: %d 个' % removed)
`;

execFileSync("python3", ["-c", script], { stdio: "inherit" });
