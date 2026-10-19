package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.omalauncher.ui.OwlAppWindow
import dev.omalauncher.ui.Pack
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.widgets.render.LocalInputFocus

/**
 * An owl app (`app "name" { }`) as its own task and Recents card, named after it and with its icon,
 * opened from the drawer or a home icon. It stays live while it shows, home or not; its close(),
 * or the app going away (removed, or no longer compiling), closes the task.
 */
class OwlAppActivity : InstantActivity() {
    private val runtime get() = (application as App).runtime
    private var name: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.data?.schemeSpecificPart
        if (name == null) {
            finishAndRemoveTask()
            return
        }
        this.name = name
        // From a Recents card after the launcher's process was ended, nothing has loaded the files yet: start it as
        // home would, and wait for the first load before deciding the app is gone.
        runtime.start()
        applySystemBars(this, Pack.isLight)
        setInstantContent {
            LaunchedEffect(Pack.isLight) { applySystemBars(this@OwlAppActivity, Pack.isLight) }
            LaunchedEffect(name) { runtime.widgets.appCloses.collect { if (it == name) closeInstantly() } }
            if (!runtime.widgets.loaded) {
                Box(Modifier.fillMaxSize().background(Pack.background))
                return@setInstantContent
            }
            // Removed since Recents was built, or it no longer compiles.
            val (spec, inst) =
                runtime.widgets.appInstance(name) ?: run {
                    LaunchedEffect(Unit) { closeInstantly() }
                    return@setInstantContent
                }
            val icon = runtime.owlIcon(spec, mono = false)
            LaunchedEffect(spec.name, icon, Pack.accent) { describeTask(spec.name, icon?.asAndroidBitmap()) }
            // While the app's `back` holds, Back goes up a level inside it; otherwise Back leaves it.
            BackHandler { if (!inst.goUp()) backInstantly() }
            CompositionLocalProvider(LocalInputFocus provides true) { OwlAppWindow(spec, inst) }
        }
    }

    override fun onStart() {
        super.onStart()
        name?.let(runtime.widgets::appStarted)
        runtime.screenStarted()
    }

    override fun onStop() {
        super.onStop()
        name?.let(runtime.widgets::appStopped)
        runtime.screenStopped()
    }

    companion object {
        /** The app in file [name]; the data names the document, so each app gets one task. */
        fun intent(
            context: Context,
            name: String,
        ): Intent = Intent(context, OwlAppActivity::class.java).setData(Uri.fromParts("owl", name, null))
    }
}
