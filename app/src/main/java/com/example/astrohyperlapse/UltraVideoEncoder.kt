package com.example.astrohyperlapse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UltraVideoEncoder {

    suspend fun encodeHyperlapse(
        masterFrames: List<File>,
        outputMp4: File,
        width: Int = 3840,
        height: Int = 2160,
        fps: Int = 60,
        bitrateMbps: Int = 100,
        dlssMultiplier: Int = 4,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        if (masterFrames.isEmpty()) return@withContext

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Защита от квадратов 20x20 на темном небе
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        val frameDurationUs = 1_000_000L / fps
        var outputFrameIndex = 0L

        var prevBitmap: Bitmap? = null
        val currScaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val dlssBitmaps = if (dlssMultiplier > 1) {
            Array(dlssMultiplier - 1) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
        } else emptyArray()

        for (i in masterFrames.indices) {
            onProgress(i + 1, masterFrames.size)
            val decoded = BitmapFactory.decodeFile(masterFrames[i].absolutePath) ?: continue
            Canvas(currScaled).drawBitmap(decoded, null, Rect(0, 0, width, height), null)
            decoded.recycle()

            if (prevBitmap != null && dlssMultiplier > 1) {
                NativeAstroProcessor.generateIntermediateFrames(prevBitmap, currScaled, dlssMultiplier, dlssBitmaps)
                for (dlssBmp in dlssBitmaps) {
                    drawToSurface(inputSurface, dlssBmp)
                    drainEncoder(encoder, bufferInfo, muxer, trackIndex, muxerStarted, frameDurationUs, outputFrameIndex++) { trk, st ->
                        trackIndex = trk; muxerStarted = st
                    }
                }
            }

            drawToSurface(inputSurface, currScaled)
            drainEncoder(encoder, bufferInfo, muxer, trackIndex, muxerStarted, frameDurationUs, outputFrameIndex++) { trk, st ->
                trackIndex = trk; muxerStarted = st
            }

            if (prevBitmap == null) {
                prevBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            Canvas(prevBitmap).drawBitmap(currScaled, 0f, 0f, null)
        }

        encoder.signalEndOfInputStream()
        drainEncoder(encoder, bufferInfo, muxer, trackIndex, muxerStarted, frameDurationUs, outputFrameIndex) { _, _ -> }

        encoder.stop()
        encoder.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }

    private fun drawToSurface(surface: Surface, bitmap: Bitmap) {
        val canvas = surface.lockCanvas(null)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        surface.unlockCanvasAndPost(canvas)
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        currentTrack: Int,
        currentStarted: Boolean,
        frameDurationUs: Long,
        frameIndex: Long,
        onStateUpdate: (Int, Boolean) -> Unit
    ) {
        var trackIndex = currentTrack
        var muxerStarted = currentStarted
        while (true) {
            val outId = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                muxerStarted = true
                onStateUpdate(trackIndex, true)
            } else if (outId >= 0) {
                val encodedData = encoder.getOutputBuffer(outId)!!
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size != 0 && muxerStarted) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    bufferInfo.presentationTimeUs = frameIndex * frameDurationUs
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                }
                encoder.releaseOutputBuffer(outId, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else {
                break
            }
        }
    }
}
