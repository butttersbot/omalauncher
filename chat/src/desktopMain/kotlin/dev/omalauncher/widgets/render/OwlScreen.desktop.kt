package dev.omalauncher.widgets.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.omalauncher.widgets.lang.OwlHost

internal actual val platformHost: OwlHost = OwlHost.Desktop

/** No AGSL here: a Shader node draws its fallback color, as on a phone before Android 13. */
internal actual val shaderNode: (@Composable (Props, Modifier) -> Unit)? = null

/** No web view here. */
internal actual val webNode: (@Composable (Props, Modifier) -> Unit)? = null
