package dev.omalauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Where a request for the agent goes. */
internal enum class AgentRoute {
    /** Agents run in Linux: without it, the launcher offers to install it instead. */
    InstallLinux,

    /** No agent is installed yet. */
    ChooseAgent,

    /** Claude Code and Codex work in the background, with approvals. */
    Background,

    /** Any other agent opens in a terminal. */
    Terminal,
}

/** The route for the default agent [bin] (null when there is none), given whether Linux is installed. */
internal fun agentRoute(
    linux: Boolean,
    bin: String?,
) = when {
    !linux -> AgentRoute.InstallLinux
    bin == null -> AgentRoute.ChooseAgent
    bin == "claude" || bin == "codex" -> AgentRoute.Background
    else -> AgentRoute.Terminal
}

/** A microphone drawn on a 24-unit grid, like the home screen's other icons. */
@Composable
internal fun MicIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(2 * u, cap = StrokeCap.Round)
        drawRoundRect(
            color,
            topLeft = androidx.compose.ui.geometry.Offset(9f * u, 2f * u),
            size = androidx.compose.ui.geometry.Size(6f * u, 12f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * u),
            style = stroke,
        )
        drawArc(
            color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(5f * u, 7f * u),
            size = androidx.compose.ui.geometry.Size(14f * u, 11f * u),
            style = stroke,
        )
        drawPath(
            Path().apply {
                moveTo(12f * u, 18f * u)
                lineTo(12f * u, 22f * u)
                moveTo(8f * u, 22f * u)
                lineTo(16f * u, 22f * u)
            },
            color,
            style = stroke,
        )
    }
}
