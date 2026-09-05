package com.quest.jellyquest.streaming

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU pass-through between ExoPlayer and the theater screen surface that also
 * samples the video's average color — the engine behind the theater's ambient
 * "bias lighting."
 *
 * ExoPlayer renders into a SurfaceTexture we own; each frame is blitted to the
 * panel's surface via GLES2, and every few frames the same texture is rendered
 * into a tiny 16x16 framebuffer, read back, and averaged into a single color
 * delivered via [onColor] (from the GL thread — marshal to main before use).
 *
 * If anything in the GL setup fails, [inputSurface] is null and the caller
 * must fall back to giving ExoPlayer the output surface directly.
 */
class VideoFrameTap(
    private val outputSurface: Surface,
    private val onColor: (r: Float, g: Float, b: Float) -> Unit,
) {

    companion object {
        private const val TAG = "VirtualMonitor"
        private const val SAMPLE_EVERY_N_FRAMES = 3
        private const val SAMPLE_SIZE = 16

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }

    private val thread = HandlerThread("VideoFrameTap").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglWindowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var oesTextureId = 0
    private var fboId = 0
    private var fboTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    @Volatile
    var inputSurface: Surface? = null
        private set

    private val texMatrix = FloatArray(16)
    private var frameCount = 0
    private val samplePixels: ByteBuffer =
        ByteBuffer.allocateDirect(SAMPLE_SIZE * SAMPLE_SIZE * 4).order(ByteOrder.nativeOrder())
    private val quadPositions: FloatBuffer = floatBufferOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f,
    )
    private val quadTexCoords: FloatBuffer = floatBufferOf(
        0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f,
    )

    @Volatile
    private var released = false

    init {
        val setupDone = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                setupGl()
            } catch (e: Throwable) {
                Log.e(TAG, "VideoFrameTap GL setup failed — falling back to direct surface", e)
                inputSurface = null
            } finally {
                setupDone.countDown()
            }
        }
        setupDone.await()
    }

    private fun setupGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "No EGL config" }
        val config = configs[0]!!

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglWindowSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(eglWindowSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext)) {
            "eglMakeCurrent failed"
        }

        program = buildProgram()

        // External OES texture that ExoPlayer decodes into.
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // Tiny FBO for the color sample readback.
        GLES20.glGenTextures(1, textures, 0)
        fboTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SAMPLE_SIZE, SAMPLE_SIZE,
            0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId, 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val st = SurfaceTexture(oesTextureId)
        st.setOnFrameAvailableListener({ frameAvailable() }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
        Log.i(TAG, "VideoFrameTap ready (GL pass-through with ${SAMPLE_SIZE}x$SAMPLE_SIZE color sampling)")
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}" }
        return prog
    }

    private fun frameAvailable() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            // Pass the frame through to the screen.
            val width = IntArray(1)
            val height = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_WIDTH, width, 0)
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_HEIGHT, height, 0)
            drawQuad(0, width[0], height[0])
            EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface)

            // Periodically sample the average color from a tiny render.
            if (++frameCount % SAMPLE_EVERY_N_FRAMES == 0) {
                drawQuad(fboId, SAMPLE_SIZE, SAMPLE_SIZE)
                samplePixels.rewind()
                GLES20.glReadPixels(
                    0, 0, SAMPLE_SIZE, SAMPLE_SIZE,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, samplePixels,
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                deliverAverage()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "VideoFrameTap frame error: ${e.message}")
        }
    }

    private fun drawQuad(framebuffer: Int, width: Int, height: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")

        quadPositions.rewind()
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quadPositions)
        GLES20.glEnableVertexAttribArray(aPosition)
        quadTexCoords.rewind()
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTexture, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun deliverAverage() {
        samplePixels.rewind()
        var r = 0L
        var g = 0L
        var b = 0L
        val count = SAMPLE_SIZE * SAMPLE_SIZE
        for (i in 0 until count) {
            r += samplePixels.get().toInt() and 0xFF
            g += samplePixels.get().toInt() and 0xFF
            b += samplePixels.get().toInt() and 0xFF
            samplePixels.get() // alpha
        }
        onColor(r / (count * 255f), g / (count * 255f), b / (count * 255f))
    }

    fun release() {
        released = true
        handler.post {
            try {
                inputSurface?.release()
                surfaceTexture?.release()
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                    )
                    if (eglWindowSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglWindowSurface)
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    EGL14.eglTerminate(eglDisplay)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "VideoFrameTap release error: ${e.message}")
            }
            thread.quitSafely()
        }
    }
}

private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(values); rewind() }
