package dev.yusuf.falconlauncher.ui

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI

private const val CYCLE_MS = 40_000L
private const val FRAME_MS = 33L

/**
 * Slowly drifting light in the featured colors over true black.
 *
 * Rendered by a tiny OpenGL fragment shader at 960×540 in its own GLSurfaceView: its own EGL
 * context and thread, completely separate from the launcher UI, so the UI never redraws for it and
 * the CPU does no painting. The shader adds per-pixel dithering so the soft gradients don't band.
 *
 * Over true black, darkening gently toward the bottom while still letting the color reach the edge. Colors come from the playing song's artwork, or otherwise stay close to the focused app.
 */
@Composable
fun AmbientBackground(colors: () -> List<Color>, modifier: Modifier = Modifier, paused: Boolean = false) {
    val renderer = remember { AmbientGlRenderer() }
    LaunchedEffect(renderer) {
        snapshotFlow { colors().map { it.toArgb() } }.collect { renderer.setColors(it) }
    }
    val viewRef = remember { arrayOfNulls<GLSurfaceView>(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewRef[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> viewRef[0]?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Paused while something covers the screen (e.g. the lights page), so it doesn't compete for the GPU.
    LaunchedEffect(paused) { viewRef[0]?.let { if (paused) it.onPause() else it.onResume() } }
    AndroidView(
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 0, 0, 0)
                // Half of 1080p: a quarter of the shader work, scaled up by the display hardware. Soft light
                // with dithering looks the same, and the UI keeps the GPU for itself.
                holder.setFixedSize(960, 540)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                viewRef[0] = this
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

private class AmbientGlRenderer : GLSurfaceView.Renderer {
    @Volatile
    private var colors = floatArrayOf(0.2f, 0.28f, 0.48f, 0.2f, 0.28f, 0.48f, 0.2f, 0.28f, 0.48f)
    private var program = 0
    private var width = 1
    private var height = 1
    private val start = SystemClock.uptimeMillis()
    private var lastFrame = 0L

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

    fun setColors(argb: List<Int>) {
        val out = FloatArray(9)
        for (i in 0 until 3) {
            val c = argb.getOrElse(i) { argb.firstOrNull() ?: 0 }
            out[i * 3] = ((c shr 16) and 0xFF) / 255f
            out[i * 3 + 1] = ((c shr 8) and 0xFF) / 255f
            out[i * 3 + 2] = (c and 0xFF) / 255f
        }
        colors = out
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        // The light drifts slowly; 30 fps looks identical to 60 and halves the GPU work.
        val now = SystemClock.uptimeMillis()
        val wait = FRAME_MS - (now - lastFrame)
        if (wait > 0) Thread.sleep(wait)
        lastFrame = SystemClock.uptimeMillis()

        val phase = (((lastFrame - start) % CYCLE_MS).toFloat() / CYCLE_MS) * (2 * PI).toFloat()
        val c = colors
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uRes"), width.toFloat(), height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uPhase"), phase)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uC0"), c[0], c[1], c[2])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uC1"), c[3], c[4], c[5])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uC2"), c[6], c[7], c[8])
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it)
        }
    }

    private companion object {
        const val VERTEX = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        // Four soft lights composited over black (same layout and motion as before), a fade to black
        // below the dock, and per-pixel dither against banding. Only integer multiples of the phase
        // are used, so the 40 s loop has no seam.
        const val FRAGMENT = """
            precision highp float;
            uniform vec2 uRes;
            uniform float uPhase;
            uniform vec3 uC0;
            uniform vec3 uC1;
            uniform vec3 uC2;

            float glow(vec2 px, vec2 center, float radius) {
                float t = clamp(distance(px, center) / radius, 0.0, 1.0);
                // Matches the old gradient stops: 1 at the center, ~0.35 halfway, 0 at the edge.
                return (1.0 - t) * (1.0 - t) * (1.0 + 0.4 * (1.0 - t));
            }

            vec3 over(vec3 base, vec3 color, float alpha) { return mix(base, color, clamp(alpha, 0.0, 1.0)); }

            void main() {
                vec2 px = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
                float w = uRes.x;
                float h = uRes.y;
                float p = uPhase;
                float breathe = 0.82 + 0.18 * sin(2.0 * p);

                vec3 col = vec3(0.0);
                col = over(col, uC0, 0.85 * breathe * glow(px, vec2(w * (0.32 + 0.09 * sin(p)), h * (0.42 + 0.10 * cos(2.0 * p))), w * 0.78));
                col = over(col, uC1, 0.5 * glow(px, vec2(w * (0.74 + 0.10 * cos(p + 1.0)), h * (0.28 + 0.12 * sin(2.0 * p))), w * 0.6));
                col = over(col, uC2, 0.32 * glow(px, vec2(w * (0.14 + 0.08 * sin(3.0 * p)), h * (0.14 + 0.08 * cos(p))), w * 0.48));
                col = over(col, uC1 * 0.6, 0.2 * (0.5 + 0.5 * sin(p + 2.0)) * glow(px, vec2(w * (0.5 + 0.35 * cos(p)), h * (0.7 + 0.1 * sin(p))), w * 0.55));

                // Darken gently toward the bottom so the dock reads clearly, but let the color reach the edge.
                col *= 1.0 - 0.45 * smoothstep(0.45, 1.0, px.y / h);

                // Dither: +/- half a step of 8-bit color, from a per-pixel hash.
                float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
                col += (n - 0.5) / 255.0;

                gl_FragColor = vec4(col, 1.0);
            }
        """
    }
}
