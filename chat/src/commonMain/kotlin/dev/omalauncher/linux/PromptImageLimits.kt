package dev.omalauncher.linux

import java.io.File

/* How many images one request carries, and the copy one becomes, shared with the desktop app; importing them is in PromptImages.kt. */

const val MAX_PROMPT_IMAGES = 4

data class PromptImage(val file: File, val guestPath: String)
