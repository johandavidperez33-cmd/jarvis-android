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
        Actualizador.revisar(this)
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
                "try{const r=${JSONObject.quote(respuesta)};agregar(${JSONObject.quote(pregunta)},'yo');" +
                    "(window.agregarAnimado?agregarAnimado(r,r.length*58):agregar(r,'jarvis'))}catch(e){}",
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
            pedirSinLimiteDeBateria()
            JarvisService.iniciar(this)
        }
        botonOido.postDelayed({ actualizarBoton() }, 600)
    }

    private fun pedirPermisos() {
        val faltan = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE)
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
        caja.addView(TextView(this).apply {
            text = if (Infrarrojo.disponible(this@MainActivity)) "Infrarrojo del celular: ✔ disponible"
            else "Infrarrojo del celular: ✘ no encontrado"
        })
        // ---- control del celular por voz (Accesibilidad)
        caja.addView(TextView(this).apply {
            text = "\n📱 Control del celular: " +
                if (Accesibilidad.activa()) "✔ Accesibilidad activa" else "✘ falta activar la Accesibilidad"
        })
        if (!Accesibilidad.activa()) {
            caja.addView(TextView(this).apply {
                textSize = 12f
                text = "Toque el botón, busque \"Jarvis (control por voz)\" y actívelo. Si sale \"Ajuste restringido\", " +
                    "use el segundo botón: ⋮ (arriba a la derecha) > \"Permitir ajustes restringidos\" y vuelva a intentar."
            })
            caja.addView(Button(this).apply {
                text = "Activar Accesibilidad"
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
            caja.addView(Button(this).apply {
                text = "Permitir ajustes restringidos"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            })
        }

        // ---- reconocimiento de la voz del dueño
        caja.addView(TextView(this).apply {
            text = "\n🔒 Reconocimiento de voz: " +
                if (Config.huella(this@MainActivity) != null) "✔ su voz está registrada" else "✘ sin registrar (responde a todos)"
        })
        caja.addView(Button(this).apply {
            text = if (Config.huella(this@MainActivity) != null) "Registrar mi voz de nuevo" else "Registrar mi voz"
            setOnClickListener { registrarVoz() }
        })
        caja.addView(android.widget.CheckBox(this).apply {
            text = "Solo responder a mi voz"
            isChecked = Config.soloMiVoz(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Config.guardarSoloMiVoz(this@MainActivity, v) }
        })
        val etiqueta = TextView(this)
        fun textoExigencia(v: Int) = "Exigencia: $v% (pide un parecido de ${((0.45 + 0.35 * v / 100.0) * 100).toInt()}%)" +
            when { v < 30 -> " · más tolerante"; v > 65 -> " · más estricto"; else -> "" }
        etiqueta.text = textoExigencia(Config.exigencia(this))
        caja.addView(etiqueta)
        caja.addView(android.widget.SeekBar(this).apply {
            max = 100
            progress = Config.exigencia(this@MainActivity)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, u: Boolean) {
                    etiqueta.text = textoExigencia(v); Config.guardarExigencia(this@MainActivity, v)
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        })
        caja.addView(TextView(this).apply {
            text = "Si no le responde a usted, baje la exigencia. Si le responde a otros, súbala."
            textSize = 12f
        })

        caja.addView(TextView(this).apply { text = "\nVersión de la app: ${BuildConfig.VERSION_NAME}" })
        caja.addView(Button(this).apply {
            text = "Buscar actualización"
            setOnClickListener { Actualizador.revisar(this@MainActivity, avisarSiNoHay = true) }
        })
        AlertDialog.Builder(this)
            .setTitle("Ajustes de Jarvis")
            .setView(caja)
            .setPositiveButton("Guardar") { _, _ ->
                Config.guardar(this, url.text.toString(), clave.text.toString(), null)
                cargar()
                if (JarvisService.activo) { JarvisService.detener(this); botonOido.postDelayed({ JarvisService.iniciar(this) }, 800) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Registro de la voz del dueño: 3 frases, guiadas por el servicio del oído. */
    private fun registrarVoz() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pedirPermisos(); return
        }
        val mensaje = TextView(this).apply {
            setPadding(48, 32, 48, 16); textSize = 16f
            text = "Preparando… (la primera vez descarga el reconocimiento de voz, 13 MB)\n\n" +
                "Hable normal, a la distancia de siempre, sin ruido de fondo."
        }
        val dialogo = AlertDialog.Builder(this)
            .setTitle("🔒 Registrar mi voz")
            .setView(mensaje)
            .setNegativeButton("Cerrar") { _, _ -> JarvisService.alRegistro = null }
            .setCancelable(false)
            .show()
        JarvisService.alRegistro = { texto, terminado ->
            mensaje.text = texto
            if (terminado) {
                dialogo.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Listo"
                JarvisService.alRegistro = null
            }
        }
        JarvisService.registrarVoz(this)
        botonOido.postDelayed({ actualizarBoton() }, 800)
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

        /** Orden del celular que llega desde la página (cuando se escribe en el chat). */
        @JavascriptInterface
        fun accionCelular(json: String) {
            runOnUiThread {
                val problema = try { Celular.ejecutar(this@MainActivity, JSONObject(json)) } catch (e: Exception) { e.message }
                if (problema != null) Toast.makeText(this@MainActivity, problema, Toast.LENGTH_LONG).show()
            }
        }

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
                if (!JarvisService.activo) JarvisService.iniciar(this@MainActivity)
                botonOido.postDelayed({ JarvisService.escucharAhora(this@MainActivity); actualizarBoton() }, 600)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true) // no cerrar: el oído sigue
    }
}
