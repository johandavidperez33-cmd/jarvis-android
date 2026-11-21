package com.jarvis.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Servicio que queda escuchando con la pantalla apagada (Vosk, sin internet).
 * Al oír "Jarvis": graba la orden -> la manda a la PC -> dispara el infrarrojo si hace falta -> responde con voz.
 */
class JarvisService : Service() {

    companion object {
        private const val CANAL = "jarvis_oido"
        private const val ID = 7
        private const val TASA = 16000
        private const val UMBRAL_VOZ = 700.0
        @Volatile var activo = false
        var alIntercambio: ((String, String) -> Unit)? = null
        var alCambiarEstado: (() -> Unit)? = null

        fun iniciar(c: Context) {
            val i = Intent(c, JarvisService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun detener(c: Context) { c.startService(Intent(c, JarvisService::class.java).setAction("DETENER")) }
        fun escucharAhora(c: Context) { c.startService(Intent(c, JarvisService::class.java).setAction("ESCUCHAR")) }
    }

    private enum class Estado { ESPERANDO, ORDEN, PROCESANDO }

    private val trabajo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var estado = Estado.ESPERANDO
    @Volatile private var pedirOrden = false
    @Volatile private var corriendo = false
    private var hiloOido: Thread? = null
    private var bloqueo: PowerManager.WakeLock? = null
    private var reproductor: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Jarvis escuchando", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CANAL)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun avisar(texto: String) {
        getSystemService(NotificationManager::class.java).notify(ID, notificacion(texto))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "DETENER") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!activo) {
            val n = notificacion("Preparando el oído…")
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, n)
            activo = true
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            bloqueo = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:oido").apply {
                setReferenceCounted(false)
                acquire()
            }
            iniciarOido()
            principal.post { alCambiarEstado?.invoke() }
        }
        if (intent?.action == "ESCUCHAR") pedirOrden = true
        return START_STICKY
    }

    private fun iniciarOido() {
        if (corriendo) return
        corriendo = true
        hiloOido = Thread({ bucleOido() }, "jarvis-oido").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun bucleOido() {
        var modelo: Model? = null
        var rec: Recognizer? = null
        var recOrden: Recognizer? = null // entiende la orden completa dentro del celular (rápido)
        var mic: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            if (!ModeloVoz.listo(this)) avisar("Descargando el oído (40 MB, solo la primera vez)…")
            val ruta = ModeloVoz.asegurar(this) { p -> if (p % 10 == 0) avisar("Descargando el oído… $p%") }
            modelo = Model(ruta)
            rec = Recognizer(modelo, TASA.toFloat(), "[\"jarvis\", \"[unk]\"]")
            recOrden = Recognizer(modelo, TASA.toFloat()).apply { setWords(true) }
            val minimo = AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TASA, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, 6400) * 2)
            if (mic.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("no pude abrir el micrófono")
            mic.startRecording()
            avisar("Diga \"Jarvis\"…")

            val buf = ShortArray(1600) // 100 ms
            val captura = ByteArrayOutputStream()
            var total = 0
            var silencio = 0
            var hablo = false
            var bipHecho = false

            while (corriendo) {
                val n = mic.read(buf, 0, buf.size)
                if (n <= 0) continue

                if (pedirOrden && estado == Estado.ESPERANDO) { // botón 🎙 de la app
                    pedirOrden = false
                    estado = Estado.ORDEN
                    captura.reset(); total = 0; silencio = 0; hablo = false
                    recOrden.reset()
                    bip(); bipHecho = true
                    avisar("Escuchando…")
                    continue
                }

                when (estado) {
                    Estado.ESPERANDO -> {
                        val final = rec.acceptWaveForm(buf, n)
                        val texto = if (final) rec.result else rec.partialResult
                        if (texto.contains("jarvis")) {
                            rec.reset()
                            estado = Estado.ORDEN
                            captura.reset(); total = 0; silencio = 0; hablo = false; bipHecho = false
                            recOrden.reset()
                            avisar("Escuchando…")
                        }
                    }
                    Estado.ORDEN -> {
                        captura.write(aBytes(buf, n))
                        recOrden.acceptWaveForm(buf, n)
                        total++
                        val voz = volumen(buf, n) > UMBRAL_VOZ
                        if (voz) silencio = 0 else silencio++
                        if (voz && total > 3) hablo = true // los primeros 300 ms pueden ser el final de "Jarvis"
                        if (!hablo && total == 8 && !bipHecho) { // dijo solo "Jarvis": bip y esperamos la orden
                            bip(); bipHecho = true
                            captura.reset()
                            recOrden.reset()
                        }
                        val termino = (hablo && silencio >= 9) || total >= 100 // 0,9 s de silencio
                        if (termino) {
                            estado = Estado.PROCESANDO
                            val local = textoSeguro(recOrden.finalResult)
                            recOrden.reset()
                            procesar(Grabadora.wav(captura.toByteArray()), local)
                        } else if (!hablo && total >= 60) {
                            estado = Estado.ESPERANDO
                            rec.reset()
                            avisar("Diga \"Jarvis\"…")
                        }
                    }
                    Estado.PROCESANDO -> { /* mientras Jarvis piensa y habla, no escuchamos */ }
                }
            }
        } catch (e: Exception) {
            avisar("No pude iniciar el oído: ${e.message}")
            corriendo = false
        } finally {
            try { mic?.stop() } catch (_: Exception) {}
            mic?.release()
            rec?.close()
            recOrden?.close()
            modelo?.close()
        }
    }

    private fun volumen(buf: ShortArray, n: Int): Double {
        var suma = 0.0
        for (i in 0 until n) suma += buf[i].toDouble() * buf[i]
        return sqrt(suma / n)
    }

    private fun aBytes(buf: ShortArray, n: Int): ByteArray {
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = buf[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** Texto que entendió Vosk, solo si está seguro (confianza promedio alta). Si no, null -> usamos Gemini. */
    private fun textoSeguro(json: String): String? {
        return try {
            val o = JSONObject(json)
            val texto = o.optString("text").trim()
            val palabras = o.optJSONArray("result") ?: return null
            if (texto.isEmpty() || palabras.length() == 0) return null
            var suma = 0.0
            for (i in 0 until palabras.length()) suma += palabras.getJSONObject(i).optDouble("conf", 0.0)
            if (suma / palabras.length() >= 0.85) texto else null
        } catch (e: Exception) {
            null
        }
    }

    private fun procesar(wav: ByteArray, textoLocal: String?) {
        avisar("Entendiendo…")
        trabajo.execute {
            try {
                var texto = textoLocal ?: run {
                    val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
                    Api.post(this, "/api/transcribir", JSONObject().put("audio", b64)).optString("texto").trim()
                }
                texto = texto.replace(Regex("^(oye\\s+)?(jarvis|yarvis|harvis)[,.!¿?\\s]*", RegexOption.IGNORE_CASE), "").trim()
                if (texto.isEmpty()) {
                    listo("No le entendí. Diga \"Jarvis\"…")
                    return@execute
                }
                avisar("Usted: $texto")
                val r = Api.post(this, "/api/chat", JSONObject().put("texto", texto).put("sesion", "app"))
                val respuesta = r.optString("respuesta")
                val acciones = r.optJSONArray("acciones")
                val huboIR = acciones != null && acciones.length() > 0
                val irOk = Infrarrojo.ejecutar(this, acciones)
                val aviso = when {
                    huboIR && irOk -> "📡 Señal infrarroja enviada ✔\n$respuesta"
                    huboIR -> "📡 ✘ El celular NO pudo enviar el infrarrojo\n$respuesta"
                    else -> respuesta
                }
                // el texto aparece justo cuando empieza a sonar la voz
                principal.post {
                    hablar(respuesta) {
                        avisar(aviso)
                        alIntercambio?.invoke(texto, respuesta)
                    }
                }
            } catch (e: Exception) {
                listo("Sin conexión con Jarvis (${e.message}). ¿Está encendida la PC?")
            }
        }
    }

    /** Vuelve a esperar la palabra "Jarvis". */
    private fun listo(mensaje: String? = null) {
        principal.post {
            estado = Estado.ESPERANDO
            if (activo) avisar(mensaje ?: "Diga \"Jarvis\"…")
        }
    }

    private fun hablar(texto: String, alComenzarAccion: () -> Unit) {
        var yaComenzo = false
        val alComenzar = { if (!yaComenzo) { yaComenzo = true; alComenzarAccion() } }
        try {
            val mp = MediaPlayer()
            reproductor = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(Api.urlVoz(this, texto))
            mp.setOnPreparedListener { it.start(); alComenzar() }
            mp.setOnCompletionListener { it.release(); reproductor = null; listo() }
            mp.setOnErrorListener { m, _, _ -> alComenzar(); m.release(); reproductor = null; listo(); true }
            mp.prepareAsync()
            principal.postDelayed({ alComenzar() }, 8000) // si la voz tarda demasiado, igual mostramos el texto
        } catch (e: Exception) {
            alComenzar()
            listo()
        }
    }

    private fun bip() {
        try {
            val tono = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tono.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            principal.postDelayed({ tono.release() }, 400)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        activo = false
        corriendo = false
        hiloOido?.join(1500)
        reproductor?.let { try { it.release() } catch (_: Exception) {} }
        bloqueo?.let { if (it.isHeld) it.release() }
        principal.post { alCambiarEstado?.invoke() }
        super.onDestroy()
    }
}
