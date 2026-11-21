package com.haowu.buxingtu.effect

/** 特效拍摄。code 对应 film.frag 里的 uEffect。 */
enum class Effect(val code: Int, val label: String) {
    NONE(0, "无"),
    FISHEYE(1, "鱼眼"),
    MIRROR(2, "镜像"),
    KALEIDO(3, "万花筒"),
    POP(4, "波普"),
    RGB(5, "色散"),
    GLITCH(6, "故障"),
    STAR(7, "星芒"),
    DOUBLE(8, "双重曝光"),
    TILT(9, "移轴"),
    SPLASH(10, "留色"),
    SKETCH(11, "素描"),
    PIXEL(12, "像素"),
    THERMAL(13, "热成像"),
    NIGHT(14, "夜视"),
    TV(15, "老电视"),
    ZOOM(16, "变焦");

    companion object {
        fun byName(name: String?): Effect = entries.firstOrNull { it.name == name } ?: NONE

        /** 星芒用的高光：阈值高、模糊窄，只留灯光这类亮点 */
        const val STAR_THRESHOLD = 0.7f
        const val STAR_RADIUS = 0.5f

        /** 双重曝光两张的叠加强度（屏幕混合时第一张的权重），预览和成片一致 */
        const val DOUBLE_AMOUNT = 0.8f
    }
}
