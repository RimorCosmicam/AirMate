package com.airmate.android.ui

import android.content.Context
import android.view.SurfaceView

/**
 * A surface that keeps the stream's shape.
 *
 * The Mac sends one resolution whichever way the tablet is held, so in portrait a 16:9 stream has
 * to letterbox. Filling the parent instead would stretch the Mac's desktop into whatever rectangle
 * the tablet happens to be, which is worse than the black bars by a wide margin.
 */
class AspectSurfaceView(context: Context) : SurfaceView(context) {
    private var aspectWidth = 16
    private var aspectHeight = 9

    fun setAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == aspectWidth && height == aspectHeight) return
        aspectWidth = width
        aspectHeight = height
        post { requestLayout() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth <= 0 || availableHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        var width = availableWidth
        var height = width * aspectHeight / aspectWidth
        if (height > availableHeight) {
            height = availableHeight
            width = height * aspectWidth / aspectHeight
        }
        setMeasuredDimension(width, height)
    }
}
