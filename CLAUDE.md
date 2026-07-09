# Finale (Shark Marmalade fork)

Personal fork of [Shark Marmalade](https://github.com/bendardenne/sharkmarmalade), a third-party
Jellyfin music client for Android Automotive OS (AAOS). GPL-2.0. **Not intended for upstreaming or
publishing** — the audience is the owner's own Polestar 3 and Jellyfin server (whose ffmpeg has
`libfdk_aac`, verified via ssh), so design decisions optimize for that setup, not a general user
base. Remotes: `origin` = wit-0-bit/sharkmarmalade (the fork), `upstream` = bendardenne.

**Identity (2026-07-05 → renamed to Finale 2026-07-06):** `applicationId = elizardbeth.finale`,
display name **Finale** (was Dorsal; the name is `@string/app_name`, which *also* becomes the
Jellyfin client name via `ClientInfo(getString(R.string.app_name), …)` — so it's how the app
shows up in the server's Devices list). The `namespace` — and therefore all source packages,
class names, the theme, and the `LOG_MARKER` logcat tag — deliberately stays
`be.bendardenne.jellyfin.aaos` / `SharkMarmalade`. The distinct id exists because upstream owns
the original id on Google Play and the only way onto a production Polestar is a Play
**closed-testing** track (ADB/developer mode is disabled on production Polestars; the
community-verified path is Play Console → Automotive release type under Advanced Settings → closed
track → add the car's Google account as tester). **Confirmed firsthand + externally (2026-07-07):**
tapping the build number 7x *does* fire the standard AOSP "you are now a developer" toast, but the
"Developer options" entry it unlocks is itself a dead end — it redirects to
developer.volvocars.com, which is documentation/marketing for their **emulator**, not a real
unlock. No ADB path exists on Polestar 2/3 (per Polestar/XDA forum threads); reportedly the
Polestar 4 — a different platform generation — does allow real developer mode. Play closed
testing is genuinely the only route for this car, not a workaround for something we missed.
(Owner's assessment of whoever specifically designed the toast-then-wall UX: they deserve a
miserable life.) Everything that rides on the app identity derives
automatically: ContentProvider authority = `${applicationId}` in the manifest and
`BuildConfig.APPLICATION_ID` in code, tab-icon artwork URIs =
`android.resource://${context.packageName}/...`. Don't reintroduce hardcoded identity strings.
(The account type / `account_type` resValue / `authenticator.xml` are gone as of v43 — see the
credentials note below.)
The applicationId is **frozen on the first Play upload** — it was changed dorsal→finale *before*
any upload, which is the only free window; after publishing it can never change.

**Release signing (set up 2026-07-06, pre-first-upload):** the release build is signed with an
**upload key** at `~/.keystores/finale-upload.jks` (RSA 2048, alias `finale-upload`, valid to
2053), whose credentials live in a gitignored `keystore.properties` at repo root (also stored in
1Password). `build.gradle.kts` loads that file into a `signingConfigs.release` and only attaches
it when the file exists (so a keyless checkout still builds an unsigned release / debug). Play App
Signing is **Google-managed** for the actual app-signing key; our key is *only* the upload key
(resettable via Play support if lost). Build the artifact with `./gradlew :automotive:bundleRelease`
→ `automotive/build/outputs/bundle/release/automotive-release.aab`. Store assets (512² icon,
feature graphic, screenshots) live in `assets/` but are still upstream Shark Marmalade's art —
replace before a real listing.

Single Gradle module (`automotive`), ~3000 lines of Kotlin, no tests (`testInstrumentationRunner`
is configured but no `test/`/`androidTest/` source sets exist). Key versions (see
`build.gradle.kts` files): AGP 9.1.0 (Gradle 9.5.1), Kotlin 2.3.4 (KSP, not kapt), Hilt 2.59.2,
media3 1.9.2 (+ `media3-exoplayer-hls`), Jellyfin SDK 1.8.6, compileSdk 36, minSdk 29,
targetSdk 35 (bumped from 34 on 2026-07-06 for Play's current-API upload floor; verified to
install/run/browse on the API-34 emulator — API-35 edge-to-edge on the sign-in/settings screens is
the one thing that couldn't be exercised there), JVM target 17. AGP 9 note: `buildConfig` and
`resValues` build features must be (and are) explicitly enabled.

## Dev environment

Once per machine, not per checkout:

- **JDK 17 is required for the Gradle build itself**, independent of the app's JVM target. AGP 9's
  `androidJdkImage`/`jlink` transform breaks on newer JDKs (confirmed failing on JDK 26). Fix:
  install `openjdk@17` and point only Gradle at it via `~/.gradle/gradle.properties` →
  `org.gradle.java.home=<path>`. Don't change the system `java`/`JAVA_HOME`.
- The Gradle wrapper **is committed** (since `1cb058b`); `./gradlew :automotive:assembleDebug` works
  from a fresh checkout once `local.properties` (gitignored) has `sdk.dir=<Android SDK path>`.
- **AAOS emulator**: a bare SDK install has no `cmdline-tools`. Download the commandline-tools
  package, accept licenses, install an automotive image (e.g.
  `system-images;android-34-ext9;android-automotive-playstore;arm64-v8a`), create an AVD with
  device profile `automotive_1024p_landscape`.
- **Sideload gotcha**: `adb install -r` (or `am force-stop`) kills the app under the emulator's
  Media Center, which then holds a dead session token — task-switching back shows
  "Loading content…" forever (verified: zero app activity in logcat during the hang; only a fresh
  drawer launch re-dispatches). Not an app bug; can't happen from normal in-car use. Workaround:
  after every install, `adb shell am force-stop com.android.car.media`.
- The emulator's foreground user is a **secondary user (10)** — `adb shell run-as <pkg>` inspects
  user 0's empty data dir unless you pass `--user $(adb shell am get-current-user)`.
- Two builds can coexist on the emulator: the old `be.bendardenne.jellyfin.aaos` install (signed
  in) and `elizardbeth.dorsal`. Each package has its own account storage — a fresh install needs a
  fresh Jellyfin sign-in.

## Module layout

```
automotive/src/main/java/be/bendardenne/jellyfin/aaos/
  JellyfinMusicService.kt          MediaLibraryService: ExoPlayer + MediaLibrarySession, cache-wrapped data source, playback reporting/polling
  JellyfinMediaLibrarySessionCallback.kt   Session callback: browse/search/voice/playback-queue entry points, PLAY_ALL + PARENT_KEY handling
  JellyfinMediaTree.kt             Lazy, cache-backed adapter: Jellyfin REST API -> media3 MediaItem tree (RAM + disk, stale-while-revalidate)
  MediaTreeDiskCache.kt            Disk cache of raw BaseItemDto JSON (children lists + per-track items), namespaced per server+token
  MediaItemFactory.kt              Builds MediaItem/MediaMetadata per BaseItemKind + the synthetic root/Browse/Play-All nodes
  AlbumArtContentProvider.kt       ContentProvider proxying+caching album art behind content:// URIs
  AudioCache.kt                    Process-singleton media3 SimpleCache (LRU, quota-sized) around the streaming DataSource
  JellyfinApi.kt                   Auth header construction for direct (non-SDK) HTTP requests + isNetworkFailure()
  JellyfinAccountManager.kt        Jellyfin credentials (server/username/token) in app-private SharedPreferences ("credentials" file; NOT the default prefs — the pref-change listener must not fire on credential writes). One-time best-effort migration from the removed AccountManager account.
  JellyfinHiltModule.kt            Provides Jellyfin/JellyfinAccountManager singletons
  CommandButtons.kt                Repeat/shuffle custom session command buttons
  SharkMarmaladeConstants.kt       LOG_MARKER, bitrate pref keys
  downloads/DownloadStore.kt       Downloaded music on disk: filesDir/downloads/<sha256(server)>/tracks/*.m4a + DTO index (artists/albums/tracks .json). Namespaced by server only (survives re-login). 10 GB cap const.
  downloads/DownloadSyncer.kt      Reconciles the server-side "finale-downloads" collection (BoxSet) against the store: expands artists→albums→tracks, downloads AAC-256 m4a files via /Audio/{id}/stream.m4a, evicts removals, incremental index writes every 10 tracks
  signin/                          Sign-in Activity/Fragments/ViewModel (server URL, username/password, QuickConnect, SignedInFragment for already-authed launches)
  settings/                        Settings Activity/Fragment/ViewModel (bitrate, log upload)
```

## Architecture as built

### Browse tree

`JellyfinMusicService` builds one `ExoPlayer` + one `MediaLibrarySession` with
`JellyfinMediaLibrarySessionCallback` as the callback; the car's own media UI renders everything
(this app has no browse UI of its own — `androidx.car:car` is declared but never used). The
callback delegates to `JellyfinMediaTree`, which serves nodes from a Guava RAM cache + the disk
DTO cache and builds `MediaItem`s through `MediaItemFactory`.

**Root = 3 tabs: Artists / Downloaded / Browse** (since v43). Artists is deliberately the landing
tab: it's disk-cached, so the app opens to something usable with no connectivity (Random landed
here before and is deliberately uncached — a flaky cell radio made the app look broken).
Downloaded is the offline library (see Downloads section). Browse is deliberately the
overflow/catch-all for lower-priority categorizations: **Artist / Genre / Album / Favourites /
Recents / Playlists / Random** (Artist appears both as a root tab and a Browse category — same
`fetchArtists()`, two tree node ids so revalidation notifies the right subscription). Old root
tabs Latest Albums/Playlists moved into Browse as Recents/Playlists; Random and Favourites
demoted into Browse in v43 (Favourites stays a favourites list — the owner favourites songs;
downloads are a separate artist/album-level set).

**Artists and albums are purely browsable** (`isBrowsable=true, isPlayable=false`). Playing:
- **Track tap is context-sensitive, keyed off the row's `mediaId`** (the only thing the car host
  echoes back on a tap — `getItem(trackId)` itself is context-free). *Inside an album view*,
  tapping a track queues the whole album in order, positioned at the track, via the `PARENT_KEY`
  extra (= `item.albumId`) + `expandSingleItem()` — so an album needs no explicit play affordance.
  *Outside an album view* (Favourites, search results), track rows are re-tagged
  `SINGLE_PREFIX` (`"SINGLE:<trackId>"`) by `JellyfinMediaTree.asSingle()`, so `isSingleItemWithParent`
  short-circuits and a tap plays **just that track**. The resolved queue always strips the tag back
  to raw ids (`stripSingle`/`resolveMediaItems`) so rating/resumption/reporting keep working.
  (Owner decision 2026-07-06: reverses the brief "every tap queues the album" behaviour from the
  overnight run.)
- Artist listings get a pinned synthetic first row **"▶ Shuffle all songs"** (`mediaId =
  "PLAY_ALL:<artistId>"`), intercepted in `onSetMediaItems`/`onAddMediaItems` *before* the
  PARENT_KEY path, resolved to all of the artist's tracks with `shuffleModeEnabled = true`,
  filtered out of `resolveMediaItems()` recursion. The row is only ever added by builder lambdas,
  never persisted (disk cache holds real DTOs only). **Favourites gets the same treatment**: a
  pinned **"Play all favourites"** row (`PLAY_ALL:FAVOURITES_ID`, in-order, `shuffle` off), added
  only when the list has ≥1 loose track. An album flavor of `playAllRow()`/`resolvePlayAll()` is
  kept only so a host holding a stale cached row resolves gracefully — the album row itself was
  dropped as redundant.
- The old `PREF_ALBUM_BEHAVIOUR` preference is gone (one-time `remove("album_behaviour")` purge in
  `SharkMarmaladeApplication`).

### Playback: always-transcode AAC-256 over HLS

`MediaItemFactory.forTrack()` requests `getUniversalAudioStreamUrl` with `container=["ts"]`
(matches no music container, so nothing ever direct-plays), `audioCodec="aac"`,
`transcodingContainer="ts"`, `transcodingProtocol=MediaStreamProtocol.HLS`, and
`audioBitRate` from the bitrate pref (default `TRANSCODE_BITRATE = 256_000`). Track MediaItems set
`MimeTypes.APPLICATION_M3U8` explicitly — the universal URL gives ExoPlayer no hint the response
is a playlist. Why: a plain transcoded HTTP stream is an unseekable chunked pipe (tried
Opus-over-HTTP first; hosts hide the seek slider entirely and startup took ~35 s), while HLS
carries duration and the server transcodes from any seek point on demand (~1 s startup, real
seek). Why AAC-256: the server's ffmpeg has `libfdk_aac`, which Jellyfin auto-prefers —
transparent at 256 kbps. Known wart: the Settings "Max bitrate" pref still offers a default
"Direct stream" entry that now silently produces the same 256k transcode (see TODO).

### Downloads (built 2026-07-08, same session as v43 — design conversation with owner)

**The download set is managed server-side via a Jellyfin collection named `finale-downloads`**
("Add to collection" on any album or artist detail page, phone/web — the owner manages it from
the couch; there is deliberately NO in-car download management). Favourites explicitly rejected
as the marker (owner favourites *songs*, downloads *artists/albums* — different sets, different
granularity). `DownloadSyncer` reconciles on service start/login + every 5 min (self-throttled
to 15-min intervals): expands collection entities → tracks, downloads missing ones sequentially
as **discrete AAC-256 .m4a files** via `/Audio/{id}/stream.m4a?audioCodec=aac&audioBitRate=256000`
(verified against the live server: valid seekable files, ~2 MB/min; NOT the HLS path streaming
uses), evicts unlisted tracks, caps at 10 GB (`DownloadStore.MAX_DOWNLOAD_BYTES`), and writes the
DTO index incrementally so a radio drop mid-sync never hides finished downloads. Missing
collection = no-op (never mass-evict on a rename); emptying the collection is the explicit purge.

**Downloaded root tab** (replaced Favourites at root) is browse-shaped — Artists / Albums —
and built ENTIRELY from the local index: one downloaded album of a 35-album artist shows that
artist containing exactly that album (owner requirement). Node ids are context-prefixed
(`DOWNLOADED_ARTIST:`/`DOWNLOADED_ALBUM:`) so RAM-cached server-context nodes don't collide.

**Local-first playback**: `MediaItemFactory.forTrack` takes a `localTrack` resolver — a
downloaded track plays from `file://` (MIME `audio/mp4`, not HLS) no matter where it was tapped
(any tab, search, voice); streaming is the fallback. The service's data source chain is wrapped
in `DefaultDataSource.Factory` to route `file://` to disk (DefaultHttpDataSource throws on file
URLs — found the hard way). `JellyfinMediaTree` resolves downloaded albums' children and
downloaded track DTOs from the index before disk-cache/network, so PARENT_KEY queue expansion,
resumption, and voice all work with zero network. Verified end-to-end on the emulator with
networking disabled: browse → tap → 12-track queue → playing from filesDir.

### Voice search

On AAOS, Assistant reaches media apps through the session's legacy `onPlayFromSearch`, which
media3's `MediaSessionLegacyStub` bridges into `onSetMediaItems`/`onAddMediaItems` as a single
MediaItem with a **blank mediaId** and the query in `requestMetadata.searchQuery` (Assistant
extras such as `android.intent.extra.focus` preserved in `requestMetadata.extras`).
`ACTION_PLAY_FROM_SEARCH` is advertised automatically — no manifest change needed. (App
Actions/`shortcuts.xml` are phone-form-factor mechanisms; an earlier plan to use them was
disproven against media3 1.9.2 bytecode.) Before this feature, the blank id crashed
`"".toUUID()` — voice play was actively broken, not just missing.

Resolution (`searchQueryOrNull`/`resolveSearch`/`rankSearchResults`): ranked matching over
`tree.search()` — artist > album > playlist > track, exact-then-contains, **type-biased**. The
requested type comes from either a **spoken keyword** parsed off the front of the query
(`parseTypeQualifier`: "play **album** Polygondwanaland", "play **song** X", also artist/playlist —
the keyword is stripped before searching) **or** Assistant's `EXTRA_MEDIA_FOCUS` extra
(`focusTypeFromExtra`), keyword winning. It only *biases* ranking, not filters — winners resolving
to zero tracks fall through to the next candidate, so a mis-heard/absent type still plays something.
Artist winners reuse the shuffle-all plumbing (`resolveParentTracks`); track winners play just that
track (they carry a `SINGLE:` id — see the browse-tree note); blank queries play one random album; a
no-match query **throws** so media3 ignores the failed future — a misheard query can't wipe the live
queue or saved resumption state. `ensureTree()` guards voice/resumption arriving on a cold process.
Verified at source level; still needs a real-Assistant end-to-end test in the car (open question:
whether Assistant passes the keyword through in `searchQuery` or pre-strips it and sets the focus
extra — the code handles both, and a leading article like "the album X" is the one phrasing not yet
handled).

### Caching

- **Audio** (`AudioCache.kt`): process-singleton `SimpleCache` (`cacheDir/audio`,
  `LeastRecentlyUsedCacheEvictor` sized `min(getCacheQuotaBytes(), 5 GiB)`, fallback 2 GiB),
  wired in `JellyfinMusicService.onLogin()` via `CacheDataSource.Factory` around the authed
  `DefaultHttpDataSource`, `FLAG_IGNORE_CACHE_ON_ERROR` so cache I/O degrades to plain streaming.
  Deliberately never released (`SimpleCache` throws on double-open; the Service can be recreated
  in-process). A `CacheKeyFactory` strips session-volatile params defensively. **Partial-cache
  HLS replay is safe — verified empirically** by recovering cached `.m3u8` payloads from the
  emulator's SimpleCache: playlists/segment URLs carry no `PlaySessionId`/`api_key`/`DeviceId`,
  and Jellyfin transcodes uncached segments on demand, so a later replay serves cached segments
  from disk and fetches the rest without a session dependency.
- **Media tree** (`MediaTreeDiskCache.kt` + `JellyfinMediaTree.childrenWithCache()`): raw
  `BaseItemDto` JSON (never `MediaItem`s — those embed stream URLs and pref-dependent values)
  under `cacheDir/tree/<sha256(baseUrl|accessToken) prefix>/`, atomic-ish temp+rename writes,
  corrupt reads deleted as misses. Disk hits are served immediately with a throttled (30 s,
  in-flight-deduped) background revalidation on `Dispatchers.IO`; a fingerprint diff
  (`id|name|isFavorite`, order-sensitive, ignoring playCount churn) triggers
  `notifyChildrenChanged` (marshaled to main). Loop-safety invariant: fresh data is written to
  disk *before* the notify; notify is skipped if the write failed. `RANDOM_ALBUMS` and `search()`
  are deliberately uncached. Tracks are persisted individually so resumption works from disk
  after cold start. `evictCache()` is RAM-only on purpose: pref changes only affect `MediaItem`
  construction, so rebuilt items pick up new prefs from disk DTOs without a network round-trip.
- Accepted residuals: namespace keyed by `accessToken` (SDK 1.8.6 exposes no `userId`), so a
  re-login to the same account = one cold cache + an orphaned bounded dir; concurrent cold-miss
  `getChildren`/`getItem` for the same key can double-fetch (no in-flight guard on the foreground
  miss path); the art cache has no size cap (see TODO); `ARTISTS`/`BROWSE_ARTISTS` cache the same
  data under two disk keys (deliberate — revalidation must notify the exact subscribed parent id;
  cost is one duplicate small fetch/file, verified negligible).

### Threading model (settled 2026-07-06, from actual bytecode)

Everything relevant runs on the **main thread**: media3 delivers session-callback methods on the
session's application looper (main here), and `SuspendToFutureAdapter.launchFuture`
(concurrent-futures-ktx 1.3.0) starts its block `UNDISPATCHED` on the calling thread and resumes
after suspension on `Dispatchers.Main`. Consequences, verified: the long-suspected
`currentPlaybackTime`/`currentTrack` cross-thread race in `JellyfinMusicService` is **not a bug**
(all reads/writes on main); `resolveParentTracks`' `session.player` mutations are on ExoPlayer's
required thread. `subscriptions` in the callback is main-thread-confined; the one off-main
producer (disk revalidation on `Dispatchers.IO`) marshals via `mainHandler.post`. This safety
rests on those dispatch defaults — don't add custom session executors or move playback resolution
off `launchFuture` without revisiting it.

## Known issues — reassessed 2026-07-06

Full re-verification of every previously documented issue plus a fresh adversarial sweep
(46-agent workflow: per-file verification, fresh-eyes review, two independent cross-check lenses
per new finding; empirical checks against the live emulator and dependency bytecode where
relevant). **Fixed and closed items are listed at the bottom so they don't get re-reported.**
Actionable tracking (with priorities reshuffled toward getting the app into the actual car) lives
in `TODO.md` — this list is the reference state.

> **STATUS (2026-07-06, `overnight-fixes` branch — local, unpushed):** an autonomous run fixed
> nearly all of the list below. **Fixed & verified:** the High QuickConnect crash + the loop never
> terminating; the empty-playlist-tap queue/resumption wipe; non-audio playlist children; the
> `storeAccount` moved-server corruption; `clientLogApi.logFile` uncaught; foreground-fetch error
> surfacing (401→sign-in); silent `reportPlayback` failures; `fetchFavourites` limit; page/pageSize
> paging (within the fetch cap); the three `AlbumArtContentProvider` items; `ensureTree`/auth on the
> four browse/search entry points; `onSetRating` cast+wrong-item; atomic `getItem`; the bitrate-pref
> mislabel; `JellyfinApi.auth` via the SDK builder; the `Authenticator` contract; Hilt scoping. The
> PARENT_KEY twins were fixed by making a track's context its **album** (`MediaItemFactory.forTrack`
> uses `item.albumId`) — see the behavioral-change note in `agent-todos.md`. An adversarial pass then
> caught and fixed four self-introduced regressions (over-broad QuickConnect guard, track-tap
> no-op/index, unbounded `itemLocks`, art-trim TOCTOU). **Still open:** true pagination *past* the
> 120 fetch cap; per-art-size factory (deferred — negligible for one head unit); `ARTISTS`/
> `BROWSE_ARTISTS` disk-key aliasing (deferred — negligible). Build green throughout; sign-in
> verified end-to-end on the emulator. The detailed list below is the pre-fix reference.

> **STATUS (2026-07-08, v43 "first real drive" batch):** the app reached the car (v42) and the
> first drive shook out a connectivity story, all addressed in v43:
> - **The car grants a 64 MB cache quota** (`getCacheQuotaBytes()`, measured via uploaded car
>   log) — the "5 GiB" streaming cache is ~8 songs on real hardware. Album art therefore moved
>   from `cacheDir` to **`filesDir/albumart`** (one-time rename migration in the provider's
>   `onCreate`; own 256 MB LRU trim). Future Downloads must live in `filesDir` too.
> - **Token drift fixed structurally**: the API client's token was process state set only at
>   service start / LOGIN_COMMAND; when the car lost the handshake, browse presented a dead token
>   (server logged "Invalid token") and the 401→sign-in mapping bounced the user to sign-in
>   forever despite valid stored credentials. Now `syncAuth()` (callback, runs in `ensureTree()`
>   on every entry point) re-applies stored credentials on drift and evicts the RAM item cache
>   (items embed token-bearing URLs); `applyAuth()` split out of `onLogin()` in the service.
>   LOGIN_COMMAND is a refresh hint, not a required link.
> - **AccountManager removed entirely** (authenticator Service/XML/manifest/resValue deleted):
>   credentials live in an app-private `credentials` SharedPreferences file; `clear()` = sign-out.
>   Verified end-to-end on the emulator against the real server (QuickConnect approved by owner).
> - **Host stale-error self-heal**: the car's media host caches an auth-error card and re-fires
>   its resolution intent (SignInActivity) even after successful sign-in. SignInActivity now
>   detects the already-authed launch, shows `SignedInFragment` ("Signed in to X as Y" +
>   sign-out) instead of a blank server form, and pokes LOGIN_COMMAND so the host re-fetches.
> - **Foreground fetch retry** (`withRetry` in the tree: 2 extra attempts, 500ms/1500ms backoff,
>   only on transport failures via `isNetworkFailure()` cause-chain walk in `JellyfinApi.kt`);
>   network failures get their own host error string ("Can't reach your Jellyfin server…")
>   distinct from generic could-not-load. Background revalidation deliberately doesn't retry.
> - **"Something went wrong / Check that Google Play is enabled"** at app launch on the car is
>   Play's own entitlement check for closed-testing installs when the car is offline — NOT our
>   code, cleared by connectivity. Open question whether Play re-checks every cold launch
>   (matters for Downloads-offline); test airplane-mode launch once Downloads exist.
> - The Polestar's cell radio sometimes fails to init until a head-unit restart (long-press the
>   media/power button) — pure car flakiness, but it's why "handle bad connectivity way better"
>   is the current theme. Next: Downloads (~10 GB in `filesDir`, AAC-256 ≈ 7–9 MB/track).

**High**
- `signin/SignInActivityViewModel.kt:61-113` — the QuickConnect polling coroutine has **zero
  error handling** and the SDK throws on every non-2xx/IO failure (verified in jellyfin-api
  1.8.6 bytecode; the `status == 200` checks are dead code). Jellyfin expires quick-connect codes
  after 10 minutes, so a user who leaves the code screen up (normal in a car — approval needs a
  second device) gets a guaranteed uncaught exception → process crash on the sign-in screen. Any
  transient network blip while polling does the same.

**Medium**
- `signin/SignInActivityViewModel.kt:80-83` — the QuickConnect poll loop never terminates on
  success: after approval it re-authenticates **every second** (new server session/token each
  time), re-fires `loggedIn`, and `SignInActivity` builds a new (never-released) MediaController
  per emission until the activity manages to finish.
- `JellyfinMediaLibrarySessionCallback.kt:338` — tapping an **empty playlist** resolves to `[]`,
  which is applied as `setMediaItems(emptyList)` (wipes the live queue) and saved via
  `savePlaylist` (destroys resumption state). This is exactly the harm the voice-search path
  guards against by throwing; the browse-tap path is unguarded.
- `JellyfinMediaTree.kt:83-91` — `getItem()`'s cold path (disk/network) rebuilds tracks with
  `parent=null`, and `revalidateItem` can stomp a with-parent RAM entry with a parentless one. A
  track tap after RAM eviction or process restart then fails `isSingleItemWithParent` and queues
  a **single track with no album context** instead of the parent's tracklist.
- `JellyfinMediaTree.kt:52-54, 230-256` — the `mediaItems` RAM cache is keyed by raw item id with
  no browse-context namespacing; `PARENT_KEY` is last-write-wins. Browsing a playlist containing
  a favourited track overwrites the Favourites-context entry, so tapping that track from the
  still-displayed Favourites screen queues the playlist's tracklist. (Pre-existing; the surface
  is wider now that more browse contexts exist.)
- `JellyfinMediaTree.kt:336-341` + `MediaItemFactory.kt:445` — `fetchItemChildren` applies no
  type filter and `MediaItemFactory.create()` throws on any non-music kind, so a video/mixed
  playlist (listed — `fetchPlaylists` doesn't filter by mediaType either) **deterministically
  fails** to browse or play, including from the persisted disk cache.
- `JellyfinAccountManager.kt:30-44` — re-login with the same username but a different server URL:
  `addAccountExplicitly` returns false (name+type already exists), the failure is ignored, the
  old server URL is kept while the **new server's token** is stored on it → permanent 401s, and
  no sign-out/removeAccount exists anywhere to recover.
- `settings/SettingsFragmentViewModel.kt:84` — `clientLogApi.logFile(content)` has no try/catch;
  an expired token or network failure during log upload propagates to the default handler
  (no `CoroutineExceptionHandler` anywhere) → crash from a Settings action.

**Low**
- `JellyfinMediaLibrarySessionCallback.kt` — `ensureTree()` covers voice/resumption but not
  `onGetChildren`/`onGetItem`/`onSearch`/`onGetSearchResult`. Verified non-crashing (the lateinit
  throw lands in the future → error result; legacy hosts always trigger `onGetLibraryRoot` during
  connect, so only a media3-native browser could ever hit it). `onGetItem`/search also skip the
  `isAuthenticated` check that `onGetChildren` performs.
- `JellyfinMediaLibrarySessionCallback.kt:689` — `onSetRating` does an unchecked
  `rating as HeartRating` (any other Rating type from a controller → main-thread CCE crash) and
  stamps the rating onto `currentMediaItem`'s UI metadata even when the rated `mediaId` is a
  different queue item (the server-side favourite lands correctly).
- `MediaItemFactory.kt:363` + `preferences.xml` — the default "Direct stream" pref entry silently
  produces the same AAC-256 transcode as "256 kbps" (byte-identical request modulo
  `maxStreamingBitrate`); the label lies. Owner decision: transcode stays the default/only
  behavior for now; a true direct-play option is a deprioritized nice-to-have.
- `build.gradle.kts` — `slf4j-api` 2.0.17 + `slf4j-android` 1.7.36 can't bind (2.x is
  ServiceLoader-only): **all Jellyfin SDK logging is NOP-discarded**, which undermines the
  Upload Logs / adb diagnostics that car testing will rely on.
- `auth/Authenticator.kt:57-71` — the authenticator's `getAuthToken` returns the stored password
  (always `""`), never the real token (stored under a *different* token type than
  `AUTHTOKEN_TYPE`); `addAccount` returns an empty Bundle (contract violation). Latent — the app
  itself only uses `peekAuthToken` via `JellyfinAccountManager`, which works.
- `signin/SignInActivity.kt:33-42` — the sign-in completion MediaController is never released,
  and `future.await()` is unguarded (a failed service connection crashes; a hung one leaves the
  sign-in screen up after a successful login).
- `signin/UsernamePasswordSignInFragment.kt:52` — `startQuickConnect` fires on every view
  creation; back-and-forward navigation spawns concurrent poll loops sharing one mutable
  `quickConnectSecret` field.
- `AlbumArtContentProvider.kt:63` — `openFile` requires an in-memory `uriMap` entry *before*
  checking whether the file already exists on disk, so art requested after process death (e.g. a
  host's persisted resumption card) or after 2000-entry LRU eviction throws even though the bytes
  are cached; the served path derives purely from `uri.path`, so reordering fixes it.
- `AlbumArtContentProvider.kt:106` — `File.createTempFile` sits outside the try block; if it
  throws (disk full), that URI's `inProgress` latch never counts down and every later request for
  it waits 15 s and fails, until process death.
- `JellyfinMediaTree.kt` — favourites fetch is the one remaining unbounded listing with no
  in-code justification (`fetchItemChildren`'s unboundedness is deliberate: those children are
  the playback queue, and truncation would change what plays); browse lists cap at 120 with no
  `startIndex`, and `onGetChildren`/`onGetSearchResult` ignore `page`/`pageSize`; foreground
  fetch paths (cold-miss browse, random, search, item resolution) still have no try/catch, so a
  network blip is an opaque failed future (background revalidation *does* catch and keep stale
  data); `getItem()`'s cold-miss path is an unsynchronized check-then-act (double fetch possible;
  revalidation paths do have in-flight dedup).
- `JellyfinMusicService.kt` — `reportPlayback`'s playstate calls have no error handling; verified
  that a failure is *completely silent* (captured in a discarded future, no log), so scrobbles
  can vanish without trace. `onGetLibraryRoot`'s art-size hint is still frozen from whichever
  caller initializes the tree first — now including the `ensureTree()` default of 512 when
  voice/resumption arrives before any browse.
- `JellyfinApi.kt` — hand-built `Authorization` header duplicates what the SDK builds internally
  (drift risk). `JellyfinHiltModule.kt` — unscoped `@Provides` in `SingletonComponent` (new
  instance per injection site; harmless today). `signin/SignInActivityViewModel.kt:78` — the
  QuickConnect code round-trips String→Int→String (works today because the server generates
  100000–999999; would misdisplay/crash on leading-zero/short codes).

**Fixed (previously documented, verified fixed on main — don't re-report):** prefListener
lateinit-tree crash (guarded); resumption empty-`PLAYLIST_IDS_PREF` crash (blank-filtered); all
four `AlbumArtContentProvider` bugs from the original review (lock held during await → quick
claim-or-join; unsynchronized cleanup race → synchronized; 404 → `null` instead of FNFE + temp
file deleted; unbounded `uriMap` → 2000-entry synchronized LRU); `sendLogs()` Process
stream-deadlock (concurrent reader threads + `waitFor` result checked + `destroyForcibly`);
artist/genre album fetches missing `limit`.

**Closed as not-a-bug (verified):** `currentPlaybackTime`/`currentTrack` cross-thread visibility
and `resolveParentTracks` player-thread access (see Threading model); partial-cache HLS replay
mid-track failure (see Caching); resumption `IllegalSeekPositionException` from stale index
(media3 catches it — refuted at bytecode level); null `logUploadStatus` Snackbar crash
(cross-check verdicts conflicted; not tracked).

**Accepted residuals (deliberate or negligible, documented in code):** voice: URI-only blank-id
requests treated as empty query; shuffle flipped before fetch (failed artist fetch can leave
shuffle on); `resolvePlayAll` flips shuffle on the add-to-queue path; revalidation
`notifyChildrenChanged` itemCount one lower than browse returns for synthetic-row parents;
shuffle-all gathers albums sequentially (slow first tap on huge artists, disk-cached after);
host-passed mixed lists containing a Play All row shift startIndex by one when filtered;
Random tab re-draws per fetch (deliberately uncached — only matters on hosts that page).

## Roadmap

See `TODO.md`. Current goal order (owner-set): get this build running in the actual Polestar 3
(QuickConnect fixes are the gate — in-car sign-in *is* QuickConnect), then the robustness batch,
then Downloads (design conversation first; the HLS switch changed its shape — a download is a
Jellyfin download-API file fetch, not stream caching).
