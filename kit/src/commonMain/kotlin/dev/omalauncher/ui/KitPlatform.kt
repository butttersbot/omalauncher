package dev.omalauncher.ui

/** Whether the system runs animations at all; Android's "remove animations" turns them off. Read by [Motion.reduced]. */
expect fun systemAnimationsOn(): Boolean
