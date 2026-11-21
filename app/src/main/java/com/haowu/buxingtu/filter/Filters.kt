package com.haowu.buxingtu.filter

import android.content.Context
import org.json.JSONObject

data class FilterGroup(val id: String, val name: String)

/** 高光光晕/柔光：amount 强度、threshold 高光阈值、tint 颜色、radius 模糊半径倍数 */
data class Halation(val amount: Float, val threshold: Float, val tint: FloatArray, val radius: Float)

/** 漏光：x/y 是光源在输出画面里的位置（左上原点） */
data class Leak(val amount: Float, val x: Float, val y: Float)

data class Filter(
    val id: String,
    val code: String,
    val name: String,
    val group: String,
    /** assets 里的 LUT 路径；null 表示原图 */
    val lut: String?,
    val grain: Float,
    val vignette: Float,
    val dateStamp: Boolean,
    val halation: Halation? = null,
    val leak: Leak? = null,
    /** 选中这个滤镜时自动换上的边框（[com.haowu.buxingtu.frame.FrameStyle] 的 id） */
    val frame: String? = null,
) {
    val thumb: String get() = "filters/thumbs/$id.jpg"
}

data class FilterCatalog(val lutSize: Int, val groups: List<FilterGroup>, val filters: List<Filter>)

/** 读 assets/filters/index.json（由 tools/lutgen 生成），并在最前面加上「原图」。 */
object Filters {
    val NONE = Filter("none", "OFF", "原图", "", null, 0f, 0f, false)

    fun load(context: Context): FilterCatalog {
        val json = context.assets.open("filters/index.json").bufferedReader().use { JSONObject(it.readText()) }
        val groups = json.getJSONArray("groups").let { arr ->
            List(arr.length()) { arr.getJSONObject(it).run { FilterGroup(getString("id"), getString("name")) } }
        }
        val filters = json.getJSONArray("filters").let { arr ->
            List(arr.length()) {
                arr.getJSONObject(it).run {
                    Filter(
                        id = getString("id"),
                        code = getString("code"),
                        name = getString("name"),
                        group = getString("group"),
                        lut = getString("lut"),
                        grain = optDouble("grain", 0.0).toFloat(),
                        vignette = optDouble("vignette", 0.0).toFloat(),
                        dateStamp = optBoolean("dateStamp", false),
                        halation = optJSONObject("halation")?.run {
                            val t = getJSONArray("tint")
                            Halation(
                                amount = getDouble("amount").toFloat(),
                                threshold = getDouble("threshold").toFloat(),
                                tint = FloatArray(3) { i -> t.getDouble(i).toFloat() },
                                radius = optDouble("radius", 1.0).toFloat(),
                            )
                        },
                        leak = optJSONObject("leak")?.run {
                            Leak(getDouble("amount").toFloat(), getDouble("x").toFloat(), getDouble("y").toFloat())
                        },
                        frame = optString("frame").ifEmpty { null },
                    )
                }
            }
        }
        return FilterCatalog(json.getInt("lutSize"), groups, listOf(NONE) + filters)
    }
}
