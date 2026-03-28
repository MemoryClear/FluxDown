#!/usr/bin/env python3
"""
gen_macos_icons.py — 从 SVG 生成 macOS AppIcon 所需的全部 PNG 尺寸

用法:
    python3 scripts/gen_macos_icons.py

依赖:
    pip3 install Pillow
    macOS 自带 qlmanage（用于 SVG→PNG 转换）
"""

import os
import subprocess
import sys
import tempfile
from pathlib import Path

SVG_SRC = Path(__file__).parent.parent / "assets/logo/fluxdown_logo.svg"
OUT_DIR = (
    Path(__file__).parent.parent / "macos/Runner/Assets.xcassets/AppIcon.appiconset"
)

SIZES = [16, 32, 64, 128, 256, 512, 1024]


def svg_to_png_via_qlmanage(svg_path: Path, tmp_dir: Path) -> Path:
    """用 macOS qlmanage 把 SVG 渲染成 1024px PNG"""
    result = subprocess.run(
        ["qlmanage", "-t", "-s", "1024", "-o", str(tmp_dir), str(svg_path)],
        capture_output=True,
        text=True,
    )
    # qlmanage 输出文件名是 <原文件名>.png
    out_file = tmp_dir / (svg_path.name + ".png")
    if not out_file.exists():
        print("qlmanage stderr:", result.stderr)
        print("qlmanage stdout:", result.stdout)
        sys.exit(f"错误: qlmanage 未生成文件 {out_file}")
    return out_file


def generate_icons(png_src: Path, out_dir: Path):
    try:
        from PIL import Image
    except ImportError:
        sys.exit("错误: 请先安装 Pillow：pip3 install Pillow")

    img = Image.open(png_src).convert("RGBA")
    print(f"源图尺寸: {img.size[0]}x{img.size[1]}")

    out_dir.mkdir(parents=True, exist_ok=True)

    for s in SIZES:
        resized = img.resize((s, s), Image.LANCZOS)
        dest = out_dir / f"app_icon_{s}.png"
        resized.save(dest, "PNG")
        print(f"  ✓ {dest.name} ({s}x{s})")


def main():
    if not SVG_SRC.exists():
        sys.exit(f"错误: SVG 文件不存在: {SVG_SRC}")

    print(f"SVG 源文件: {SVG_SRC}")
    print(f"输出目录:   {OUT_DIR}")
    print()

    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        print("正在用 qlmanage 渲染 SVG → PNG (1024px)...")
        png_src = svg_to_png_via_qlmanage(SVG_SRC, tmp_dir)
        print(f"中间文件: {png_src}\n")

        print("正在生成各尺寸 PNG...")
        generate_icons(png_src, OUT_DIR)

    print("\n✅ 完成！共生成 {} 个文件。".format(len(SIZES)))
    print("重新构建 Flutter macOS 应用后 Dock 图标即可更新。")


if __name__ == "__main__":
    main()
