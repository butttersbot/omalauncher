# Pet home and Steps widgets

Home widgets in the herd pet's HeartGold look (Boss, 2026-10-21; mock-up: design/soma-pets-mockup.html).

- `pet-home.owl`: the pet's room. Tap to let the pet out (it floats over the screen, the herd sheep as before) or
  call it home (off the screen). `launcher("pet-out")` / `launcher("pet-home")` switch `HerdSheep.shown`.
- `steps.owl`: today's steps from the phone's step counter, the pet walking the road toward the goal (10,000).
  The first tap asks for the activity recognition permission (`launcher("steps-allow")`); later taps have the pet
  say how today compares with the week.

The launcher writes what they draw into the guest: `~/.cache/soma-pet/pet.json` (herd/PetWidget.kt: out or home,
the worn skin's frames as sprite rows) and `~/.cache/soma-steps/steps.json` (steps/Steps.kt).

The HGSS font (`hgss.ttf`, from herd/pet/ui/fonts) is embedded in each widget as a base64 asset. To install:

    tools/pet-widgets/install.sh    # writes both into ~/.config/omalauncher/widgets with the font embedded
