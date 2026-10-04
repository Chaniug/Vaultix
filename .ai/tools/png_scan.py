#!/usr/bin/env python3
"""无依赖 PNG 像素取证：判断「浮层是否遮住内容」。

背景（2026-10-04 教训）：只量**纵向**颜色变化能证明「有没有几何重叠」，
但证明不了「边界看不看得出来」—— 若浮层底色与页面背景**同值**，
间隙在像素层面属于同一块颜色，人眼读不出边界，观感就是「遮住」。
⇒ 必须**双向扫描**。

用法（纯 stdlib，不需要 Pillow / numpy）：
    python png_scan.py shot.png --col 628 --range 0:760   # 纵向：浮层/内容边界
    python png_scan.py shot.png --row 520 --range 0:1256  # 横向：间隙是否同色
    python png_scan.py shot.png --auto                    # 双向都扫一遍

⚠️ 坐标一律用**设备像素**。换算 dp：先量已知尺寸的元素反推 density
   （本项目实测：卡片宽 1139px / 328dp ≈ 3.47）。
"""
import sys
import zlib
import struct


def load(path):
    """解码 PNG 为 (w, h, bpp, pixels)。支持 8bit RGB / RGBA。"""
    data = open(path, 'rb').read()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise SystemExit(f'不是 PNG 文件: {path}')
    pos, idat, w, h, ct = 8, b'', 0, 0, 6
    while pos < len(data):
        length = struct.unpack('>I', data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b'IHDR':
            w, h, _bitdepth, ct = struct.unpack('>IIBB', chunk[:10])
        elif ctype == b'IDAT':
            idat += chunk
        elif ctype == b'IEND':
            break
        pos += 12 + length
    if _bitdepth != 8:
        raise SystemExit('只支持 8bit 位深（截图默认即是）')
    bpp = 4 if ct == 6 else 3
    if ct not in (2, 6):
        raise SystemExit(f'只支持 TrueColor / RGBA，实际 colorType={ct}')
    raw = zlib.decompress(idat)
    stride = w * bpp
    out = bytearray()
    prev = bytearray(stride)
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        if f:  # 0 = None，省掉整行循环
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                if f == 1:
                    line[i] = (line[i] + a) & 255
                elif f == 2:
                    line[i] = (line[i] + b) & 255
                elif f == 3:
                    line[i] = (line[i] + (a + b) // 2) & 255
                else:  # f == 4, Paeth
                    pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                    pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                    line[i] = (line[i] + pred) & 255
        out += line
        prev = line
    return w, h, bpp, bytes(out)


def scan(path, axis, fixed, lo, hi):
    w, h, bpp, px = load(path)
    limit = h if axis == 'col' else w
    fixed = max(0, min(fixed, (h if axis == 'col' else w) - 1))
    if lo >= hi:
        raise SystemExit(f'--range 必须 lo < hi（现在 {lo} >= {hi}）')
    hi = min(hi, limit)

    def at(i):
        x, y = (fixed, i) if axis == 'col' else (i, fixed)
        s = y * w * bpp + x * bpp
        return tuple(px[s:s + 3])

    label = f'{axis}={fixed}'
    print(f'--- {path}  {label}  范围 {lo}..{hi} ---')
    prev, start, changes = None, lo, []
    for i in range(lo, hi):
        v = at(i)
        if v != prev:
            if prev is not None:
                changes.append((start, i, prev))
            start, prev = i, v
    changes.append((start, hi, prev))

    for s, e, c in changes:
        span = e - s
        note = ''
        if span >= 8:
            note = f'   <-- 连续 {span}px 同色（{span / 3.47:.1f}dp @density3.47）'
        print(f'{s:5d}..{e:5d}  rgb{c}{note}')
    distinct = {c for _, _, c in changes}
    print(f'\n共 {len(changes)} 段 / {len(distinct)} 种颜色')
    if len(distinct) == 1:
        print('★ 全段同色 ⇒ 这一带没有任何可见边界（若此处是浮层与内容的间隙，'
              '则观感必然是「遮住」）')
    return changes


def main():
    args = sys.argv[1:]
    if not args or args[0] in ('-h', '--help'):
        print(__doc__)
        return
    path = args[0]
    opts, auto = {}, True
    for i, a in enumerate(args):
        if a == '--col':
            opts['axis'], opts['fixed'] = 'col', int(args[i + 1])
        elif a == '--row':
            opts['axis'], opts['fixed'] = 'row', int(args[i + 1])
    if 'axis' in opts:
        auto = False
    if auto:
        w, h, _b, _p = load(path)
        print(f'图片 {w}x{h} —— 自动双向扫描中点\n')
        scan(path, 'col', w // 2, 0, min(h, 900))
        print()
        scan(path, 'row', h // 4, 0, w)
    else:
        lo, hi = 0, 10 ** 9
        for i, a in enumerate(args):
            if a == '--range':
                lo, hi = (int(v) for v in args[i + 1].split(':'))
        scan(path, opts['axis'], opts['fixed'], lo, hi)


if __name__ == '__main__':
    main()
