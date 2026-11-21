package com.jarvis.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Ejecuta en el celular las órdenes que da Jarvis (por voz, sin tocar la pantalla). */
object Celular {
    private val principal = Handler(Looper.getMainLooper())

    private fun norm(s: String) = Accesibilidad.normalizar(s)

    /** Abre una pantalla. Con el servicio de Accesibilidad activo funciona aunque la pantalla esté apagada. */
    private fun abrir(c: Context, i: Intent): Boolean {
        val ctx: Context = Accesibilidad.instancia ?: c
        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Ejecuta una acción {"accion": ..., "texto": ..., "mensaje": ..., "cantidad": ...}. Devuelve un problema o null si salió bien. */
    fun ejecutar(c: Context, a: JSONObject): String? {
        val texto = a.optString("texto")
        val mensaje = a.optString("mensaje")
        val cantidad = a.optInt("cantidad", 0)
        val acc = Accesibilidad.instancia
        val sinAcc = "Active la Accesibilidad de Jarvis en ⚙ para eso"
        return when (a.optString("accion")) {
            "abrir_app" -> if (abrirApp(c, texto)) null else "No encontré la app \"$texto\""
            "linterna_encender" -> if (linterna(c, true)) null else "No pude encender la linterna"
            "linterna_apagar" -> if (linterna(c, false)) null else "No pude apagar la linterna"
            "volumen_subir" -> { volumen(c, AudioManager.ADJUST_RAISE, if (cantidad > 0) cantidad else 3); null }
            "volumen_bajar" -> { volumen(c, AudioManager.ADJUST_LOWER, if (cantidad > 0) cantidad else 3); null }
            "silenciar" -> { volumen(c, AudioManager.ADJUST_MUTE, 1); null }
            "pausa_reproducir" -> { tecla(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); null }
            "siguiente" -> { tecla(c, KeyEvent.KEYCODE_MEDIA_NEXT); null }
            "anterior" -> { tecla(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS); null }
            "llamar" -> llamar(c, texto)
            "whatsapp" -> whatsapp(c, texto, mensaje)
            "alarma" -> alarma(c, texto)
            "temporizador" -> if (abrir(c, Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, if (cantidad > 0) cantidad else 60)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))) null else "No pude poner el temporizador"
            "inicio" -> if (acc?.inicio() == true) null else sinAcc
            "atras" -> if (acc?.atras() == true) null else sinAcc
            "recientes" -> if (acc?.recientes() == true) null else sinAcc
            "notificaciones" -> if (acc?.notificaciones() == true) null else sinAcc
            "bloquear_pantalla" -> if (acc?.bloquear() == true) null else sinAcc
            "captura_pantalla" -> if (acc?.captura() == true) null else sinAcc
            "tocar" -> if (acc == null) sinAcc else if (acc.tocar(texto)) null else "No vi \"$texto\" en la pantalla"
            "escribir" -> if (acc == null) sinAcc else if (acc.escribir(texto)) null else "No encontré dónde escribir"
            "desplazar_arriba" -> if (acc == null) sinAcc else if (acc.desplazar(false)) null else "No hay nada que desplazar"
            "desplazar_abajo" -> if (acc == null) sinAcc else if (acc.desplazar(true)) null else "No hay nada que desplazar"
            "abrir_web" -> {
                val u = if (texto.startsWith("http")) texto else "https://$texto"
                if (abrir(c, Intent(Intent.ACTION_VIEW, Uri.parse(u)))) null else "No pude abrir la página"
            }
            "buscar_youtube" -> if (abrir(c, Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(texto))))) null else "No pude abrir YouTube"
            "navegar" -> if (abrir(c, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(texto))))
                    || abrir(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(texto))))) null else "No pude abrir el mapa"
            "ajustes_wifi" -> if (abrir(c, Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))) null else "No pude abrir el WiFi"
            "ajustes_bluetooth" -> if (abrir(c, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) null else "No pude abrir el Bluetooth"
            "ajustes" -> if (abrir(c, Intent(Settings.ACTION_SETTINGS))) null else "No pude abrir los ajustes"
            else -> "Orden desconocida"
        }
    }

    // ---------------------------------------------------------------- apps
    fun abrirApp(c: Context, nombre: String): Boolean {
        val pm = c.packageManager
        val buscado = norm(nombre).removePrefix("la app de ").removePrefix("la aplicacion de ").removePrefix("el ").removePrefix("la ").trim()
        if (buscado.isEmpty()) return false
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val conNombre = apps.map { Pair(norm(it.loadLabel(pm).toString()), it.activityInfo.packageName) }
        val elegido = conNombre.firstOrNull { it.first == buscado }
            ?: conNombre.firstOrNull { it.first.startsWith(buscado) }
            ?: conNombre.firstOrNull { it.first.contains(buscado) }
            ?: conNombre.firstOrNull { buscado.contains(it.first) && it.first.length >= 4 }
            ?: return false
        val i = pm.getLaunchIntentForPackage(elegido.second) ?: return false
        return abrir(c, i)
    }

    fun existeApp(c: Context, nombre: String): Boolean {
        val pm = c.packageManager
        val buscado = norm(nombre)
        if (buscado.length < 2) return false
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .any { val n = norm(it.loadLabel(pm).toString()); n == buscado || n.startsWith(buscado) }
    }

    // ---------------------------------------------------------------- hardware
    fun linterna(c: Context, encender: Boolean): Boolean {
        return try {
            val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(id, encender)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun volumen(c: Context, direccion: Int, veces: Int) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        repeat(veces.coerceIn(1, 15)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direccion, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun tecla(c: Context, codigo: Int) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, codigo, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, codigo, 0))
    }

    // ---------------------------------------------------------------- contactos, llamadas, WhatsApp
    private data class Contacto(val id: Long, val nombre: String, val numero: String)

    private fun buscarContacto(c: Context, nombre: String): Contacto? {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val buscado = norm(nombre).removePrefix("a ").trim()
        val lista = mutableListOf<Contacto>()
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) lista += Contacto(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: "")
        }
        return lista.firstOrNull { norm(it.nombre) == buscado }
            ?: lista.firstOrNull { norm(it.nombre).startsWith(buscado) }
            ?: lista.firstOrNull { norm(it.nombre).split(" ").contains(buscado) }
            ?: lista.firstOrNull { norm(it.nombre).contains(buscado) }
    }

    private fun llamar(c: Context, quien: String): String? {
        val digitos = quien.filter { it.isDigit() || it == '+' }
        val numero = if (digitos.length >= 6) digitos else buscarContacto(c, quien)?.numero
            ?: return if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                "Necesito permiso para ver sus contactos (abra la app)" else "No encontré a \"$quien\" en sus contactos"
        val puedeLlamar = c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (puedeLlamar) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(numero)))
        return if (abrir(c, i)) null else "No pude llamar"
    }

    private fun whatsapp(c: Context, quien: String, mensaje: String): String? {
        val contacto = buscarContacto(c, quien)
            ?: return "No encontré a \"$quien\" en sus contactos"
        // 1) abrir el chat de WhatsApp de ese contacto
        var abierto = false
        c.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contacto.id.toString(), "vnd.android.cursor.item/vnd.com.whatsapp.profile"), null
        )?.use { cur ->
            if (cur.moveToFirst()) {
                val uri = Uri.parse("content://com.android.contacts/data/${cur.getLong(0)}")
                abierto = abrir(c, Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
            }
        }
        if (!abierto) {
            val num = contacto.numero.filter { it.isDigit() }
            abierto = abrir(c, Intent(Intent.ACTION_VIEW,
                Uri.parse("https://api.whatsapp.com/send?phone=$num&text=" + Uri.encode(mensaje))))
        }
        if (!abierto) return "No pude abrir WhatsApp"
        if (mensaje.isBlank()) return null
        // 2) escribir y enviar con la Accesibilidad
        val acc = Accesibilidad.instancia
        if (acc == null) {
            (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("mensaje", mensaje))
            return "Abrí el chat; copié el mensaje (active la Accesibilidad para que lo envíe solo)"
        }
        principal.postDelayed({
            acc.escribir(mensaje, "com.whatsapp:id/entry")
            principal.postDelayed({ acc.tocarId("com.whatsapp:id/send") || acc.tocar("Enviar") }, 600)
        }, 2500)
        return null
    }

    private fun alarma(c: Context, hora: String): String? {
        val m = Regex("(\\d{1,2})(?::|\\.|\\s)?(\\d{2})?").find(hora) ?: return "No entendí la hora"
        val h = m.groupValues[1].toInt().coerceIn(0, 23)
        val min = m.groupValues[2].ifEmpty { "0" }.toInt().coerceIn(0, 59)
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, min)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (abrir(c, i)) null else "No pude poner la alarma"
    }

    // ---------------------------------------------------------------- órdenes rápidas (sin internet)
    /**
     * Reconoce órdenes simples del celular y las convierte en acción, sin consultar a la PC.
     * Devuelve (acción, frase de respuesta) o null si hay que preguntarle a Jarvis en la PC.
     */
    fun ordenRapida(c: Context, textoOriginal: String): Pair<JSONObject, String>? {
        val t = norm(textoOriginal).replace(Regex("^(oye\\s+)?jarvis[,\\s]*"), "").replace(Regex("[¿?¡!.,]"), "").trim()
        fun acc(a: String, txt: String = "") = JSONObject().put("tipo", "celular").put("accion", a).put("texto", txt)
        val r: Pair<JSONObject, String>? = when {
            Regex("(enciende|prende|activa|pon)\\s+(la\\s+)?(linterna|lampara|luz del (celular|telefono))").containsMatchIn(t) ->
                Pair(acc("linterna_encender"), "Linterna encendida, señor.")
            Regex("(apaga|desactiva|quita)\\s+(la\\s+)?(linterna|lampara|luz del (celular|telefono))").containsMatchIn(t) ->
                Pair(acc("linterna_apagar"), "Linterna apagada, señor.")
            Regex("(sube|subele|subir|aumenta|mas)\\s+(el\\s+)?(volumen|sonido)").containsMatchIn(t) ->
                Pair(acc("volumen_subir"), "Listo, señor.")
            Regex("(baja|bajale|bajar|disminuye|menos)\\s+(el\\s+)?(volumen|sonido)").containsMatchIn(t) ->
                Pair(acc("volumen_bajar"), "Listo, señor.")
            Regex("^(silencia|silencio|mute)").containsMatchIn(t) -> Pair(acc("silenciar"), "Hecho, señor.")
            Regex("^(pausa|pausar|pon pausa|deten|para) (la )?(musica|cancion|video)?$|^pausa$").containsMatchIn(t) ->
                Pair(acc("pausa_reproducir"), "Hecho, señor.")
            Regex("^(siguiente|otra cancion|cambia (la )?cancion|pasa (la )?cancion)").containsMatchIn(t) ->
                Pair(acc("siguiente"), "Hecho, señor.")
            Regex("^(anterior|cancion anterior|regresa (la )?cancion)").containsMatchIn(t) ->
                Pair(acc("anterior"), "Hecho, señor.")
            Regex("^(ve al |vete al |pantalla de )?inicio$").containsMatchIn(t) -> Pair(acc("inicio"), "Enseguida, señor.")
            Regex("^(atras|regresa|vuelve|ve atras)$").containsMatchIn(t) -> Pair(acc("atras"), "Enseguida, señor.")
            Regex("bloquea (el celular|el telefono|la pantalla)").containsMatchIn(t) -> Pair(acc("bloquear_pantalla"), "Bloqueando, señor.")
            Regex("(toma|haz|saca) (una )?captura").containsMatchIn(t) -> Pair(acc("captura_pantalla"), "Captura tomada, señor.")
            Regex("^(abre|abrir|lanza|inicia|ejecuta) (las )?notificaciones").containsMatchIn(t) -> Pair(acc("notificaciones"), "Enseguida, señor.")
            else -> {
                val m = Regex("^(abre|abrir|abreme|lanza|inicia|ejecuta|pon)\\s+(la app de |la aplicacion de |la app |el |la )?(.+)$").find(t)
                val app = m?.groupValues?.get(3)?.trim()
                if (app != null && existeApp(c, app)) Pair(acc("abrir_app", app), "Abriendo, señor.") else null
            }
        }
        return r
    }

    // ---------------------------------------------------------------- frases pregrabadas (respuesta al instante)
    val FRASES = listOf(
        "Linterna encendida, señor.", "Linterna apagada, señor.", "Listo, señor.", "Hecho, señor.",
        "Enseguida, señor.", "Bloqueando, señor.", "Captura tomada, señor.", "Abriendo, señor."
    )

    fun archivoFrase(c: Context, frase: String) = File(File(c.filesDir, "frases"), "${frase.hashCode()}.mp3")

    /** Descarga una vez las frases desde Jarvis (misma voz) para poder responder sin esperar. */
    fun prepararFrases(c: Context) {
        if (Config.url(c).isBlank()) return
        Thread {
            for (f in FRASES) {
                val arch = archivoFrase(c, f)
                if (arch.exists() && arch.length() > 1000) continue
                try {
                    arch.parentFile?.mkdirs()
                    val con = URL(Api.urlVoz(c, f)).openConnection() as HttpURLConnection
                    con.connectTimeout = 15000; con.readTimeout = 30000
                    if (con.responseCode == 200) con.inputStream.use { e -> arch.outputStream().use { e.copyTo(it) } }
                    con.disconnect()
                } catch (_: Exception) {
                }
            }
        }.start()
    }
}
