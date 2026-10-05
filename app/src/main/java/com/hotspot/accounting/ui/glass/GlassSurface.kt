package com.hotspot.accounting.ui.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A translucent, blurred "material" surface in the spirit of iOS 26's Liquid Glass.
 *
 * ## How the blur actually works
 *
 * `Modifier.blur()` blurs the composable it is applied to, not what is painted behind it — so it
 * cannot frost the backdrop on its own. It can, however, blur a *translucent* fill laid over the
 * backdrop, which is what produces the frosted look: the tint is diffused, and because it is
 * see-through the colours behind it come through softened. Content is added in a separate inner Box
 * so text stays crisp.
 *
 * An earlier attempt assigned `android.graphics.RenderEffect` to `graphicsLayer`, which does not
 * compile: that slot takes Compose's own `RenderEffect` type. Using `Modifier.blur` is both correct
 * and simpler, because it already no-ops below API 31 (where `RenderEffect` does not exist) instead
 * of requiring a version branch here.
 *
 * ## Why the backdrop matters
 *
 * Glass is only visible when something is behind it. [GlassBackdrop] supplies that: over a flat
 * colour, a translucent card is indistinguishable from a tinted panel.
 */
enum class GlassMaterial(
    /** Tint applied over whatever is behind the pane. */
    val tint: Color,
    /** How strongly the backdrop is diffused, in dp. */
    val blurRadius: Dp,
    /** Opacity of the hairline rim that catches light at the edges. */
    val rimAlpha: Float,
) {
    /** Barely-there chrome for bars overlaying content. */
    ULTRA_THIN(Color(0x14FFFFFF), 18.dp, 0.30f),

    /** Default content cards. */
    REGULAR(Color(0x1FFFFFFF), 24.dp, 0.38f),

    /** Panels that must hold text against a busy backdrop. */
    THICK(Color(0x2EFFFFFF), 32.dp, 0.46f),

    /** Bottom navigation and toolbars. */
    CHROME(Color(0x1AFFFFFF), 28.dp, 0.34f),
}

/** True when the platform can blur a layer, i.e. the pane can be genuinely frosted. */
val glassBlurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Renders [content] inside a translucent glass pane of the given [shape].
 *
 * Layer order, outermost first:
 *  1. an elevated, shape-clipped layer so the pane appears to float above the backdrop;
 *  2. the blurred translucent fill;
 *  3. a soft top-leading sheen, mimicking light catching a curved edge;
 *  4. a hairline rim, which is what actually sells the material boundary.
 */
@Composable
fun GlassPane(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    material: GlassMaterial = GlassMaterial.REGULAR,
    /** Extra tint layered over the material, for accent panes such as warnings. */
    accent: Color = Color.Transparent,
    contentPadding: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val fill = if (accent == Color.Transparent) material.tint
    else accent.copy(alpha = material.tint.alpha)

    Box(
        modifier = modifier
            .graphicsLayer {
                shadowElevation = 18f
                this.shape = shape
                clip = true
            }
            .clip(shape)
            .background(fill)
            // Blurs the translucent fill over the backdrop, which reads as frosted glass. No-ops
            // below API 31, where RenderEffect is unavailable.
            .blur(material.blurRadius, edgeTreatment = BlurredEdgeTreatment(shape))
            .drawWithContent {
                drawContent()
                // Sheen: brighter at the top-leading corner, fading away, as if lit from above.
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.16f * (material.rimAlpha / 0.38f)),
                            Color.Transparent,
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height * 0.9f),
                    ),
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = material.rimAlpha),
                        Color.White.copy(alpha = material.rimAlpha * 0.25f),
                        Color.White.copy(alpha = material.rimAlpha * 0.6f),
                    ),
                ),
                shape = shape,
            ),
    ) {
        Box(modifier = Modifier.padding(contentPadding)) { content() }
    }
}

/**
 * The colourful, non-uniform backdrop that makes translucency legible.
 *
 * Deliberately not a flat colour. Soft radial blooms of accent colour give the blurred panes
 * something to diffuse; positions are fixed rather than animated, because moving blooms make every
 * pane shimmer as it recomposes.
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    scheme.background,
                    scheme.surfaceVariant.copy(alpha = 0.55f),
                    scheme.background,
                ),
            )
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val blooms = listOf(
                        Triple(scheme.primary, Offset(size.width * 0.16f, size.height * 0.12f), 0.72f),
                        Triple(scheme.tertiary, Offset(size.width * 0.92f, size.height * 0.74f), 0.62f),
                        Triple(scheme.secondary, Offset(size.width * 0.72f, size.height * 0.30f), 0.48f),
                    )
                    for ((colour, centre, scale) in blooms) {
                        val radius = size.minDimension * scale
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(colour.copy(alpha = 0.34f), Color.Transparent),
                                center = centre,
                                radius = radius,
                            ),
                            radius = radius,
                            center = centre,
                        )
                    }
                }
        )
        content()
    }
}
