package dev.omalauncher.widgets.runtime

/**
 * The one widget that may open the listening screen for MG1 (Boss, 2026-10-30): by the file it is loaded from. The launcher
 * saves store widgets as market-*.owl and shared ones as shared-*.owl, so neither can arrive under this name, whatever the
 * widget calls itself. It is no wall against what can write in the widgets folder: an agent, or Linux.
 */
const val DICTATION_WIDGET_FILE = "dictation-mg1.owl"

internal const val DICTATION_REFUSED = "dictation is only for the widget in $DICTATION_WIDGET_FILE"
