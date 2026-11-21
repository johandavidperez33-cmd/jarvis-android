package com.jarvis.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Revisa en GitHub si hay una versión nueva de la app y la instala con un toque. */
object Actualizador {
    private const val BASE = "https://github.com/johandavidperez33-cmd/jarvis-android/releases/download/apk/"

    private fun descargar(url: String, timeoutLectura: Int = 30000): HttpURLConnection {
        var direccion = url
        repeat(5) { // GitHub redirige a otro servidor para las descargas
            val con = URL(direccion).openConnection() as HttpURLConnection
            con.instanceFollowRedirects = false
            con.connectTimeout = 15000
            con.readTimeout = timeoutLectura
            val codigo = con.responseCode
            if (codigo in 300..399) {
                direccion = con.getHeaderField("Location") ?: throw IllegalStateException("redirección sin destino")
                con.disconnect()
            } else {
                if (codigo != 200) throw IllegalStateException("error $codigo")
                return con
            }
        }
        throw IllegalStateException("demasiadas redirecciones")
    }

    /** Llamar al abrir la app. avisarSiNoHay = true cuando el usuario lo pide a mano. */
    fun revisar(act: Activity, avisarSiNoHay: Boolean = false) {
        Thread {
            try {
                val texto = descargar(BASE + "version.json").inputStream.bufferedReader().use { it.readText() }
                val remota = JSONObject(texto)
                val version = remota.optInt("version", 0)
                if (version > BuildConfig.VERSION_CODE) {
                    act.runOnUiThread { preguntar(act, remota.optString("nombre", version.toString())) }
                } else if (avisarSiNoHay) {
                    act.runOnUiThread { Toast.makeText(act, "Ya tienes la última versión (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                if (avisarSiNoHay) act.runOnUiThread {
                    Toast.makeText(act, "No pude revisar actualizaciones: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun preguntar(act: Activity, nombre: String) {
        if (act.isFinishing) return
        AlertDialog.Builder(act)
            .setTitle("Actualización de Jarvis")
            .setMessage("Hay una versión nueva ($nombre). Tienes la ${BuildConfig.VERSION_NAME}.\n¿Actualizar ahora? No se borra nada.")
            .setPositiveButton("Actualizar") { _, _ -> instalar(act) }
            .setNegativeButton("Después", null)
            .show()
    }

    private fun instalar(act: Activity) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(act, "Activa \"Permitir de esta fuente\" para Jarvis y vuelve a intentar", Toast.LENGTH_LONG).show()
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
            return
        }
        Toast.makeText(act, "Descargando la versión nueva…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val archivo = File(act.cacheDir, "actualizacion/jarvis.apk")
                archivo.parentFile?.mkdirs()
                descargar(BASE + "jarvis.apk", 120000).inputStream.use { entrada ->
                    archivo.outputStream().use { entrada.copyTo(it) }
                }
                val uri = FileProvider.getUriForFile(act, "${act.packageName}.archivos", archivo)
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                act.runOnUiThread { act.startActivity(i) }
            } catch (e: Exception) {
                act.runOnUiThread { Toast.makeText(act, "No pude descargar la actualización: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
