package com.jarvis.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.ConsumerIrManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Ajustes guardados en el celular: dirección de Jarvis, clave de acceso y clave de Picovoice. */
object Config {
    private fun p(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun url(c: Context) = (p(c).getString("url", "") ?: "").trim().trimEnd('/')
    fun clave(c: Context) = p(c).getString("clave", "") ?: ""
    fun pico(c: Context) = p(c).getString("pico", "") ?: ""
    // ---- reconocimiento de la voz del dueño
    fun huella(c: Context): DoubleArray? {
        val s = p(c).getString("huella", null) ?: return null
        return try {
            val a = JSONArray(s); DoubleArray(a.length()) { a.getDouble(it) }
        } catch (e: Exception) { null }
    }
    fun guardarHuella(c: Context, h: DoubleArray?) {
        val e = p(c).edit()
        if (h == null) e.remove("huella") else e.putString("huella", JSONArray(h.toList()).toString())
        e.apply()
    }
    fun soloMiVoz(c: Context) = p(c).getBoolean("solo_mi_voz", true)
    fun guardarSoloMiVoz(c: Context, v: Boolean) = p(c).edit().putBoolean("solo_mi_voz", v).apply()
    /** Exigencia 0..100 -> umbral de parecido 0,45..0,80 (por defecto 0,60). */
    fun exigencia(c: Context) = p(c).getInt("exigencia", 43)
    fun guardarExigencia(c: Context, v: Int) = p(c).edit().putInt("exigencia", v.coerceIn(0, 100)).apply()
    fun umbral(c: Context) = 0.45 + 0.35 * exigencia(c) / 100.0

    fun guardar(c: Context, url: String?, clave: String?, pico: String?) {
        val e = p(c).edit()
        url?.takeIf { it.isNotBlank() }?.let { e.putString("url", it.trim().trimEnd('/')) }
        clave?.takeIf { it.isNotBlank() }?.let { e.putString("clave", it.trim().lowercase()) }
        pico?.takeIf { it.isNotBlank() }?.let { e.putString("pico", it.trim()) }
        e.apply()
    }
}

/** Compara huellas de voz (similitud coseno, 1 = idénticas). */
object Huella {
    fun desdeResultado(json: String): Pair<DoubleArray, Int>? {
        return try {
            val o = JSONObject(json)
            val a = o.optJSONArray("spk") ?: return null
            Pair(DoubleArray(a.length()) { a.getDouble(it) }, o.optInt("spk_frames", 0))
        } catch (e: Exception) { null }
    }
    fun normalizar(v: DoubleArray): DoubleArray {
        val n = sqrt(v.sumOf { it * it }).takeIf { it > 0 } ?: 1.0
        return DoubleArray(v.size) { v[it] / n }
    }
    fun similitud(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        val x = normalizar(a); val y = normalizar(b)
        return (x.indices).sumOf { x[it] * y[it] }
    }
    fun promedio(lista: List<DoubleArray>): DoubleArray {
        val n = lista.map { normalizar(it) }
        return DoubleArray(n[0].size) { i -> n.sumOf { it[i] } / n.size }
    }
}

/** Conexión con el servidor de Jarvis en la PC. */
object Api {
    fun post(c: Context, ruta: String, cuerpo: JSONObject): JSONObject {
        val con = URL(Config.url(c) + ruta).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15000
            con.readTimeout = 90000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-clave", Config.clave(c))
            con.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            val codigo = con.responseCode
            val flujo = if (codigo < 400) con.inputStream else con.errorStream
            val texto = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (codigo == 401) throw IOException("clave de acceso incorrecta")
            if (codigo >= 400) throw IOException("error $codigo del servidor")
            return JSONObject(texto)
        } finally {
            con.disconnect()
        }
    }

    fun urlVoz(c: Context, texto: String): String =
        Config.url(c) + "/api/voz?clave=" + URLEncoder.encode(Config.clave(c), "UTF-8") +
            "&texto=" + URLEncoder.encode(texto, "UTF-8")
}

/** Emisor infrarrojo del celular (para el aire acondicionado). */
object Infrarrojo {
    fun disponible(c: Context): Boolean {
        val ir = c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager ?: return false
        return ir.hasIrEmitter()
    }

    fun transmitir(c: Context, frecuencia: Int, patron: IntArray): Boolean {
        val ir = c.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager ?: return false
        if (!ir.hasIrEmitter()) return false
        return try {
            ir.transmit(frecuencia, patron)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Ejecuta las "acciones" que devuelve Jarvis (por ahora, señales infrarrojas). */
    fun ejecutar(c: Context, acciones: JSONArray?): Boolean {
        if (acciones == null) return true
        var todoBien = true
        for (i in 0 until acciones.length()) {
            val a = acciones.optJSONObject(i) ?: continue
            if (a.optString("tipo") != "ir") continue
            val arr = a.optJSONArray("patron") ?: continue
            val patron = IntArray(arr.length()) { arr.getInt(it) }
            if (!transmitir(c, a.optInt("frecuencia", 38000), patron)) todoBien = false
        }
        return todoBien
    }
}

/** Graba la orden que dices después de "Jarvis" y la devuelve como WAV. */
object Grabadora {
    private const val TASA = 16000

    @SuppressLint("MissingPermission")
    fun grabarOrden(): ByteArray? {
        val minimo = AudioRecord.getMinBufferSize(TASA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, TASA,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, 4096) * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        val salida = ByteArrayOutputStream()
        val buf = ShortArray(1600) // 100 ms
        var hablo = false
        var silencio = 0
        var total = 0
        rec.startRecording()
        try {
            while (total < 100) { // máximo 10 segundos
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                total++
                var suma = 0.0
                for (i in 0 until n) suma += buf[i].toDouble() * buf[i]
                val volumen = sqrt(suma / n)
                if (volumen > 700) {
                    hablo = true
                    silencio = 0
                } else {
                    silencio++
                }
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                salida.write(bytes)
                if (hablo && silencio >= 12) break // 1,2 s de silencio: terminó de hablar
                if (!hablo && total >= 60) break   // 6 s sin hablar
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return if (hablo) wav(salida.toByteArray()) else null
    }

    fun wav(pcm: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1.toShort()); b.putShort(1.toShort())
        b.putInt(TASA); b.putInt(TASA * 2); b.putShort(2.toShort()); b.putShort(16.toShort())
        b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
        return b.array()
    }
}
