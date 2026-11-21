package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer

/**
 * Servicio de Accesibilidad: permite a Jarvis tocar botones, escribir, desplazarse,
 * ir a inicio/atrás, bloquear la pantalla y abrir apps aunque la pantalla esté apagada.
 * Se activa en Ajustes > Accesibilidad > Jarvis.
 */
class Accesibilidad : AccessibilityService() {

    companion object {
        @Volatile var instancia: Accesibilidad? = null
        fun activa() = instancia != null

        fun normalizar(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").trim()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instancia = this
    }

    override fun onDestroy() {
        instancia = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun global(accion: Int): Boolean = performGlobalAction(accion)

    fun inicio() = global(GLOBAL_ACTION_HOME)
    fun atras() = global(GLOBAL_ACTION_BACK)
    fun recientes() = global(GLOBAL_ACTION_RECENTS)
    fun notificaciones() = global(GLOBAL_ACTION_NOTIFICATIONS)
    fun bloquear() = if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_LOCK_SCREEN) else false
    fun captura() = if (Build.VERSION.SDK_INT >= 28) global(GLOBAL_ACTION_TAKE_SCREENSHOT) else false

    private fun todos(raiz: AccessibilityNodeInfo?, salida: MutableList<AccessibilityNodeInfo> = mutableListOf()):
        MutableList<AccessibilityNodeInfo> {
        if (raiz == null) return salida
        salida += raiz
        for (i in 0 until raiz.childCount) todos(raiz.getChild(i), salida)
        return salida
    }

    private fun clicable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var x = n
        while (x != null && !x.isClickable) x = x.parent
        return x
    }

    /** Toca el elemento de la pantalla cuyo texto o descripción se parezca a "texto". */
    fun tocar(texto: String): Boolean {
        val buscado = normalizar(texto)
        if (buscado.isEmpty()) return false
        val nodos = todos(rootInActiveWindow)
        fun etiqueta(n: AccessibilityNodeInfo) =
            normalizar("${n.text ?: ""} ${n.contentDescription ?: ""}")
        val exacto = nodos.firstOrNull { normalizar((it.text ?: it.contentDescription ?: "").toString()) == buscado }
        val parecido = exacto ?: nodos.firstOrNull { etiqueta(it).contains(buscado) }
        val objetivo = clicable(parecido) ?: return false
        return objetivo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Toca por identificador interno (ej. com.whatsapp:id/send). */
    fun tocarId(id: String): Boolean {
        val n = rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull() ?: return false
        return (clicable(n) ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Escribe en el campo de texto que esté seleccionado (o el primero que encuentre). */
    fun escribir(texto: String, id: String? = null): Boolean {
        val raiz = rootInActiveWindow ?: return false
        val campo = id?.let { raiz.findAccessibilityNodeInfosByViewId(it)?.firstOrNull() }
            ?: raiz.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: todos(raiz).firstOrNull { it.isEditable }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
        }
        return campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun desplazar(adelante: Boolean): Boolean {
        val n = todos(rootInActiveWindow).firstOrNull { it.isScrollable } ?: return false
        return n.performAction(
            if (adelante) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }
}
