package com.embytv.player.kernel.impl.exo

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.embytv.player.kernel.VideoLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Renders P5 Dolby Vision with correct IPTPQc2→SDR color conversion.
 *
 * Uses ImageReader to capture raw decoded YUV frames from the decoder,
 * bypassing the OES sampler's incorrect YUV→RGB conversion. The raw YUV
 * is treated as IPTPQc2 components (Y=Intensity, U=P, V=T) and the LUT
 * does the correct color space conversion.
 */
class RawYuvCompositor(private val target: Surface) {

    private data class GlProgram(
        val id: Int,
        val position: Int,
        val texCoord: Int,
        val texMatrix: Int,
        val textureY: Int,
        val textureUV: Int,
        val colorLut: Int
    )

    private val glThread = HandlerThread("raw-yuv-p5").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var inputSurface: Surface? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglSurfaceView: EGLSurface = EGL14.EGL_NO_SURFACE

    private var p5Program: GlProgram? = null
    private var fallbackProgram: GlProgram? = null
    private var textureY = 0
    private var textureUV = 0
    private var colorLutTextureId = 0

    private var viewportW = 1
    private var viewportH = 1
    private var released = false
    private var renderQueued = false
    private lateinit var vertexBuffer: FloatBuffer

    private var imageReader: ImageReader? = null

    init {
        glHandler.post {
            try {
                initGl()
            } catch (error: Throwable) {
                VideoLog.e("RawYuvCompositor init failed: ${error.message}")
                cleanupGl()
            } finally {
                readyLatch.countDown()
            }
        }
        readyLatch.await()
    }

    fun getInputSurface(): Surface? {
        return inputSurface  // null if not ready yet, caller handles
    }
    fun isReady(): Boolean = inputSurface != null
    fun matchesTarget(surface: Surface): Boolean = target === surface

    /** Called when the video format is known — creates the ImageReader. */
    fun onVideoFormat(width: Int, height: Int) {
        if (imageReader != null) return
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, glHandler)
        imageReader = reader
        inputSurface = reader.surface
        VideoLog.i("RawYuvCompositor ImageReader created ${width}x$height")
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            queueFrame(image)
        } finally {
            image.close()
        }
    }

    private fun queueFrame(image: Image) {
        val planes = image.planes
        if (planes.size < 3) return

        // Y plane
        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        val yStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        // U and V planes (NV12 or YUV420 format)
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val w = image.width
        val h = image.height

        // Upload Y plane as GL_R8 texture
        val yPixels = ByteArray(w * h)
        copyPlane(yBuffer, yPixels, yStride, yPixelStride, w, h)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureY)
        val yBuf = ByteBuffer.wrap(yPixels)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, yBuf)

        // Upload UV as interleaved GL_RG8 (pack U and V into one texture)
        val uvPixels = ByteArray(w * h / 2)
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val u = uBuffer.get(row * uvStride + col * uvPixelStride).toInt() and 0xFF
                val v = vBuffer.get(row * uvStride + col * uvPixelStride).toInt() and 0xFF
                uvPixels[row * w + col * 2] = u.toByte()
                uvPixels[row * w + col * 2 + 1] = v.toByte()
            }
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureUV)
        val uvBuf = ByteBuffer.wrap(uvPixels)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, w / 2, h / 2, 0, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, uvBuf)
    }

    private fun copyPlane(src: ByteBuffer, dst: ByteArray, stride: Int, pixelStride: Int, w: Int, h: Int) {
        for (row in 0 until h) {
            for (col in 0 until w) {
                dst[row * w + col] = src.get(row * stride + col * pixelStride)
            }
        }
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw IllegalStateException("eglInitialize failed")
        eglConfig = chooseConfig()
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext ES3 failed")
        createWindowSurface()
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurfaceView, eglSurfaceView, eglContext))
            throw IllegalStateException("eglMakeCurrent failed")

        val texIds = IntArray(2)
        GLES30.glGenTextures(2, texIds, 0)
        textureY = texIds[0]; textureUV = texIds[1]
        for (tex in intArrayOf(textureY, textureUV)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        colorLutTextureId = DoviGlCompositor.createColorLutTexture()
        fallbackProgram = createProgram("raw-yuv-fallback", RAW_YUV_FRAGMENT, false).also {
            if (it == null) throw IllegalStateException("fallback shader failed")
        }
        p5Program = createProgram("raw-yuv-p5", RAW_YUV_P5_FRAGMENT, true)
        if (p5Program == null) VideoLog.e("RawYuvCompositor P5 shader unavailable")

        vertexBuffer = ByteBuffer.allocateDirect(DoviGlCompositor.QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(DoviGlCompositor.QUAD_VERTICES).position(0)

        VideoLog.i("RawYuvCompositor ready viewport=${viewportW}x$viewportH p5Lut=${p5Program != null}")
    }

    private fun renderFrame() {
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurfaceView == EGL14.EGL_NO_SURFACE) return
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurfaceView, eglSurfaceView, eglContext)) return
            updateSurfaceSize()
            GLES30.glViewport(0, 0, viewportW, viewportH)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            drawQuad(p5Program ?: fallbackProgram ?: return)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurfaceView, System.nanoTime())
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurfaceView)) {
                VideoLog.e("RawYuvCompositor eglSwapBuffers failed")
            }
        } catch (error: Throwable) {
            VideoLog.e("RawYuvCompositor render failed: ${error.message}")
        }
    }

    private fun drawQuad(program: GlProgram) {
        GLES30.glUseProgram(program.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureY)
        GLES30.glUniform1i(program.textureY, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureUV)
        GLES30.glUniform1i(program.textureUV, 1)
        if (program.colorLut >= 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, colorLutTextureId)
            GLES30.glUniform1i(program.colorLut, 2)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        GLES30.glUniformMatrix4fv(program.texMatrix, 1, false, identity, 0)
        vertexBuffer.position(0)
        GLES30.glEnableVertexAttribArray(program.position)
        GLES30.glVertexAttribPointer(program.position, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES30.glEnableVertexAttribArray(program.texCoord)
        GLES30.glVertexAttribPointer(program.texCoord, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(program.position)
        GLES30.glDisableVertexAttribArray(program.texCoord)
    }

    private fun createProgram(label: String, fragmentSource: String, needsColorLut: Boolean): GlProgram? {
        val vertex = compileShader(label, GLES30.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
        val fragment = compileShader(label, GLES30.GL_FRAGMENT_SHADER, fragmentSource) ?: run { GLES30.glDeleteShader(vertex); return null }
        val programId = GLES30.glCreateProgram()
        if (programId == 0) { GLES30.glDeleteShader(vertex); GLES30.glDeleteShader(fragment); return null }
        GLES30.glAttachShader(programId, vertex); GLES30.glAttachShader(programId, fragment); GLES30.glLinkProgram(programId)
        val status = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vertex); GLES30.glDeleteShader(fragment)
        if (status[0] != GLES30.GL_TRUE) { VideoLog.e("$label link: ${GLES30.glGetProgramInfoLog(programId)}"); GLES30.glDeleteProgram(programId); return null }
        val r = GlProgram(programId,
            GLES30.glGetAttribLocation(programId, "aPosition"),
            GLES30.glGetAttribLocation(programId, "aTexCoord"),
            GLES30.glGetUniformLocation(programId, "uTexMatrix"),
            GLES30.glGetUniformLocation(programId, "uTextureY"),
            GLES30.glGetUniformLocation(programId, "uTextureUV"),
            if (needsColorLut) GLES30.glGetUniformLocation(programId, "uColorLut") else -1)
        if (r.position < 0 || r.texCoord < 0 || r.texMatrix < 0 || r.textureY < 0 || r.textureUV < 0 || (needsColorLut && r.colorLut < 0)) {
            VideoLog.e("$label location missing"); GLES30.glDeleteProgram(programId); return null
        }
        return r
    }

    private fun compileShader(label: String, type: Int, source: String): Int? {
        val shader = GLES30.glCreateShader(type) ?: return null
        GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) { VideoLog.e("$label compile: ${GLES30.glGetShaderInfoLog(shader)}"); GLES30.glDeleteShader(shader); return null }
        return shader
    }

    private fun requestRender() {
        if (released || renderQueued) return
        renderQueued = true
        glHandler.post { renderQueued = false; renderFrame() }
    }

    fun release() {
        if (released) return; released = true
        glHandler.post { try { cleanupGl() } finally { glThread.quitSafely() } }
    }

    private fun cleanupGl() {
        imageReader?.close()
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT && eglSurfaceView != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurfaceView, eglSurfaceView, eglContext)
                listOf(p5Program, fallbackProgram).forEach { it?.let { GLES30.glDeleteProgram(it.id) } }
                if (textureY != 0) GLES30.glDeleteTextures(1, intArrayOf(textureY), 0)
                if (textureUV != 0) GLES30.glDeleteTextures(1, intArrayOf(textureUV), 0)
                if (colorLutTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(colorLutTextureId), 0)
            }
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurfaceView); EGL14.eglDestroyContext(eglDisplay, eglContext); EGL14.eglTerminate(eglDisplay)
        } catch (_: Exception) {}
    }

    private fun createWindowSurface() {
        val config = eglConfig ?: throw IllegalStateException("EGL config missing")
        eglSurfaceView = EGL14.eglCreateWindowSurface(eglDisplay, config, target, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurfaceView == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed")
        updateSurfaceSize()
    }

    private fun updateSurfaceSize() {
        val size = IntArray(2)
        EGL14.eglQuerySurface(eglDisplay, eglSurfaceView, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurfaceView, EGL14.EGL_HEIGHT, size, 1)
        viewportW = size[0].coerceAtLeast(1); viewportH = size[1].coerceAtLeast(1)
    }

    private fun chooseConfig(): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) || count[0] == 0)
            throw IllegalStateException("eglChooseConfig failed")
        return configs[0]
    }

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            in vec4 aPosition; in vec2 aTexCoord; uniform mat4 uTexMatrix;
            out vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy; }"""

        private const val RAW_YUV_FRAGMENT = """#version 300 es
            precision mediump float; in vec2 vTexCoord; out vec4 fragColor;
            uniform sampler2D uTextureY; uniform sampler2D uTextureUV;
            void main() {
                float y = texture(uTextureY, vTexCoord).r;
                vec2 uv = texture(uTextureUV, vTexCoord).rg - 0.5;
                float r = y + 1.5748 * uv.r; float g = y - 0.1873 * uv.g - 0.4681 * uv.r; float b = y + 1.8556 * uv.g;
                fragColor = vec4(clamp(r,0.0,1.0), clamp(g,0.0,1.0), clamp(b,0.0,1.0), 1.0);
            }"""

        private const val RAW_YUV_P5_FRAGMENT = """#version 300 es
            precision mediump float; in vec2 vTexCoord; out vec4 fragColor;
            uniform sampler2D uTextureY; uniform sampler2D uTextureUV;
            uniform highp sampler3D uColorLut;
            void main() {
                float intensity = texture(uTextureY, vTexCoord).r;
                vec2 pt = texture(uTextureUV, vTexCoord).rg;
                vec3 color = texture(uColorLut, clamp(vec3(intensity, pt.g, pt.r), 0.0, 1.0)).rgb;
                fragColor = vec4(color, 1.0);
            }"""
    }
}