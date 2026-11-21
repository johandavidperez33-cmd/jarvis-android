package com.jarvis.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Descarga (una sola vez) el modelo de voz en español de Vosk, ~40 MB. */
object ModeloVoz {
    private const val URL_MODELO = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
    private const val CARPETA = "vosk-es"

    fun carpeta(c: Context) = File(c.filesDir, CARPETA)
    fun listo(c: Context) = File(carpeta(c), "listo").exists()

    /** Devuelve la ruta del modelo, descargándolo si hace falta. progreso recibe 0..100. */
    fun asegurar(c: Context, progreso: (Int) -> Unit): String {
        val destino = carpeta(c)
        if (listo(c)) return rutaModelo(destino)
        destino.deleteRecursively()
        destino.mkdirs()
        val con = URL(URL_MODELO).openConnection() as HttpURLConnection
        con.connectTimeout = 20000
        con.readTimeout = 60000
        try {
            val total = con.contentLengthLong.takeIf { it > 0 } ?: 40_000_000L
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
                                val p = ((leido * 100) / (total * 2)).toInt().coerceIn(0, 99) // descomprimido ~2x
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
        return rutaModelo(destino)
    }

    /** El zip trae una carpeta adentro (vosk-model-small-es-0.42): la buscamos. */
    private fun rutaModelo(destino: File): String {
        val interna = destino.listFiles()?.firstOrNull { it.isDirectory && File(it, "am").exists() }
        return (interna ?: destino).absolutePath
    }
}
