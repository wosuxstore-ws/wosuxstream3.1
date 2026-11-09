# Wosux Stream: free live TV for Android phones, tablets and Android TV boxes

Wosux Stream is a fast, Netflix-style player for live TV. It plays free, community-maintained **M3U playlists** of publicly
available streams (by default [iptv-org](https://github.com/iptv-org/iptv)); you can also load any M3U link of your own.
No ads, no accounts, no paid services. The app contains no channels itself.

Kotlin, View-based UI (XML + ViewBinding), AndroidX, Media3 (ExoPlayer), Coil, OkHttp. Works with a touch screen and with a TV remote (D-pad).

## Features
- **Home**: a hero banner, then rows of channels. By default (source **Sports + Pakistan**) the first row is **Sports** (the sports channels; "See all >" opens all of them as a grid), followed by one row for each category of the **Pakistani** channels (Pakistani News, Pakistani Entertainment, ...). **Continue watching** (your last channels) and **My List** (your favorites) come after those rows. With any other source, Home shows that source's categories, with Continue watching and My List first.
- **Browse**: a grid of every channel with instant search (also inside your favorites).
- **My List**: your favorite channels, newest first.
- **Sources**: Sports + Pakistan (the default), or pick a country, category or language, or paste your own M3U link, or All channels.
- **Player**: tune-in screen with the channel logo, an overlay with play / pause, previous / next and favorite, a channel list panel, swipe zapping on a phone, and a friendly error card with **Retry** and **Next channel** (it can jump to the next channel by itself).
- **Offline channels**: channels are tested in the background and the ones that do not work are dimmed. Zapping can skip them, and Home can hide them.
- **Settings**: hide offline channels, skip offline channels when zapping, check channels in the background, video quality (Auto / SD / 720p), channel source, refresh the playlist, clear the image cache, and the version.
- **Fast and offline-friendly**: the playlist and the channel logos are cached on the device, so Home opens quickly and still shows offline. The playlist is refreshed when it is older than 6 hours (or with "Refresh playlist now").
- **Crash report**: if the app ever crashed, it shows the saved report at the next start.

## Controls
| | Remote (D-pad) | Touch |
|---|---|---|
| Move around the lists | arrow keys | scroll |
| Open / play a channel | OK | tap |
| Add / remove a favorite (on a channel card) | hold OK | long-press |
| Add / remove a favorite (in the player) | heart button (OK) | heart button |
| Next / previous channel (in the player) | UP / DOWN or CH+ / CH- | swipe up / down on the video, or the Next / Prev buttons |
| Show / hide the controls (in the player) | OK, or LEFT / RIGHT to show them | tap the video |
| Play / pause | Play / Pause key | pause button |
| Open the channel list (in the player) | RIGHT on the list button (the last button), then OK | list button |
| Close the channel list | LEFT or BACK | tap the video or BACK |
| Pick a channel in the list | UP / DOWN, then OK | tap |
| Leave the player / close a category grid | BACK | back arrow or BACK |

On a TV box the navigation bar is at the top; on a phone it is at the bottom.

## Build the APK with GitHub Actions
1. Create a new GitHub repository and upload everything in this folder (keep the `.github` folder). You can do it in the web page, no git needed.
2. Open the **Actions** tab, click **Build APK** in the left list, then **Run workflow** (choose your branch) and press the green button.
   A push to a branch does **not** start a build; a tag like `v1.0.0` does.
3. Wait a few minutes. A green tick means success. Open the run and download the artifact **Wosux-Stream-apk** at the bottom (a zip with two APKs):
   - `Wosux-Stream.apk`: the release version (smaller, signed). Installs as `com.wosux.stream`.
   - `Wosux-Stream-test.apk`: the debug version. Installs next to the release one as `com.wosux.stream.test`, so you can test without touching your real install.
4. Optional: push a tag such as `v1.0.0` and both APKs are also attached to a **GitHub Release** (handy for downloading directly on the TV box).
5. If the run is red, open it, click the red step and read the red lines.

### Signing (your own key, optional)
By default the APK is signed with the key committed in `app/freetv.keystore` (password `freetv123`), so every build installs over the previous one.
**In a public repo anyone can read that key and password.** That is fine for a personal app. If you want your own private key, add these four
**secrets** (GitHub > Settings > Secrets and variables > Actions > New repository secret):

| Secret | Value |
|---|---|
| `WOSUX_KEYSTORE_BASE64` | your keystore file as base64 text |
| `WOSUX_STORE_PASSWORD` | the keystore password |
| `WOSUX_KEY_ALIAS` | the key alias |
| `WOSUX_KEY_PASSWORD` | the key password |

Set all four, or none. With all four, the workflow decodes the keystore and signs with it; the passwords never appear in the build files.
With none, the build works exactly as before. If only some are set the run stops early with a clear message.

To make a keystore (on a computer with Java) and its base64 text:
```
keytool -genkeypair -v -keystore my.keystore -alias wosux -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 my.keystore        # Linux (macOS: base64 -i my.keystore)
```
Local builds read the same names as environment variables (`WOSUX_STORE_PASSWORD`, `WOSUX_KEY_ALIAS`, `WOSUX_KEY_PASSWORD`) and fall back to the committed values.

## Install
- **Phone / tablet:** open the APK and allow "install unknown apps" when asked.
- **Android box:** copy `Wosux-Stream.apk` to a USB stick and open it with a file manager, or use the free *Downloader* app with your GitHub Release link,
  or `adb connect <box-ip>` then `adb install Wosux-Stream.apk`.

## Notes
- Minimum Android 5.0 (API 21). Version 1.0.0.
- After this update the app opens with **Sports + Pakistan**. If you had picked a country, category or language before, it is replaced once (a custom M3U link is kept); pick another source any time in **Settings > Channel source** or the Source button.
- Free streams come and go. Some are geo-blocked or offline: pick another source in **Settings > Channel source**, or use **Refresh playlist now**.
- **Changing the signing key means you must uninstall the old app once** (Android refuses to update an app signed with a different key). Your favorites and recents are then gone.
- The release build is shrunk (R8), so a crash report from `Wosux-Stream.apk` shows short names; the `-test.apk` keeps readable ones.
- Not a channel provider: the app only plays the playlists you point it at. If you ever publish it publicly, you are responsible for the sources you use, and app stores have their own rules about apps that stream third-party content. Read them first.

## Live match card (shows only when you switch it on)
Under the top banner Home can show a card like a TV schedule entry: a picture with a small camera badge, a heading, the time range, a red progress bar and a chevron.
Tap it (TV: press OK) and the channel you chose in the admin page opens in the player. While the card is hidden or deleted, Home looks exactly as before.

1. Upload the new `cpanel-ads/admin.php` over the old one in your cPanel `ads` folder. Set your password on line 5 again, and the time zone on line 6 (default `Asia/Karachi`).
2. Open `https://yourdomain.com/ads/admin.php` > **Live match card**:
   - **Heading** (required), optional second line, optional picture (about 600 x 400 px; without a picture the channel logo is used).
   - Optional **start** and **end** time (both or none). With both, the card shows the time range and a progress bar, and it hides itself when the end time has passed.
   - **Channel that opens on click**: type in the search box (for example `PTV`) and pick a channel from the list. The list is read by the page from the same two playlists the app uses on Home (Sports + Pakistan) and is renewed every 6 hours (button **Refresh channel list now**). A channel that is not in the list can be added with **Other stream link**.
   - Tick **Show in app** and press **Save live card**.
3. When the match is over press **Hide from app** (the card stays saved, so next time you only press **Show in app**), or **Delete**.
4. Build the APK once (the app needs the new code). After that the card is controlled from the admin page only: the app asks for `live.json` (next to `banners.json`) about once a minute while Home is open.

By hand: copy `cpanel-ads/live.example.json` to `live.json` in the same folder and edit it (`"enabled": true` shows it; `start` / `end` are seconds since 1970, `0` = none). The channel's own `url` is what plays, so the card also works when that channel is not in the playlist that is loaded.

## Banners from your cPanel (shop products)
Home shows your banners (pictures and/or short videos) in the big box at the top, in place of the featured channel ("LIVE ..." card); it scrolls away with the list like that card did. With no banner (or none that can load) the featured channel is shown as before. Tap a banner (TV: press OK on it) and its link opens in the browser.
The app reads everything straight from your own hosting (GoDaddy cPanel). No Firebase, no ad network, no other service.

1. In cPanel > **File Manager** > `public_html`, create a folder, for example `ads`.
2. Upload your banner pictures (`.jpg` / `.png` / `.webp`) and videos (`.mp4`) into it. Good size for phones: about **1200 x 800 px** (3:2, the shape of the box; edges are cropped a little to fill it, so keep text away from the borders), videos short and small (a few MB, H.264 `.mp4`).
3. Easiest: upload `cpanel-ads/admin.php` into the same folder, set your own password on line 5, open `https://yourdomain.com/ads/admin.php` and add / pause / delete banners there (it creates `banners.json` for you).
   Or by hand: upload `cpanel-ads/banners.json` from this project into the same folder and edit it (one block per banner):
   - `src`: the file name (or a full address). `link`: where the click goes (must start with `http://` or `https://`).
   - optional `seconds` (how long a picture stays; videos play until the end), optional `"disabled": true` to pause a banner.
4. Open `https://yourdomain.com/ads/banners.json` in a browser. If you see the text, it works.
5. In `app/src/main/java/com/freetv/app/AdBanner.kt` change `FEED_URL` to that address, then build the APK as usual.

After that you never need to rebuild: change files or `banners.json` on cPanel and the app picks it up (when Home is opened, at most every 5 minutes).
Until `FEED_URL` is set, the app makes no request and shows no banner. Max 10 banners; a picture or video that cannot load is skipped.
