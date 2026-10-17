package dev.omalauncher.widgets.render

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ShaderBrush
import dev.omalauncher.ui.Palette
import dev.omalauncher.widgets.lang.Builtins
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.num

/** On Android 13 and later a Shader node draws (AGSL); before that, its fallback color. */
internal actual val shaderNode: (@Composable (Props, Modifier) -> Unit)? =
    if (Build.VERSION.SDK_INT >= 33) { p, m -> ShaderNode(p, m) } else null

/** A Web node draws on the phone, on its widget's screen. */
internal actual val webNode: (@Composable (Props, Modifier) -> Unit)? = { p, m -> WebNode(p, m) }

/** Declared for every shader; widget code must not declare them again. */
private const val SHADER_HEADER = "uniform float2 size;\nuniform float time;\n"

/** A full-bleed AGSL fragment shader: `half4 main(float2 fragCoord)`, redrawn every frame it reads time. */
@RequiresApi(33)
@Composable
internal fun ShaderNode(
    p: Props,
    modifier: Modifier,
) {
    val inst = p.inst
    val code = p.observe("code")?.toString().orEmpty()
    val shader = remember(code) { runCatching { RuntimeShader(SHADER_HEADER + code) }.onFailure { inst.report("Shader: ${it.message}") }.getOrNull() }
    val fallback = p.color("fallback") { Palette.surface }
    if (shader == null) {
        Spacer(modifier.drawBehind { drawRect(fallback()) })
        return
    }
    val brush = remember(shader) { ShaderBrush(shader) }
    Spacer(
        modifier.drawBehind {
            val started = System.nanoTime()
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("time", inst.time().toFloat())
            (p.now("uniforms") as? Obj)?.fields?.forEach { (name, v) ->
                try {
                    when {
                        v is Lst -> shader.setFloatUniform(name, v.items.map { f -> f.num()?.toFloat() ?: 0f }.toFloatArray())
                        v.num() != null -> shader.setFloatUniform(name, v.num()!!.toFloat())
                        else ->
                            Builtins.argb(v, inst)?.let { c ->
                                val col = androidx.compose.ui.graphics.Color(c)
                                shader.setFloatUniform(name, col.red, col.green, col.blue, col.alpha)
                            }
                    }
                } catch (e: IllegalArgumentException) {
                    inst.report("Shader uniform '$name': ${e.message}")
                }
            }
            drawRect(brush)
            inst.recordDraw(System.nanoTime() - started)
        },
    )
}
