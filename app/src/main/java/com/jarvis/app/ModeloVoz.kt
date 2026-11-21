package com.jarvis.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Descarga (una sola vez) los modelos de Vosk: voz en español (~40 MB) y huella de voz (~13 MB). */
object ModeloVoz {
    private const val URL_ES = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    private const val URL_SPK = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"

    fun listo(c: Context) = File(File(c.filesDir, "vosk-es"), "listo").exists()
    fun listoHuella(c: Context) = File(File(c.filesDir, "vosk-spk"), "listo").exists()

    /** Modelo de voz en español. progreso recibe 0..100. */
    fun asegurar(c: Context, progreso: (Int) -> Unit): String =
        bajar(c, URL_ES, "vosk-es", "am", 80_000_000L, progreso)

    /** Modelo de huella de voz (para reconocer al dueño). */
    fun asegurarHuella(c: Context, progreso: (Int) -> Unit): String =
        bajar(c, URL_SPK, "vosk-spk", "mfcc.conf", 30_000_000L, progreso)

    private fun bajar(c: Context, url: String, nombre: String, marcador: String, tamanoAprox: Long,
                      progreso: (Int) -> Unit): String {
        val destino = File(c.filesDir, nombre)
        if (File(destino, "listo").exists()) return ruta(destino, marcador)
        destino.deleteRecursively()
        destino.mkdirs()
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 20000
        con.readTimeout = 60000
        try {
            var leido = 0L
            var ultimo = -1
            ZipInputStream(con.inputStream.buffered()).use { zip ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val entrada = zip.nextEntry ?: break
                    val archivo = File(destino, entrada.name)
                    if (!archivo.canonicalPath.startsWith(destino.canonicalPath)) continue // seguridad
                    if (entrada.isDirectory) {
                        archivo.mkdirs()
                    } else {
                        archivo.parentFile?.mkdirs()
                        archivo.outputStream().use { out ->
                            while (true) {
                                val n = zip.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                leido += n
                                val p = ((leido * 100) / tamanoAprox).toInt().coerceIn(0, 99)
                                if (p != ultimo) { ultimo = p; progreso(p) }
                            }
                        }
                    }
                }
            }
        } finally {
            con.disconnect()
        }
        File(destino, "listo").writeText("ok")
        progreso(100)
        return ruta(destino, marcador)
    }

    /** El zip trae una carpeta adentro: buscamos la que tiene el archivo marcador. */
    private fun ruta(destino: File, marcador: String): String {
        if (File(destino, marcador).exists()) return destino.absolutePath
        val interna = destino.listFiles()?.firstOrNull { it.isDirectory && File(it, marcador).exists() }
        return (interna ?: destino).absolutePath
    }
}
