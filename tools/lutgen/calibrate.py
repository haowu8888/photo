#!/usr/bin/env python3
"""用网上的同场景对比图校准滤镜：从「基准图 → 目标图」的像素对里拟合 33³ LUT。

数据集和对应关系在 calibration.json；图片下载到 ~/.cache/shuitu-calib（不进仓库）。
拟合结果写到 tools/lutgen/calibrated/<id>.png，filters.json 里带 "calibrated" 的滤镜由 lutgen 直接使用。

求解：min (1/M)·|A·V − Y|² + λ·|L·V|² + μ·|V − P|²
  A：样本在 LUT 格点上的三线性权重；L：格点图拉普拉斯（平滑）；P：手调配方的 LUT（数据覆盖不到的颜色退回它）。
  无 scipy 依赖，矩阵不显式构造，用共轭梯度解正规方程。

用法：
  python3 tools/lutgen/calibrate.py            # 留一场景交叉验证 + 用全部场景拟合并写出
  python3 tools/lutgen/calibrate.py --only cc,vv  # 只处理指定滤镜
"""
import argparse
import json
import re
import urllib.error
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image

import lutgen

HERE = Path(__file__).parent
CONFIG = HERE / "calibration.json"
OUT = HERE / "calibrated"
CACHE = Path.home() / ".cache" / "shuitu-calib"
N = lutgen.N
FIT_LONG_SIDE = 640


def fetch(dataset, name):
    path = CACHE / dataset["id"] / re.sub(r"[^\w.-]", "_", name)
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(dataset["baseUrl"] + name, timeout=60) as r:
            path.write_bytes(r.read())
    return path


def split_compound(path):
    """白底上左右并排两张照片的对比图 → 两张裁好的照片（同尺寸）。标题文字行占比低，会被排除。"""
    im = Image.open(path).convert("RGBA")
    a = np.asarray(im, float)
    ink = (a[..., 3] > 0) & (a[..., :3].min(-1) < 240)
    rows = np.where(ink.mean(1) > 0.3)[0]
    top, bottom = rows.min(), rows.max() + 1
    cols = ink[top:bottom].mean(0) > 0.5
    edges = np.flatnonzero(np.diff(np.r_[0, cols.astype(int), 0]))
    segs = [(edges[i], edges[i + 1]) for i in range(0, len(edges), 2)]
    segs = sorted(segs, key=lambda s: s[1] - s[0], reverse=True)[:2]
    segs.sort()
    w = min(r - l for l, r in segs) - 4
    h = bottom - top - 4
    rgb = im.convert("RGB")
    return [rgb.crop((l + 2, top + 2, l + 2 + w, top + 2 + h)) for l, _ in segs]


def load(path, panel=None):
    im = Image.open(path).convert("RGB")
    if panel is not None:
        # 不同合成图里切出的照片可能差一两个像素，统一缩到同一尺寸后才能逐像素对齐
        im = split_compound(path)[panel].resize((672, 448), Image.LANCZOS)
    s = FIT_LONG_SIDE / max(im.size)
    im = im.resize((round(im.width * s), round(im.height * s)), Image.BOX)
    return np.asarray(im, float).reshape(-1, 3) / 255


def load_look(ds, subject, look):
    """普通数据集：每种风格一个文件；compound 数据集：looks[look] = [合成图编号, 左0/右1]。"""
    if ds.get("type") == "compound":
        panel_file, side = ds["looks"][look]
        return load(fetch(ds, ds["pattern"].format(subject=subject, panel=panel_file)), side)
    # 同一风格在不同场景里文件名可能不一致（如 eterna-bb / eternabb），look 可以给候选列表
    names = look if isinstance(look, list) else [look]
    for i, name in enumerate(names):
        try:
            return load(fetch(ds, ds["pattern"].format(subject=subject, look=name)))
        except urllib.error.HTTPError:
            if i == len(names) - 1:
                raise


def trilinear(x):
    """样本 (M,3) → 8 个格点的扁平索引 (M,8) 和权重 (M,8)，格点顺序 [b][g][r]。"""
    p = np.clip(x, 0, 1) * (N - 1)
    i0 = np.clip(np.floor(p).astype(int), 0, N - 2)
    f = p - i0
    idx, w = [], []
    for db in (0, 1):
        for dg in (0, 1):
            for dr in (0, 1):
                idx.append(((i0[:, 2] + db) * N + i0[:, 1] + dg) * N + i0[:, 0] + dr)
                w.append((f[:, 0] if dr else 1 - f[:, 0]) * (f[:, 1] if dg else 1 - f[:, 1]) * (f[:, 2] if db else 1 - f[:, 2]))
    return np.stack(idx, 1), np.stack(w, 1)


def laplacian(v):
    """格点图拉普拉斯（只连存在的邻居，对称）。v: (N,N,N,3)"""
    out = np.zeros_like(v)
    for axis in range(3):
        d = np.diff(v, axis=axis)
        pad_lo = [(0, 0)] * 4
        pad_hi = [(0, 0)] * 4
        pad_lo[axis] = (1, 0)
        pad_hi[axis] = (0, 1)
        out += np.pad(d, pad_lo) - np.pad(d, pad_hi)
    return out


def fit(src, dst, prior, lam=2e-4, mu=2e-5, iters=400):
    n = N ** 3
    idx, w = trilinear(src)
    m = len(src)

    def apply_a(v):
        return (v[idx] * w[..., None]).sum(1)

    def apply_at(r):
        out = np.zeros((n, 3))
        for k in range(8):
            for c in range(3):
                out[:, c] += np.bincount(idx[:, k], weights=w[:, k] * r[:, c], minlength=n)
        return out

    def h(v):
        lap = laplacian(laplacian(v.reshape(N, N, N, 3))).reshape(n, 3)
        return apply_at(apply_a(v)) / m + lam * lap + mu * v

    p = prior.reshape(n, 3)
    b = apply_at(dst) / m + mu * p
    x = p.copy()
    r = b - h(x)
    d = r.copy()
    rs = (r * r).sum(0)
    for _ in range(iters):
        hd = h(d)
        alpha = rs / np.maximum((d * hd).sum(0), 1e-30)
        x += alpha * d
        r -= alpha * hd
        rs_new = (r * r).sum(0)
        if np.all(np.sqrt(rs_new) < 1e-7):
            break
        d = r + (rs_new / np.maximum(rs, 1e-30)) * d
        rs = rs_new
    return np.clip(x.reshape(N, N, N, 3), 0, 1)


def srgb_to_lab(c):
    c = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    xyz = c @ np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]]).T
    xyz /= np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.stack([116 * f[:, 1] - 16, 500 * (f[:, 0] - f[:, 1]), 200 * (f[:, 1] - f[:, 2])], 1)


def delta_e(a, b):
    """CIE76 色差，平均值。"""
    return float(np.linalg.norm(srgb_to_lab(a) - srgb_to_lab(b), axis=1).mean())


def apply_lut(lut, x):
    return lutgen.sample_lut(lut, x.reshape(1, -1, 3)).reshape(-1, 3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only")
    args = ap.parse_args()

    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    recipes = {f["id"]: f for f in json.loads(lutgen.RECIPES.read_text(encoding="utf-8"))["filters"]}
    datasets = {d["id"]: d for d in config["datasets"]}
    OUT.mkdir(exist_ok=True)
    report = []

    for item in config["filters"]:
        fid = item["filter"]
        if args.only and fid not in args.only.split(","):
            continue
        ds = datasets[item["dataset"]]
        subjects = ds["subjects"]
        pairs = [(load_look(ds, s, ds["base"]), load_look(ds, s, item["look"])) for s in subjects]
        prior = lutgen.build_lut(recipes[fid]["color"])

        # 留一场景交叉验证
        scores = []
        for k in range(len(pairs)):
            train = [p for i, p in enumerate(pairs) if i != k]
            lut = fit(np.concatenate([p[0] for p in train]), np.concatenate([p[1] for p in train]), prior)
            if item.get("mono"):
                lut = np.repeat(lut.mean(-1, keepdims=True), 3, -1)
            src, dst = pairs[k]
            scores.append((delta_e(src, dst), delta_e(apply_lut(prior, src), dst), delta_e(apply_lut(lut, src), dst)))
        base, hand, fitted = np.mean(scores, 0)

        lut = fit(np.concatenate([p[0] for p in pairs]), np.concatenate([p[1] for p in pairs]), prior)
        if item.get("mono"):
            lut = np.repeat(lut.mean(-1, keepdims=True), 3, -1)
        lutgen.lut_to_png(lut, OUT / f"{fid}.png")
        report.append((fid, item["look"], base, hand, fitted))
        print(f"{fid:4s} ← {str(item['look']):15s} ΔE  不加滤镜 {base:5.2f} | 手调 {hand:5.2f} | 校准 {fitted:5.2f}", flush=True)

    if report:
        print("（ΔE 为留一场景交叉验证：用其余场景拟合，在没见过的场景上测；越小越接近真机）")


if __name__ == "__main__":
    main()
