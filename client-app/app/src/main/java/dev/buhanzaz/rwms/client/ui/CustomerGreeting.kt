package dev.buhanzaz.rwms.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.res.vectorResource
import dev.buhanzaz.rwms.client.R
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Refracts the imported SVG contours in their actual layout bounds. The wave settles to zero before
 * drawing the unchanged image, so entry uses one logo with the same scale, insets and position.
 * Contours are sampled once, in vector units; frames reuse one path and never scale a video/bitmap.
 */
@Composable
internal fun Modifier.customerGreetingWave(progress: () -> Float): Modifier {
    val vector = ImageVector.vectorResource(R.drawable.block_box_logo_svg)
    val contours = remember(vector) {
        vector.root.map { node ->
            val path = node as VectorPath
            path to sampleCustomerLogoContour(path)
        }
    }
    return drawWithCache {
        val vectorScale = minOf(size.width / vector.viewportWidth, size.height / vector.viewportHeight)
        val left = (size.width - vector.viewportWidth * vectorScale) / 2f
        val top = (size.height - vector.viewportHeight * vectorScale) / 2f
        val wavePath = Path()
        val sheen = Brush.linearGradient(
            0f to Color.Transparent,
            0.35f to Color.Transparent,
            0.5f to Color.White,
            0.65f to Color.Transparent,
            1f to Color.Transparent,
            start = Offset(-46f, -12f),
            end = Offset(46f, 108f),
        )
        onDrawWithContent {
            val phase = progress().coerceIn(0f, 1f)
            if (phase >= 0.72f) {
                drawContent()
            } else {
                val opacity = customerGreetingReveal(phase, 0f, 0.42f)
                val amplitude = 1f - customerGreetingReveal(phase, 0f, 0.72f)
                val travel = phase * 2f * PI.toFloat()
                val lightProgress = customerGreetingReveal(phase, 0.08f, 0.72f)
                val lightOpacity = 0.36f * sin(PI.toFloat() * lightProgress) * opacity
                val lightX = -100f + lightProgress * (vector.viewportWidth + 200f)
                translate(left, top) {
                    scale(vectorScale, vectorScale, Offset.Zero) {
                        contours.forEachIndexed { part, (source, samples) ->
                            wavePath.rewind()
                            wavePath.fillType = source.pathFillType
                            var index = 0
                            while (index < samples.size) {
                                val x = samples[index + 1]
                                val y = samples[index + 2]
                                val u = x / vector.viewportWidth
                                val v = y / vector.viewportHeight
                                val horizontalWave = sin(v * 2f * PI.toFloat() - travel)
                                val verticalWave = sin(u * 3f * PI.toFloat() - travel)
                                val crossingWave = sin((u + v) * 2f * PI.toFloat() + travel * 0.7f)
                                val displacedX = x + amplitude * (11f * horizontalWave + 3f * crossingWave)
                                val displacedY = y + amplitude * (7f * verticalWave + 2f * crossingWave)
                                // Equal fractions delimit separate SVG contours, including letter counters.
                                if (index == 0 || samples[index] == samples[index - 3]) {
                                    if (index > 0) wavePath.close()
                                    wavePath.moveTo(displacedX, displacedY)
                                } else {
                                    wavePath.lineTo(displacedX, displacedY)
                                }
                                index += 3
                            }
                            wavePath.close()
                            val direction = if (part == 0) -1f else 1f
                            translate(direction * 4f * amplitude, direction * 3f * amplitude) {
                                rotate(direction * 4f * amplitude, Offset(vector.viewportWidth / 2f, vector.viewportHeight / 2f)) {
                                    drawPath(wavePath, checkNotNull(source.fill), alpha = opacity * source.fillAlpha)
                                    clipPath(wavePath) {
                                        translate(left = lightX) {
                                            drawRect(
                                                brush = sheen,
                                                topLeft = Offset(-100f, -36f),
                                                size = Size(200f, vector.viewportHeight + 72f),
                                                alpha = lightOpacity,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sampleCustomerLogoContour(path: VectorPath): FloatArray {
    val samples = PathParser().addPathNodes(path.pathData).toPath().asAndroidPath().approximate(0.02f)
    return buildList {
        var index = 0
        while (index < samples.size) {
            if (index > 0 && samples[index] != samples[index - 3]) {
                val dx = samples[index + 1] - samples[index - 2]
                val dy = samples[index + 2] - samples[index - 1]
                // Path.approximate samples curves only; straight box edges need wave vertices too.
                val steps = ceil(hypot(dx, dy)).toInt().coerceAtLeast(1)
                for (step in 1 until steps) {
                    val fraction = step.toFloat() / steps
                    add(samples[index - 3] + (samples[index] - samples[index - 3]) * fraction)
                    add(samples[index - 2] + dx * fraction)
                    add(samples[index - 1] + dy * fraction)
                }
            }
            add(samples[index])
            add(samples[index + 1])
            add(samples[index + 2])
            index += 3
        }
    }.toFloatArray()
}

/** Smooth endpoints keep both refraction and the following control reveal from snapping. */
internal fun customerGreetingReveal(progress: Float, start: Float, end: Float): Float {
    val fraction = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}
