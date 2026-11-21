// 光晕第一步：从原图取高光，画到短边 240 的小缓冲。3×3 采样防止缩小时闪烁。
// 头部由 GlowPass 拼接（和 film.frag 一样有 OES / 2D 两种输入）。
precision highp float;

#ifdef INPUT_OES
uniform samplerExternalOES uInput;
#else
uniform sampler2D uInput;
#endif
uniform float uThreshold;

in vec2 vUv;
in vec2 vTex;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec2 dx = dFdx(vTex) * 0.33;
    vec2 dy = dFdy(vTex) * 0.33;
    vec3 sum = vec3(0.0);
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            sum += texture(uInput, vTex + dx * float(i) + dy * float(j)).rgb;
        }
    }
    vec3 src = sum / 9.0;
    float w = smoothstep(uThreshold, 1.0, dot(src, LUMA));
    fragColor = vec4(src * w, 1.0);
}
