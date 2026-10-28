package com.procutstudio.editor.media.gl

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram

/**
 * Base for the app's own full-frame fragment shaders. It owns the [GlProgram], draws the standard
 * two-triangle quad and leaves the uniforms to [setUniforms].
 */
@UnstableApi
internal abstract class QuadShaderProgram(
    useHdr: Boolean,
    fragmentSource: String,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    protected val program: GlProgram
    protected var frameWidth: Int = 1
        private set
    protected var frameHeight: Int = 1
        private set

    init {
        program = try {
            GlProgram(ShaderSources.VERTEX, fragmentSource)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        program.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix())
        program.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix())
    }

    /** Called on the GL thread before every draw. Samplers other than `uTexSampler` go here. */
    protected abstract fun setUniforms(presentationTimeUs: Long)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        frameWidth = inputWidth.coerceAtLeast(1)
        frameHeight = inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            setUniforms(presentationTimeUs)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    protected fun uniform(name: String, vararg values: Float) {
        program.setFloatsUniformIfPresent(name, values)
    }
}
