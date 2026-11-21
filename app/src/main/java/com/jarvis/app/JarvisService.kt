package com.jarvis.app

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Servicio que queda escuchando con la pantalla apagada.
 * Al oír "Jarvis": bip -> graba la orden -> la manda a la PC -> dispara el infrarrojo si hace falta -> responde con voz.
 */
class JarvisService : Service() {

    companion object {
        private const val CANAL = "jarvis_oido"
        private const val ID = 7
        @Volatile var activo = false
        /** La pantalla de la app se suscribe para mostrar la conversación. */
        var alIntercambio: ((String, String) -> Unit)? = null
        var alCambiarEstado: (() -> Unit)? = null

        fun iniciar(c: Context) {
            val i = Intent(c, JarvisService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun detener(c: Context) { c.startService(Intent(c, JarvisService::class.java).setAction("DETENER")) }
        fun escucharAhora(c: Context) { c.startService(Intent(c, JarvisService::class.java).setAction("ESCUCHAR")) }
    }

    private var porcupine: PorcupineManager? = null
    private val trabajo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var ocupado = false
    private var bloqueo: PowerManager.WakeLock? = null
    private var reproductor: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Jarvis escuchando", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CANAL)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(abrir)
            .setOngoing(true)
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
            val n = notificacion("Diga \"Jarvis\"…")
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID, n)
            }
            activo = true
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            bloqueo = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:oido").apply {
                setReferenceCounted(false)
                acquire()
            }
            iniciarOido()
            principal.post { alCambiarEstado?.invoke() }
        }
        if (intent?.action == "ESCUCHAR") principal.post { activado() }
        return START_STICKY
    }

    private fun iniciarOido() {
        if (porcupine != null || !activo) return
        val clave = Config.pico(this)
        if (clave.isBlank()) {
            avisar("Falta la clave de Picovoice: abre la app y toca ⚙")
            return
        }
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(clave)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.65f)
                .build(applicationContext, PorcupineManagerCallback { _ -> principal.post { activado() } })
            porcupine?.start()
            avisar("Diga \"Jarvis\"…")
        } catch (e: Exception) {
            porcupine = null
            avisar("No pude iniciar el oído: ${e.message}")
        }
    }

    private fun pararOido() {
        try {
            porcupine?.stop()
            porcupine?.delete()
        } catch (_: Exception) {
        }
        porcupine = null
    }

    private fun activado() {
        if (ocupado || !activo) return
        ocupado = true
        pararOido()
        reproductor?.let { try { it.release() } catch (_: Exception) {} }
        reproductor = null
        avisar("Escuchando…")
        trabajo.execute {
            try {
                bip()
                val wav = Grabadora.grabarOrden()
                if (wav == null) {
                    terminar("Diga \"Jarvis\"…")
                    return@execute
                }
                avisar("Entendiendo…")
                val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
                val texto = Api.post(this, "/api/transcribir", JSONObject().put("audio", b64))
                    .optString("texto").trim()
                if (texto.isEmpty()) {
                    terminar("No le entendí. Diga \"Jarvis\"…")
                    return@execute
                }
                avisar("Usted: $texto")
                val r = Api.post(this, "/api/chat", JSONObject().put("texto", texto).put("sesion", "app"))
                val respuesta = r.optString("respuesta")
                if (!Infrarrojo.ejecutar(this, r.optJSONArray("acciones"))) {
                    avisar("No pude usar el infrarrojo del celular")
                }
                principal.post { alIntercambio?.invoke(texto, respuesta) }
                avisar(respuesta)
                principal.post { hablar(respuesta) }
            } catch (e: Exception) {
                terminar("Sin conexión con Jarvis (${e.message}). ¿Está encendida la PC?")
            }
        }
    }

    private fun terminar(mensaje: String? = null) {
        principal.post {
            ocupado = false
            if (activo) {
                iniciarOido()
                if (mensaje != null) avisar(mensaje)
            }
        }
    }

    private fun hablar(texto: String) {
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
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                it.release(); reproductor = null; terminar()
            }
            mp.setOnErrorListener { m, _, _ ->
                m.release(); reproductor = null; terminar(); true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            terminar()
        }
    }

    private fun bip() {
        try {
            val tono = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tono.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            Thread.sleep(250)
            tono.release()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        activo = false
        pararOido()
        reproductor?.let { try { it.release() } catch (_: Exception) {} }
        bloqueo?.let { if (it.isHeld) it.release() }
        principal.post { alCambiarEstado?.invoke() }
        super.onDestroy()
    }
}
