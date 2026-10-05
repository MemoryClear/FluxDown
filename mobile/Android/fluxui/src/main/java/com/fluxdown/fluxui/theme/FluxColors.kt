package com.fluxdown.fluxui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 文件分类（分类色点只做辅助提示；权威信号是图块内的类别图标）。 */
enum class FileCategory { Video, Audio, Document, Image, Program, Archive, Ebook, Other }

/**
 * Flux Lumen 色板：12 阶中性灰 + 单一发光强调色（及其派生）+ 三个降饱和状态色 + 8 个分类色点。
 * 其余颜色不存在。业务视图只能经 [FluxTheme.colors] 读取（docs 01-foundations §2 / §11.1）。
 */
@Immutable
class FluxColors(
    val dark: Boolean,
    /** n1..n12（ramp[0] = canvas）。保留阶用于降级实色、图表网格。 */
    val ramp: List<Color>,
    val canvas: Color,
    val ink: Color,
    val inkMuted: Color,
    /** 仅用于占位符、尾随次要元信息、chev 与装饰；不承载唯一信息（对比度 ≈4.1）。 */
    val inkFaint: Color,
    val glass1: Color,
    val glass2: Color,
    val glass3: Color,
    val glass4: Color,
    val glassSolid1: Color,
    val glassSolid2: Color,
    val glassSolid3: Color,
    val glassSolid4: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val highlight: Color,
    val sheetBg: Color,
    val menuBg: Color,
    val dim: Color,
    val scrimTopFrom: Color,
    val accent: Color,
    val accentHi: Color,
    val accentLo: Color,
    val accentMid: Color,
    val accentGlow: Color,
    val accentFillA: Color,
    val accentFillB: Color,
    val onAccent: Color,
    val coral: Color,
    val coralText: Color,
    val mint: Color,
    val mintText: Color,
    val amber: Color,
    val amberText: Color,
    val onCoral: Color,
    val violet: Color,
    val flowDone: Color,
    val flowRemain: Color,
    val flowFail: Color,
    private val categoryColors: Array<Color>,
    val grainAlpha: Float,
    val grainBlend: BlendMode,
    val auraModeGain: Float,
) {
    fun category(c: FileCategory): Color = categoryColors[c.ordinal]

    /** `canvas X% + glassN` 的复合底（Oklab 预乘插值，等价 CSS color-mix）。 */
    fun mixCanvas(glass: Color, canvasFraction: Float): Color = lerp(glass, canvas, canvasFraction)

    companion object {
        private val DarkRamp = longArrayOf(
            0xFF07080A, 0xFF0B0D10, 0xFF101216, 0xFF15181D, 0xFF1B1E25, 0xFF232730,
            0xFF2F3440, 0xFF424857, 0xFF6B7183, 0xFF9298A8, 0xFFC3C7D2, 0xFFF1F2F5,
        ).map(::Color)
        private val PaperRamp = longArrayOf(
            0xFFF4F4F1, 0xFFEDEDE9, 0xFFE5E5E0, 0xFFDCDCD6, 0xFFD1D1CA, 0xFFC2C2BB,
            0xFFA9A9A1, 0xFF8B8B83, 0xFF6B6B64, 0xFF4D4D47, 0xFF2E2E2A, 0xFF0F0F0D,
        ).map(::Color)
        private val CategoryDark = longArrayOf(
            0xFFB28CFF, 0xFFFF8AD0, 0xFF6FB4FF, 0xFF5FE3A8, 0xFFFFB064, 0xFFE6D36A, 0xFF5CD6D6, 0xFF8E94A4,
        )

        fun of(dark: Boolean, seed: Color, imported: ImportedPalette? = null): FluxColors {
            val ramp = imported?.ramp(dark) ?: if (dark) DarkRamp else PaperRamp
            val canvas = ramp[0]
            val acc = resolveAccent(seed, dark, canvas)
            val w = Color.White
            val inkRaw = if (dark) Color(0xFFF1F2F5) else Color(0xFF0F0F0D)
            val g = if (dark) floatArrayOf(.04f, .07f, .10f, .14f) else floatArrayOf(.46f, .66f, .82f, .94f)
            val line = if (dark) w else Color(0xFF0F0F0D)
            val coral = Color(if (dark) 0xFFFF5A5F else 0xFFD93A41)
            return FluxColors(
                dark = dark,
                ramp = ramp,
                canvas = canvas,
                ink = ramp[11],
                inkMuted = if (dark || imported != null) ramp[9] else Color(0xFF55554F),
                inkFaint = if (dark || imported != null) ramp[8] else Color(0xFF77776F),
                glass1 = w.copy(alpha = g[0]),
                glass2 = w.copy(alpha = g[1]),
                glass3 = w.copy(alpha = g[2]),
                glass4 = w.copy(alpha = g[3]),
                glassSolid1 = w.copy(alpha = g[0]).compositeOver(canvas),
                glassSolid2 = w.copy(alpha = g[1]).compositeOver(canvas),
                glassSolid3 = w.copy(alpha = g[2]).compositeOver(canvas),
                glassSolid4 = w.copy(alpha = g[3]).compositeOver(canvas),
                hairline = line.copy(alpha = if (dark) .08f else .09f),
                hairlineStrong = line.copy(alpha = if (dark) .16f else .18f),
                highlight = w.copy(alpha = if (dark) .13f else .90f),
                sheetBg = if (dark) Color(20, 22, 28).copy(alpha = .74f) else Color(250, 250, 247).copy(alpha = .80f),
                menuBg = if (dark) Color(26, 29, 36).copy(alpha = .78f) else Color(252, 252, 249).copy(alpha = .84f),
                dim = if (dark) Color(2, 3, 5).copy(alpha = .55f) else Color(244, 244, 241).copy(alpha = .55f),
                scrimTopFrom = canvas.copy(alpha = if (dark) .85f else .90f),
                accent = seed,
                accentHi = acc.hi,
                accentLo = seed.copy(alpha = .16f),
                accentMid = seed.copy(alpha = .34f),
                accentGlow = acc.hi.copy(alpha = .55f),
                accentFillA = acc.fillA,
                accentFillB = acc.fillB,
                onAccent = acc.on,
                coral = coral,
                coralText = Color(if (dark) 0xFFFF7A7F else 0xFFC42A31),
                mint = Color(if (dark) 0xFF3DDC97 else 0xFF0E9F63),
                mintText = Color(if (dark) 0xFF3DDC97 else 0xFF097F4E),
                amber = Color(if (dark) 0xFFF5B544 else 0xFFB7791F),
                amberText = Color(if (dark) 0xFFF5B544 else 0xFF946016),
                onCoral = if (dark) Color(0xFF04101F) else w,
                violet = Color(0xFF8B5CF6),
                flowDone = inkRaw.copy(alpha = if (dark) .52f else .55f),
                flowRemain = inkRaw.copy(alpha = .08f),
                flowFail = coral.copy(alpha = if (dark) .70f else .75f),
                categoryColors = Array(CategoryDark.size) { i ->
                    Color(CategoryDark[i]).let { if (dark) it else lerp(it, Color.Black, .26f) }
                },
                grainAlpha = if (dark) .035f else .05f,
                grainBlend = if (dark) BlendMode.Softlight else BlendMode.Multiply,
                auraModeGain = if (dark) 1f else .55f,
            )
        }
    }

    /** 静态资产常量（启动图标、小组件首帧、通知 tint），运行时颜色一律走派生值。 */
    object Brand {
        val glowBlue = Color(0xFF5B9BFF)
        val flux = Color(0xFF3B82F6)
    }
}

/** 导入主题（ThemeDocument v2）两端色 → 12 阶灰（§2.9）。 */
@Immutable
class ImportedPalette(val background: Color, val foreground: Color) {
    fun ramp(dark: Boolean): List<Color> = (if (dark) T_DARK else T_PAPER).map { lerp(background, foreground, it) }

    private companion object {
        val T_DARK = floatArrayOf(0f, .029f, .058f, .090f, .122f, .168f, .231f, .324f, .503f, .660f, .841f, 1f)
        val T_PAPER = floatArrayOf(0f, .027f, .057f, .092f, .135f, .193f, .293f, .416f, .552f, .686f, .835f, 1f)
    }
}

@Immutable
data class AccentSlots(val fillA: Color, val fillB: Color, val on: Color, val hi: Color)

/** WCAG 2.x 对比度。 */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + .05f
    val lb = b.luminance() + .05f
    return max(la, lb) / min(la, lb)
}

private fun minContrast(text: Color, a: Color, b: Color) = min(contrast(text, a), contrast(text, b))

/**
 * 强调色护栏（§2.3）：保证按钮标签对渐变两端 ≥ 4.5:1，accentHi 对 canvas ≥ 4.5:1。
 * 纯函数；`lerp` 即 Oklab 插值，与 CSS `color-mix(in oklab)` 等价。
 */
fun resolveAccent(seed: Color, dark: Boolean, canvas: Color): AccentSlots {
    val inkOn = Color(0xFF04101F)
    val white = Color.White
    var fa = if (dark) lerp(seed, white, .24f) else lerp(seed, white, .08f)
    var fb = if (dark) seed else lerp(seed, Color.Black, .12f)
    val pref = if (dark) inkOn else white
    val alt = if (dark) white else inkOn
    var on = pref
    if (minContrast(pref, fa, fb) < 4.5f) {
        if (minContrast(alt, fa, fb) >= 4.5f) {
            on = alt
        } else {
            val to = if (dark) white else Color.Black
            var i = 0
            while (i < 40 && minContrast(pref, fa, fb) < 4.5f) {
                fa = lerp(fa, to, .04f)
                fb = lerp(fb, to, .04f)
                i++
            }
        }
    }
    var hi = if (dark) lerp(seed, white, .24f) else lerp(seed, Color.Black, .12f)
    if (!dark) {
        var k = .12f
        while (contrast(hi, canvas) < 4.5f && k < .8f) {
            k += .02f
            hi = lerp(seed, Color.Black, k)
        }
    }
    return AccentSlots(fa, fb, on, hi)
}

/** OKLab 彩度 sqrt(a²+b²)，用于判定壁纸取色是否过灰（< 0.04 回退品牌蓝）。 */
fun Color.oklabChroma(): Float {
    val c = convert(androidx.compose.ui.graphics.colorspace.ColorSpaces.Oklab)
    return sqrt(c.green * c.green + c.blue * c.blue)
}
