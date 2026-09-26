package com.example.astrohyperlapse

import android.graphics.Bitmap

object NativeAstroProcessor {
    init {
        System.loadLibrary("astro_processor")
    }

    external fun initProcessor(
        skyLinePct: Float,
        groundLinePct: Float,
        stretchFactor: Float,
        groundSuppression: Float,
        isZenithMode: Boolean,
        isRollingMode: Boolean,
        stackTarget: Int
    ): Long

    external fun updateLiveParams(
        handle: Long,
        skyLinePct: Float,
        groundLinePct: Float,
        stretchFactor: Float,
        groundSuppression: Float,
        isZenithMode: Boolean
    )

    external fun addFrame(handle: Long, bitmap: Bitmap): Boolean
    external fun finalizeMasterFrame(handle: Long, outBitmap: Bitmap)
    external fun resetBatch(handle: Long)
    external fun releaseProcessor(handle: Long)

    external fun generateIntermediateFrames(
        bmp1: Bitmap,
        bmp2: Bitmap,
        multiplier: Int,
        outBitmaps: Array<Bitmap>
    )
}
