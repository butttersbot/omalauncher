package dev.omalauncher.artifactpreview

import dev.omalauncher.updates.AppOffer
import dev.omalauncher.updates.AutoUpdated
import dev.omalauncher.updates.DesignLine
import dev.omalauncher.updates.LauncherState
import dev.omalauncher.updates.PhoneUpdates

/**
 * Fictional update offers for the walk (cards soma-chat/Updates). Nothing here installs anything: a tap on [ update … ]
 * only moves the fixture to the next state and logs it (SomaWalk), the way the phone would after its checks.
 */
fun updatesFixture(scenario: String): PhoneUpdates {
    val u = PhoneUpdates()
    val soma = AppOffer("dev.omalauncher.boss", "Soma", "175", "176", 176, "Oct 29, 18:10",
        listOf("Connections matches its approved design.", "The Agents page puts questions for you first.", "soma --mesh can see the Fold."),
        35, 35, DesignLine(9, 3, 0, true), launcher = true, phoneSteps = List(6) { "fixture phone step ${it + 1}" })
    val messages = AppOffer("dev.omalauncher.messages", "Soma Messages", "0.8.5", "0.8.6", 86, "Oct 29, 18:02",
        listOf("Replies thread under the message they answer."), 9, 9, DesignLine(3, 0, 0, true), failure = "Android asked for a confirmation")
    u.offers = if (scenario == "updated") emptyList() else listOf(soma, messages)
    u.history = listOf(AutoUpdated("Hunch", "0.7.2", "0.7.3", "17:42"), AutoUpdated("Soma Notes", "0.1.10", "0.1.11", "16:58"))
    u.launcher = when (scenario) {
        "waiting" -> LauncherState.Waiting(listOf("Phone B (“Connections walk”)"))
        "updating" -> LauncherState.Updating(2)
        "updated" -> LauncherState.Updated("176", 6)
        "failed" -> LauncherState.Failed("175", "Android refused the package (its signature doesn't match). Nothing was changed.", "{\"fixture_only\": true}")
        else -> LauncherState.Idle
    }
    u.update = { offer ->
        android.util.Log.i("SomaWalk", "fixture update tap ${offer.pkg}")
        if (offer.launcher) u.launcher = LauncherState.Waiting(listOf("Phone B (“Connections walk”)"))
        else u.offers = u.offers.filterNot { it.pkg == offer.pkg }
    }
    u.cancel = { android.util.Log.i("SomaWalk", "fixture update cancel"); u.launcher = LauncherState.Idle }
    u.seen = { u.launcher = LauncherState.Idle }
    return u
}
