#!/usr/bin/env python3
"""把 filters.json 里的配方烘焙成 3D LUT PNG，并生成样张对比图。

LUT 布局：宽 N、高 N*N 的 RGB PNG，像素 (x=r, y=b*N+g)。
按行读出来正好是 GL 3D 纹理要的 [b][g][r] 顺序，App 直接 glTexImage3D 上传。

用法：
  python3 tools/lutgen/lutgen.py                       # 生成 LUT + index.json 到 app assets
  python3 tools/lutgen/lutgen.py --preview DIR OUT_DIR # 另外用 DIR 里的照片出样张
"""
import argparse
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

N = 33
ROOT = Path(__file__).resolve().parents[2]
RECIPES = Path(__file__).with_name("filters.json")
SAMPLE = Path(__file__).with_name("sample.jpg")
CALIBRATED = Path(__file__).with_name("calibrated")  # 滤镜条缩略图用的样图
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "filters"
LUMA = np.array([0.2126, 0.7152, 0.0722])

# HSL 分区：中心色相（度）和半宽
BANDS = {
    "red": (0, 30), "orange": (30, 25), "yellow": (60, 30), "green": (120, 50),
    "cyan": (180, 35), "blue": (225, 40), "purple": (270, 35), "magenta": (315, 35),
}


def monotone_curve(points):
    """Fritsch-Carlson 单调三次插值，返回可对数组求值的函数。"""
    xs = np.array([p[0] for p in points], float)
    ys = np.array([p[1] for p in points], float)
    h = np.diff(xs)
    d = np.diff(ys) / h
    m = np.zeros_like(xs)
    m[0], m[-1] = d[0], d[-1]
    for i in range(1, len(xs) - 1):
        if d[i - 1] * d[i] <= 0:
            m[i] = 0
        else:
            w1, w2 = 2 * h[i] + h[i - 1], h[i] + 2 * h[i - 1]
            m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])

    def f(x):
        x = np.clip(x, xs[0], xs[-1])
        k = np.clip(np.searchsorted(xs, x, side="right") - 1, 0, len(xs) - 2)
        t = (x - xs[k]) / h[k]
        t2, t3 = t * t, t * t * t
        return ((2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h[k] * m[k]
                + (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h[k] * m[k + 1])

    return f


def rgb_to_hsv(c):
    r, g, b = c[..., 0], c[..., 1], c[..., 2]
    mx, mn = c.max(-1), c.min(-1)
    delta = mx - mn
    safe = np.where(delta > 1e-8, delta, 1)
    h = np.where(mx == r, ((g - b) / safe) % 6,
                 np.where(mx == g, (b - r) / safe + 2, (r - g) / safe + 4)) * 60
    h = np.where(delta > 1e-8, h, 0)
    s = np.where(mx > 1e-8, delta / np.where(mx > 1e-8, mx, 1), 0)
    return np.stack([h, s, mx], -1)


def hsv_to_rgb(c):
    h, s, v = c[..., 0] % 360, c[..., 1], c[..., 2]
    k = lambda n: (n + h / 60) % 6
    f = lambda n: v - v * s * np.clip(np.minimum(k(n), 4 - k(n)), 0, 1)
    return np.stack([f(5), f(3), f(1)], -1)


def hue_color(deg):
    """亮度归零的色相向量，用来做分离着色而不改变亮度。"""
    c = hsv_to_rgb(np.array([deg, 1.0, 1.0]))
    return c - c @ LUMA


def apply_color(c, p):
    """对 (..., 3) 的 sRGB 数组应用配方的 color 段。"""
    c = c.copy()
    t, tint = p.get("temp", 0), p.get("tint", 0)
    c = c * np.array([1 + t, 1 - tint, 1 - t])

    if "mixer" in p:  # 通道混合：输出通道 i = sum(mixer[i][j] * 输入通道 j)
        c = c @ np.array(p["mixer"], float).T

    if "curve" in p:
        c = monotone_curve(p["curve"])(c)
    for i, ch in enumerate("rgb"):
        if ch in p:
            c[..., i] = monotone_curve(p[ch])(c[..., i])

    if "mono" in p:
        w = np.array(p["mono"], float)
        y = np.clip(c, 0, 1) @ (w / w.sum())
        return np.clip(np.repeat(y[..., None], 3, -1), 0, 1)

    if "hsl" in p:
        hsv = rgb_to_hsv(np.clip(c, 0, 1))
        h = hsv[..., 0]
        dh = np.zeros_like(h)
        sm = np.ones_like(h)
        vm = np.ones_like(h)
        for band, adj in p["hsl"].items():
            center, width = BANDS[band]
            d = np.abs((h - center + 180) % 360 - 180)
            w = np.where(d < width, np.cos(d / width * math.pi / 2) ** 2, 0)
            dh += adj.get("hue", 0) * w
            sm *= 1 + (adj.get("sat", 1) - 1) * w
            vm *= 1 + adj.get("lum", 0) * w * hsv[..., 1]
        hsv[..., 0] = h + dh
        hsv[..., 1] = np.clip(hsv[..., 1] * sm, 0, 1)
        hsv[..., 2] = hsv[..., 2] * vm
        c = hsv_to_rgb(hsv)

    sat = p.get("sat", 1)
    if sat != 1:
        y = (c @ LUMA)[..., None]
        c = y + (c - y) * sat

    if "split" in p:
        sp = p["split"]
        bal = sp.get("balance", 0)
        y = np.clip(c @ LUMA, 0, 1)[..., None]
        if "shadow" in sp:
            hue, amt = sp["shadow"]
            c = c + hue_color(hue) * amt * (1 - y) ** 2 * (1 - bal)
        if "highlight" in sp:
            hue, amt = sp["highlight"]
            c = c + hue_color(hue) * amt * y ** 2 * (1 + bal)

    return np.clip(c, 0, 1)


def build_lut(p):
    g = np.linspace(0, 1, N)
    b, gg, r = np.meshgrid(g, g, g, indexing="ij")  # [b][g][r]
    return apply_color(np.stack([r, gg, b], -1), p)  # (N, N, N, 3)


def lut_to_png(lut, path):
    img = (np.round(lut.reshape(N * N, N, 3) * 255)).astype(np.uint8)
    Image.fromarray(img, "RGB").save(path, optimize=True)


def sample_lut(lut, img):
    """和 GPU 一样的三线性采样，用来验证 LUT 的效果。"""
    x = img * (N - 1)
    i0 = np.clip(np.floor(x).astype(int), 0, N - 2)
    f = x - i0
    r0, g0, b0 = i0[..., 0], i0[..., 1], i0[..., 2]
    fr, fg, fb = f[..., 0:1], f[..., 1:2], f[..., 2:3]
    out = 0
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                w = (fr if dr else 1 - fr) * (fg if dg else 1 - fg) * (fb if db else 1 - fb)
                out = out + w * lut[b0 + db, g0 + dg, r0 + dr]
    return out


GLOW_SHORT_SIDE = 240  # 光晕缓冲短边像素，和 App 的 GlowPass 一致


def gaussian_taps(arr, axis, step):
    """9 抽头高斯（sigma = 2 抽头），抽头间隔 step 像素，边缘夹紧。与 blur.frag 相同。"""
    out = np.zeros_like(arr)
    n = arr.shape[axis]
    idx = np.arange(n)
    weights = np.exp(-np.arange(-4, 5) ** 2 / 8.0)
    weights /= weights.sum()
    for k, wk in zip(range(-4, 5), weights):
        pos = np.clip(idx + k * step, 0, n - 1)
        i0 = np.floor(pos).astype(int)
        i1 = np.minimum(i0 + 1, n - 1)
        f = (pos - i0).reshape([-1 if d == axis else 1 for d in range(arr.ndim)])
        out += wk * (np.take(arr, i0, axis) * (1 - f) + np.take(arr, i1, axis) * f)
    return out


def halation_glow(src, hal):
    """高光阈值 → 缩到短边 240 → 两轮横竖模糊 → 放大回原尺寸。"""
    h, w, _ = src.shape
    scale = GLOW_SHORT_SIDE / min(w, h)
    gw, gh = max(1, round(w * scale)), max(1, round(h * scale))
    small = np.asarray(Image.fromarray((src * 255).round().astype(np.uint8)).resize((gw, gh), Image.BOX), float) / 255
    y = small @ LUMA
    t = np.clip((y - hal["threshold"]) / (1 - hal["threshold"]), 0, 1)
    g = small * (t * t * (3 - 2 * t))[..., None]
    step = hal.get("radius", 1.0)
    for _ in range(2):
        g = gaussian_taps(gaussian_taps(g, 1, step), 0, step)
    channels = [np.asarray(Image.fromarray(g[..., i].astype(np.float32), "F").resize((w, h), Image.BILINEAR)) for i in range(3)]
    return np.stack(channels, -1)


def light_leak(w, h, leak):
    """漏光：从光源位置向外的暖色光斑，内黄外红。与 film.frag 同公式。"""
    yy, xx = np.mgrid[0:h, 0:w]
    aspect = w / h
    dx = ((xx + 0.5) / w - leak["x"]) * aspect
    dy = (yy + 0.5) / h - leak["y"]
    r = np.sqrt((dx * 1.6) ** 2 + (dy * 0.7) ** 2)
    m = np.exp(-r * r * 6.0)
    t = np.clip(r / 0.5, 0, 1)
    t = (t * t * (3 - 2 * t))[..., None]
    color = np.array([1.0, 0.75, 0.25]) * (1 - t) + np.array([1.0, 0.2, 0.05]) * t
    return np.clip(color * m[..., None] * leak["amount"], 0, 1)


def bilinear(img, x, y):
    """在 (x, y) 像素坐标（像素中心为整数+0.5）双线性采样，边缘夹紧。"""
    h, w, _ = img.shape
    x = np.clip(x - 0.5, 0, w - 1)
    y = np.clip(y - 0.5, 0, h - 1)
    x0 = np.minimum(np.floor(x).astype(int), w - 2)
    y0 = np.minimum(np.floor(y).astype(int), h - 2)
    fx = (x - x0)[..., None]
    fy = (y - y0)[..., None]
    return (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x0 + 1] * fx * (1 - fy)
            + img[y0 + 1, x0] * (1 - fx) * fy + img[y0 + 1, x0 + 1] * fx * fy)


def skin_mask(c):
    y = c @ np.array([0.299, 0.587, 0.114])
    cb = (c[..., 2] - y) * 0.564 + 0.5
    cr = (c[..., 0] - y) * 0.713 + 0.5
    d = np.hypot((cb - 0.42) / 0.09, (cr - 0.60) / 0.08)
    ss = lambda e0, e1, x: (lambda t: t * t * (3 - 2 * t))(np.clip((x - e0) / (e1 - e0), 0, 1))
    return (1 - ss(0.6, 1.2, d)) * ss(0.08, 0.2, y)


def apply_beauty(img, smooth=0.0, whiten=0.0, rosy=0.0):
    """与 film.frag 的 beauty() 同算法：肤色区域双边滤波磨皮 → 对数曲线美白 → 红润。"""
    h, w, _ = img.shape
    skin = skin_mask(img)[..., None]
    c = img
    if smooth > 0:
        yy, xx = np.mgrid[0:h, 0:w] + 0.5
        radius = min(w, h) * 0.006
        total, wsum = img.copy(), np.ones((h, w, 1))
        for ring in (1, 2):
            r = radius * ring * 0.5
            for k in range(8):
                a = k * 0.785398 + ring * 0.392699
                s = bilinear(img, xx + np.cos(a) * r, yy + np.sin(a) * r)
                wt = np.exp(-((s - img) ** 2).sum(-1, keepdims=True) * 60.0)
                total += s * wt
                wsum += wt
        c = img + (total / wsum - img) * smooth * skin
    if whiten > 0:
        beta = 1 + whiten * 4
        bright = np.log(c * (beta - 1) + 1) / np.log(beta)
        c = c + (bright - c) * (0.35 + 0.65 * skin)
    if rosy > 0:
        c = c + (c * np.array([1.06, 0.96, 0.98]) - c) * rosy * skin
    return c


def apply_effects(img, flt, src=None, seed=7):
    """与 shader 同顺序同公式：光晕、漏光（屏幕混合）→ 暗角 → 颗粒（颗粒用 numpy 随机数近似）。
    src 是套 LUT 之前的原图，光晕从原图高光取。"""
    h, w, _ = img.shape
    hal = flt.get("halation")
    if hal and src is not None:
        glow = halation_glow(src, hal) * np.array(hal["tint"]) * hal["amount"]
        img = 1 - (1 - img) * (1 - np.clip(glow, 0, 1))
    leak = flt.get("leak")
    if leak:
        img = 1 - (1 - img) * (1 - light_leak(w, h, leak))
    v = flt.get("vignette", 0)
    if v:
        yy, xx = np.mgrid[0:h, 0:w]
        aspect = w / h
        dx, dy = (xx / w - 0.5) * aspect, yy / h - 0.5
        d = np.sqrt(dx * dx + dy * dy) / math.hypot(aspect * 0.5, 0.5)
        t = np.clip((d - 0.3) / 0.7, 0, 1)
        img = img * (1 - v * t * t * (3 - 2 * t))[..., None]
    gr = flt.get("grain", 0)
    if gr:
        rng = np.random.default_rng(seed)
        n = rng.random((h, w)) + rng.random((h, w)) - 1
        y = np.clip(img @ LUMA, 0, 1)
        img = img + (n * gr * 0.22 * (0.35 + 2.6 * y * (1 - y)))[..., None]
    return np.clip(img, 0, 1)


def load_font(size):
    for f in ("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
              "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"):
        if Path(f).exists():
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


def contact_sheet(photo, filters, luts, out, tile_w=420):
    src = Image.open(photo).convert("RGB")
    tile_h = round(tile_w * src.height / src.width)
    src = src.resize((tile_w, tile_h), Image.LANCZOS)
    arr = np.asarray(src, float) / 255
    tiles = [("原图", arr)] + [
        (f"{f['code']} {f['name']}", apply_effects(sample_lut(luts[f["id"]], arr), f, arr))
        for f in filters
    ]
    cols = 6
    rows = math.ceil(len(tiles) / cols)
    label_h, gap = 34, 6
    sheet = Image.new("RGB", (cols * (tile_w + gap) + gap, rows * (tile_h + label_h + gap) + gap),
                      (24, 24, 24))
    draw = ImageDraw.Draw(sheet)
    font = load_font(20)
    for i, (label, a) in enumerate(tiles):
        x = gap + (i % cols) * (tile_w + gap)
        y = gap + (i // cols) * (tile_h + label_h + gap)
        sheet.paste(Image.fromarray((a * 255).round().astype(np.uint8)), (x, y))
        draw.text((x + 4, y + tile_h + 4), label, fill=(235, 235, 235), font=font)
    sheet.save(out, quality=90)


def thumbnails(filters, luts, size=180):
    """滤镜条缩略图：样图居中裁正方形后套滤镜。none.jpg 是原图。"""
    src = Image.open(SAMPLE).convert("RGB")
    s = min(src.size)
    left, top = (src.width - s) // 2, (src.height - s) // 2
    arr = np.asarray(src.crop((left, top, left + s, top + s)).resize((size, size), Image.LANCZOS), float) / 255
    out = ASSETS / "thumbs"
    out.mkdir(parents=True, exist_ok=True)
    save = lambda a, name: Image.fromarray((a * 255).round().astype(np.uint8)).save(out / name, quality=88)
    save(arr, "none.jpg")
    for f in filters:
        save(apply_effects(sample_lut(luts[f["id"]], arr), f, arr), f"{f['id']}.jpg")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", nargs=2, metavar=("PHOTO_DIR", "OUT_DIR"))
    args = ap.parse_args()

    spec = json.loads(RECIPES.read_text(encoding="utf-8"))
    (ASSETS / "luts").mkdir(parents=True, exist_ok=True)
    luts, index = {}, []
    for f in spec["filters"]:
        # calibrated：用 calibrate.py 从真机对比图拟合的 LUT；color 段仍是数据覆盖不到时的先验
        cal = CALIBRATED / f"{f['id']}.png"
        if f.get("calibrated") and cal.exists():
            luts[f["id"]] = np.asarray(Image.open(cal).convert("RGB"), float).reshape(N, N, N, 3) / 255
        else:
            luts[f["id"]] = build_lut(f["color"])
        lut_to_png(luts[f["id"]], ASSETS / "luts" / f"{f['id']}.png")
        entry = {k: v for k, v in f.items() if k not in ("color", "calibrated")}
        entry["lut"] = f"filters/luts/{f['id']}.png"
        index.append(entry)
    (ASSETS / "index.json").write_text(
        json.dumps({"lutSize": N, "groups": spec["groups"], "filters": index},
                   ensure_ascii=False, indent=2), encoding="utf-8")
    thumbnails(spec["filters"], luts)
    print(f"{len(index)} 个滤镜 → {ASSETS}")

    if args.preview:
        src, out = map(Path, args.preview)
        out.mkdir(parents=True, exist_ok=True)
        for photo in sorted(src.glob("*.jpg")):
            contact_sheet(photo, spec["filters"], luts, out / f"sheet_{photo.stem}.jpg")
        print(f"样张 → {out}")


if __name__ == "__main__":
    main()
