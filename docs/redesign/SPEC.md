# Immich TV redesign — implementation brief

Owner: Javier (picajoso). Orchestrator: Claude (Opus). Implementer: you.
Repos (local clones, branch work happens here):
- App: /home/claude/immich-android-tv  (Kotlin, Leanback 1.2, minSdk 24, target 35). Default branch: main.
- Viewer submodule: /home/claude/mediasliderview  (branch `immich-tv`; the app pins it at `mediaslider/`).

Design references (read the HTML; every screen is an inline-styled 1920×1080 artboard, values are in px at 1080p — convert 1px ≈ 0.5dp on a 1080p TV, i.e. divide by 2 for dp/sp):
- Apple style: /home/claude/canvas/project/Rediseño Immich TV.dc.html  (CSS vars at top: dark + light; the app only needs DARK)
- Google TV style: /home/claude/canvas/project/Google TV.dc.html  (also contains sections ⑧–⑯ that Apple lacks)
- Images used by the mockups: /home/claude/canvas/project/img/

## Goal
Two complete UI styles, selectable at runtime: **Settings › View settings › Interface style: Apple / Google TV** (default Apple). Same navigation, same data; only look differs. Apple screens missing from the Apple mockup (Random, People, Recent, Favorites, On this day, Seasonal, Folders, Edit, Settings) are adapted from the Google TV mockup using Apple tokens.

## Tokens (dark only)
Apple: bg #000000 · sidebar #161618 · line #17FFFFFF · text #F5F5F7 · text2 #DBF5F5F7 · dim #A1A1A6 · accent #0A84FF · selected-row #1AFFFFFF · focus ring #FFFFFF · pill bg #1C1C1E · font Inter (downloadable font, fallback sans-serif) · titles bold, tight tracking · tile radius 0 (grid gap 2dp) · focused tile scale 1.14 + 2dp white ring + shadow.
Google TV: bg #17181B · surface1 #1F2024 · surface2 #2B2C31 · text #E8E9EC · dim #A9ADB5 · accent #8AB4F8 · font Outfit (downloadable, fallback sans-serif) · titles medium weight · tile radius 6dp (gap 8dp) · focus scale 1.08–1.10 + 2dp white border · pills fully rounded, selected = text-colored pill with bg-colored text.
Minimum text size 13sp (≈26px in mockups). Safe margins ≈ 40dp sides, 26dp top.

## Behaviour that is the same in both styles
1. **Side menu** visible while focus is in it; when focus moves into content (→) the menu hides completely and content uses full width (Apple: 7 → 9 columns; Google TV: drawer overlays the content with a dark scrim instead of pushing it). A thin vertical tab at the left edge hints that ← reopens it. Apple sidebar groups: "Library" (Photos, Albums, People, Favorites, Recent, Random) and "Collections" (On this day, Seasonal, Timeline, Folders, Edit), Settings pinned at the bottom.
2. **Photo grid** (Photos, Random, Recent, Favorites, month view): one continuous square grid, NO day separators. Header row: title left, **date pill** right showing "Wed, Nov 12, 2026 · <city>" of the FOCUSED photo, updated on selection change (text only — no layout work). City from asset exifInfo.city (omit "· city" if missing).
3. **Viewer** (mediaslider): photo only. On open (and on any key press) a small pill fades in bottom-center: white heart icon ONLY if favorite · place · date; it fades out after 3 s. **↓ (or Info key)** opens an info side panel on the right; the photo is re-laid-out to the left so nothing overlaps. Panel: date + time, place, camera, exposure, size; buttons "In favorites/Favorite" (toggle) and "Slideshow". ↑ or Back closes it.
4. **Albums**: hero = cover of the focused album filling the screen below the title (dark gradients left/bottom for legibility), album name + date + count over it, a row of album cards at the very bottom.
5. **Timeline**: left column of years with photo counts; right a 4×3 grid of the 12 months of the selected year, each with ONE cover (first asset of the month) + count; empty months dashed/dimmed. OK on a month → month view (continuous grid + date pill + "‹ Sep / Nov ›" buttons jumping months). Use Immich time buckets API for counts; never preload whole months.
6. **People**: cards 4:3, head-and-shoulders crop computed from the face bounding box with ~60% margin (fallback: person thumbnail), name + count, 6 per row, "Add a name" for unnamed.
7. **On this day**: one horizontal row per year ("1 year ago · 2025 · place · n"), first photo of latest year as faded hero background top-right.
8. **Folders**: only folders, 6 per row, each cover a 3-photo split mosaic (big left, two stacked right), breadcrumb, Timeline/Folders toggle.
9. **Edit**: check what the current Edit section does. If it is not "customize side menu", STOP and report.
10. **Settings**: two-pane: list left (Server, View settings, Screensaver, Debug, Donate), detail of focused item right. "Interface style" lives in View settings.

## Phases (commit per phase, clear messages, branch `redesign-ui` in BOTH repos)
- P0 Infrastructure: `UiStyle` pref + selector, a style provider (colors, dimens, fonts, presenters) consumed by fragments/presenters; activity recreate on change. Downloadable fonts setup.
- P1 Navigation + photo grid + date pill (Photos first, then reuse for Random/Recent/Favorites).
- P2 Viewer pill + info panel (mediaslider repo; then bump submodule pointer in app branch).
- P3 Albums + Timeline + month view.
- P4 People, On this day, Seasonal, Folders, Edit, Settings.

## Constraints
- This environment CANNOT build Android (dl.google.com / maven.google.com / gradle blocked). Write code carefully, keep changes compilable by reading APIs already used in the codebase; prefer Leanback/AndroidX APIs already in the dependency list. Do not add new dependencies unless unavoidable (report first).
- GitHub pushes from this session are currently refused (app not installed). Commit locally; try `git -c push.negotiate=true push -u origin redesign-ui`; if refused, keep going and report. Do not open PRs against anything but `main` (app) / `immich-tv` (slider).
- Don't touch release/signing files, secrets, or google-services.
- Keep existing features working (screensaver, video playback, login).

## Reporting / consulting
Work through P0–P2 in this run, then stop and report. Stop earlier if you hit a product/design decision you can't resolve from this brief or the mockups. Your final message must contain: what was done per phase (files), what is untested/risky, and any questions prefixed `QUESTION:`. Keep it concise.
