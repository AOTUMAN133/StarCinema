package com.starcinema.player.kernel.impl.exo

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.starcinema.player.kernel.VideoLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * EXO-only Dolby Vision Profile 5 compositor.
 *
 * The expensive IPTPQc2, tone-map and gamut conversion is precomputed into a
 * 65^3 RGB16F LUT. Each rendered pixel performs one external-YUV sample and one
 * trilinear LUT sample, directly into the window surface with no FBO/blit pass.
 */
class DoviGlCompositor(private val target: Surface) {

    private data class GlProgram(
        val id: Int,
        val position: Int,
        val texCoord: Int,
        val texMatrix: Int,
        val texture: Int,
        val colorLut: Int
    )

    private val glThread = HandlerThread("dovi-gl-p5").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var inputSurface: Surface? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var p5Program: GlProgram? = null
    private var fallbackProgram: GlProgram? = null

    private var externalTextureId = 0
    private var colorLutTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    private var viewportW = 1
    private var viewportH = 1
    private var released = false
    private var renderQueued = false

    private lateinit var vertexBuffer: FloatBuffer
    private val textureMatrix = FloatArray(16)

    init {
        glHandler.post {
            try {
                initGl()
            } catch (error: Throwable) {
                VideoLog.e("$TAG init failed: ${error.message}")
                cleanupGl()
            } finally {
                readyLatch.countDown()
                if (inputSurface == null) {
                    glThread.quitSafely()
                }
            }
        }
        readyLatch.await()
        if (inputSurface == null) {
            throw IllegalStateException("DoviGlCompositor init failed")
        }
    }

    fun getInputSurface(): Surface = inputSurface ?: throw IllegalStateException("not ready")

    fun isReady(): Boolean = inputSurface != null

    fun matchesTarget(surface: Surface): Boolean = target === surface

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("eglGetDisplay failed")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize failed")
        }
        eglConfig = chooseConfig()
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("eglCreateContext ES3 failed")
        }
        createWindowSurface()
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }

        fallbackProgram = createProgram("rgb-oes", RGB_OES_FRAGMENT_SHADER_ES3, false)
        if (fallbackProgram == null) {
            throw IllegalStateException("RGB-OES fallback shader failed")
        }

        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        if (extensions.split(' ').contains(YUV_EXTENSION)) {
            colorLutTextureId = createColorLutTexture()
            p5Program = createProgram("p5-color-lut", P5_COLOR_FRAGMENT_SHADER, true)
        }
        if (p5Program == null || colorLutTextureId == 0) {
            VideoLog.e("$TAG P5 LUT unavailable; RGB-OES fallback will be used")
        }

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        externalTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        surfaceTexture = SurfaceTexture(externalTextureId)
        surfaceTexture?.setOnFrameAvailableListener(
            { requestRender() },
            glHandler
        )

        vertexBuffer = ByteBuffer
            .allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer.put(QUAD_VERTICES).position(0)

        inputSurface = Surface(surfaceTexture)
        VideoLog.i("$TAG ready viewport=${viewportW}x$viewportH p5Lut=${p5Program != null}")
    }

    private fun createColorLutTexture(): Int {
        val maxSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, maxSize, 0)
        if (maxSize[0] < LUT_SIZE) {
            throw IllegalStateException("GL_MAX_3D_TEXTURE_SIZE=${maxSize[0]} < $LUT_SIZE")
        }

        val buffer = createColorLutBuffer()
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        if (textureId == 0) {
            throw IllegalStateException("3D LUT texture allocation failed")
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGB16F,
            LUT_SIZE,
            LUT_SIZE,
            LUT_SIZE,
            0,
            GLES30.GL_RGB,
            GLES30.GL_FLOAT,
            buffer
        )
        val uploadError = GLES30.glGetError()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
        if (uploadError != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            throw IllegalStateException("3D LUT upload failed error=0x${Integer.toHexString(uploadError)}")
        }
        return textureId
    }

    private fun createColorLutBuffer(): FloatBuffer {
        val entryCount = LUT_SIZE * LUT_SIZE * LUT_SIZE
        val buffer = ByteBuffer
            .allocateDirect(entryCount * 3 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val denominator = (LUT_SIZE - 1).toDouble()
        for (vIndex in 0 until LUT_SIZE) {
            val t = vIndex / denominator - 0.5
            for (uIndex in 0 until LUT_SIZE) {
                val p = uIndex / denominator - 0.5
                for (yIndex in 0 until LUT_SIZE) {
                    val intensity = yIndex / denominator
                    appendP5SdrColor(buffer, intensity, p, t)
                }
            }
        }
        buffer.position(0)
        return buffer
    }

    private fun appendP5SdrColor(
        output: FloatBuffer,
        intensity: Double,
        p: Double,
        t: Double
    ) {
        val lPq = clamp01(intensity + p * 0.09756893051461392 + t * 0.2052264331645916)
        val mPq = clamp01(intensity - p * 0.11387648547314712 + t * 0.13321715836999806)
        val sPq = clamp01(intensity + p * 0.03261510991706641 - t * 0.6768871830691794)

        val l = pqEotf(lPq)
        val m = pqEotf(mPq)
        val s = pqEotf(sPq)

        val rNits = max(0.0, l * 3.238945127267647 - m * 2.3256681338659786 + s * 0.08675798718136027)
        val gNits = max(0.0, -l * 0.7193271248402032 + m * 1.878320846020886 - s * 0.15898003108508518)
        val bNits = max(0.0, -l * 0.002807411439865128 - m * 0.07153879799954892 + s * 1.0743737402016156)

        val luminance = rNits * 0.2627002120112671 +
            gNits * 0.6779980715188708 +
            bNits * 0.05930171646986196
        val toneScale = if (luminance > 0.000001) {
            (1.0 - exp(-luminance / SDR_PAPER_WHITE_NITS)) / luminance
        } else {
            1.0 / SDR_PAPER_WHITE_NITS
        }
        val r2020 = rNits * toneScale
        val g2020 = gNits * toneScale
        val b2020 = bNits * toneScale

        val r709 = clamp01(r2020 * 1.660491 - g2020 * 0.587641 - b2020 * 0.072850)
        val g709 = clamp01(-r2020 * 0.124550 + g2020 * 1.132900 - b2020 * 0.008349)
        val b709 = clamp01(-r2020 * 0.018151 - g2020 * 0.100579 + b2020 * 1.118730)

        output.put(srgbOetf(r709).toFloat())
        output.put(srgbOetf(g709).toFloat())
        output.put(srgbOetf(b709).toFloat())
    }

    private fun pqEotf(value: Double): Double {
        if (value <= 0.0) {
            return 0.0
        }
        val powered = value.pow(1.0 / PQ_M2)
        val numerator = max(powered - PQ_C1, 0.0)
        val denominator = PQ_C2 - PQ_C3 * powered
        if (numerator <= 0.0 || denominator <= 0.0) {
            return 0.0
        }
        return (numerator / denominator).pow(1.0 / PQ_M1) * 10000.0
    }

    private fun srgbOetf(value: Double): Double {
        return if (value < 0.0031308) {
            value * 12.92
        } else {
            1.055 * value.pow(1.0 / 2.4) - 0.055
        }
    }

    private fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)

    private fun createWindowSurface() {
        val config = eglConfig ?: throw IllegalStateException("EGL config missing")
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            target,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface failed error=0x${eglErrorHex()}")
        }
        updateSurfaceSize()
        VideoLog.i("$TAG window surface ${viewportW}x$viewportH")
    }

    private fun updateSurfaceSize() {
        val size = IntArray(2)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, size, 1)
        val width = size[0].coerceAtLeast(1)
        val height = size[1].coerceAtLeast(1)
        if (width != viewportW || height != viewportH) {
            VideoLog.i("$TAG window size ${viewportW}x$viewportH -> ${width}x$height")
            viewportW = width
            viewportH = height
        }
    }

    private fun renderFrame() {
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) {
            return
        }
        val texture = surfaceTexture ?: return
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                VideoLog.e("$TAG eglMakeCurrent failed error=0x${eglErrorHex()}")
                return
            }
            updateSurfaceSize()

            texture.updateTexImage()
            texture.getTransformMatrix(textureMatrix)

            GLES30.glViewport(0, 0, viewportW, viewportH)
            drawQuad(p5Program ?: fallbackProgram ?: return, textureMatrix)

            if (texture.timestamp > 0L) {
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, texture.timestamp)
            }
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                VideoLog.e("$TAG eglSwapBuffers failed error=0x${eglErrorHex()}")
            }
        } catch (error: Throwable) {
            VideoLog.e("$TAG render failed: ${error.message}")
        }
    }

    private fun drawQuad(program: GlProgram, matrix: FloatArray) {
        GLES30.glUseProgram(program.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES30.glUniform1i(program.texture, 0)
        if (program.colorLut >= 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, colorLutTextureId)
            GLES30.glUniform1i(program.colorLut, 1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
        GLES30.glUniformMatrix4fv(program.texMatrix, 1, false, matrix, 0)

        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(program.position)
        GLES30.glVertexAttribPointer(
            program.position,
            2,
            GLES30.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer
        )
        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(program.texCoord)
        GLES30.glVertexAttribPointer(
            program.texCoord,
            2,
            GLES30.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(program.position)
        GLES30.glDisableVertexAttribArray(program.texCoord)
    }

    private fun createProgram(label: String, fragmentSource: String, needsColorLut: Boolean): GlProgram? {
        val vertex = compileShader(label, GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_ES3) ?: return null
        val fragment = compileShader(label, GLES30.GL_FRAGMENT_SHADER, fragmentSource) ?: run {
            GLES30.glDeleteShader(vertex)
            return null
        }
        val programId = GLES30.glCreateProgram()
        if (programId == 0) {
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
            return null
        }
        GLES30.glAttachShader(programId, vertex)
        GLES30.glAttachShader(programId, fragment)
        GLES30.glLinkProgram(programId)
        val status = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        if (status[0] != GLES30.GL_TRUE) {
            VideoLog.e("$TAG $label program link failed: ${GLES30.glGetProgramInfoLog(programId)}")
            GLES30.glDeleteProgram(programId)
            return null
        }
        val result = GlProgram(
            programId,
            GLES30.glGetAttribLocation(programId, "aPosition"),
            GLES30.glGetAttribLocation(programId, "aTexCoord"),
            GLES30.glGetUniformLocation(programId, "uTexMatrix"),
            GLES30.glGetUniformLocation(programId, "uTexture"),
            if (needsColorLut) GLES30.glGetUniformLocation(programId, "uColorLut") else -1
        )
        if (result.position < 0 || result.texCoord < 0 || result.texMatrix < 0 ||
            result.texture < 0 || (needsColorLut && result.colorLut < 0)
        ) {
            VideoLog.e("$TAG $label shader location missing")
            GLES30.glDeleteProgram(programId)
            return null
        }
        return result
    }

    private fun compileShader(label: String, type: Int, source: String): Int? {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            return null
        }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            VideoLog.e("$TAG $label shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return null
        }
        return shader
    }

    private fun requestRender() {
        if (released || renderQueued) {
            return
        }
        renderQueued = true
        glHandler.post {
            renderQueued = false
            renderFrame()
        }
    }

    fun release() {
        if (released) {
            return
        }
        released = true
        glHandler.post {
            try {
                cleanupGl()
            } finally {
                glThread.quitSafely()
            }
        }
    }

    private fun cleanupGl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT &&
                eglSurface != EGL14.EGL_NO_SURFACE
            ) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                listOf(p5Program, fallbackProgram).forEach { program ->
                    if (program != null) {
                        GLES30.glDeleteProgram(program.id)
                    }
                }
                if (externalTextureId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(externalTextureId), 0)
                }
                if (colorLutTextureId != 0) {
                    GLES30.glDeleteTextures(1, intArrayOf(colorLutTextureId), 0)
                }
            }
            inputSurface?.release()
            inputSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
        } catch (error: Throwable) {
            VideoLog.e("$TAG cleanup failed: ${error.message}")
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun chooseConfig(): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw IllegalStateException("eglChooseConfig failed error=0x${eglErrorHex()}")
        }
        return configs[0]
    }

    private fun eglErrorHex(): String = Integer.toHexString(EGL14.eglGetError())

    companion object {
            private const val TAG = "DoviGlCompositor"
            private const val YUV_EXTENSION = "GL_OES_EGL_image_external_essl3"
            private const val LUT_SIZE = 65
            private const val PQ_M1 = 0.1593017578125
            private const val PQ_M2 = 78.84375
            private const val PQ_C1 = 0.8359375
            private const val PQ_C2 = 18.8515625
            private const val PQ_C3 = 18.6875
            private const val SDR_PAPER_WHITE_NITS = 100.0
            private const val VERTEX_STRIDE_BYTES = 16

            // Shared constants for LUT generation (used by RawYuvCompositor too)
            internal const val SHARED_LUT_SIZE = LUT_SIZE

            val QUAD_VERTICES = floatArrayOf(
                -1f, -1f, 0f, 0f,
                +1f, -1f, 1f, 0f,
                -1f, +1f, 0f, 1f,
                +1f, +1f, 1f, 1f
            )

            private const val VERTEX_SHADER_ES3 = """#version 300 es
                in vec4 aPosition;
                in vec2 aTexCoord;
                uniform mat4 uTexMatrix;
                out vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
            """

            private const val RGB_OES_FRAGMENT_SHADER_ES3 = """#version 300 es
                #extension GL_OES_EGL_image_external_essl3 : require
                precision mediump float;
                in vec2 vTexCoord;
                out vec4 fragColor;
                uniform samplerExternalOES uTexture;
                void main() {
                    fragColor = texture(uTexture, vTexCoord);
                }
            """

            private const val P5_COLOR_FRAGMENT_SHADER = """#version 300 es
                #extension GL_OES_EGL_image_external_essl3 : require
                precision mediump float;
                in vec2 vTexCoord;
                out vec4 fragColor;
                uniform samplerExternalOES uTexture;
                uniform highp sampler3D uColorLut;
                void main() {
                    // OES sampler outputs PQ BT.2020 RGB (YUV→RGB via hardware)
                    vec4 rgb = texture(uTexture, vTexCoord);
                    highp vec3 lutCoord = vec3(
                        clamp(rgb.r, 0.0, 1.0),
                        clamp(rgb.g, 0.0, 1.0),
                        clamp(rgb.b, 0.0, 1.0)
                    );
                    vec3 color = texture(uColorLut, lutCoord).rgb;
                    fragColor = vec4(color, 1.0);
                }
            """

            /** Shared P5 3D LUT creation — usable from any GL context. */
            fun createColorLutTexture(): Int {
                val maxSize = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, maxSize, 0)
                require(maxSize[0] >= LUT_SIZE) { "GL_MAX_3D_TEXTURE_SIZE=${maxSize[0]} < $LUT_SIZE" }

                val buffer = createColorLutBuffer()
                val textures = IntArray(1)
                GLES30.glGenTextures(1, textures, 0)
                val textureId = textures[0]
                require(textureId != 0) { "3D LUT texture allocation failed" }

                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexImage3D(
                    GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
                    LUT_SIZE, LUT_SIZE, LUT_SIZE, 0,
                    GLES30.GL_RGB, GLES30.GL_FLOAT, buffer
                )
                val uploadError = GLES30.glGetError()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
                if (uploadError != GLES30.GL_NO_ERROR) {
                    GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                    throw IllegalStateException("3D LUT upload failed error=0x${Integer.toHexString(uploadError)}")
                }
                return textureId
            }

            private fun createColorLutBuffer(): FloatBuffer {
                val entryCount = LUT_SIZE * LUT_SIZE * LUT_SIZE
                val buffer = ByteBuffer.allocateDirect(entryCount * 3 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                val denominator = (LUT_SIZE - 1).toDouble()
                for (bIndex in 0 until LUT_SIZE) {
                    val bPq = bIndex / denominator
                    for (gIndex in 0 until LUT_SIZE) {
                        val gPq = gIndex / denominator
                        for (rIndex in 0 until LUT_SIZE) {
                            val rPq = rIndex / denominator
                            // PQ BT.2020 RGB → sRGB with tone mapping
                            val rNits = pqEotf(rPq)
                            val gNits = pqEotf(gPq)
                            val bNits = pqEotf(bPq)
                            val luminance = rNits * 0.2627002120112671 +
                                gNits * 0.6779980715188708 +
                                bNits * 0.05930171646986196
                            val toneScale = if (luminance > 0.000001)
                                (1.0 - exp(-luminance / SDR_PAPER_WHITE_NITS)) / luminance
                            else 1.0 / SDR_PAPER_WHITE_NITS
                            val r709 = clamp01(rNits * toneScale * 1.660491 - gNits * toneScale * 0.587641 - bNits * toneScale * 0.072850)
                            val g709 = clamp01(-rNits * toneScale * 0.124550 + gNits * toneScale * 1.132900 - bNits * toneScale * 0.008349)
                            val b709 = clamp01(-rNits * toneScale * 0.018151 - gNits * toneScale * 0.100579 + bNits * toneScale * 1.118730)
                            buffer.put(srgbOetf(r709).toFloat())
                            buffer.put(srgbOetf(g709).toFloat())
                            buffer.put(srgbOetf(b709).toFloat())
                        }
                    }
                }
                buffer.position(0)
                return buffer
            }

            private fun pqEotf(value: Double): Double {
                if (value <= 0.0) return 0.0
                val powered = value.pow(1.0 / PQ_M2)
                return (max(powered - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * powered)).pow(1.0 / PQ_M1) * 10000.0
            }

            private fun srgbOetf(value: Double): Double =
                if (value < 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055

            private fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)
        }
    }