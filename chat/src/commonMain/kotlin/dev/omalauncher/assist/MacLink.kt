package dev.omalauncher.assist

/** What the last contact with the Mac came to, as far as the heal cares. */
enum class MacLink {
    /** It answered, even with a refusal: the network to it works. */
    Answered,

    /** No answer at all: the one case Tailscale being off can explain. */
    NoAnswer,

    /** No Mac is set up, so there is nothing to reach. */
    NotSetUp,
}

fun macLink(health: MacHealthResult): MacLink = when (health) {
    is MacHealthResult.Up, is MacHealthResult.Refused -> MacLink.Answered
    MacHealthResult.Unreachable -> MacLink.NoAnswer
    MacHealthResult.NotConfigured -> MacLink.NotSetUp
}
