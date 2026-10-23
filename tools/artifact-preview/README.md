# Isolated artifact workspace fixture

Opt in with `-PartifactPreview`; see `docs/CHAT-ARTIFACT-WORKSPACE.md`.

This separate Android app runs the production `OmaChatScreen` and production artifact WebView with a fictional conversation and an in-memory output reader. It loads the existing Manga pack. The host cannot connect to a runner or send real prompts. It is a test harness, not a second implementation of Soma Chat.

`PreviewInstrumentation` checks HTML/JavaScript, closing back to the preserved draft, and the existing composer. It saves a screenshot in the fixture's external files folder. Normal launcher builds never include this module or its instrumentation.
