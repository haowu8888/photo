package com.haowu.buxingtu.preset

import com.haowu.buxingtu.effect.Effect
import com.haowu.buxingtu.filter.Filter
import com.haowu.buxingtu.filter.FilterCatalog
import com.haowu.buxingtu.filter.Filters
import com.haowu.buxingtu.filter.Leak
import kotlin.random.Random

/** 相机外形，图标按它画（ui/CamIcon.kt）。 */
enum class Body { COMPACT, RANGEFINDER, SLR, INSTANT, DISPOSABLE, CCD, DV, TOY, TLR }

data class CamCategory(val id: String, val name: String)

/**
 * 一台「相机」= 滤镜 + 画幅 + 边框 + 日期戳 + 随机漏光 + 默认配件（特效）。
 * 界面上只选相机，参数都跟着相机走；颜色是图标用的机身/饰条/点缀色。
 */
data class Cam(
    val id: String,
    val name: String,
    val desc: String,
    val category: String,
    /** filters.json 里的滤镜 id，"none" = 原图 */
    val filterId: String,
    val body: Body,
    val color: Long,
    val trim: Long,
    val accent: Long,
    /** 选中时切到的画幅（长边/短边），null = 保持用户当前画幅 */
    val ratio: Float? = null,
    /** 选中时自动换上的边框（FrameStyle id），null = 用户自己选的 */
    val frame: String? = null,
    /** null = 跟滤镜默认 */
    val dateStamp: Boolean? = null,
    /** >0：每拍一张漏光换个随机位置，这是强度 */
    val leak: Float = 0f,
    val effect: Effect = Effect.NONE,
)

/** 相机和它解析出来的滤镜。 */
data class ResolvedCam(val cam: Cam, val filter: Filter) {
    val dateStamp: Boolean get() = cam.dateStamp ?: filter.dateStamp
    val leakAmount: Float get() = if (cam.leak > 0f) cam.leak else filter.leak?.amount ?: 0f

    /** 套上这次的随机漏光位置；seed 每拍一张换一次，预览和成片用同一个 seed 所以一致。 */
    fun filterFor(seed: Int): Filter {
        if (cam.leak <= 0f) return filter
        return filter.copy(leak = randomLeak(cam.leak, seed))
    }
}

object Cams {
    val categories = listOf(
        CamCategory("film", "胶片"),
        CamCategory("instant", "拍立得"),
        CamCategory("digital", "数码"),
        CamCategory("daily", "日常"),
        CamCategory("mono", "黑白"),
        CamCategory("fun", "趣味"),
    )

    // 名字是自编的型号，不用相机/胶卷品牌名
    val all = listOf(
        // 胶片
        Cam("cc35", "CC 35", "经典正片 · 低饱和硬朗", "film", "cc", Body.RANGEFINDER, 0xFF262626, 0xFFC9CCD1, 0xFFE24B3B),
        Cam("cn400", "CN 400", "经典负片 · 青绿暗部", "film", "cn", Body.COMPACT, 0xFF3A3F45, 0xFF8A9099, 0xFF4CC3B0),
        Cam("nn70", "NN 70", "怀旧负片 · 琥珀高光", "film", "nn", Body.RANGEFINDER, 0xFF6B4A33, 0xFFD8CBAE, 0xFFE8A13B),
        Cam("p4", "P4 Port", "人像负片 · 暖肤色", "film", "p4", Body.SLR, 0xFF2A2A2A, 0xFFBFC3C8, 0xFFF08A3C),
        Cam("g2", "G2 Gold", "金色 · 暖黄日常", "film", "g2", Body.COMPACT, 0xFFE3B23C, 0xFF2A2A2A, 0xFFD33A2C, leak = 0.3f),
        Cam("e1", "E1 Land", "风光 · 高饱和细腻", "film", "e1", Body.SLR, 0xFF1C1C1C, 0xFF3C3C3C, 0xFFD94040),
        Cam("800t", "800T", "夜景灯光 · 红晕光晕", "film", "cs", Body.SLR, 0xFF243447, 0xFFB8C0C8, 0xFF59B7FF),
        Cam("su400", "SU 400", "日常 400 · 通透", "film", "su", Body.COMPACT, 0xFF2E6E4F, 0xFFE6E6E6, 0xFFF2C94C),
        Cam("c200", "C2 200", "平价 200 · 偏暖", "film", "c2", Body.COMPACT, 0xFFB5463A, 0xFFEDE6DA, 0xFFFFD45C),
        Cam("ag", "AG Red", "暖红 · 浓郁", "film", "ag", Body.RANGEFINDER, 0xFF7E2A2A, 0xFFCFC6BA, 0xFFFFB347),
        Cam("lp", "LP Lomo", "紫调 · 偏色玩具感", "film", "lp", Body.TOY, 0xFF6E4B9E, 0xFFEFE8F7, 0xFFFFC94D, leak = 0.35f),
        Cam("et", "ET Cine", "电影 · 低饱和宽容", "film", "et", Body.SLR, 0xFF303A40, 0xFF9AA3A8, 0xFFE0B04A),
        Cam("bb", "BB 35", "漂白 · 高反差", "film", "bb", Body.RANGEFINDER, 0xFF4A4D52, 0xFFDADDE0, 0xFF9AA0A6),
        Cam("hk90", "HK 90", "港风 · 霓虹浓郁", "film", "hk", Body.COMPACT, 0xFF7C1C2B, 0xFF1E1E1E, 0xFF3BE0C8, leak = 0.25f),
        Cam("sun", "Sunset", "落日 · 橙色余晖", "film", "sun", Body.TOY, 0xFFF08A3C, 0xFFFFF1DE, 0xFFC0392B),
        Cam("d98", "98 Leak", "一次性 · 每张漏光都不一样", "film", "h98", Body.DISPOSABLE, 0xFF2D6FB7, 0xFFEDEDED, 0xFFF5C542, dateStamp = true, leak = 0.65f),
        Cam("qs27", "QS 27", "一次性 · 闪光直出", "film", "cu", Body.DISPOSABLE, 0xFF1E8A5A, 0xFFF4F4F4, 0xFFE94B3C, dateStamp = true, leak = 0.3f),

        // 拍立得
        Cam("sq600", "SQ 600", "方形相纸 · 褪色青绿", "instant", "ip", Body.INSTANT, 0xFFF1ECE2, 0xFF2B2B2B, 0xFFE24B3B, ratio = 1f, frame = "polaroid"),
        Cam("mini11", "Mini 11", "迷你相纸 · 粉嫩", "instant", "ip", Body.INSTANT, 0xFFF4B6C2, 0xFFFFF5F7, 0xFFE2557A, frame = "instax"),
        Cam("sqbw", "SQ Mono", "方形相纸 · 黑白", "instant", "hp", Body.INSTANT, 0xFF2A2A2A, 0xFFEDEDED, 0xFFBDBDBD, ratio = 1f, frame = "polaroid"),
        Cam("wide", "Mini Sky", "迷你相纸 · 清新蓝", "instant", "jp", Body.INSTANT, 0xFF9CC9E8, 0xFFF5FAFF, 0xFF3D8BD9, frame = "instax"),

        // 数码
        Cam("plain", "Plain", "原图 · 不加滤镜", "digital", "none", Body.COMPACT, 0xFF5A5F66, 0xFFB9BEC4, 0xFFFFFFFF),
        Cam("ccd03", "CCD 03", "千禧数码 · 冷调溢光", "digital", "ccd", Body.CCD, 0xFFC0C4CA, 0xFF8E949B, 0xFF3D7BD9, dateStamp = true),
        Cam("cpm", "CPM 2K", "数码胶片 · 日期戳", "digital", "cpm", Body.CCD, 0xFF8FA3B8, 0xFF5E6E80, 0xFFF29A3A, dateStamp = true),
        Cam("dv99", "DV 99", "千禧 DV · 录像带色偏", "digital", "dv", Body.DV, 0xFF2F3136, 0xFF7B8088, 0xFFE53935, ratio = 4f / 3f, dateStamp = true),
        Cam("st28", "Street 28", "街拍 · 复古低饱和", "digital", "gr", Body.COMPACT, 0xFF1B1B1B, 0xFF4A4A4A, 0xFFEDEDED),
        Cam("gp28", "GP 28", "正片 · 浓郁", "digital", "gp", Body.COMPACT, 0xFF222222, 0xFF555555, 0xFFFF6E40),
        Cam("gn28", "GN 28", "负片 · 偏青低反差", "digital", "gn", Body.COMPACT, 0xFF2B2E33, 0xFF60656C, 0xFF4DD0B4),
        Cam("gb28", "GB 28", "漂白 · 冷硬", "digital", "gb", Body.COMPACT, 0xFF34373C, 0xFF6A6F76, 0xFFB0BEC5),
        Cam("pv100", "PV 100", "标准正片 · 准确", "digital", "pv", Body.RANGEFINDER, 0xFF3B3B3B, 0xFFD3D6DA, 0xFF7FB2E5),
        Cam("vv50", "VV 50", "鲜艳正片 · 蓝绿浓", "digital", "vv", Body.RANGEFINDER, 0xFF1F3A2E, 0xFFD3D6DA, 0xFF5BD18B),
        Cam("as100", "AS 100", "柔和人像 · 低反差", "digital", "as", Body.RANGEFINDER, 0xFFB79B83, 0xFFF0E8DD, 0xFFE58E7A),
        Cam("ra400", "RA 400", "写实 · 硬朗准确", "digital", "ra", Body.SLR, 0xFF2D2F33, 0xFFB5BAC0, 0xFF43A0E0),
        Cam("ln", "LN Neo", "经典新调 · 偏青复古", "digital", "ln", Body.RANGEFINDER, 0xFF1D1D1D, 0xFFC4C7CB, 0xFFD32F2F),
        Cam("fl7", "FL 7", "胶片感 · 柔和", "digital", "fl", Body.SLR, 0xFF2A2A2A, 0xFF6B6B6B, 0xFFF39C12),
        Cam("in7", "IN 7", "即时 · 明亮", "digital", "in", Body.SLR, 0xFF333333, 0xFF777777, 0xFFE67E22),
        Cam("sh7", "SH 7", "柔和高调 · 通透", "digital", "sh", Body.CCD, 0xFFE8E4DE, 0xFFB5AFA6, 0xFFE8A0A0),
        Cam("vv2", "VV2", "鲜艳 · 饱和", "digital", "vv2", Body.CCD, 0xFFD94C4C, 0xFF9E2F2F, 0xFFFFE082),
        Cam("dn", "DN Air", "航拍 · 自然", "digital", "dn", Body.CCD, 0xFF6F7680, 0xFF41464D, 0xFFFFFFFF),
        Cam("vl", "VL Air", "清透 · 明快", "digital", "vl", Body.CCD, 0xFFB7C3CE, 0xFF7E8B98, 0xFF2196F3),
        Cam("bm", "BM Pocket", "黑柔 · 柔光高光", "digital", "bm", Body.DV, 0xFF26282C, 0xFF5A5E64, 0xFFFFB74D),

        // 日常
        Cam("crm", "Cream", "奶油 · 柔暖", "daily", "crm", Body.TOY, 0xFFF3E3C7, 0xFFFFFBF2, 0xFFD9A066),
        Cam("jp", "JP Fresh", "日系 · 清新", "daily", "jp", Body.CCD, 0xFFF2F2F0, 0xFFBDBDB8, 0xFF7CC4E0),
        Cam("kr", "KR Soft", "韩系 · 干净", "daily", "kr", Body.CCD, 0xFFE9DCE4, 0xFFB9A8B3, 0xFFD48BA8),
        Cam("pch", "Peach", "蜜桃 · 粉嫩", "daily", "pch", Body.TOY, 0xFFF7B8A2, 0xFFFFF0EA, 0xFFE76F51),
        Cam("for", "Forest", "森系 · 绿意", "daily", "for", Body.TOY, 0xFF7FA37A, 0xFFF0F5EC, 0xFF3E6B3A),

        // 黑白
        Cam("tx400", "TX 400", "街头黑白 · 粗颗粒", "mono", "tx", Body.SLR, 0xFF1A1A1A, 0xFFBFBFBF, 0xFFFFFFFF),
        Cam("ac100", "AC 100", "细腻黑白", "mono", "ac", Body.RANGEFINDER, 0xFF2B2B2B, 0xFFCFCFCF, 0xFF9E9E9E),
        Cam("gh", "GH 3200", "高对比黑白 · 硬调", "mono", "gh", Body.COMPACT, 0xFF111111, 0xFF3A3A3A, 0xFFE0E0E0),
        Cam("lm", "LM 10", "层次黑白", "mono", "lm", Body.RANGEFINDER, 0xFF141414, 0xFFB0B0B0, 0xFFD32F2F),
        Cam("hp5", "HP 66", "柔和黑白 · 中画幅", "mono", "hp", Body.TLR, 0xFF1E1E1E, 0xFFB8B8B8, 0xFFE0E0E0, ratio = 1f),

        // 趣味：自带一个配件
        Cam("fish", "Fish 180", "鱼眼 · 圆形畸变", "fun", "cpm", Body.TOY, 0xFF2F80ED, 0xFFE8F1FD, 0xFFFFD43B, ratio = 1f, effect = Effect.FISHEYE),
        Cam("prism", "Prism", "棱镜 · 色散重影", "fun", "hk", Body.TOY, 0xFF8E44AD, 0xFFF3E9F8, 0xFF1ABC9C, effect = Effect.RGB),
        Cam("double", "Double X", "双重曝光 · 拍两张叠在一起", "fun", "cn", Body.RANGEFINDER, 0xFF3A3A3A, 0xFFE0C9A6, 0xFFE57373, effect = Effect.DOUBLE),
        Cam("star", "Star 6", "星芒 · 灯光变星星", "fun", "cs", Body.SLR, 0xFF1F2A44, 0xFFC5CAE9, 0xFFFFEB3B, effect = Effect.STAR),
        Cam("night", "Night V", "夜视 · 绿屏", "fun", "none", Body.DV, 0xFF3B4A2F, 0xFF6B7A55, 0xFF76FF03, effect = Effect.NIGHT),
        Cam("thermo", "Thermo", "热成像", "fun", "none", Body.DV, 0xFF263238, 0xFF546E7A, 0xFFFF5722, effect = Effect.THERMAL),
        Cam("pixel", "Pixel 8", "像素 · 8 位游戏机", "fun", "none", Body.TOY, 0xFF9E9E9E, 0xFFE0E0E0, 0xFF7B1FA2, ratio = 1f, effect = Effect.PIXEL),
        Cam("tv", "Old TV", "老电视 · 扫描线", "fun", "dv", Body.DV, 0xFF6D4C41, 0xFFBCAAA4, 0xFFFFCA28, ratio = 4f / 3f, effect = Effect.TV),
    )

    fun resolve(catalog: FilterCatalog): List<ResolvedCam> {
        val byId = catalog.filters.associateBy { it.id }
        return all.mapNotNull { c -> (byId[c.filterId] ?: if (c.filterId == "none") Filters.NONE else null)?.let { ResolvedCam(c, it) } }
    }
}

/** 漏光从画面四边随机一处渗进来（坐标左上原点，稍微出界更自然）。 */
fun randomLeak(amount: Float, seed: Int): Leak {
    val r = Random(seed)
    val along = 0.15f + r.nextFloat() * 0.7f
    val (x, y) = when (r.nextInt(4)) {
        0 -> -0.05f to along
        1 -> 1.05f to along
        2 -> along to -0.05f
        else -> along to 1.05f
    }
    return Leak(amount * (0.75f + r.nextFloat() * 0.5f), x, y)
}
