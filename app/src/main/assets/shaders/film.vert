// 头部 #version 由 FilmProgram 拼接
in vec2 aPos;
in vec2 aUv;
in vec2 aTex;
out vec2 vUv;
out vec2 vTex;

void main() {
    vUv = aUv;
    vTex = aTex;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
