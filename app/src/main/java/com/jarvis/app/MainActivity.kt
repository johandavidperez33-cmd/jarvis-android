package com.jarvis.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Pantalla principal: muestra la página de Jarvis y el botón del "oído" con pantalla apagada. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var botonOido: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leerEnlace(intent)
        construir()
        pedirPermisos()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (leerEnlace(intent)) {
            Toast.makeText(this, "Jarvis configurado ✔", Toast.LENGTH_SHORT).show()
            cargar()
        }
    }

    /** jarvis://config?url=...&clave=...&pico=... */
    private fun leerEnlace(i: Intent?): Boolean {
        val d = i?.data ?: return false
        if (d.scheme != "jarvis") return false
        Config.guardar(this, d.getQueryParameter("url"), d.getQueryParameter("clave"), d.getQueryParameter("pico"))
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun construir() {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#02070d"))
        }
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
        }
        botonOido = Button(this).apply { setOnClickListener { alternarOido() } }
        val ajustes = Button(this).apply { text = "⚙"; setOnClickListener { mostrarAjustes() } }
        barra.addView(botonOido, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        barra.addView(ajustes)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#02070d"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Solo damos el micrófono a la página de nuestro propio Jarvis
                val propio = Uri.parse(Config.url(this@MainActivity)).host
                runOnUiThread {
                    if (propio != null && request.origin.host == propio) request.grant(request.resources)
                    else request.deny()
                }
            }
        }
        web.addJavascriptInterface(Puente(), "AndroidJarvis")

        raiz.addView(barra)
        raiz.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(raiz)

        actualizarBoton()
        if (Config.url(this).isBlank()) mostrarAjustes() else cargar()
    }

    private fun cargar() {
        val u = Config.url(this)
        if (u.isBlank()) return
        web.loadUrl(u + "/#clave=" + Uri.encode(Config.clave(this)))
    }

    override fun onResume() {
        super.onResume()
        JarvisService.alIntercambio = { pregunta, respuesta ->
            web.evaluateJavascript(
                "try{agregar(${JSONObject.quote(pregunta)},'yo');agregar(${JSONObject.quote(respuesta)},'jarvis')}catch(e){}",
                null
            )
        }
        JarvisService.alCambiarEstado = { actualizarBoton() }
        actualizarBoton()
    }

    override fun onPause() {
        JarvisService.alIntercambio = null
        JarvisService.alCambiarEstado = null
        super.onPause()
    }

    private fun actualizarBoton() {
        botonOido.text = if (JarvisService.activo) "🟢 Oído activo · toca para apagar"
        else "⚪ Activar oído (pantalla apagada)"
    }

    private fun alternarOido() {
        if (JarvisService.activo) {
            JarvisService.detener(this)
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pedirPermisos(); return
            }
            if (Config.pico(this).isBlank()) {
                Toast.makeText(this, "Primero pega tu clave de Picovoice en ⚙", Toast.LENGTH_LONG).show()
                mostrarAjustes(); return
            }
            pedirSinLimiteDeBateria()
            JarvisService.iniciar(this)
        }
        botonOido.postDelayed({ actualizarBoton() }, 600)
    }

    private fun pedirPermisos() {
        val faltan = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) faltan += Manifest.permission.POST_NOTIFICATIONS
        val pedir = faltan.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (pedir.isNotEmpty()) requestPermissions(pedir.toTypedArray(), 1)
    }

    @SuppressLint("BatteryLife")
    private fun pedirSinLimiteDeBateria() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun mostrarAjustes() {
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        fun campo(titulo: String, valor: String, oculto: Boolean = false): EditText {
            caja.addView(TextView(this).apply { text = titulo })
            val e = EditText(this).apply {
                setText(valor)
                setSingleLine()
                inputType = if (oculto) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            caja.addView(e)
            return e
        }
        val url = campo("Dirección de Jarvis (https://…)", Config.url(this))
        val clave = campo("Clave de acceso", Config.clave(this), true)
        val pico = campo("Clave de Picovoice (AccessKey)", Config.pico(this), true)
        caja.addView(TextView(this).apply {
            text = if (Infrarrojo.disponible(this@MainActivity)) "Infrarrojo del celular: ✔ disponible"
            else "Infrarrojo del celular: ✘ no encontrado"
        })
        AlertDialog.Builder(this)
            .setTitle("Ajustes de Jarvis")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                Config.guardar(this, url.text.toString(), clave.text.toString(), pico.text.toString())
                cargar()
                if (JarvisService.activo) { JarvisService.detener(this); botonOido.postDelayed({ JarvisService.iniciar(this) }, 800) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Funciones que la página de Jarvis puede usar dentro de la app. */
    inner class Puente {
        @JavascriptInterface
        fun transmitirIR(frecuencia: Int, patronJson: String): Boolean {
            return try {
                val arr = JSONArray(patronJson)
                Infrarrojo.transmitir(this@MainActivity, frecuencia, IntArray(arr.length()) { arr.getInt(it) })
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun tieneIR(): Boolean = Infrarrojo.disponible(this@MainActivity)

        /** La página avisa la clave correcta cuando el usuario entra bien: la app la recuerda. */
        @JavascriptInterface
        fun guardarClave(clave: String) {
            Config.guardar(this@MainActivity, null, clave, null)
        }

        @JavascriptInterface
        fun oidoActivo(): Boolean = JarvisService.activo

        @JavascriptInterface
        fun escuchar() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pedirPermisos(); return@runOnUiThread
                }
                if (!JarvisService.activo) {
                    if (Config.pico(this@MainActivity).isBlank()) {
                        Toast.makeText(this@MainActivity, "Pega tu clave de Picovoice en ⚙", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    JarvisService.iniciar(this@MainActivity)
                }
                botonOido.postDelayed({ JarvisService.escucharAhora(this@MainActivity); actualizarBoton() }, 400)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true) // no cerrar: el oído sigue
    }
}
