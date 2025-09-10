package com.google.android.systemui.smartspace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.withSave
import com.google.android.systemui.res.R

class PagerDots @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    @VisibleForTesting
    var numPages = -1
        private set

    @VisibleForTesting
    var currentPageIndex = -1
        private set

    private var currentPositionIndex = -1
    private var currentPositionOffset = 0f
    private val dotSize = resources.getDimension(R.dimen.page_indicator_dot_size)
    private val dotMargin = resources.getDimension(R.dimen.page_indicator_dot_margin)
    private val activeDotSize = dotSize * 2f
    private val dotRadius = dotSize / 2f
    private val tempRectF = RectF()
    private var primaryColor =
        getContext().obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).run {
            val color = getColor(0, 0)
            recycle()
            color
        }

    @VisibleForTesting
    val paint =
        Paint().apply {
            style = Paint.Style.FILL
            color = primaryColor
        }

    override fun onDraw(canvas: Canvas) {
        if (numPages < 2) {
            return
        }
        canvas.withSave {
            scale(if (isLayoutRtl) -1f else 1f, 1f, width * 0.5f, 0f)
            translate(paddingStart.toFloat(), paddingTop.toFloat())
            val sizeOffset = (activeDotSize - dotSize) * currentPositionOffset
            val activeAlpha = (primaryColor shr 24) and 255
            val inactiveAlpha = (activeAlpha * 0.4f).toInt()
            val alphaOffset = ((activeAlpha - inactiveAlpha) * currentPositionOffset).toInt()
            tempRectF.top = 0f
            tempRectF.bottom = dotSize
            tempRectF.left = 0f
            for (i in 0 until numPages) {
                val size =
                    when (i) {
                        currentPositionIndex -> activeDotSize - sizeOffset
                        currentPositionIndex + 1 -> dotSize + sizeOffset
                        else -> dotSize
                    }
                val alpha =
                    when (i) {
                        currentPositionIndex -> activeAlpha - alphaOffset
                        currentPositionIndex + 1 -> inactiveAlpha + alphaOffset
                        else -> inactiveAlpha
                    }
                tempRectF.right = tempRectF.left + size
                paint.alpha = alpha
                drawRoundRect(tempRectF, dotRadius, dotRadius, paint)
                tempRectF.left = tempRectF.right + dotMargin
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
                MeasureSpec.getSize(widthMeasureSpec)
            } else {
                ((dotMargin + dotSize) * (numPages - 1) + activeDotSize).toInt()
            }
        val height =
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                MeasureSpec.getSize(heightMeasureSpec)
            } else {
                dotSize.toInt()
            }
        setMeasuredDimension(
            paddingRight + paddingLeft + width,
            paddingBottom + paddingTop + height,
        )
    }

    fun setNumPages(pages: Int, isRtl: Boolean) {
        if (pages == numPages) {
            return
        }
        numPages = if (pages <= 0) 1 else pages
        if (currentPageIndex < 0) {
            updateCurrentPageIndex(if (isRtl) numPages - 1 else 0)
            currentPositionIndex = currentPageIndex
        } else if (currentPageIndex >= numPages) {
            updateCurrentPageIndex(if (isRtl) 0 else numPages - 1)
            currentPositionIndex = currentPageIndex
        }
        if (numPages < 2) {
            BcSmartspaceTemplateDataUtils.updateVisibility(this, GONE)
        } else if (visibility != INVISIBLE) {
            BcSmartspaceTemplateDataUtils.updateVisibility(this, VISIBLE)
        }
        requestLayout()
        invalidate()
    }

    fun setPageOffset(position: Int, positionOffset: Float) {
        currentPositionIndex = position
        currentPositionOffset = positionOffset
        invalidate()
        updateCurrentPageIndex(if (positionOffset >= 0.5) position + 1 else position)
    }

    fun setPrimaryColor(color: Int) {
        primaryColor = color
        paint.color = color
        invalidate()
    }

    private fun updateCurrentPageIndex(index: Int) {
        if (index == currentPageIndex) {
            return
        }
        currentPageIndex = index
        contentDescription =
            context.getString(
                R.string.accessibility_smartspace_page,
                currentPageIndex + 1,
                numPages,
            )
    }
}
