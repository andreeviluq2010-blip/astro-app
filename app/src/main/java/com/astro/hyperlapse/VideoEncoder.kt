package com.astro.hyperlapse

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

class VideoEncoder(
    private val outputFile: File,
    private val width: Int = 3840,
    private val height: Int = 2160,
    private val bitrateMbps: Int = 100,
    private val fps: Int = 30
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0L
    var encodedFramesCount = 0
        private set

    fun start() {
        try {
            val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(mime).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Start error: ${e.message}")
        }
    }

    @Synchronized
    fun encodeFrame(yuvData: ByteArray) {
        val c = codec ?: return
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buf = c.getInputBuffer(inIndex)
                buf?.clear()
                val limit = minOf(buf?.remaining() ?: 0, yuvData.size)
                buf?.put(yuvData, 0, limit)
                val pts = frameIndex * 1_000_000L / fps
                c.queueInputBuffer(inIndex, 0, limit, pts, 0)
                frameIndex++
            }
            drainEncoder(false)
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Encode error: ${e.message}")
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        val info = MediaCodec.BufferInfo()
        var loops = 0
        while (loops < 30) {
            loops++
            val outIndex = c.dequeueOutputBuffer(info, 2_000)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
            } else if (outIndex >= 0) {
                val encodedData = c.getOutputBuffer(outIndex)
                if (encodedData != null && info.size > 0 && muxerStarted) {
                    encodedData.position(info.offset)
                    encodedData.limit(info.offset + info.size)
                    m.writeSampleData(trackIndex, encodedData, info)
                    encodedFramesCount++
                }
                c.releaseOutputBuffer(outIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    @Synchronized
    fun stopAndRelease(): Boolean {
        var savedSuccessfully = false
        try {
            if (encodedFramesCount > 0) {
                drainEncoder(true)
            }
        } catch (_: Exception) {}

        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null

        try {
            if (muxerStarted && encodedFramesCount > 0) {
                muxer?.stop()
                savedSuccessfully = true
            }
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Muxer stop safe catch: ${e.message}")
        }
        try {
            muxer?.release()
        } catch (_: Exception) {}
        muxer = null

        // Если ни одного мастер-кадра еще не было записано, удаляем пустую болванку файла
        if (!savedSuccessfully && outputFile.exists()) {
            try { outputFile.delete() } catch (_: Exception) {}
        }
        return savedSuccessfully
    }
}
