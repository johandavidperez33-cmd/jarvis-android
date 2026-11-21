package com.jarvis.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Holograma estilo J.A.R.V.I.S. de Iron Man: reactor con anillos que giran a distintas velocidades,
 * marcas de escala, barrido de escaneo, onda de voz circular y núcleo brillante.
 * Todo se dibuja con aceleración gráfica (sin desenfoques pesados) para que vaya fluido.
 */
class HologramaView(c: Context) : View(c) {
    @Volatile var estado = "escuchando"
    @Volatile var nivel = 0f

    private var suave = 0f            // nivel de voz suavizado
    private var energia = 0f          // 0 → 1 al aparecer (los anillos "se arman")
    private var rojo = 63f; private var verde = 216f; private var azul = 255f // color actual (se mezcla suave)
    private var giro = 0f             // ángulo acumulado: cambia de velocidad sin saltos
    private var vel = 1f
    private var ultimo = 0L
    private var t = 0f

    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val onda = Path()
    private val puntos = 96
    private val radios = FloatArray(puntos)
    private var guionFino: DashPathEffect? = null
    private var guionNucleo: DashPathEffect? = null
    private var nucleo: RadialGradient? = null
    private var colorNucleo = 0
    private var tamano = 0f

    private fun objetivoColor() = when (estado) {
        "escuchando" -> intArrayOf(110, 240, 255)  // cian claro
        "pensando" -> intArrayOf(255, 190, 80)     // ámbar
        "error" -> intArrayOf(255, 95, 95)         // rojo
        else -> intArrayOf(63, 200, 255)           // azul Jarvis (hablando)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        tamano = min(w, h) / 2f
        guionFino = DashPathEffect(floatArrayOf(tamano * 0.02f, tamano * 0.05f), 0f)
        guionNucleo = DashPathEffect(floatArrayOf(tamano * 0.05f, tamano * 0.03f), 0f)
        nucleo = null
    }

    private fun argb(a: Int) = Color.argb(a.coerceIn(0, 255), rojo.toInt(), verde.toInt(), azul.toInt())

    /** Arco con "resplandor": uno ancho y transparente debajo y uno fino y brillante encima. */
    private fun arco(cv: Canvas, r: Float, ini: Float, largo: Float, w: Float, a: Int) {
        val ae = (a * energia).toInt()
        trazo.color = argb(ae / 4); trazo.strokeWidth = w * 3.2f
        cv.drawArc(-r, -r, r, r, ini, largo * energia, false, trazo)
        trazo.color = argb(ae); trazo.strokeWidth = w
        cv.drawArc(-r, -r, r, r, ini, largo * energia, false, trazo)
    }

    private fun aro(cv: Canvas, r: Float, w: Float, a: Int, guion: DashPathEffect? = null) {
        trazo.color = argb((a * energia).toInt()); trazo.strokeWidth = w; trazo.pathEffect = guion
        cv.drawCircle(0f, 0f, r, trazo); trazo.pathEffect = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ahora = System.nanoTime()
        val dt = if (ultimo == 0L) 0.016f else ((ahora - ultimo) / 1e9f).coerceIn(0f, 0.05f)
        ultimo = ahora; t += dt
        val k = 1f - Math.pow(0.0005, dt.toDouble()).toFloat() // suavizado igual a cualquier FPS

        // color, velocidad y energía cambian de forma gradual (nada de saltos)
        val oc = objetivoColor()
        rojo += (oc[0] - rojo) * k; verde += (oc[1] - verde) * k; azul += (oc[2] - azul) * k
        val ov = when (estado) { "pensando" -> 3.2f; "escuchando" -> 1.4f; "error" -> 0.5f; else -> 1f }
        vel += (ov - vel) * k
        giro += dt * 40f * vel
        energia += (1f - energia) * (k * 0.6f)

        var objetivo = nivel
        if (estado == "hablando") objetivo = 0.3f + 0.35f * abs(sin(t * 7.3f) * sin(t * 2.9f + 1f)) + 0.15f * abs(sin(t * 17f))
        if (estado == "pensando") objetivo = 0.18f + 0.08f * sin(t * 5f)
        if (estado == "error") objetivo = 0.05f
        suave += (objetivo - suave) * (k * 1.5f).coerceAtMost(1f)
        nivel *= 0.9f

        val R = tamano
        canvas.save(); canvas.translate(width / 2f, height / 2f)

        // 1) anillo exterior con marcas de escala (como un dial)
        canvas.save(); canvas.rotate(giro * 0.25f)
        for (i in 0 until 72) {
            val largo = if (i % 6 == 0) 0.07f else 0.03f
            val a = if (i % 6 == 0) 200 else 90
            trazo.color = argb((a * energia).toInt()); trazo.strokeWidth = if (i % 6 == 0) 2.5f else 1.5f
            val ang = (i * 5f) * PI.toFloat() / 180f
            canvas.drawLine(cos(ang) * R * 0.97f, sin(ang) * R * 0.97f, cos(ang) * R * (0.97f - largo), sin(ang) * R * (0.97f - largo), trazo)
        }
        canvas.restore()

        // 2) tres segmentos grandes que giran al revés
        for (i in 0 until 3) arco(canvas, R * 0.84f, -giro * 1.2f + i * 120f, 78f, 4f, 220)
        aro(canvas, R * 0.78f, 1.5f, 80, guionFino)

        // 3) barrido de escaneo (más visible cuando piensa)
        val barrido = if (estado == "pensando") 230 else 110
        arco(canvas, R * 0.72f, giro * 3f, 40f, 6f, barrido)
        arco(canvas, R * 0.72f, giro * 3f + 180f, 40f, 6f, barrido)

        // 4) anillo de bloques (reactor)
        for (i in 0 until 10) arco(canvas, R * 0.64f, giro * 0.8f + i * 36f, 24f, 8f, if (i % 2 == 0) 120 else 190)

        // 5) onda de voz circular (forma suave, no rayitas)
        for (i in 0 until puntos) {
            val a = i * 2f * PI.toFloat() / puntos
            val ruido = sin(a * 5f + t * 6f) * 0.5f + sin(a * 9f - t * 9f) * 0.3f + sin(a * 3f + t * 2.3f) * 0.2f
            val objetivoR = R * (0.47f + suave * 0.11f * ruido + suave * 0.04f)
            radios[i] += (objetivoR - radios[i]) * if (radios[i] == 0f) 1f else 0.35f
        }
        onda.reset()
        for (i in 0..puntos) {
            val j = i % puntos
            val a = j * 2f * PI.toFloat() / puntos
            val x = cos(a) * radios[j]; val y = sin(a) * radios[j]
            if (i == 0) onda.moveTo(x, y) else onda.lineTo(x, y)
        }
        trazo.color = argb((60 * energia).toInt()); trazo.strokeWidth = 9f; canvas.drawPath(onda, trazo)
        trazo.color = argb((235 * energia).toInt()); trazo.strokeWidth = 2.5f; canvas.drawPath(onda, trazo)

        // 6) núcleo brillante que late con la voz
        val c = argb(255)
        if (nucleo == null || abs(Color.red(colorNucleo) - Color.red(c)) + abs(Color.green(colorNucleo) - Color.green(c)) + abs(Color.blue(colorNucleo) - Color.blue(c)) > 6) {
            colorNucleo = c
            nucleo = RadialGradient(0f, 0f, R * 0.3f, intArrayOf(Color.WHITE, c, Color.argb(90, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        val escala = (0.85f + suave * 0.35f + 0.03f * sin(t * 3f)) * energia
        canvas.save(); canvas.scale(escala, escala)
        relleno.shader = nucleo; relleno.alpha = 255
        canvas.drawCircle(0f, 0f, R * 0.3f, relleno)
        canvas.restore()
        canvas.save(); canvas.rotate(-giro * 1.5f)
        aro(canvas, R * 0.33f, 2f, 170, guionNucleo)
        canvas.restore()

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
    private var contextoVentana: Context? = null
    private var saliendo = false
    private val ocultarTarea = Runnable { quitar() }

    // efecto "máquina de escribir" del texto
    private var mensajeCompleto = ""
    private var letras = 0
    private val escribir = object : Runnable {
        override fun run() {
            val tv = texto ?: return
            if (letras >= mensajeCompleto.length) return
            letras = (letras + maxOf(1, mensajeCompleto.length / 60)).coerceAtMost(mensajeCompleto.length)
            tv.text = mensajeCompleto.substring(0, letras)
            principal.postDelayed(this, 18)
        }
    }

    private fun dp(c: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    /** ¿Tiene permiso para dibujar encima de otras apps? */
    fun puedeMostrar(c: Context) =
        Accesibilidad.instancia != null || (Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(c))

    /** estado: escuchando | pensando | hablando | error */
    fun mostrar(c: Context, estado: String, mensaje: String?) = principal.post {
        principal.removeCallbacks(ocultarTarea)
        val v = vista
        // la ventana pudo morir (p. ej. se reinició Accesibilidad): se rehace
        val sirve = v != null && v.isAttachedToWindow &&
            (contextoVentana !is Accesibilidad || contextoVentana === Accesibilidad.instancia)
        if (v != null && !sirve) descartar()
        if (vista == null && !crear(c)) return@post
        val caja = vista ?: return@post
        if (saliendo) { // estaba desapareciendo: vuelve sin crear otra ventana encima
            saliendo = false
            caja.animate().cancel()
            caja.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
        }
        dibujo?.estado = estado
        if (mensaje != null && mensaje != mensajeCompleto) {
            mensajeCompleto = mensaje; letras = 0
            principal.removeCallbacks(escribir); principal.post(escribir)
        }
        texto?.setTextColor(if (estado == "error") Color.rgb(255, 150, 150) else Color.rgb(214, 246, 255))
        if (Config.despertarPantalla(c)) despertar(c)
    }

    fun nivel(v: Float) { dibujo?.let { it.nivel = maxOf(it.nivel, v.coerceIn(0f, 1f)) } }

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
            setPadding(dp(ctx, 18f), dp(ctx, 12f), dp(ctx, 18f), dp(ctx, 14f))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(140, 2, 22, 36), Color.argb(210, 1, 8, 14))).apply {
                cornerRadius = dp(ctx, 28f).toFloat()
                setStroke(dp(ctx, 1f), Color.argb(120, 63, 216, 255))
            }
            alpha = 0f
            setLayerType(View.LAYER_TYPE_HARDWARE, null) // animación fluida
        }
        val holo = HologramaView(ctx)
        caja.addView(holo, LinearLayout.LayoutParams(dp(ctx, 170f), dp(ctx, 170f)))
        val etiqueta = TextView(ctx).apply {
            text = "J.A.R.V.I.S."
            setTextColor(Color.rgb(63, 216, 255)); textSize = 10f; letterSpacing = 0.4f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); gravity = Gravity.CENTER
            setShadowLayer(10f, 0f, 0f, Color.rgb(63, 216, 255))
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(ctx, 90f)
        }
        return try {
            wm.addView(caja, params)
            gestor = wm; vista = caja; dibujo = holo; texto = msj; contextoVentana = ctx
            saliendo = false; mensajeCompleto = ""
            caja.scaleX = 0.8f; caja.scaleY = 0.8f; caja.translationY = dp(ctx, 20f).toFloat()
            caja.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setInterpolator(DecelerateInterpolator(2f)).setDuration(320).start()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Desaparece suave; si en ese momento llega otro "mostrar", se reutiliza la misma ventana. */
    private fun quitar() {
        val v = vista ?: return
        saliendo = true
        v.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(320).withEndAction {
            if (saliendo && vista === v) descartar()
        }.start()
    }

    private fun descartar() {
        val v = vista
        principal.removeCallbacks(escribir)
        if (v != null) try { gestor?.removeView(v) } catch (_: Exception) {}
        vista = null; dibujo = null; texto = null; gestor = null; contextoVentana = null
        saliendo = false; mensajeCompleto = ""
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
