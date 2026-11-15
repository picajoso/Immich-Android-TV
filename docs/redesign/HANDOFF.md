# Redesign — on-device fix-up guide

The `redesign-ui` branch implements the two interface styles described in `SPEC.md`, but it was written without any device or emulator, so it does not yet match the designs on screen. This guide is for an agent with **local shell access, a working Android build and `adb` access to the TV**, whose job is to make the running app match the reference screens.

## Inputs
- `docs/redesign/SPEC.md`: tokens, behaviour and phases. It is the source of truth for behaviour.
- `docs/redesign/reference/*.jpg`: the target screens at 1920×1080, 1:1 with a 1080p TV. `INDEX.md` describes each one.
  - `apple-01…07`: Apple style. Photos with the menu open, Photos with the menu hidden, viewer pill, viewer info panel, Albums, Timeline, month view.
  - `googletv-01…16`: Google TV style. The same 7 screens, then Random, People, Recent, Favorites, On this day, Seasonal, Folders, Edit and Settings.
  - **Apple has no reference for screens 8–16**: build them with the Google TV layout and the Apple tokens from SPEC.md. That means a black background, the Apple sidebar, square tiles with a 2dp gap, a white focus ring with scale 1.14, Inter for titles, and a #1C1C1E date pill.
- `docs/redesign/source/*.dc.html`: the HTML source of the mockups. Every size, colour, radius and spacing is in inline CSS, in px at 1080p. **On a 1080p TV, 1px in these files equals 0.5dp** (density 2.0); check `adb shell wm density` and adjust if the TV differs.

## Loop (repeat per screen)
1. Build and install the debug build. It installs next to the release app as `nl.giejay.android.tv.immich.debug`:
   `./gradlew assembleDebug && adb -s $TV install -r app/build/outputs/apk/debug/app-debug.apk`
2. Open the app and navigate with key events:
   `adb -s $TV shell input keyevent KEYCODE_DPAD_RIGHT` (also `_LEFT`, `_UP`, `_DOWN`, `_CENTER`, `KEYCODE_BACK`, `KEYCODE_INFO`).
   Launch: `adb -s $TV shell monkey -p nl.giejay.android.tv.immich.debug -c android.intent.category.LEANBACK_LAUNCHER 1`.
3. Capture: `adb -s $TV exec-out screencap -p > shots/<screen>.png`.
4. Compare against the matching reference image side by side. List every visible difference: layout, sizes, spacing, colours, fonts, corner radii, focus treatment, what is shown or hidden, and text. Fix them in code, then rebuild and recapture until the screen matches.
5. Check behaviour too, not only stills:
   - Menu: it hides on → into content and comes back on ←.
   - Date pill: it updates while moving focus.
   - Viewer pill: it fades out after 3 s and comes back on any key.
   - Info panel: ↓ opens it and Back closes it.
   - Timeline: the month buttons jump months.
6. Switch style in **Settings › View settings › Interface style**, then repeat for the other style.
7. Commit per screen or per small group, with clear messages, on `redesign-ui`. The viewer lives in the `mediaslider` submodule (repo picajoso/MediaSliderView, branch `redesign-ui`): commit and push there first, then commit the updated submodule pointer in the app.

## Known gaps to fix (from the implementation notes)
- **Google TV drawer:** it should overlay the content with a dark scrim, as in googletv-01. Today it pushes the content aside like the stock Leanback headers.
- **Column count:** set it automatically (about 7 columns with the menu, 9 without for Apple; 7 and 8 for Google TV) and later make it user-adjustable in View settings. Check that the switch on menu hide and show works.
- **Focused tile:** it may be clipped or drawn under its neighbours. Fix with `clipChildren`/`clipToPadding` and the z-order.
- **People:**
  - The cards should show head and shoulders (crop from the face bounding box with margin), not a tight face. See googletv-09.
  - Show the photo count per person, from the person statistics endpoint, loaded for visible cards only.
- **Settings:** should sit at the bottom of the menu in Apple style if feasible. Otherwise leave it last.
- **Folders:** Back should go up one folder level.
- **Fonts:** Inter and Outfit are downloadable fonts and need Google Play services. If they don't load on the TV, bundle the font files in `res/font`.

## Rules
- Do not touch the release package `nl.giejay.android.tv.immich`, signing, secrets or `google-services.json`.
- Keep login, video playback and the screensaver working.
- When a design decision isn't settled by SPEC.md or the references, ask Javier and do not guess.
