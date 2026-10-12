# -*- coding: utf-8 -*-
"""零依赖生成连点器启动图标：简约和谐绿+白 —— 浅绿白圆角底 + 细圆环 + 中心点。纯标准库(zlib/struct)。"""
import zlib, struct, os

def chunk(tag, data):
    c = struct.pack('>I', len(data)) + tag + data
    c += struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    return c

def write_png(path, w, h, pixels):
    raw = b''
    for y in range(h):
        raw += b'\x00'
        for x in range(w):
            r, g, b, a = pixels[y][x]
            raw += bytes((int(r), int(g), int(b), int(a)))
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)

S = 3  # 3x3 超采样抗锯齿

GREEN_R, GREEN_G, GREEN_B = 53, 199, 90   # #35C75A 柔和绿

def col(u, v):
    """归一化坐标 -> (r,g,b,a 0..255)"""
    # 圆角方形底(圆角22%)：浅绿白 #F0FBF0
    rr, rad = 0.96, 0.22
    dx = max(abs(u) - (rr - rad), 0.0)
    dy = max(abs(v) - (rr - rad), 0.0)
    edge = rad - (dx * dx + dy * dy) ** 0.5
    if edge <= 0.015:
        return (0, 0, 0, 0)
    alpha = min(1.0, edge / 0.03)
    r, g, b = 240, 251, 240
    d = (u * u + v * v) ** 0.5
    # 绿色细环（外径 0.46 / 内径 0.30，抗锯齿由超采样完成）
    if 0.30 < d < 0.46:
        r, g, b = GREEN_R, GREEN_G, GREEN_B
    # 中心点
    if d < 0.135:
        r, g, b = GREEN_R, GREEN_G, GREEN_B
    return (r, g, b, int(alpha * 255))

def render(size):
    px = [[(0, 0, 0, 0)] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            ar = ag = ab = aa = 0.0
            n = 0
            for i in range(S):
                for j in range(S):
                    u = (x + (i + 0.5) / S) / size * 2 - 1
                    v = (y + (j + 0.5) / S) / size * 2 - 1
                    r, g, b, a = col(u, v)
                    if a > 0:
                        ar += r * a / 255.0; ag += g * a / 255.0; ab += b * a / 255.0
                        aa += a; n += 1
            if n == 0:
                continue
            a = aa / n
            if a > 0:
                den = a * n
                c = (ar * 255.0 / den, ag * 255.0 / den, ab * 255.0 / den, a)
                if not all(0 <= t < 256 for t in c):
                    print('BAD', x, y, c, 'ar,aa,n=', ar, aa, n)
                px[y][x] = c
    return px

DENS = {'mipmap-mdpi': 48, 'mipmap-hdpi': 72, 'mipmap-xhdpi': 96,
        'mipmap-xxhdpi': 144, 'mipmap-xxxhdpi': 192}
BASE = os.path.dirname(os.path.abspath(__file__))
for d, s in DENS.items():
    out = os.path.join(BASE, 'app', 'src', 'main', 'res', d)
    os.makedirs(out, exist_ok=True)
    write_png(os.path.join(out, 'ic_launcher.png'), s, s, render(s))
    print('wrote', d, s)
print('OK')