package com.haowu.buxingtu.gl

import android.content.Context
import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** shader 编译和全屏四边形，FilmProgram 与 GlowPass 共用。 */
object Gl {
    private const val HEADER = "#version 300 es\n"
    private const val OES_HEADER = "#extension GL_OES_EGL_image_external_essl3 : require\n#define INPUT_OES\n"

    fun program(context: Context, vertAsset: String, fragAsset: String, oes: Boolean = false): Int {
        val read = { name: String -> context.assets.open("shaders/$name").bufferedReader().use { it.readText() } }
        val vs = compile(GL_VERTEX_SHADER, HEADER + read(vertAsset))
        val fs = compile(GL_FRAGMENT_SHADER, HEADER + (if (oes) OES_HEADER else "") + read(fragAsset))
        val program = glCreateProgram()
        glAttachShader(program, vs)
        glAttachShader(program, fs)
        glLinkProgram(program)
        glDeleteShader(vs)
        glDeleteShader(fs)
        val ok = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetProgramInfoLog(program)
            glDeleteProgram(program)
            throw IllegalStateException("shader 链接失败: $log")
        }
        return program
    }

    private fun compile(type: Int, src: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, src)
        glCompileShader(shader)
        val ok = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(shader)
            glDeleteShader(shader)
            throw IllegalStateException("shader 编译失败: $log")
        }
        return shader
    }

    /** RGBA8 纹理 + 帧缓冲，线性过滤、边缘夹紧。返回 (texture, framebuffer)。 */
    fun renderTarget(width: Int, height: Int): Pair<Int, Int> {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        val tex = ids[0]
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glGenFramebuffers(1, ids, 0)
        val fb = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fb)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            deleteTarget(tex to fb)
            throw IllegalStateException("帧缓冲创建失败（${width}×$height）")
        }
        return tex to fb
    }

    fun deleteTarget(target: Pair<Int, Int>) {
        glDeleteTextures(1, intArrayOf(target.first), 0)
        glDeleteFramebuffers(1, intArrayOf(target.second), 0)
    }
}

/**
 * 全屏四边形，顶点属性 aPos / aUv / aTex（见 film.vert）。
 * 顶点顺序 左上、左下、右上、右下（三角带）。
 */
class Quad {
    private val vertices: FloatBuffer =
        ByteBuffer.allocateDirect(4 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /**
     * @param texCorners 输入纹理坐标，8 个 float，顺序同上
     * @param flipY 为 true 时输出左上角落在帧缓冲第 0 行（离屏缓冲统一用这种，按 uv 采样即正向）
     */
    fun draw(program: Int, texCorners: FloatArray, flipY: Boolean) {
        val top = if (flipY) -1f else 1f
        val pos = floatArrayOf(-1f, top, -1f, -top, 1f, top, 1f, -top)
        vertices.clear()
        for (i in 0 until 4) {
            vertices.put(pos[i * 2]).put(pos[i * 2 + 1])
            vertices.put(UV[i * 2]).put(UV[i * 2 + 1])
            vertices.put(texCorners[i * 2]).put(texCorners[i * 2 + 1])
        }
        val locations = listOf("aPos", "aUv", "aTex").map { glGetAttribLocation(program, it) }
        locations.forEachIndexed { i, loc ->
            if (loc < 0) return@forEachIndexed
            vertices.position(i * 2)
            glVertexAttribPointer(loc, 2, GL_FLOAT, false, 6 * 4, vertices)
            glEnableVertexAttribArray(loc)
        }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        locations.forEach { if (it >= 0) glDisableVertexAttribArray(it) }
    }

    companion object {
        /** 输出 uv，左上原点；也是「原样拷贝」时的纹理坐标 */
        val UV = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)
    }
}
