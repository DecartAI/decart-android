package ai.decart.sdk.realtime

import android.media.MediaCodecList
import android.media.MediaFormat

internal object H264Support {
    private const val MIME_H264 = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 720

    private val encodeSupported: Boolean by lazy { queryEncoder(DEFAULT_WIDTH, DEFAULT_HEIGHT) }
    private val decodeSupported: Boolean by lazy { queryDecoder(DEFAULT_WIDTH, DEFAULT_HEIGHT) }

    fun canEncode(): Boolean = encodeSupported

    fun canDecode(): Boolean = decodeSupported

    private fun queryEncoder(width: Int, height: Int): Boolean {
        val format = MediaFormat.createVideoFormat(MIME_H264, width, height)
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null
        }.getOrDefault(false)
    }

    private fun queryDecoder(width: Int, height: Int): Boolean {
        val format = MediaFormat.createVideoFormat(MIME_H264, width, height)
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
        }.getOrDefault(false)
    }
}
