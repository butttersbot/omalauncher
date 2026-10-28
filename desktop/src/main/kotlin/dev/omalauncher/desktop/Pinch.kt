package dev.omalauncher.desktop

import java.lang.reflect.Proxy
import javax.swing.JComponent

/**
 * A pinch on a Mac's trackpad (desktop slice 5c): Java hears it only through Apple's gesture listener, which the JDK keeps
 * in a package of its own (opened to Soma by `--add-exports java.desktop/com.apple.eawt.event`). Reached by reflection so
 * the Linux jar, which has none, builds and runs the same; there, and wherever it can't be reached, there is no pinch and
 * [ pages ] and Cmd-0 still are.
 */
object Pinch {
    /** How far the fingers go (summed, as the trackpad says it) before a pinch counts. */
    private const val ENOUGH = 0.25

    /** A pause this long (ms) ends one pinch: the next movement starts counting again. */
    private const val PAUSE = 400L

    /**
     * Listens for pinches over [component]: [onPinch] with true for fingers closing (the pages small), false for spreading
     * (the page again), once per pinch. Answers what stops listening.
     */
    fun attach(component: JComponent, onPinch: (inward: Boolean) -> Unit): () -> Unit = runCatching {
        val utilities = Class.forName("com.apple.eawt.event.GestureUtilities")
        val base = Class.forName("com.apple.eawt.event.GestureListener")
        val magnification = Class.forName("com.apple.eawt.event.MagnificationListener")
        var sum = 0.0
        var last = 0L
        var told = false
        val listener = Proxy.newProxyInstance(magnification.classLoader, arrayOf(magnification)) { self, method, args ->
            when (method.name) {
                "magnify" -> {
                    val event = args!![0]
                    val amount = event.javaClass.getMethod("getMagnification").invoke(event) as Double
                    val now = System.currentTimeMillis()
                    if (now - last > PAUSE) { sum = 0.0; told = false }
                    last = now
                    sum += amount
                    if (!told && kotlin.math.abs(sum) >= ENOUGH) {
                        told = true
                        onPinch(sum < 0)
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.getOrNull(0)
                "toString" -> "Soma's pinch"
                else -> null
            }
        }
        utilities.getMethod("addGestureListenerTo", JComponent::class.java, base).invoke(null, component, listener)
        val stop: () -> Unit = { runCatching { utilities.getMethod("removeGestureListenerFrom", JComponent::class.java, base).invoke(null, component, listener) } }
        stop
    }.getOrElse { {} }
}
