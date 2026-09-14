package com.leadaxe.lxbox

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.animation.TimeInterpolator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.min

/// Круглая кнопка подключения лаунчера DeltaRay в стилистике иконки:
/// тёмный зелёно-графит + золотой кольцевой прогресс скана ZeroDPI.
/// Прогресс «прыгает» к проценту с overshoot-интерполяцией; в неопределённой
/// фазе дуга вращается, вокруг кнопки пульсирует мягкое золотое свечение.
class ConnectRingButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class Mode { Idle, Connecting, Connected }

    private var mode = Mode.Idle
    private var determinate = false
    private var percent = 0f
    private var sweep = 0f
    private var indetStart = -90f
    private var pulse = 0f
    private var pressedScale = 1f
    private var label: String? = null

    private val gold = ContextCompat.getColor(context, R.color.launcher_gold)
    private val goldHi = ContextCompat.getColor(context, R.color.launcher_gold_hi)
    private val goldDeep = ContextCompat.getColor(context, R.color.launcher_gold_deep)
    private val goldDim = ContextCompat.getColor(context, R.color.launcher_gold_dim)
    private val cream = ContextCompat.getColor(context, R.color.launcher_cream)
    private val muted = ContextCompat.getColor(context, R.color.launcher_muted)
    private val surface = ContextCompat.getColor(context, R.color.launcher_surface)
    private val surfaceHi = ContextCompat.getColor(context, R.color.launcher_surface_hi)

    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
    private val ringStroke = 3.5f * density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = gold
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = cream
        isFakeBoldText = true
        textSize = 30f * scaledDensity
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = muted
        textSize = 11.5f * scaledDensity
    }

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var baseShader: Shader? = null
    private var ringShader: Shader? = null

    private var arcAnim: ValueAnimator? = null
    private var indetAnim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null
    private var pressAnim: ValueAnimator? = null

    /// easeOutBack: дуга слегка «перелётывает» цель и возвращается —
    /// визуально процент прыгает, дочитывается и оседает.
    private val overshoot = object : TimeInterpolator {
        private val c1 = 1.70158f
        private val c3 = c1 + 1f
        override fun getInterpolation(input: Float): Float =
            1f + c3 * (input - 1f) * (input - 1f) * (input - 1f) +
                c1 * (input - 1f) * (input - 1f)
    }

    init {
        isClickable = true
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - 5f * density - ringStroke
        if (radius > 0f) {
            baseShader = RadialGradient(
                cx, cy, radius * 1.15f,
                intArrayOf(surfaceHi, surface),
                null, Shader.TileMode.CLAMP,
            )
            ringShader = SweepGradient(
                cx, cy,
                intArrayOf(goldDeep, gold, goldHi, goldDeep),
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        canvas.save()
        canvas.scale(pressedScale, pressedScale, cx, cy)

        if (mode != Mode.Idle) {
            val glowR = radius + 14f * density
            glowPaint.shader = RadialGradient(
                cx, cy, glowR,
                intArrayOf(
                    withAlpha(goldDim, (20 + 16 * pulse).toInt()),
                    withAlpha(goldDim, 0),
                ),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, glowR, glowPaint)
        }

        basePaint.shader = baseShader
        canvas.drawCircle(cx, cy, radius, basePaint)

        thinPaint.color = goldDim
        thinPaint.alpha = 90
        thinPaint.strokeWidth = 1f * density
        canvas.drawCircle(cx, cy, radius, thinPaint)

        ringPaint.shader = ringShader
        val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        if (determinate) {
            ringPaint.alpha = 255
            if (sweep > 0.5f) {
                canvas.drawArc(arcRect, -90f, sweep, false, ringPaint)
            }
        } else {
            ringPaint.alpha = (150 + 105 * pulse).toInt()
            canvas.drawArc(arcRect, indetStart, 100f, false, ringPaint)
        }

        drawCenter(canvas)
        canvas.restore()
    }

    private fun drawCenter(canvas: Canvas) {
        val glyphY = cy - 7f * density
        when (mode) {
            Mode.Idle -> {
                drawPlay(canvas, cx, glyphY)
                labelPaint.color = gold
            }
            Mode.Connecting -> {
                if (determinate) {
                    percentPaint.color = cream
                    canvas.drawText("${percent.toInt()}%", cx, cy - 9f * density, percentPaint)
                } else {
                    glyphPaint.color = goldDim
                    glyphPaint.style = Paint.Style.FILL
                    val gap = 8.5f * density
                    val r0 = 1.9f * density
                    glyphPaint.alpha = 150
                    canvas.drawCircle(cx - gap, glyphY, r0, glyphPaint)
                    canvas.drawCircle(cx, glyphY, r0, glyphPaint)
                    canvas.drawCircle(cx + gap, glyphY, r0 * (1.6f + 0.5f * pulse), glyphPaint)
                }
                labelPaint.color = muted
            }
            Mode.Connected -> {
                drawPower(canvas, cx, glyphY)
                labelPaint.color = cream
            }
        }
        label?.let { canvas.drawText(it, cx, cy + 30f * density, labelPaint) }
    }

    private fun drawPlay(canvas: Canvas, x: Float, y: Float) {
        glyphPaint.style = Paint.Style.FILL
        glyphPaint.color = gold
        glyphPaint.alpha = 255
        val path = Path()
        path.moveTo(x - 9f * density, y - 12f * density)
        path.lineTo(x - 9f * density, y + 12f * density)
        path.lineTo(x + 12f * density, y)
        path.close()
        canvas.drawPath(path, glyphPaint)
    }

    private fun drawPower(canvas: Canvas, x: Float, y: Float) {
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 2.6f * density
        glyphPaint.color = gold
        glyphPaint.alpha = 255
        // Кольцо с разрывом сверху + вертикальная ножка = power-символ.
        canvas.drawArc(
            RectF(x - 11f * density, y - 11f * density, x + 11f * density, y + 11f * density),
            -65f, 310f, false, glyphPaint,
        )
        canvas.drawLine(x, y - 15f * density, x, y - 8f * density, glyphPaint)
    }

    // -- API состояния ---------------------------------------------------------

    /// Готов к подключению: пустое кольцо + play. Процент — ноль.
    fun setIdle() {
        mode = Mode.Idle
        determinate = false
        percent = 0f
        val text = context.getString(R.string.launcher_btn_connect)
        label = text
        contentDescription = text
        stopGlow()
        animateSweep(0f)
        invalidate()
    }

    /// Идёт сканирование / подключение. [percent] = известный процент
    /// (дуга прыгает к нему); null — неопределённая фаза (дуга вращается).
    fun setConnecting(percent: Int? = null) {
        mode = Mode.Connecting
        val text = context.getString(R.string.launcher_btn_scanning)
        label = text
        contentDescription = text
        if (percent != null) {
            val target = percent.toFloat().coerceIn(0f, 100f)
            determinate = true
            stopIndet()
            this.percent = target
            animateSweep(target * 3.6f)
        } else {
            determinate = false
            startIndet()
        }
        startPulse()
        invalidate()
    }

    /// Подключено / готово к отключению: полное кольцо + power-символ.
    fun setDisconnect() {
        mode = Mode.Connected
        determinate = true
        percent = 100f
        val text = context.getString(R.string.launcher_btn_disconnect)
        label = text
        contentDescription = text
        stopIndet()
        animateSweep(360f)
        startPulse()
        invalidate()
    }

    // -- Анимации --------------------------------------------------------------

    private fun animateSweep(target: Float) {
        arcAnim?.cancel()
        arcAnim = ValueAnimator.ofFloat(sweep, target).apply {
            duration = 480L
            interpolator = overshoot
            addUpdateListener {
                sweep = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startIndet() {
        if (indetAnim?.isRunning == true) return
        indetAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                indetStart = -90f + (it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    private fun stopIndet() {
        indetAnim?.cancel()
        indetAnim = null
    }

    private fun startPulse() {
        if (pulseAnim?.isRunning == true) return
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopGlow() {
        indetAnim?.cancel()
        indetAnim = null
        pulseAnim?.cancel()
        pulseAnim = null
        pulse = 0f
    }

    // -- Touch -----------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animatePress(0.94f, 90L)
                return true
            }
            MotionEvent.ACTION_UP -> {
                animatePress(1f, 180L)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                animatePress(1f, 120L)
                return true
            }
            MotionEvent.ACTION_MOVE -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun animatePress(target: Float, duration: Long) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressedScale, target).apply {
            this.duration = duration
            interpolator = overshoot
            addUpdateListener {
                pressedScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        arcAnim?.cancel()
        indetAnim?.cancel()
        pulseAnim?.cancel()
        pressAnim?.cancel()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}