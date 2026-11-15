# CLAUDE.md — Immich TV

Android TV client for Immich (Kotlin, Leanback 1.2, minSdk 24, target 35). Personal fork of
giejay's Immich-Android-TV, now independent (see `NOTICE.md`, `README.md`). GPL-3.0; the viewer
module `mediaslider/` is Apache-2.0 (descends from Zeuskartik/MediaSliderView via giejay).

## Identity and build
- `applicationId` **javipas.android.tv.immich** (debug adds `.debug`). The Kotlin **namespace stays
  `nl.giejay.android.tv.immich`** (do not rename packages in code; components are therefore written
  `javipas.android.tv.immich/nl.giejay.android.tv.immich.X`, e.g. the screensaver in the README and
  `res/xml/screensaver.xml`, with a `src/debug` override for the `.debug` id).
- **No Firebase / Crashlytics / google-services.json** anymore. Release logs go to logcat only
  (`ImmichApplication.ReleaseTree`). `okio` is declared explicitly.
- Secrets are git-ignored: `app/src/main/res/values/strings_other.xml` (host, api key, auth url;
  template in `app/strings_other.xml.example`) and `local.properties` (SDK + release signing).
  CI (`.github/workflows`) writes `strings_other.xml` from a secret; `compile-check.yml` uses a
  placeholder. Never commit keys.
- Build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/ImmichTV-<version>.apk`.
- The viewer is a git submodule: `mediaslider/` = github.com/picajoso/MediaSliderView, branch
  `immich-tv`. Change the viewer there first (commit + push), then commit the new pointer here.
  The branch `old-favorites-work` in that repo keeps an older unmerged favourites-sync attempt.

## Working on the real TV (what worked)
- adb over Wi-Fi (`adb pair ip:port code`, then `adb connect ip:port`; restart the adb server if
  pairing answers "protocol fault"). Screens: `adb exec-out screencap -p`. Keys: `adb shell input
  keyevent KEYCODE_DPAD_*`; text: `adb shell input text`.
- Density 320 on a 1080p TV → **1 mockup px = 0.5 dp**.
- Switch style without the UI: edit `ui_style` (`APPLE` | `GOOGLE_TV`) in the debug app's shared
  prefs with `run-as javipas.android.tv.immich.debug`, then restart the app. Locale:
  `adb shell cmd locale set-app-locales <pkg> --locales en-US` (empty string to reset).
- uiautomator dumps give real view bounds and the focused view (bounds in `uiautomator dump`
  ignore translations).
- The Immich test server runs 3.x; see "Server compatibility". Do not press the delete key
  (183) in the viewer while testing: it really trashes the photo.

## Design source
`docs/redesign/`: `SPEC.md` (tokens/behaviour), `HANDOFF.md` (on-device loop), `reference/*.jpg`
(target screens, 1920x1080), `source/*.dc.html` (exact CSS values, px at 1080p). Apple references
exist for screens 1–7 only; Apple screens 8–16 = Google TV layout + Apple tokens.

## Two interface styles
`UiStyle` pref (Settings › View settings › Interface style, default Apple). Everything visual reads
`UiStyleProvider.current()` (`ui/UiStyleProvider.kt`): `UiTokens` (colors, radii, gaps, focus scale,
title sizes…), fonts (`UiFonts`: Inter / Outfit as downloadable fonts, sans-serif fallback),
`viewerStyle()` for the viewer. Changing the style recreates `MainActivity`.
- **Menu** (`home/HomeFragment`, `ImmichRowPresenter`, `SectionHeaderPresenter`): Leanback
  `BrowseSupportFragment` headers restyled in `styleHeaders()`. Apple: labelled groups (Library /
  Collections), accent icons, Photos counter, translucent selected row. Google TV: flat list,
  profile row (name from `/users/me`), white pill that hugs its content, Photos counter too.
  Settings is pinned at the bottom with an empty `SpacerRow`. In **both** styles the menu **pushes**
  the content aside (Leanback margin) and hides when focus enters the content; an edge tab hints
  that ← reopens it. Entries live in `HomeFragment.APPLE_GROUPS/GOOGLE_GROUPS` and `HEADERS`;
  Seasonal, Folders and Recent were **removed from the menu** (code kept: `SeasonalFragment`,
  `FolderFragment`, `RecentAssetsFragment`).
- **Photo grids** (`shared/fragment/VerticalCardGridFragment`, `GenericAssetFragment`,
  `card/PhotoGridLayout`, `PhotoTilePresenter`): used by Photos, Random, Favorites, month view and
  album details when `gridTitle != null`. Header = (breadcrumb +) title, optional action
  (`setHeaderAction`, e.g. Shuffle / Slideshow + count) and the date·place pill of the focused photo.
  Column count follows the width actually visible (menu shown/hidden); grids opened outside the
  browse (month view, album details) are "standalone": own header geometry, no menu. The header
  action only becomes focusable after the grid had focus once (otherwise it steals the initial
  focus); UP on the first row reaches it.
- **Albums** (`album/AlbumsHeroFragment`): Apple → `AppleAlbumsView` (latest album as fixed hero,
  "My Albums" / "Shared Albums" rows); Google TV → hero follows the focused album + one card row.
  **Album details** = standard photo grid with breadcrumb "Albums".
- **Timeline** (`assets/TimelineOverviewFragment`): years column + 4×3 months with one cover each;
  RIGHT/LEFT between the columns is handled by key listeners (single-column Leanback grids do not
  let focus out sideways). Month view = `TimelineFragment` (breadcrumb, prev/next month buttons).
- **People**, **On this day** (`onthisday`, `assets/PhotoRowsFragment`: one row of tiles per year),
  **Edit** menu, **Settings** (two-pane, `settings/SettingsFragment`; old Leanback preference
  screens remain underneath for the actual options).
- **Viewer** (`mediaslider/…/ViewerOverlay.kt`, `ViewerStyle.kt`, `MediaSliderView.kt`): info pill
  (bottom centre, fades after 3 s), info panel on ↓/Info (photo re-laid out to the left, rounded in
  Google TV), buttons Favourite / Start slideshow (focused button is the filled one).
  `MediaSliderConfiguration.autoStartSlideshow` opens the viewer already playing.
- Text ignores the system font scale (`MainActivity.attachBaseContext` forces 1.0) so layouts match
  the 1080p designs.

## Data and caching
- `shared/cache/SwrCache`: stale-while-revalidate on disk (`cacheDir/swr`, per server, dates as
  epoch). **Albums**: cached list shown at once; `/albums` is called with `If-None-Match` (the
  server answers 304, ~10 MB saved with 7k albums); the shared call follows in the background.
  **People** (+ stored photo counts), **Timeline** (buckets, covers re-asked only when a month's
  count changed), **Favorites** first page, **On this day** (per day and content filter; never
  cache a result that lost years to failed requests: `ApiClient.onThisDayIncomplete`).
- Photos/Random are not cached (the server answers in ~50 ms; the wait is image loading, handled by
  Glide).
- **Server compatibility**: Immich 3.x answers 400 to datetimes without an offset; search dates are
  sent as UTC with `Z` (`ApiClient.dateTimeFormatter`). `/timeline/bucket` is columnar; its
  `fileCreatedAt` (UTC) feeds the date pill of month views.
- Number formatting always groups thousands (`ui/UiFormat`).

## Languages
Follows the Android TV language; English (default) and Spanish. UI text is written in English in
the code and looked up with `Tr.t("…")` (`shared/util/Tr.kt` → string `t_<normalised text>`);
counts with `Tr.photos(n)` / `Tr.people(n)` (`%d` patterns). Resources: `res/values*/strings_tr.xml`
(generated), `res/values*/arrays.xml` (option lists), `mediaslider/…/strings_viewer.xml`. The
generator and the ES dictionary are in `docs/redesign/i18n-gen.py` / `i18n-es.py`
(see `docs/redesign/I18N.md`): add the Spanish text for every new `Tr.t` string, then run it.
Dates and month names come from the locale (`DateFormat.getBestDateTimePattern`).

## Other behaviour worth knowing
- **Delete photo**: the remote's red button (`KEYCODE_PROG_RED`, 183) → `MainActivity` →
  `KeyEventsViewModel.postDeleteEvent` → viewer moves the asset to the Immich trash. The button is
  mapped with Key Mapper, whose constraint "App in foreground" must point to the installed id
  (`javipas.android.tv.immich[.debug]`). Turn off "Repeat until released" there.
- Leanback gotchas met: `scale_frame` content is replaced on every page change (do not add views
  to it); `BrowseSupportFragment` shows its own title (kept empty); programmatic grids need explicit
  focus handling at their edges; a `HorizontalGridView` inside a vertical list needs a fixed height
  and `setNumRows(1)`.

## Repository hygiene
Compiled APKs are not committed (`*.apk` ignored; publish as GitHub Releases). On GitHub both
repos (app and viewer) were forks of giejay's; detaching the fork relationship needs a GitHub
Support request ("Detach fork").
