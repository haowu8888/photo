// 胶片滤镜：特效/瘦脸大眼（改采样坐标）→ 美颜 → LUT → 光晕、漏光、星芒、叠图（屏幕混合）→ 波普 → 暗角 → 颗粒。预览（相机 OES 纹理）和拍照（普通 2D 纹理）共用。
// 头部 #version、OES 扩展和 INPUT_OES 定义由 FilmProgram 拼接。
precision highp float;
precision highp int;

#ifdef INPUT_OES
uniform samplerExternalOES uInput;
#else
uniform sampler2D uInput;
#endif
uniform highp sampler3D uLut;
uniform float uLutSize;
uniform float uIntensity;  // 0 = 原图
uniform float uVignette;
uniform float uGrain;
uniform vec2 uGrainGrid;   // 颗粒格子数（与输出分辨率无关，预览和成片颗粒大小一致）
uniform uint uSeed;
uniform float uAspect;     // 输出宽 / 高
uniform sampler2D uGlow;   // GlowPass 输出的模糊高光，按输出坐标采样
uniform float uHalo;       // 光晕强度，0 = 关
uniform vec3 uHaloTint;
uniform float uLeak;       // 漏光强度，0 = 关
uniform vec2 uLeakPos;
uniform float uSmooth;       // 磨皮 0..1
uniform float uWhiten;       // 美白 0..1
uniform float uRosy;         // 红润 0..1
uniform float uBeautyRadius; // 磨皮半径（输出像素，按短边比例算，预览和成片一致）
uniform vec2 uOutSize;       // 输出像素尺寸
// 输出 uv → 输入纹理坐标的仿射映射（由四角纹理坐标算出），变形后要按新 uv 重新取纹理坐标
uniform vec2 uTexOrigin;
uniform vec2 uTexU;
uniform vec2 uTexV;
// 瘦脸大眼（多张脸），坐标都在输出的宽高比空间（x ∈ [0, uAspect]，y ∈ [0, 1]）
#define MAX_FACES 4
uniform int uFaceCount;              // 0 = 没有脸或没开
uniform vec3 uEyes[MAX_FACES * 2];   // 每张脸两只眼：x, y, 半径
uniform vec4 uSlimPts[MAX_FACES * 4];// 每张脸四个下颌点：x, y, 位移 dx, dy
uniform float uSlimR[MAX_FACES];
uniform float uEyeAmt;
// 特效（见 Effect.kt）：0 无 1 鱼眼 2 镜像 3 万花筒 4 波普 5 色散 6 故障 7 星芒 8 双重曝光
// 9 移轴 10 留色 11 素描 12 像素 13 热成像 14 夜视 15 老电视 16 变焦
uniform int uEffect;
uniform sampler2D uOverlay;  // 双重曝光第一张（只在预览里叠，成片在 CPU 上合成）
uniform float uOverlayAmt;

in vec2 vUv;   // 输出坐标，左上原点
in vec2 vTex;  // 输入纹理坐标
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float PI = 3.14159265;
// 波普四格：左上、右上、左下、右下 各自的暗色 / 亮色
const vec3 POP_DARK[4] = vec3[4](vec3(0.55, 0.05, 0.35), vec3(0.05, 0.2, 0.55), vec3(0.6, 0.25, 0.0), vec3(0.1, 0.4, 0.15));
const vec3 POP_LIGHT[4] = vec3[4](vec3(1.0, 0.85, 0.3), vec3(1.0, 0.55, 0.75), vec3(0.45, 0.9, 1.0), vec3(1.0, 1.0, 0.6));

uvec3 pcg3d(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return v;
}

float hash1(uvec3 v) {
    return float(pcg3d(v).x & 0xFFFFFFu) / 16777216.0;
}

/** 几何类特效：改采样位置。inside 是鱼眼圆内遮罩。 */
vec2 effectUv(vec2 uv, out float inside) {
    inside = 1.0;
    vec2 a = vec2(uAspect, 1.0);
    if (uEffect == 1) {
        // 鱼眼：整帧内容塞进内切圆，r_src = tan(r·θ)/tan(θ)，中心放大、边缘压缩。
        // 圆边对应该方向上画面的边缘，取样不会超出画面
        vec2 p = (uv - 0.5) * a;
        float R = 0.5 * min(uAspect, 1.0) * 0.98;
        float r = length(p);
        if (r <= 0.0) return vec2(0.5);
        float rn = r / R;
        inside = 1.0 - smoothstep(0.985, 1.0, rn);
        vec2 dir = p / r;
        float edge = min(0.5 * uAspect / max(abs(dir.x), 1e-4), 0.5 / max(abs(dir.y), 1e-4));
        float rs = tan(min(rn, 1.0) * 1.2) / tan(1.2) * edge;
        return dir * rs / a + 0.5;
    }
    if (uEffect == 2) return vec2(uv.x < 0.5 ? uv.x : 1.0 - uv.x, uv.y);
    if (uEffect == 3) {
        // 万花筒：按 60° 折叠成 6 瓣，半径缩到内切圆内避免取到画面外
        vec2 p = (uv - 0.5) * a;
        float seg = PI / 3.0;
        float ang = mod(atan(p.y, p.x), 2.0 * seg);
        if (ang > seg) ang = 2.0 * seg - ang;
        float r = length(p) * (0.5 * min(uAspect, 1.0)) / (0.5 * length(a));
        return r * vec2(cos(ang), sin(ang)) / a + 0.5;
    }
    if (uEffect == 4) return fract(uv * 2.0);
    if (uEffect == 12) {
        // 像素：短边 48 格的正方形马赛克，取格子中心
        float cell = min(uAspect, 1.0) / 48.0;
        vec2 q = (floor(uv * a / cell) + 0.5) * cell;
        return q / a;
    }
    if (uEffect == 15) {
        // 老电视：桶形弯曲的显像管，屏幕外是黑的
        vec2 p = uv - 0.5;
        p *= 1.0 + 0.22 * dot(p, p);
        vec2 edge = smoothstep(vec2(0.5), vec2(0.49), abs(p));
        inside = edge.x * edge.y;
        return p + 0.5;
    }
    if (uEffect == 6) {
        // 故障：随机横条左右错位，每 6 帧换一次花样
        uint s = uSeed / 6u;
        uint band = uint(floor(uv.y * 28.0));
        if (hash1(uvec3(band, s, 7u)) > 0.78) uv.x += (hash1(uvec3(band, s, 13u)) - 0.5) * 0.12;
        return uv;
    }
    return uv;
}

vec2 texAt(vec2 uv) {
    return uTexOrigin + uTexU * uv.x + uTexV * uv.y;
}

/** 反向映射：输出点该从原图哪里取色。瘦脸=脸颊附近从更外侧取，大眼=眼周从更靠近眼心处取。 */
vec2 faceWarp(vec2 uv) {
    vec2 q = uv * vec2(uAspect, 1.0);
    for (int f = 0; f < MAX_FACES; f++) {
        if (f >= uFaceCount) break;
        float r2 = uSlimR[f] * uSlimR[f];
        for (int i = 0; i < 4; i++) {
            vec4 sp = uSlimPts[f * 4 + i];
            vec2 d = q - sp.xy;
            float dd = dot(d, d);
            if (dd < r2) {
                float t = 1.0 - dd / r2;
                q -= sp.zw * t * t;
            }
        }
    }
    for (int f = 0; f < MAX_FACES; f++) {
        if (f >= uFaceCount) break;
        for (int i = 0; i < 2; i++) {
            vec3 e = uEyes[f * 2 + i];
            vec2 v = q - e.xy;
            float rr = e.z * e.z;
            float dd = dot(v, v);
            if (dd < rr) q = e.xy + v * (1.0 - uEyeAmt * (1.0 - dd / rr));
        }
    }
    return q / vec2(uAspect, 1.0);
}

/** 肤色权重：YCbCr 色度平面上的椭圆，过暗的像素不算。 */
float skinMask(vec3 c) {
    float y = dot(c, vec3(0.299, 0.587, 0.114));
    vec2 cbcr = vec2((c.b - y) * 0.564 + 0.5, (c.r - y) * 0.713 + 0.5);
    float d = length((cbcr - vec2(0.42, 0.60)) / vec2(0.09, 0.08));
    return (1.0 - smoothstep(0.6, 1.2, d)) * smoothstep(0.08, 0.2, y);
}

vec3 beauty(vec3 src, vec2 tc) {
    float skin = skinMask(src);
    vec3 c = src;
    // 采样偏移按「一个输出像素」在纹理坐标里的跨度换算，旋转、镜像、裁切都自动适配
    vec2 ex = uTexU / uOutSize.x;
    vec2 ey = uTexV / uOutSize.y;
    if (uSmooth > 0.0) {
        // 双边滤波：两圈各 8 个采样，颜色差越大权重越小，保住五官和发丝边缘
        vec3 sum = src;
        float wsum = 1.0;
        for (int ring = 1; ring <= 2; ring++) {
            float r = uBeautyRadius * float(ring) * 0.5;
            for (int k = 0; k < 8; k++) {
                float a = float(k) * 0.785398 + float(ring) * 0.392699;
                vec3 s = texture(uInput, tc + ex * (cos(a) * r) + ey * (sin(a) * r)).rgb;
                vec3 diff = s - src;
                float w = exp(-dot(diff, diff) * 60.0);
                sum += s * w;
                wsum += w;
            }
        }
        c = mix(src, sum / wsum, uSmooth * skin);
    }
    if (uWhiten > 0.0) {
        float beta = 1.0 + uWhiten * 4.0;
        vec3 bright = log(c * (beta - 1.0) + 1.0) / log(beta);
        c = mix(c, bright, mix(0.35, 1.0, skin));
    }
    if (uRosy > 0.0) {
        c = mix(c, c * vec3(1.06, 0.96, 0.98), uRosy * skin);
    }
    return c;
}

void main() {
    float inside = 1.0;
    vec2 uv = uEffect > 0 ? effectUv(vUv, inside) : vUv;
    bool geometric = uEffect == 1 || uEffect == 2 || uEffect == 3 || uEffect == 4 || uEffect == 6
        || uEffect == 12 || uEffect == 15 || uEffect == 16;
    if (uFaceCount > 0 && !geometric) uv = faceWarp(uv);  // 几何特效下脸的位置对不上，不做瘦脸大眼
    vec2 tc = texAt(uv);
    vec2 px = uTexU / uOutSize.x;   // 一个输出像素在纹理坐标里的跨度
    vec2 py = uTexV / uOutSize.y;
    float shortSide = min(uOutSize.x, uOutSize.y);
    vec3 src;
    float edge = 0.0;
    if (uEffect == 5 || uEffect == 6 || uEffect == 15) {
        // 色散：红蓝通道沿径向反向错开；故障、老电视：水平错开
        vec2 d = uEffect == 5 ? (uv - 0.5) * 0.025 : vec2(uEffect == 6 ? 0.008 : 0.0025, 0.0);
        src = vec3(texture(uInput, texAt(uv + d)).r, texture(uInput, tc).g, texture(uInput, texAt(uv - d)).b);
    } else if (uEffect == 9) {
        // 移轴：中间一条清晰带，上下越远越虚（12 点黄金角圆盘采样）
        float r = smoothstep(0.08, 0.38, abs(vUv.y - 0.5)) * 0.012 * shortSide;
        src = texture(uInput, tc).rgb;
        for (int k = 1; k <= 12; k++) {
            float rk = r * sqrt(float(k) / 12.0);
            float a = float(k) * 2.39996;
            src += texture(uInput, tc + px * (cos(a) * rk) + py * (sin(a) * rk)).rgb;
        }
        src /= 13.0;
    } else if (uEffect == 16) {
        // 变焦爆炸：沿中心方向的径向模糊，越靠边拖得越长
        src = vec3(0.0);
        for (int k = 0; k < 12; k++) src += texture(uInput, texAt(0.5 + (uv - 0.5) * (1.0 - 0.012 * float(k)))).rgb;
        src /= 12.0;
    } else if (uEffect == 11) {
        // 素描：亮度的 Sobel 边缘
        src = texture(uInput, tc).rgb;
        float o = max(1.0, 0.0012 * shortSide);
        float l[9];
        for (int j = 0; j < 3; j++)
            for (int i = 0; i < 3; i++)
                l[j * 3 + i] = dot(texture(uInput, tc + px * (float(i - 1) * o) + py * (float(j - 1) * o)).rgb, LUMA);
        float gx = l[2] + 2.0 * l[5] + l[8] - l[0] - 2.0 * l[3] - l[6];
        float gy = l[6] + 2.0 * l[7] + l[8] - l[0] - 2.0 * l[1] - l[2];
        edge = length(vec2(gx, gy));
    } else {
        src = texture(uInput, tc).rgb;
    }
    if (uSmooth > 0.0 || uWhiten > 0.0 || uRosy > 0.0) src = beauty(src, tc);
    float scale = (uLutSize - 1.0) / uLutSize;
    vec3 graded = texture(uLut, clamp(src, 0.0, 1.0) * scale + 0.5 / uLutSize).rgb;
    vec3 c = mix(src, graded, uIntensity);

    if (uHalo > 0.0) {
        vec3 glow = texture(uGlow, vUv).rgb * uHaloTint * uHalo * uIntensity;
        c = 1.0 - (1.0 - c) * (1.0 - clamp(glow, 0.0, 1.0));
    }
    if (uLeak > 0.0) {
        vec2 ld = (vUv - uLeakPos) * vec2(uAspect, 1.0);
        float r = length(ld * vec2(1.6, 0.7));
        vec3 leak = mix(vec3(1.0, 0.75, 0.25), vec3(1.0, 0.2, 0.05), smoothstep(0.0, 0.5, r));
        leak *= exp(-r * r * 6.0) * uLeak * uIntensity;
        c = 1.0 - (1.0 - c) * (1.0 - clamp(leak, 0.0, 1.0));
    }

    if (uEffect == 7) {
        // 星芒：沿 4 个方向（8 道光）累加模糊高光
        vec3 star = vec3(0.0);
        for (int i = 0; i < 4; i++) {
            float ang = float(i) * PI * 0.25 + PI * 0.125;
            vec2 step = vec2(cos(ang), sin(ang)) / vec2(uAspect, 1.0) * 0.012;
            for (int k = 1; k <= 10; k++) {
                float w = pow(1.0 - float(k) / 11.0, 2.0);
                star += (texture(uGlow, vUv + step * float(k)).rgb + texture(uGlow, vUv - step * float(k)).rgb) * w;
            }
        }
        c = 1.0 - (1.0 - c) * (1.0 - clamp(star * 0.35, 0.0, 1.0));
    }
    if (uOverlayAmt > 0.0) {
        c = 1.0 - (1.0 - c) * (1.0 - texture(uOverlay, vUv).rgb * uOverlayAmt);
    }
    if (uEffect == 4) {
        int cell = int(floor(vUv.x * 2.0) + floor(vUv.y * 2.0) * 2.0);
        float y = clamp(floor(dot(clamp(c, 0.0, 1.0), LUMA) * 4.0) / 3.0, 0.0, 1.0);
        c = mix(POP_DARK[cell], POP_LIGHT[cell], y);
    } else if (uEffect >= 9) {
        float y = dot(clamp(c, 0.0, 1.0), LUMA);
        if (uEffect == 9) {
            // 移轴：微缩模型感要更艳、更硬
            c = (mix(vec3(y), c, 1.35) - 0.5) * 1.08 + 0.5;
        } else if (uEffect == 10) {
            // 留色：只留红色系，其余变黑白
            float mx = max(c.r, max(c.g, c.b));
            float sat = (mx - min(c.r, min(c.g, c.b))) / (mx + 1e-4);
            float keep = smoothstep(0.06, 0.18, c.r - max(c.g, c.b)) * smoothstep(0.15, 0.3, sat);
            c = mix(vec3(y), c, keep);
        } else if (uEffect == 11) {
            float ink = smoothstep(0.2, 0.7, edge) * 0.8 + (1.0 - dot(src, LUMA)) * 0.1;
            c = vec3(0.97, 0.96, 0.92) * (1.0 - ink);
        } else if (uEffect == 12) {
            c = floor(c * 6.0 + 0.5) / 6.0;
        } else if (uEffect == 13) {
            // 热成像：亮度映射到 黑 → 蓝 → 品红 → 橙 → 黄 → 白
            float t = clamp(y, 0.0, 1.0) * 5.0;
            vec3 p0 = vec3(0.0, 0.0, 0.1), p1 = vec3(0.1, 0.0, 0.6), p2 = vec3(0.8, 0.0, 0.6);
            vec3 p3 = vec3(1.0, 0.5, 0.0), p4 = vec3(1.0, 0.95, 0.3), p5 = vec3(1.0);
            c = t < 1.0 ? mix(p0, p1, t) : t < 2.0 ? mix(p1, p2, t - 1.0) : t < 3.0 ? mix(p2, p3, t - 2.0)
              : t < 4.0 ? mix(p3, p4, t - 3.0) : mix(p4, p5, t - 4.0);
        } else if (uEffect == 14) {
            // 夜视：绿色荧光屏 + 圆形镜筒
            float gy = clamp(dot(src, LUMA) * 1.8, 0.0, 1.0);
            c = vec3(0.15, 1.0, 0.25) * gy + vec3(0.02, 0.08, 0.03);
            float r = length((vUv - 0.5) * vec2(uAspect, 1.0)) / (0.5 * min(uAspect, 1.0) * 1.15);
            c *= 1.0 - smoothstep(0.75, 1.0, r);
        } else if (uEffect == 15) {
            // 老电视：扫描线（固定 320 线，预览和成片一致）
            c *= 0.82 + 0.18 * sin(vUv.y * PI * 640.0);
        }
    }

    vec2 d = (vUv - 0.5) * vec2(uAspect, 1.0);
    float dist = length(d) / length(vec2(uAspect * 0.5, 0.5));
    c *= 1.0 - uVignette * uIntensity * smoothstep(0.3, 1.0, dist);

    float grain = uEffect == 14 ? max(uGrain, 0.5) : uGrain;
    if (grain > 0.0) {
        uvec2 cell = uvec2(vUv * uGrainGrid);
        uvec3 h = pcg3d(uvec3(cell, uSeed));
        float n = float(h.x & 0xFFFFFFu) / 16777216.0 + float(h.y & 0xFFFFFFu) / 16777216.0 - 1.0;
        float y = dot(clamp(c, 0.0, 1.0), LUMA);
        c += n * grain * (uEffect == 14 ? 1.0 : uIntensity) * 0.22 * (0.35 + 2.6 * y * (1.0 - y));
    }
    fragColor = vec4(clamp(c, 0.0, 1.0) * inside, 1.0);
}
