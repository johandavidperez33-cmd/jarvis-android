package com.jarvis.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Dibujo animado del holograma (anillos que giran, ondas de voz, núcleo brillante). */
class HologramaView(c: Context) : View(c) {
    var estado = "escuchando"
    var nivel = 0f
    private var suave = 0f
    private val inicio = System.nanoTime()
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val brillo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL) }
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // necesario para el resplandor

    private fun color() = when (estado) {
        "escuchando" -> Color.rgb(125, 255, 212)
        "pensando" -> Color.rgb(255, 198, 92)
        "error" -> Color.rgb(255, 107, 107)
        else -> Color.rgb(63, 216, 255)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (System.nanoTime() - inicio) / 1e9f
        val R = minOf(width, height) / 2f
        val cx = width / 2f; val cy = height / 2f
        val c = color()
        val vel = when (estado) { "pensando" -> 3.5f; "escuchando" -> 1.8f; else -> 1f }
        var objetivo = nivel
        if (estado == "hablando") objetivo = 0.25f + 0.45f * abs(sin(t * 8.3f) * sin(t * 3.1f)) + 0.12f * Math.random().toFloat()
        if (estado == "pensando") objetivo = 0.2f + 0.12f * sin(t * 6f)
        suave += (objetivo - suave) * 0.2f
        nivel *= 0.88f
        val rot = Math.toDegrees((t * 0.4f * vel).toDouble()).toFloat()

        canvas.save(); canvas.translate(cx, cy)
        fun aro(r: Float, w: Float, a: Int, guion: FloatArray? = null) {
            trazo.color = c; trazo.alpha = a; trazo.strokeWidth = w
            trazo.pathEffect = guion?.let { DashPathEffect(it, 0f) }
            canvas.drawCircle(0f, 0f, r, trazo); trazo.pathEffect = null
        }
        fun arco(r: Float, ini: Float, largo: Float, w: Float, a: Int) {
            brillo.color = c; brillo.alpha = a / 2; brillo.strokeWidth = w * 2.2f
            canvas.drawArc(-r, -r, r, r, ini, largo, false, brillo)
            trazo.color = c; trazo.alpha = a; trazo.strokeWidth = w
            canvas.drawArc(-r, -r, r, r, ini, largo, false, trazo)
        }
        aro(R * 0.96f, 1.5f, 90)
        for (i in 0 until 3) arco(R * 0.88f, -rot * 1.3f + i * 120f, 80f, 4f, 210)
        aro(R * 0.8f, 1.5f, 70, floatArrayOf(4f, 10f))
        for (i in 0 until 6) arco(R * 0.72f, rot * 2f + i * 60f, 34f, 7f, if (i % 2 == 0) 110 else 170)
        aro(R * 0.62f, 2f, 130)

        // ondas de voz
        trazo.color = c; trazo.alpha = 230; trazo.strokeWidth = 3f
        val n = 64
        for (i in 0 until n) {
            val ang = (i * 2 * Math.PI / n + t * 0.3).toFloat()
            val ruido = 0.55f + 0.45f * sin(i * 1.7f + t * 9f) * sin(i * 0.53f - t * 5f)
            val largo = R * (0.03f + suave * 0.2f * abs(ruido))
            val r0 = R * 0.42f
            canvas.drawLine(cos(ang) * r0, sin(ang) * r0, cos(ang) * (r0 + largo), sin(ang) * (r0 + largo), trazo)
        }
        // núcleo
        val rn = R * (0.25f + suave * 0.07f)
        relleno.shader = RadialGradient(0f, 0f, rn, intArrayOf(Color.WHITE, c, Color.TRANSPARENT), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, rn, relleno)
        aro(R * 0.3f, 2f, 170, floatArrayOf(R * 0.05f, R * 0.025f))
        canvas.restore()
        postInvalidateOnAnimation()
    }
}

/** Ventana flotante con el holograma, encima de cualquier app (como la barra de "Hey Google"). */
object Holograma {
    private val principal = Handler(Looper.getMainLooper())
    private var vista: LinearLayout? = null
    private var dibujo: HologramaView? = null
    private var texto: TextView? = null
    private var gestor: WindowManager? = null
    private val ocultarTarea = Runnable { quitar() }

    private fun dp(c: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    /** estado: escuchando | pensando | hablando | error */
    fun mostrar(c: Context, estado: String, mensaje: String?) = principal.post {
        principal.removeCallbacks(ocultarTarea)
        if (vista == null && !crear(c)) return@post
        dibujo?.estado = estado
        if (mensaje != null) texto?.text = mensaje
        texto?.setTextColor(if (estado == "error") Color.rgb(255, 150, 150) else Color.rgb(214, 246, 255))
        if (Config.despertarPantalla(c)) despertar(c)
    }

    fun nivel(v: Float) { dibujo?.nivel = maxOf(dibujo?.nivel ?: 0f, v.coerceIn(0f, 1f)) }

    fun ocultar(demoraMs: Long = 1800) = principal.post {
        principal.removeCallbacks(ocultarTarea)
        principal.postDelayed(ocultarTarea, demoraMs)
    }

    @SuppressLint("WrongConstant")
    private fun crear(c: Context): Boolean {
        val acc = Accesibilidad.instancia
        val ctx: Context
        val tipo: Int
        when {
            acc != null -> { ctx = acc; tipo = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
            Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(c) -> { ctx = c.applicationContext; tipo = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
            else -> return false // sin permiso para dibujar encima de otras apps
        }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val caja = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, 18f), dp(ctx, 10f), dp(ctx, 18f), dp(ctx, 14f))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(150, 3, 25, 40), Color.argb(215, 1, 8, 14))).apply {
                cornerRadius = dp(ctx, 26f).toFloat()
                setStroke(dp(ctx, 1f), Color.argb(130, 63, 216, 255))
            }
            alpha = 0f
        }
        val holo = HologramaView(ctx)
        caja.addView(holo, LinearLayout.LayoutParams(dp(ctx, 150f), dp(ctx, 150f)))
        val etiqueta = TextView(ctx).apply {
            text = "J.A.R.V.I.S."
            setTextColor(Color.rgb(63, 216, 255)); textSize = 10f; letterSpacing = 0.35f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); gravity = Gravity.CENTER
        }
        caja.addView(etiqueta)
        val msj = TextView(ctx).apply {
            setTextColor(Color.rgb(214, 246, 255)); textSize = 15f; gravity = Gravity.CENTER
            maxLines = 4; setShadowLayer(8f, 0f, 0f, Color.rgb(63, 216, 255))
            setPadding(0, dp(ctx, 6f), 0, 0)
        }
        caja.addView(msj, LinearLayout.LayoutParams(dp(ctx, 290f), LinearLayout.LayoutParams.WRAP_CONTENT))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, tipo,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(ctx, 90f)
        }
        return try {
            wm.addView(caja, params)
            gestor = wm; vista = caja; dibujo = holo; texto = msj
            caja.scaleX = 0.85f; caja.scaleY = 0.85f
            caja.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun quitar() {
        val v = vista ?: return
        v.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(300).withEndAction {
            try { gestor?.removeView(v) } catch (_: Exception) {}
        }.start()
        vista = null; dibujo = null; texto = null
    }

    /** Enciende la pantalla un momento para que se vea el holograma. */
    @Suppress("DEPRECATION")
    private fun despertar(c: Context) {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        try {
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:holo")
                .acquire(8000)
        } catch (_: Exception) {
        }
    }
}
