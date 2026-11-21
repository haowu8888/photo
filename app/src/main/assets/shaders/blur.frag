// 9 抽头高斯（sigma = 2 抽头），uStep = 抽头间隔（纹理坐标）。横竖各跑一次。
precision highp float;

uniform sampler2D uInput;
uniform vec2 uStep;

in vec2 vUv;
in vec2 vTex;
out vec4 fragColor;

void main() {
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int k = -4; k <= 4; k++) {
        float w = exp(-float(k * k) / 8.0);
        sum += texture(uInput, vTex + uStep * float(k)).rgb * w;
        total += w;
    }
    fragColor = vec4(sum / total, 1.0);
}
