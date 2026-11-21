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
import org.vosk.SpeakerModel
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Servicio que queda escuchando con la pantalla apagada (Vosk, sin internet).
 * Al oír "Jarvis": graba la orden, comprueba que sea la voz del dueño, la manda a la PC,
 * dispara el infrarrojo si hace falta y responde con voz.
 */
class JarvisService : Service() {

    companion object {
        private const val CANAL = "jarvis_oido"
        private const val ID = 7
        private const val TASA = 16000
        private const val UMBRAL_VOZ = 700.0
        private const val FRASES_REGISTRO = 3
        @Volatile var activo = false
        var alIntercambio: ((String, String) -> Unit)? = null
        var alCambiarEstado: (() -> Unit)? = null
        /** Progreso del registro de voz: (mensaje, terminado) */
        var alRegistro: ((String, Boolean) -> Unit)? = null

        fun iniciar(c: Context) {
            val i = Intent(c, JarvisService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun detener(c: Context) { c.startService(Intent(c, JarvisService::class.java).setAction("DETENER")) }
        fun escucharAhora(c: Context) { c.startService(Intent(c, JarvisService::class.java).setAction("ESCUCHAR")) }
        fun registrarVoz(c: Context) {
            val i = Intent(c, JarvisService::class.java).setAction("REGISTRAR")
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
    }

    private enum class Estado { ESPERANDO, ORDEN, PROCESANDO, REGISTRO }

    private val trabajo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var estado = Estado.ESPERANDO
    @Volatile private var pedirOrden = false
    @Volatile private var pedirRegistro = false
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

    private fun avisarRegistro(texto: String, terminado: Boolean = false) {
        avisar(texto)
        principal.post { alRegistro?.invoke(texto, terminado) }
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
            Celular.prepararFrases(this) // frases pregrabadas para responder al instante
            principal.post { alCambiarEstado?.invoke() }
        }
        when (intent?.action) {
            "ESCUCHAR" -> pedirOrden = true
            "REGISTRAR" -> pedirRegistro = true
        }
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
        var modeloHuella: SpeakerModel? = null
        var rec: Recognizer? = null
        var recOrden: Recognizer? = null // entiende la orden y saca la huella de voz, dentro del celular
        var mic: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            if (!ModeloVoz.listo(this)) avisar("Descargando el oído (40 MB, solo la primera vez)…")
            val ruta = ModeloVoz.asegurar(this) { p -> if (p % 10 == 0) avisar("Descargando el oído… $p%") }
            if (!ModeloVoz.listoHuella(this)) avisar("Descargando el reconocimiento de voz (13 MB)…")
            val rutaHuella = ModeloVoz.asegurarHuella(this) { p -> if (p % 20 == 0) avisar("Descargando reconocimiento de voz… $p%") }
            val m = Model(ruta); modelo = m
            val mh = SpeakerModel(rutaHuella); modeloHuella = mh
            val r = Recognizer(m, TASA.toFloat(), "[\"jarvis\", \"[unk]\"]"); rec = r
            val ro = Recognizer(m, TASA.toFloat(), mh).apply { setWords(true) }; recOrden = ro
            val minimo = AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val mc = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, TASA, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, 6400) * 2)
            mic = mc
            if (mc.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("no pude abrir el micrófono")
            mc.startRecording()
            avisar(if (Config.huella(this) == null) "Diga \"Jarvis\"… (consejo: registre su voz en ⚙)" else "Diga \"Jarvis\"…")

            val buf = ShortArray(1600) // 100 ms
            val captura = ByteArrayOutputStream()
            var total = 0
            var silencio = 0
            var hablo = false
            var bipHecho = false
            val muestras = mutableListOf<DoubleArray>() // huellas del registro

            fun empezarCaptura() {
                captura.reset(); total = 0; silencio = 0; hablo = false
                ro.reset()
            }

            while (corriendo) {
                val n = mc.read(buf, 0, buf.size)
                if (n <= 0) continue

                if (pedirRegistro && estado == Estado.ESPERANDO) {
                    pedirRegistro = false
                    muestras.clear()
                    estado = Estado.REGISTRO
                    empezarCaptura()
                    avisarRegistro("Registro de voz 1/$FRASES_REGISTRO: después del bip diga una frase larga, por ejemplo \"Jarvis, ¿qué hora es ahora mismo?\"")
                    bip()
                    continue
                }
                if (pedirOrden && estado == Estado.ESPERANDO) { // botón 🎙 de la app
                    pedirOrden = false
                    estado = Estado.ORDEN
                    empezarCaptura()
                    bip(); bipHecho = true
                    avisar("Escuchando…")
                    continue
                }

                when (estado) {
                    Estado.ESPERANDO -> {
                        val final = r.acceptWaveForm(buf, n)
                        val texto = if (final) r.result else r.partialResult
                        if (texto.contains("jarvis")) {
                            r.reset()
                            estado = Estado.ORDEN
                            empezarCaptura(); bipHecho = false
                            avisar("Escuchando…")
                        }
                    }
                    Estado.ORDEN -> {
                        captura.write(aBytes(buf, n))
                        ro.acceptWaveForm(buf, n)
                        total++
                        val voz = volumen(buf, n) > UMBRAL_VOZ
                        if (voz) silencio = 0 else silencio++
                        if (voz && total > 3) hablo = true // los primeros 300 ms pueden ser el final de "Jarvis"
                        if (!hablo && total == 8 && !bipHecho) { // dijo solo "Jarvis": bip y esperamos la orden
                            bip(); bipHecho = true
                            empezarCaptura()
                        }
                        val termino = (hablo && silencio >= 9) || total >= 100 // 0,9 s de silencio
                        if (termino) {
                            val resultado = ro.finalResult
                            ro.reset()
                            val rechazo = rechazarVoz(resultado)
                            if (rechazo != null) {
                                estado = Estado.ESPERANDO
                                r.reset()
                                avisar(rechazo)
                            } else {
                                estado = Estado.PROCESANDO
                                val crudo = try { JSONObject(resultado).optString("text") } catch (e: Exception) { "" }
                                val rapida = if (crudo.isNotBlank()) Celular.ordenRapida(this, crudo) else null
                                if (rapida != null) ordenRapida(rapida.first, rapida.second, crudo) // al instante, sin internet
                                else procesar(Grabadora.wav(captura.toByteArray()), textoSeguro(resultado))
                            }
                        } else if (!hablo && total >= 60) {
                            estado = Estado.ESPERANDO
                            r.reset()
                            avisar("Diga \"Jarvis\"…")
                        }
                    }
                    Estado.REGISTRO -> {
                        ro.acceptWaveForm(buf, n)
                        total++
                        val voz = volumen(buf, n) > UMBRAL_VOZ
                        if (voz) { hablo = true; silencio = 0 } else silencio++
                        if ((hablo && silencio >= 10) || total >= 80) {
                            val h = Huella.desdeResultado(ro.finalResult)
                            ro.reset()
                            if (h == null || h.second < 60) {
                                avisarRegistro("Muy corto. Repita la frase ${muestras.size + 1}/$FRASES_REGISTRO, un poco más larga, después del bip.")
                            } else {
                                muestras += h.first
                                if (muestras.size >= FRASES_REGISTRO) {
                                    val promedio = Huella.promedio(muestras)
                                    Config.guardarHuella(this, promedio)
                                    val parecido = muestras.minOf { Huella.similitud(promedio, it) }
                                    estado = Estado.ESPERANDO
                                    r.reset()
                                    avisarRegistro("✔ Voz registrada (coherencia ${(parecido * 100).roundToInt()}%). " +
                                        "Desde ahora Jarvis solo le responde a usted.", true)
                                    continue
                                }
                                avisarRegistro("Bien. Frase ${muestras.size + 1}/$FRASES_REGISTRO: diga otra frase distinta después del bip.")
                            }
                            empezarCaptura()
                            bip()
                        } else if (!hablo && total >= 70) {
                            empezarCaptura()
                            avisarRegistro("No le escuché. Diga la frase ${muestras.size + 1}/$FRASES_REGISTRO después del bip.")
                            bip()
                        }
                    }
                    Estado.PROCESANDO -> { /* mientras Jarvis piensa y habla, no escuchamos */ }
                }
            }
        } catch (e: Exception) {
            avisar("No pude iniciar el oído: ${e.message}")
            principal.post { alRegistro?.invoke("Error: ${e.message}", true) }
            corriendo = false
        } finally {
            try { mic?.stop() } catch (_: Exception) {}
            mic?.release()
            rec?.close()
            recOrden?.close()
            modeloHuella?.close()
            modelo?.close()
        }
    }

    /** Devuelve un mensaje si la voz NO es la del dueño (o null si se acepta). */
    private fun rechazarVoz(resultado: String): String? {
        val dueno = Config.huella(this) ?: return null            // sin registro: responde a todos
        if (!Config.soloMiVoz(this)) return null
        val h = Huella.desdeResultado(resultado)
            ?: return "🔒 Muy corto para reconocer su voz. Diga \"Jarvis\" y una frase completa."
        val parecido = Huella.similitud(dueno, h.first)
        val pct = (parecido * 100).roundToInt()
        val minimo = (Config.umbral(this) * 100).roundToInt()
        return if (parecido >= Config.umbral(this)) null
        else "🔒 Voz no reconocida (parecido $pct%, se pide $minimo%). Ignorado. Diga \"Jarvis\"…"
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
                val r = Api.post(this, "/api/chat", JSONObject().put("texto", texto).put("sesion", "app")
                    .put("voz", true).put("origen", "app"))
                val respuesta = r.optString("respuesta")
                val acciones = r.optJSONArray("acciones")
                var huboIR = false
                val problemas = mutableListOf<String>()
                if (acciones != null) for (i in 0 until acciones.length()) {
                    val a = acciones.optJSONObject(i) ?: continue
                    when (a.optString("tipo")) {
                        "ir" -> {
                            huboIR = true
                            val arr = a.optJSONArray("patron") ?: continue
                            if (!Infrarrojo.transmitir(this, a.optInt("frecuencia", 38000), IntArray(arr.length()) { arr.getInt(it) }))
                                problemas += "📡 ✘ El celular NO pudo enviar el infrarrojo"
                        }
                        "celular" -> Celular.ejecutar(this, a)?.let { problemas += "⚠ $it" }
                    }
                }
                val aviso = (listOfNotNull(if (huboIR && problemas.isEmpty()) "📡 Señal infrarroja enviada ✔" else null) +
                    problemas + respuesta).joinToString("\n")
                val audio = r.optString("audio").takeIf { it.length > 100 }
                // el texto aparece justo cuando empieza a sonar la voz
                principal.post {
                    hablar(respuesta, audio) {
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
            if (activo && mensaje != null) avisar(mensaje)
        }
    }

    /** Orden simple del celular: se ejecuta y se responde al instante con una frase pregrabada. */
    private fun ordenRapida(accion: JSONObject, frase: String, dicho: String) {
        val problema = Celular.ejecutar(this, accion)
        val respuesta = problema ?: frase
        principal.post { alIntercambio?.invoke(dicho, respuesta) }
        avisar(if (problema != null) "⚠ $problema" else "⚡ $frase")
        val archivo = Celular.archivoFrase(this, frase)
        if (problema == null && archivo.exists() && archivo.length() > 1000) {
            principal.post { reproducirArchivo(archivo.absolutePath) }
        } else {
            principal.post { hablar(respuesta, null) {} }
        }
    }

    private fun reproducirArchivo(ruta: String) {
        try {
            val mp = MediaPlayer()
            reproductor = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(ruta)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { it.release(); reproductor = null; listo() }
            mp.setOnErrorListener { m, _, _ -> m.release(); reproductor = null; listo(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            listo()
        }
    }

    private fun hablar(texto: String, audioB64: String?, alComenzarAccion: () -> Unit) {
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
            if (audioB64 != null) { // el audio vino junto con la respuesta: no hay que pedirlo
                val archivo = java.io.File(cacheDir, "respuesta.mp3")
                archivo.writeBytes(Base64.decode(audioB64, Base64.DEFAULT))
                mp.setDataSource(archivo.absolutePath)
            } else {
                mp.setDataSource(Api.urlVoz(this, texto))
            }
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
