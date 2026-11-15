# Immich Android TV

Immich is a self hosted backup solution for photos and videos. Current features include:

- Upload and view videos and photos
- Auto backup when the app is opened
- Selective album(s) for backup
- Multi-user support
- Album and Shared albums

More info here: https://github.com/immich-app/immich

This Android TV app will allow you to view those uploaded photos and videos. Current features
include:

### New in v3.23 

- **"Move to Trash" Support**: Added ability to move photos directly to the Immich trash using the remote's RED button (KEYCODE_PROG_RED, keycode 183).
Note: This requires pre-mapping the button using an Android tool like KeyMapper. The media slider automatically transitions to the next image once we move one photo/video to the trashcan and refreshes metadata immediately after a deletion.

### New in v3.22 

- **Elegant Timeline & Smart Navigation**: This release brings a major visual overhaul to the Timeline (text-only mode) and significantly improves navigation fluidity, making the browsing experience smoother and more "cinematic" on your TV.

### New in v3.21
- **Show Only VideosFavorites**: Your personal TikTok, "doomscrolling" place to just browse and view just videos in all the sections of Immich-Android-TV. Marvellous :)

### New in v3.20
- **Favorites Section**: Browse all your favorite photos and videos in a dedicated section
- **Real-time Sync**: Changes to favorites are instantly reflected across all screens
- **3x3 Grid Layout**: Consistent viewing experience matching the Photos section
- **Server-side Filtering**: Efficient loading of favorite items directly from Immich API

### Existing Features
- Browse photos and videos from your Immich server
- Album management
- People recognition
- Timeline view

And also:

| Features in the Official Version (giejay)                                      | Status |
|:-------------------------------------------------------------------------------|--------|
| Sign in by phone (https://github.com/giejay/Immich-Android-TV-Authentication)  | Done   |
| Sign in by entering API key                                                    | Done   |
| Demo environment                                                               | Done   |
| Album fetching + Lazy loading                                                  | Done   |
| Showing the photos inside an album                                             | Done   |
| Showing people, random, recent or seasonal photos                              | Done   |
| Slideshow of the photos and videos with a configured interval                  | Done   |
| Setting the app as the screensaver                                             | Done   |
| Setting the albums to show in the screensaver                                  | Done   |
| Configure the interval of the screensaver                                      | Done   |
| Add generic sorting of albums and photos                                       | Done   |
| Add sorting for specific album (select last item in row and press right again) | Done   |
| Showing the 4K thumbnail instead of the full image to speed up loading         | Done   |
| Showing the EXIF data and improving the slideshow view                         | Done   |
| Configure whether to play sound with videos                                    | Done   |
| Smarter merging of portrait photos (same people, same date, same city)         | Todo   |
| Add transitions to slideshow                                                   | Todo   |
| Add places/tags view                                                           | Todo   |
| Add background media playing info to screensaver                               | Todo   |
| Casting capabilities                                                           | Todo   |
| Searching in and for albums                                                    | Todo   |
| Dependency injection with Hilt/Dagger                                          | Todo   |

## Screenshots

|                                                                                    |                                                                      |                                                                                    |
|:----------------------------------------------------------------------------------:|:--------------------------------------------------------------------:|:----------------------------------------------------------------------------------:|
|        ![Alt text](/screenshots/homescreen-1.png?raw=true "Album overview")        |      ![Alt text](/screenshots/photos.png?raw=true "All photos")      |      ![Alt text](/screenshots/sorting-options.png?raw=true "Sorting options")      |
|         ![Alt text](/screenshots/home-edit.png?raw=true "Edit homescreen")         | ![Alt text](/screenshots/settings-view.png?raw=true "View settings") | ![Alt text](/screenshots/settings-screensaver.png?raw=true "Screensaver settings") |
| ![Alt text](/screenshots/screensaver-portrait.png?raw=true "Screensaver portrait") |        ![Alt text](/screenshots/people.png?raw=true "People")        |             ![Alt text](/screenshots/seasonl.png?raw=true "Seasonal")              |

## About this fork

Immich TV is a fork of [Immich-Android-TV](https://github.com/giejay/Immich-Android-TV) by
**giejay**, which started it and wrote most of the original code. This fork has since gone its own
way: a redesigned interface (Apple and Google TV styles), its own viewer, caching, translations and
its own application id (`javipas.android.tv.immich`). It is not affiliated with the original
project. See [NOTICE.md](NOTICE.md) for the copyright and licence details.

## Build steps

1. Clone project with `git clone --recurse git@github.com:picajoso/Immich-Android-TV.git`
2. copy app/src/strings_other.xml.example to app/src/main/res/values/strings_other.xml and modify
   the address and API keys for your demo server.
3. Build apk with `./gradlew assembleRelease`

## Support the project

You can support the project in several ways. The first one is by creating nice descriptive bug
reports if you find any: https://github.com/giejay/Immich-Android-TV/issues/new/choose.
<br><br>Even better is creating a PR: https://github.com/giejay/Immich-Android-TV/pulls.
<br><br>
Lastly, if you feel this Android TV app is a useful addition to the already great Immich app, you
might consider buying me a coffee or a beer:

[!["Buy Me A Coffee"](https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png)](https://www.buymeacoffee.com/giejay)

## FAQ

#### I'n not able to set the app as a screensaver

1. Enable development mode on the device (click the build number or "Android TV OS Build" 7 times in
   the System->About settings).
2. Go to System -> Developer Options and enable USB Debugging.
3. If you don't have ADB installed on your PC, follow these
   instructions: https://www.xda-developers.com/install-adb-windows-macos-linux/
4. After downloading/installing ADB on the PC, connect to the device using it's IP: adb connect
   192.168.xx.xx.
5. Once you are connected, execute the following command: 'adb shell settings put secure
   screensaver_components javipas.android.tv.immich/nl.giejay.android.tv.immich.screensaver.ScreenSaverService'
6. Done!
