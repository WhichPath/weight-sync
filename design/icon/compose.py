#!/usr/bin/env python3
"""阿福同步 · 应用图标组合脚本（V7 定稿）

输入：同目录下的 4 个源 SVG
      afu.svg            蚂蚁阿福（程序化描摹，512 画布）
      healthconnect.svg  Health Connect（程序化描摹，512 画布）
      garmin.svg         Garmin 三角（官方 SVG）
      trident.svg        三叉同步标（Tabler Icons arrows-split-2，官方 SVG）

输出：
      ic_launcher_foreground.svg   组合后的矢量（预览基准）
      ic_launcher_foreground.xml   → app/src/main/res/drawable/
      ic_launcher_background.xml   → app/src/main/res/drawable/
      ic_launcher_monochrome.xml   → app/src/main/res/drawable/
      mipmap-*/ic_launcher(.round).png 各密度 PNG 兜底

依赖：rsvg-convert、python3-pil、python3-numpy（仅本机生成用，不进 APK）
"""
import math
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_RES = os.path.join(HERE, "out")
CANVAS = 108.0

# ----------------------------------------------------------------- 布局参数（V7 定稿）
LAYOUT = {
    # 元素: (中心x, 中心y, 旋转°, 边长)   旋转以元素自身中心为基点
    "afu":           (27.0, 27.0, -45.0, 50.0),
    "garmin":       (103.2, 36.1,  70.0, 50.0),
    "healthconnect": (56.8, 90.0,  -4.4, 50.0),
}
TRI_SCALE = 4.0          # 三叉放大倍数
TRI_ALPHA = 0.2          # 三叉不透明度
TRI_ROT = -45.0          # 三叉朝向（与阿福 y 轴重合）
BG_COLOR = "#00897B"     # App 主绿 MintAccentGreen


# ================================================================ 路径工具
NUMRE = re.compile(r'-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')
ARGS = {"M": 2, "L": 2, "T": 2, "S": 4, "Q": 4, "C": 6, "A": 7,
        "m": 2, "l": 2, "t": 2, "s": 4, "q": 4, "c": 6, "a": 7,
        "H": 1, "h": 1, "V": 1, "v": 1, "Z": 0, "z": 0}


def parse_path(d):
    """SVG path → [(cmd, [params])]"""
    items = []
    for m in re.finditer(r'([MmLlHhVvCcSsQqTtAaZz])|(-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)', d):
        items.append(('cmd', m.group(1)) if m.group(1) else ('num', float(m.group(2))))
    segs, i, cmd = [], 0, None
    while i < len(items):
        kind, val = items[i]
        if kind == 'cmd':
            cmd = val
            i += 1
            if cmd in 'Zz':
                segs.append((cmd, []))
                cmd = None
                continue
            if cmd not in ARGS:
                raise ValueError('bad cmd ' + cmd)
        if cmd is None:
            raise ValueError('numbers before command')
        need = ARGS[cmd]
        nums = []
        while len(nums) < need and i < len(items) and items[i][0] == 'num':
            nums.append(items[i][1])
            i += 1
        if len(nums) < need:
            break
        segs.append((cmd, nums))
    fixed = []
    for c, ns in segs:
        if c in 'Mm' and len(ns) > 2:
            fixed.append((c, ns[:2]))
            lc = 'L' if c == 'M' else 'l'
            for k in range(2, len(ns), 2):
                fixed.append((lc, ns[k:k + 2]))
        else:
            fixed.append((c, ns))
    return fixed


def path_to_str(segs, prec=4):
    out = []
    for cmd, p in segs:
        if cmd in 'Zz':
            out.append('Z')
        else:
            out.append(cmd + " ".join(f"{v:.{prec}f}".rstrip('0').rstrip('.') for v in p))
    return " ".join(out)


def affine(tx, ty, rot_deg=0.0, s=1.0, sy=None, anchor=(0.0, 0.0)):
    """X' = T(target)·R·S·T(-anchor) → (a,b,c,d,e,f)"""
    sy = s if sy is None else sy
    r = math.radians(rot_deg)
    a, b = math.cos(r) * s, math.sin(r) * s
    c, d = -math.sin(r) * sy, math.cos(r) * sy
    e = tx - (a * anchor[0] + c * anchor[1])
    f = ty - (b * anchor[0] + d * anchor[1])
    return (a, b, c, d, e, f)


def transform_path(segs, a, b, c, d, e, f):
    """把仿射烘焙进 pathData（相对命令输出相对增量，H/V 在 90° 旋转时互换）"""
    def tp(x, y):
        return (a * x + c * y + e, b * x + d * y + f)

    def lin(dx, dy):
        return (a * dx + c * dy, b * dx + d * dy)

    out, cur, start = [], (0.0, 0.0), (0.0, 0.0)
    last_c2 = last_q = None
    for cmd, p in segs:
        u = cmd.upper()
        if u == 'M':
            x, y = (cur[0] + p[0], cur[1] + p[1]) if cmd.islower() else (p[0], p[1])
            out.append((cmd, list(lin(p[0], p[1])) if cmd.islower() else list(tp(x, y))))
            cur = start = (x, y)
            last_c2 = last_q = None
        elif u == 'L':
            x, y = (cur[0] + p[0], cur[1] + p[1]) if cmd.islower() else (p[0], p[1])
            out.append((cmd, list(lin(p[0], p[1])) if cmd.islower() else list(tp(x, y))))
            cur = (x, y)
            last_c2 = last_q = None
        elif u == 'H':
            if abs(a) < 1e-9 and abs(b) > 1e-9:
                out.append(('v', [lin(p[0], 0)[1]] if cmd.islower() else [tp(p[0], cur[1])[1]]))
                cur = (cur[0] + p[0], cur[1]) if cmd.islower() else (p[0], cur[1])
            else:
                out.append((cmd, [lin(p[0], 0)[0]] if cmd.islower() else [tp(p[0], cur[1])[0]]))
                cur = (cur[0] + p[0], cur[1]) if cmd.islower() else (p[0], cur[1])
            last_c2 = last_q = None
        elif u == 'V':
            if abs(d) < 1e-9 and abs(c) > 1e-9:
                out.append(('h', [lin(0, p[0])[0]] if cmd.islower() else [tp(cur[0], p[0])[0]]))
                cur = (cur[0], cur[1] + p[0]) if cmd.islower() else (cur[0], p[0])
            else:
                out.append((cmd, [lin(0, p[0])[1]] if cmd.islower() else [tp(cur[0], p[0])[1]]))
                cur = (cur[0], cur[1] + p[0]) if cmd.islower() else (cur[0], p[0])
            last_c2 = last_q = None
        elif u == 'C':
            pts = [(cur[0] + p[k], cur[1] + p[k + 1]) if cmd.islower() else (p[k], p[k + 1])
                   for k in (0, 2, 4)]
            if cmd.islower():
                dd = [lin(pts[0][0] - cur[0], pts[0][1] - cur[1]),
                      lin(pts[1][0] - cur[0], pts[1][1] - cur[1]),
                      lin(pts[2][0] - cur[0], pts[2][1] - cur[1])]
                out.append((cmd, [v for q in dd for v in q]))
                cur = pts[-1]
                last_c2 = (cur[0] - p[4] + p[2], cur[1] - p[5] + p[3])
            else:
                out.append((cmd, [v for q in [tp(*q) for q in pts] for v in q]))
                cur = pts[-1]
                last_c2 = pts[-2]
            last_q = None
        elif u == 'S':
            pts = [(cur[0] + p[k], cur[1] + p[k + 1]) if cmd.islower() else (p[k], p[k + 1])
                   for k in (0, 2)]
            c1 = (2 * cur[0] - last_c2[0], 2 * cur[1] - last_c2[1]) if last_c2 else cur
            if cmd.islower():
                dd = [lin(c1[0] - cur[0], c1[1] - cur[1]),
                      lin(pts[0][0] - cur[0], pts[0][1] - cur[1]),
                      lin(pts[1][0] - cur[0], pts[1][1] - cur[1])]
                out.append((cmd, [v for q in dd for v in q]))
                cur = pts[-1]
                last_c2 = (cur[0] - p[2] + p[0], cur[1] - p[3] + p[1])
            else:
                out.append((cmd, [v for q in [tp(*c1), tp(*pts[0]), tp(*pts[1])] for v in q]))
                cur = pts[-1]
                last_c2 = pts[-2]
            last_q = None
        elif u == 'Q':
            pts = [(cur[0] + p[k], cur[1] + p[k + 1]) if cmd.islower() else (p[k], p[k + 1])
                   for k in (0, 2)]
            if cmd.islower():
                dd = [lin(pts[0][0] - cur[0], pts[0][1] - cur[1]),
                      lin(pts[1][0] - cur[0], pts[1][1] - cur[1])]
                out.append((cmd, [v for q in dd for v in q]))
                cur = pts[-1]
                last_q = (cur[0] - p[2] + p[0], cur[1] - p[3] + p[1])
            else:
                out.append((cmd, [v for q in [tp(*pts[0]), tp(*pts[1])] for v in q]))
                cur = pts[-1]
                last_q = pts[0]
            last_c2 = None
        elif u == 'T':
            x, y = (cur[0] + p[0], cur[1] + p[1]) if cmd.islower() else (p[0], p[1])
            q = (2 * cur[0] - last_q[0], 2 * cur[1] - last_q[1]) if last_q else cur
            if cmd.islower():
                dd = [lin(q[0] - cur[0], q[1] - cur[1]), lin(p[0], p[1])]
                out.append((cmd, [v for pp in dd for v in pp]))
                cur = (cur[0] + p[0], cur[1] + p[1])
                last_q = q
            else:
                out.append((cmd, [v for pp in [tp(*q), tp(x, y)] for v in pp]))
                cur = (x, y)
                last_q = q
            last_c2 = None
        elif u == 'A':
            x, y = (cur[0] + p[5], cur[1] + p[6]) if cmd.islower() else (p[5], p[6])
            det = abs(a * d - b * c)
            sc = math.sqrt(det) if det > 1e-12 else 1.0
            phi = p[2] + math.degrees(math.atan2(b, a))
            if cmd.islower():
                dx, dy = lin(p[5], p[6])
                out.append((cmd, [p[0] * sc, p[1] * sc, phi, p[3], p[4], dx, dy]))
            else:
                out.append((cmd, [p[0] * sc, p[1] * sc, phi, p[3], p[4], tp(x, y)[0], tp(x, y)[1]]))
            cur = (x, y)
            last_c2 = last_q = None
        elif u == 'Z':
            out.append((cmd, []))
            cur = start
            last_c2 = last_q = None
    return out


def control_bbox(paths):
    """控制点粗框（仅用于定 viewBox）；必须正确处理相对命令，否则会得到垃圾框"""
    xs, ys = [], []
    for d in paths:
        cur = [0.0, 0.0]
        start = [0.0, 0.0]
        for cmd, p in parse_path(d):
            u = cmd.upper()
            rel = cmd.islower()
            if u in 'ML':
                cur = [cur[0] + p[0], cur[1] + p[1]] if rel else [p[0], p[1]]
                xs.append(cur[0]); ys.append(cur[1])
                if u == 'M':
                    start = list(cur)
            elif u == 'H':
                cur[0] = cur[0] + p[0] if rel else p[0]
                xs.append(cur[0]); ys.append(cur[1])
            elif u == 'V':
                cur[1] = cur[1] + p[0] if rel else p[0]
                xs.append(cur[0]); ys.append(cur[1])
            elif u in 'CSQT':
                for k in range(0, len(p), 2):
                    x = cur[0] + p[k] if rel else p[k]
                    y = cur[1] + p[k + 1] if rel else p[k + 1]
                    xs.append(x); ys.append(y)
                cur = [xs[-1], ys[-1]]
            elif u == 'A':
                x = cur[0] + p[5] if rel else p[5]
                y = cur[1] + p[6] if rel else p[6]
                cur = [x, y]
                xs.append(x); ys.append(y)
            elif u == 'Z':
                cur = list(start)
    if not xs:
        return (0.0, 0.0, 0.0, 0.0)
    return min(xs), min(ys), max(xs), max(ys)


def true_bbox(paths, stroke=False, sw=0.0):
    """真实紧致外接框：光栅化实测（贝塞尔控制点会超出曲线，不能直接用）"""
    from PIL import Image
    import numpy as np
    x0, y0, x1, y1 = control_bbox(paths)
    pad = max(x1 - x0, y1 - y0) * 0.02 + 1
    vx, vy, vw, vh = x0 - pad, y0 - pad, (x1 - x0) + 2 * pad, (y1 - y0) + 2 * pad
    body = "".join(
        (f'<path d="{d}" fill="none" stroke="#000" stroke-width="{sw}" stroke-linecap="round" '
         f'stroke-linejoin="round"/>' if stroke else
         f'<path d="{d}" fill="#000" fill-rule="evenodd"/>') for d in paths)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{vw:.3f}" height="{vh:.3f}" '
           f'viewBox="{vx:.3f} {vy:.3f} {vw:.3f} {vh:.3f}">{body}</svg>')
    tmp = os.path.join(OUT_RES, "_bb.svg")
    open(tmp, "w").write(svg)
    subprocess.run(["rsvg-convert", "-w", "1000", "-h", "1000", tmp,
                    "-o", os.path.join(OUT_RES, "_bb.png")], check=True)
    a = np.array(Image.open(os.path.join(OUT_RES, "_bb.png")).convert("RGBA"))[:, :, 3]
    ys, xs = np.nonzero(a > 8)
    fx = vw / 1000.0
    return (vx + xs.min() * fx, vy + ys.min() * fx, vx + (xs.max() + 1) * fx, vy + (ys.max() + 1) * fx)


# ================================================================ SVG 读取
def read_svg(path):
    svg = open(path).read()
    paths = []
    for m in re.finditer(r'<path\b([^>]*?)/?>', svg, re.S):
        a = m.group(1)
        d = re.search(r'\sd="([^"]+)"', a)
        if d:
            f = re.search(r'\sfill="([^"]+)"', a)
            paths.append((d.group(1), f.group(1) if f else None))
    dm = re.search(r'<defs>.*?</defs>', svg, re.S)
    return paths, (dm.group(0) if dm else "")


def normalize(paths, defs=""):
    """归一化到 1x1 单位方框：最大边=1，包围框居中（三个 logo 视觉大小一致）"""
    bb = true_bbox([d for d, _ in paths])
    w, h = bb[2] - bb[0], bb[3] - bb[1]
    s = 1.0 / max(w, h)
    cx, cy = (bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2
    m = affine(0.5, 0.5, 0.0, s, anchor=(cx, cy))
    out = [(path_to_str(transform_path(parse_path(d), *m), prec=5), f) for d, f in paths]
    if defs:                       # 渐变坐标同步变换
        defs = re.sub(r'(x1|y1|x2|y2)="([-\d.]+)"',
                      lambda mm: '%s="%.5f"' % (mm.group(1),
                                                float(mm.group(2)) * s + (m[4] if mm.group(1)[0] == 'x' else m[5])),
                      defs)
    return out, defs


def arc_to_cubics(x0, y0, rx, ry, phi_deg, large, sweep, x1, y1):
    """按 SVG 规范把椭圆弧精确转成若干三次贝塞尔（≤90° 一段）"""
    phi = math.radians(phi_deg)
    dx2, dy2 = (x0 - x1) / 2.0, (y0 - y1) / 2.0
    x1p = math.cos(phi) * dx2 + math.sin(phi) * dy2
    y1p = -math.sin(phi) * dx2 + math.cos(phi) * dy2
    rx, ry = abs(rx), abs(ry)
    if rx < 1e-12 or ry < 1e-12:
        return [((x0, y0), (x1, y1), (x1, y1))]
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx *= math.sqrt(lam); ry *= math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = (1 if large != sweep else -1) * math.sqrt(max(0.0, num / den)) if den > 1e-12 else 0.0
    cxp, cyp = co * rx * y1p / ry, -co * ry * x1p / rx
    cx = math.cos(phi) * cxp - math.sin(phi) * cyp + (x0 + x1) / 2.0
    cy = math.sin(phi) * cxp + math.cos(phi) * cyp + (y0 + y1) / 2.0

    def ang(ux, uy, vx, vy):
        d = (ux * vx + uy * vy) / (math.hypot(ux, uy) * math.hypot(vx, vy) + 1e-12)
        a = math.acos(max(-1.0, min(1.0, d)))
        return -a if ux * vy - uy * vx < 0 else a

    ux, uy = (x1p - cxp) / rx, (y1p - cyp) / ry
    vx, vy = (-x1p - cxp) / rx, (-y1p - cyp) / ry
    t1 = ang(1, 0, ux, uy)
    dt = ang(ux, uy, vx, vy)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(1, int(math.ceil(abs(dt) / (math.pi / 2) - 1e-9)))
    seg = dt / n
    k = 4.0 / 3.0 * math.tan(seg / 4.0)

    def pt(t):
        return (cx + rx * math.cos(t) * math.cos(phi) - ry * math.sin(t) * math.sin(phi),
                cy + rx * math.cos(t) * math.sin(phi) + ry * math.sin(t) * math.cos(phi))

    def dv(t):
        return (-rx * math.sin(t) * math.cos(phi) - ry * math.cos(t) * math.sin(phi),
                -rx * math.sin(t) * math.sin(phi) + ry * math.cos(t) * math.cos(phi))

    out = []
    for i in range(n):
        a1, a2 = t1 + i * seg, t1 + (i + 1) * seg
        p1, p2 = pt(a1), pt(a2)
        d1, d2 = dv(a1), dv(a2)
        out.append(((p1[0] + k * d1[0], p1[1] + k * d1[1]),
                    (p2[0] - k * d2[0], p2[1] - k * d2[1]), p2))
    return out


def arcs_to_cubics(paths):
    """把所有弧线命令展开成三次贝塞尔，后续变换即为纯代数、无歧义"""
    out = []
    for d in paths:
        segs = parse_path(d)
        cur = [0.0, 0.0]
        start = [0.0, 0.0]
        res = []
        for cmd, p in segs:
            u = cmd.upper()
            rel = cmd.islower()
            if u == 'A':
                x = cur[0] + p[5] if rel else p[5]
                y = cur[1] + p[6] if rel else p[6]
                for (c1, c2, pe) in arc_to_cubics(cur[0], cur[1], p[0], p[1], p[2], p[3], p[4], x, y):
                    res.append(('C', [c1[0], c1[1], c2[0], c2[1], pe[0], pe[1]]))
                cur = [x, y]
            elif u == 'Z':
                res.append(('Z', []))
                cur = list(start)
            else:
                if u in 'ML':
                    cur = [cur[0] + p[0], cur[1] + p[1]] if rel else [p[0], p[1]]
                    if u == 'M':
                        start = list(cur)
                elif u == 'H':
                    cur[0] = cur[0] + p[0] if rel else p[0]
                elif u == 'V':
                    cur[1] = cur[1] + p[0] if rel else p[0]
                elif u in 'CSQT':
                    cur = [cur[0] + p[-2], cur[1] + p[-1]] if rel else [p[-2], p[-1]]
                res.append((cmd, list(p)))
        out.append(path_to_str(res, prec=3))
    return out


def matrix_str(m):
    return "matrix(%.6f,%.6f,%.6f,%.6f,%.4f,%.4f)" % m


# ================================================================ 主流程
def build():
    os.makedirs(OUT_RES, exist_ok=True)
    layers = []
    for key in ("afu", "healthconnect", "garmin"):
        paths, defs = read_svg(os.path.join(HERE, key + ".svg"))
        paths, defs = normalize(paths, defs)
        cx, cy, rot, size = LAYOUT[key]
        m = affine(cx, cy, rot, size, anchor=(0.5, 0.5))
        layers.append((key, paths, defs, m))

    # 三叉：整体引用官方字形，只做 group 变换（旋转/缩放/平移），不改写 pathData，
    #       这样弧线由渲染器原生处理，绝不走样。
    #       净朝向 = 官方原朝向 +90°（直杆朝上）+ TRI_ROT（与阿福 y 轴同线）
    tri_paths, _ = read_svg(os.path.join(HERE, "trident.svg"))
    tri_paths = [d for d, _ in tri_paths]
    cb = control_bbox(tri_paths)
    c0 = ((cb[0] + cb[2]) / 2, (cb[1] + cb[3]) / 2)      # 自身十字中心 = 旋转/缩放基点
    net_rot = 90.0 + TRI_ROT
    from PIL import Image
    import numpy as np

    def tri_group(target):
        """生成三叉的 group 变换字符串（相对 24 网格视口）"""
        return ('transform="translate(%.4f,%.4f) translate(%.4f,%.4f) rotate(%.4f) '
                'scale(%.4f) translate(%.4f,%.4f)"'
                % (target[0] - c0[0], target[1] - c0[1], c0[0], c0[1],
                   net_rot, TRI_SCALE, -c0[0], -c0[1]))

    def measure(target):
        frag = ('<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">'
                '<g %s fill="none" stroke="#000" stroke-width="2" stroke-linecap="round" '
                'stroke-linejoin="round">%s</g></svg>'
                % (tri_group(target), "".join('<path d="%s"/>' % d for d in tri_paths)))
        p = os.path.join(OUT_RES, "_probe")
        open(p + ".svg", "w").write(frag)
        subprocess.run(["rsvg-convert", "-w", "1080", "-h", "1080", p + ".svg", "-o", p + ".png"],
                       check=True)
        al = np.array(Image.open(p + ".png").convert("RGBA"))[:, :, 3]
        ys, xs = np.nonzero(al > 8)
        os.remove(p + ".svg"); os.remove(p + ".png")
        return (xs.min() / 10.0, ys.min() / 10.0, (xs.max() + 1) / 10.0, (ys.max() + 1) / 10.0)

    # 迭代把三叉的"真实外接框中心"对齐到画布中心
    target = [CANVAS / 2, CANVAS / 2]
    for _ in range(6):
        fb = measure(target)
        dx = CANVAS / 2 - (fb[0] + fb[2]) / 2
        dy = CANVAS / 2 - (fb[1] + fb[3]) / 2
        target[0] += dx
        target[1] += dy
        if abs(dx) < 0.1 and abs(dy) < 0.1:
            break
    fb = measure(target)
    tri_group_attr = tri_group(target)
    tri_target = tuple(target)
    print("  三叉：外接框 %.1fx%.1f，中心 (%.2f,%.2f)（画布中心 54,54）"
          % (fb[2] - fb[0], fb[3] - fb[1], (fb[0] + fb[2]) / 2, (fb[1] + fb[3]) / 2))

    # ---- 组合 SVG（预览基准）
    defs, body = [], []
    for key, paths, gd, m in layers:
        if gd:
            defs.append(gd)
        body.append('<g transform="%s">%s</g>' % (
            matrix_str(m),
            "".join('<path d="%s" fill="%s" fill-rule="evenodd"/>' % (d, f) for d, f in paths)))
    body.insert(0, '<g %s fill="none" stroke="#FFFFFF" stroke-width="2" stroke-opacity="%s" '
                    'stroke-linecap="round" stroke-linejoin="round">%s</g>'
                % (tri_group_attr, TRI_ALPHA,
                   "".join('<path d="%s"/>' % d for d in tri_paths)))
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">'
           '<defs>%s</defs>%s</svg>' % ("".join(defs), "".join(body)))
    open(os.path.join(OUT_RES, "ic_launcher_foreground.svg"), "w").write(svg)

    # ---- VectorDrawable（变换烘焙进 pathData，避免 group 变换语义差异）
    NS = ('xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    xmlns:aapt="http://schemas.android.com/aapt"')
    vd = []
    # 三叉在最下层（与 SVG 图层顺序一致），合并为单 <path> 避免描边交叉处 alpha 叠加
    vd.append('<group\n            android:pivotX="%.4f"\n            android:pivotY="%.4f"\n'
              '            android:rotation="%.4f"\n            android:scaleX="%.4f"\n'
              '            android:scaleY="%.4f"\n            android:translateX="%.4f"\n'
              '            android:translateY="%.4f">\n'
              '        <path\n            android:pathData="%s"\n'
              '            android:strokeColor="#FFFFFF"\n            android:strokeWidth="2"\n'
              '            android:strokeAlpha="%s"\n            android:strokeLineCap="round"\n'
              '            android:strokeLineJoin="round"/>\n        </group>'
              % (c0[0], c0[1], net_rot, TRI_SCALE, TRI_SCALE,
                 tri_target[0] - c0[0], tri_target[1] - c0[1],
                 " ".join(tri_paths), TRI_ALPHA))

    def vd_path(d, fill=None, grad=None, alpha=None, rule="evenOdd"):
        r = '\n            android:fillType="%s"' % rule
        a = '\n            android:fillAlpha="%s"' % alpha if alpha is not None else ''
        if grad:
            g = ('<aapt:attr name="android:fillColor">\n            <gradient\n'
                 '                android:type="linear"\n'
                 '                android:startX="%.3f"\n                android:startY="%.3f"\n'
                 '                android:endX="%.3f"\n                android:endY="%.3f">\n' % grad[:4]
                 + "".join('                <item android:offset="%s" android:color="%s"/>\n' % (o, c)
                           for o, c in grad[4:]) + '            </gradient>\n        </aapt:attr>')
            return '<path\n            android:pathData="%s"%s%s>\n            %s\n        </path>' % (d, r, a, g)
        return ('<path\n            android:pathData="%s"\n            android:fillColor="%s"%s%s/>'
                % (d, fill, r, a))

    for key, paths, gd, m in layers:
        for d, f in paths:
            segs = parse_path(d)
            baked = path_to_str(transform_path(segs, *m), prec=4)
            if f and f.startswith("url(#"):
                gid = re.search(r'url\(#([^)]+)\)', f).group(1)
                gm = re.search(r'<linearGradient[^>]*id="%s"[^>]*' % gid, gd)
                xs = [float(v) for v in re.findall(r'(?:x1|x2)="([-\d.]+)"', gm.group(0))]
                ys = [float(v) for v in re.findall(r'(?:y1|y2)="([-\d.]+)"', gm.group(0))]
                stops = re.findall(r'<stop offset="([^"]+)" stop-color="([^"]+)"', gd)
                p1 = (m[0] * xs[0] + m[2] * ys[0] + m[4], m[1] * xs[0] + m[3] * ys[0] + m[5])
                p2 = (m[0] * xs[1] + m[2] * ys[1] + m[4], m[1] * xs[1] + m[3] * ys[1] + m[5])
                hexes = []
                for off, sc in stops:
                    n = sc[sc.index('(') + 1:sc.index(')')].split(',')
                    hexes.append((off, "#%02X%02X%02X" % tuple(int(float(v)) for v in n[:3])))
                vd.append(vd_path(baked, grad=(p1[0], p1[1], p2[0], p2[1], *hexes)))
            else:
                vd.append(vd_path(baked, fill=f))
    fg = ('<vector %s\n    android:width="108dp"\n    android:height="108dp"\n'
          '    android:viewportWidth="108"\n    android:viewportHeight="108">\n%s\n</vector>\n'
          % (NS, "\n".join(vd)))
    open(os.path.join(OUT_RES, "ic_launcher_foreground.xml"), "w").write(fg)

    # ---- 背景层（纯色 App 主绿）
    bg = ('<vector %s\n    android:width="108dp"\n    android:height="108dp"\n'
          '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
          '    <path android:pathData="M0,0h108v108h-108z" android:fillColor="%s"/>\n</vector>\n'
          % (NS, BG_COLOR))
    open(os.path.join(OUT_RES, "ic_launcher_background.xml"), "w").write(bg)

    # ---- 单色层（主题图标）：三个 logo 实心剪影
    mono = []
    for key, paths, gd, m in layers:
        for d, f in paths:
            segs = parse_path(d)
            baked = path_to_str(transform_path(segs, *m), prec=4)
            mono.append(vd_path(baked, fill="#000000"))
    mo = ('<vector %s\n    android:width="108dp"\n    android:height="108dp"\n'
          '    android:viewportWidth="108"\n    android:viewportHeight="108">\n%s\n</vector>\n'
          % (NS, "\n".join(mono)))
    open(os.path.join(OUT_RES, "ic_launcher_monochrome.xml"), "w").write(mo)
    print("已生成：", ", ".join(sorted(os.listdir(OUT_RES))))


def make_pngs(svg_path, res_root):
    """各密度 PNG 兜底：ic_launcher.png=圆角方形，ic_launcher_round.png=圆形"""
    from PIL import Image, ImageDraw
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, size in densities.items():
        d = os.path.join(res_root, "mipmap-" + dpi)
        os.makedirs(d, exist_ok=True)
        bg = Image.new("RGBA", (size, size), BG_COLOR)
        tmp = os.path.join(OUT_RES, "_ic.png")
        subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), svg_path, "-o", tmp],
                       check=True)
        fg = Image.open(tmp).convert("RGBA")
        bg.paste(fg.convert("RGB"), (0, 0), fg)
        for name, shape in (("ic_launcher.png", "square"), ("ic_launcher_round.png", "circle")):
            im = bg.copy()
            m = Image.new("L", (size, size), 0)
            dr = ImageDraw.Draw(m)
            if shape == "circle":
                dr.ellipse((0, 0, size - 1, size - 1), fill=255)
            else:
                dr.rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * 0.22), fill=255)
            im.putalpha(m)
            im.save(os.path.join(d, name))
        print("  PNG:", dpi, size)


def make_preview(svg_path, out_png, px=512):
    """生成设计预览图：方图 + 圆形遮罩 + 圆角方形遮罩"""
    from PIL import Image, ImageDraw
    bg = Image.new("RGBA", (px, px), BG_COLOR)
    tmp = os.path.join(OUT_RES, "_prev.png")
    subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px), svg_path, "-o", tmp], check=True)
    fg = Image.open(tmp).convert("RGBA")
    bg.paste(fg.convert("RGB"), (0, 0), fg)
    tiles = []
    for shape in ("square", "circle", "squircle"):
        im = bg.copy()
        m = Image.new("L", (px, px), 0)
        dr = ImageDraw.Draw(m)
        if shape == "circle":
            dr.ellipse((0, 0, px - 1, px - 1), fill=255)
        else:
            dr.rounded_rectangle((0, 0, px - 1, px - 1),
                                 radius=int(px * (0.22 if shape == "squircle" else 0.0)), fill=255)
        im.putalpha(m)
        tiles.append(im)
    sheet = Image.new("RGB", (px * 3 + 40, px), "#202124")
    for i, t in enumerate(tiles):
        sheet.paste(t.convert("RGB"), (i * (px + 20), 0), t)
    sheet.save(out_png)


if __name__ == "__main__":
    build()
    res_root = os.path.abspath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))
    make_pngs(os.path.join(OUT_RES, "ic_launcher_foreground.svg"), res_root)
    make_preview(os.path.join(OUT_RES, "ic_launcher_foreground.svg"),
                 os.path.join(HERE, "preview.png"))
    for f in ("_bb.svg", "_bb.png", "_prev.png", "_ic.png"):
        p = os.path.join(OUT_RES, f)
        if os.path.exists(p):
            os.remove(p)
    print("完成：资源已写入 app/src/main/res/，预览见 design/icon/preview.png")
