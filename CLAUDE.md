# Shark Marmalade

Third-party Jellyfin music client for Android Automotive OS (AAOS). Single Gradle module
(`automotive`), package `be.bendardenne.jellyfin.aaos`, ~2000 lines of Kotlin, no tests
(`testInstrumentationRunner` is configured in `build.gradle.kts` but no `test/` or `androidTest/`
source sets exist).

Key versions (see `build.gradle.kts` / `automotive/build.gradle.kts`): AGP 9.1.0, Kotlin 2.3.4 (KSP,
not kapt), Hilt 2.59.2, media3 1.9.2, Jellyfin SDK 1.8.6, compileSdk 36, minSdk 29, targetSdk 34,
JVM target 17.

## Dev environment

A fresh checkout is missing several things a normal Android Studio user gets for free. All fixed
once per machine, not per checkout:

- **JDK 17 is required for the Gradle build itself**, independent of the app's own JVM target.
  AGP 9's `androidJdkImage`/`jlink` transform breaks on newer JDKs (confirmed failing on JDK 26 —
  `jlink` errors on `core-for-system-modules.jar`). Fix: install `openjdk@17` and point Gradle at it
  via `~/.gradle/gradle.properties` → `org.gradle.java.home=<path to openjdk@17>`. Don't change the
  system `java`/`JAVA_HOME` — just Gradle's.
- **The Gradle wrapper is not committed** (checked full git history — it never was, and nothing in
  `.gitignore` excludes it either). Generate it with `gradle wrapper --gradle-version <version>`
  matching the AGP/Gradle pairing in `build.gradle.kts` (currently Gradle 9.5.1).
- **`local.properties`** (gitignored) needs `sdk.dir=<path to Android SDK>`.
- **AAOS emulator**: a bare SDK install has no `cmdline-tools` (so no `sdkmanager`/`avdmanager`).
  Download the commandline-tools package separately, accept licenses, then install an automotive
  system image, e.g. `system-images;android-34-ext9;android-automotive-playstore;arm64-v8a`, and
  create an AVD with device profile `automotive_1024p_landscape`.
- Verified end-to-end this session: `./gradlew :automotive:assembleDebug` builds, the AAOS emulator
  boots and reports `android.hardware.type.automotive`, and the debug APK installs via `adb install`.

## Module layout

```
automotive/src/main/java/be/bendardenne/jellyfin/aaos/
  JellyfinMusicService.kt          MediaLibraryService: owns the ExoPlayer + MediaLibrarySession, playback reporting/polling
  JellyfinMediaLibrarySessionCallback.kt   MediaLibrarySession.Callback: browse/search/playback-queue entry points
  JellyfinMediaTree.kt             Lazy, cache-backed adapter: Jellyfin REST API -> media3 MediaItem tree (RAM + disk, stale-while-revalidate)
  MediaTreeDiskCache.kt            Disk cache of raw BaseItemDto JSON (children lists + per-track items), namespaced per server+token
  MediaItemFactory.kt              Builds MediaItem/MediaMetadata per BaseItemKind (artist/album/playlist/track)
  AlbumArtContentProvider.kt       ContentProvider that proxies+caches album art so ExoPlayer/media UI can load it by content:// URI
  AudioCache.kt                    Process-singleton media3 SimpleCache (LRU, sized from the system cache quota) wrapped around the streaming DataSource
  JellyfinApi.kt                   Auth header construction for direct (non-SDK) HTTP requests
  JellyfinAccountManager.kt        Wraps Android AccountManager for the stored Jellyfin account/token
  JellyfinHiltModule.kt            Provides Jellyfin/JellyfinAccountManager singletons
  CommandButtons.kt                Repeat/shuffle custom session command buttons
  SharkMarmaladeConstants.kt       Shared string constants (log tag, preference keys)
  auth/                            AbstractAccountAuthenticator + its bound Service
  signin/                          Sign-in Activity/Fragments/ViewModel (server URL, username/password, QuickConnect)
  settings/                        Settings Activity/Fragment/ViewModel (album behaviour, bitrate, log upload)
```

## Architecture: the media browse tree

`JellyfinMusicService` (a `MediaLibraryService`) builds one `ExoPlayer` + one
`MediaLibrarySession` with `JellyfinMediaLibrarySessionCallback` as its callback — this is the
standard media3 integration point AAOS host UIs talk to. The callback is thin and mostly delegates
to `JellyfinMediaTree`, which lazily fetches and caches (`Guava Cache<String, MediaItem>`) nodes
from the Jellyfin REST API. `MediaItemFactory` turns a Jellyfin `BaseItemDto` into a media3
`MediaItem`/`MediaMetadata`, one builder per `BaseItemKind` (artist/album/playlist/track).

**Root today has exactly 4 static children**: Latest Albums, Random Albums, Favourites, Playlists
(`JellyfinMediaTree.kt:58-64`). There is no root-level "Artists" entry — even though the
artist→albums plumbing already exists and works (`getArtistAlbums()`, `MediaItemFactory.forArtist()`),
it's only reachable today via Favourites (if an artist is favourited) or Search.

**Album nodes are either browsable (show tracks) XOR playable (play the whole album)**, controlled
by a global user preference `PREF_ALBUM_BEHAVIOUR` (`"Play"`/`"Expand"`, default `"Play"`) —
`MediaItemFactory.kt` `forAlbum()`: `.setIsBrowsable(preferenceIsExpand).setIsPlayable(!preferenceIsExpand)`.

This mutual exclusivity is **this app's own design choice, not a platform constraint** — verified
this session against the actual media3 1.9.2 sources and primary Android documentation:

- `MediaMetadata.isBrowsable`/`isPlayable` are independent nullable booleans; media3-session's
  `LegacyConversions.java` ORs them into separate bitflags (`FLAG_BROWSABLE`, `FLAG_PLAYABLE`) when
  bridging to the legacy protocol AAOS hosts actually speak. Both can be `true` at once.
- Android's own docs (developer.android.com/training/cars/media) state verbatim: *"A media item can
  have one or both of these flags. A media item that can be browsed for and played operates like a
  playlist. You can select the item to play all of its descendants, or you can browse its
  descendants."* — i.e. "tap to play the whole album, or drill into its tracks" is the officially
  sanctioned pattern for exactly this case.
- AAOS hosts pass a root-tab-count hint (`MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT`, forwarded
  from the legacy `BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT`; historically ~4 root tabs is the
  common host budget) that this app currently never reads (`onGetLibraryRoot` only reads the
  art-size hint). Root is already at exactly 4 — adding a 5th tab risks it being silently dropped
  or made less discoverable on hosts that enforce a tight limit.
- Google's car design guidance: *"Avoid browsable content that extends more than three levels deep
  from the top level."* Root → Artists → Albums → Songs sits exactly at that ceiling — don't add a
  4th layer (e.g. Genre, Disc) under it. Note `Favourites → Artist → Albums → Tracks` is *already* a
  live 4-level path today via the existing artist plumbing — a pre-existing exception, not something
  a new Artists tab introduces.

## Known issues (found + adversarially verified this session)

Each finding below was independently re-read against the actual file/lines — either by a separate
reviewer agent whose verdict was checked against the code, or (for the concurrency-focused pass,
which errored out once and was re-run) directly by whoever wrote this doc.

**High severity**
- `JellyfinMediaLibrarySessionCallback.kt`: `tree` is a `lateinit var` only assigned inside
  `onGetLibraryRoot`. The `SharedPreferences` listener that calls `tree.evictCache()` is registered
  unconditionally in `init{}`, which runs at construction — before any browser has connected. If the
  user opens Settings and changes a preference before any browse call has ever happened (e.g. right
  after install, or a service restart for playback resumption with no browser yet attached),
  `tree.evictCache()` throws `UninitializedPropertyAccessException`, uncaught, from inside the
  preference-change callback.
- `JellyfinMediaTree.kt` (`getItemChildren`, `getArtistAlbums`, `getFavourite`): these three query
  paths pass no `limit` to `itemsApi.getItems`, unlike every other listing call in the file — a
  large album/playlist/artist discography or a big Favourites list fetches and returns an unbounded
  result set in one `onGetChildren()` response over Binder IPC.
- `AlbumArtContentProvider.kt` `openFile()` has several compounding bugs around the same
  `inProgress`/`uriMap` state (lines 46-97):
  - The `synchronized(inProgress)` block (lines 58-69) holds the lock **while blocking on
    `.await(15, TimeUnit.SECONDS)`** (line 61). `inProgress` is one shared map for *every* URI, so a
    thread waiting on art for URI A blocks any other thread that's merely trying to check/start a
    *completely unrelated* URI B — the lock serializes all concurrent album-art loads app-wide
    whenever any two happen to overlap, not just repeated requests for the same URI.
  - The cleanup `inProgress.get(remoteUri)?.countDown(); inProgress.remove(remoteUri)` (lines 93-94)
    runs **outside** that synchronized block (it's inside the unrelated `.use{}` at line 77) — so a
    `remove()` on the plain `HashMap` can race unsynchronized against another thread's
    `synchronized(inProgress) { contains/put }` for a different URI, which is undefined behavior for
    `HashMap` (lost/corrupted entries).
  - On a non-200 response or null body (line 78), `file` is never created (only `tmpFile.renameTo(file)`
    on the success path, line 88) and the downloaded `tmpFile` (line 71) is never cleaned up, but the
    latch still counts down (line 93 runs regardless) and line 97 unconditionally does
    `ParcelFileDescriptor.open(file, ...)` — every waiter, plus the original caller, gets an uncaught
    `FileNotFoundException` for what's actually a normal "no art for this item" 404 from the server,
    and a stray temp file is leaked into cache storage every time this happens.
  - `uriMap` (line 29, companion object) is a plain unsynchronized `HashMap` populated by `mapUri()`
    (called from every `MediaItemFactory.artUri()` resolution) with **no eviction anywhere in the
    codebase** — it grows for the entire process lifetime, unrelated to `tree.evictCache()` (which
    only clears the media-item cache). Writes happen from whatever thread a suspend call resumes on;
    reads happen from `openFile()` on ContentProvider binder threads, which Android may invoke
    concurrently for different URIs — concurrent unsynchronized `put`s can lose entries, in which
    case a later `openFile()` for a URI the UI was "just given" throws `FileNotFoundException`.
- `SettingsFragmentViewModel.kt` `sendLogs()`: `Runtime.exec("logcat ...").waitFor(10, SECONDS)`
  discards whether it actually timed out, then unconditionally does a blocking read of `errorStream`
  before ever touching `inputStream` — the classic Java `Process` deadlock (neither stream is
  drained concurrently; if `logcat`'s stdout fills its pipe buffer before exiting, it blocks forever
  and the "Uploading..." status never resolves).

**Medium severity**
- `JellyfinMediaTree.kt` `getLatestAlbums`/`getRandomAlbums`/`getPlaylists` cap at
  `maxItemsPerPage=120` with no `startIndex`, and `onGetChildren` ignores the host's `page`/`pageSize`
  entirely — content beyond the first 120 is permanently unreachable, and `RANDOM_ALBUMS` in
  particular re-draws a fresh non-deterministic random set on every call, breaking hosts that expect
  stable paging.
- `JellyfinMediaTree.kt`: the shared `mediaItems` cache is keyed only by raw item ID with no
  browse-context namespacing. A track cached with `parent=FAVOURITES` can later be overwritten
  (last-write-wins) with `parent=<playlistId>` by an unrelated browse of a playlist containing the
  same track — tapping that track from the still-displayed Favourites screen can then queue the
  wrong parent's tracklist for playback.
- `JellyfinMediaLibrarySessionCallback.kt` `onPlaybackResumption`: on first-ever launch (or after
  clearing data), `PLAYLIST_IDS_PREF` defaults to `""`; `"".split(",")` yields `listOf("")` (not an
  empty list), so `tree.getItem("")` runs and hits `"".toUUID()`, which throws — breaking playback
  resumption for every user's first resumption attempt instead of resuming nothing gracefully.
- `JellyfinMediaTree.kt` `getItem()`: unsynchronized check-then-act across a suspending network call
  (`getIfPresent` → await → `put`) — two concurrent callers requesting the same uncached id both
  issue redundant network calls, with the second silently clobbering the first's cache entry.
- None of `JellyfinMediaTree`'s API calls are wrapped in try/catch, nor are their only callers — a
  transient network blip surfaces as an opaque failed future with no retry and no way to distinguish
  it from a real error.
- `JellyfinMediaLibrarySessionCallback.kt`: `subscriptions` (a plain `mutableMapOf<MediaLibrarySession,
  MutableSet<String>>`) is mutated in `onSubscribe`/`onUnsubscribe` and iterated in `prefListener`
  (the `SharedPreferences` change callback) with no lock, `ConcurrentHashMap`, or documented
  thread-confinement. In the current wiring this likely stays safe in practice — media3 dispatches
  session callbacks on the session's Looper (main thread here, since the session is built in
  `onCreate()` with no custom executor) and Android's `SharedPreferences` always notifies listeners
  on the main thread regardless of which thread called `apply()` — but that safety rests entirely on
  two implementation details this file doesn't enforce or document; it would silently break if either
  changed (e.g. a custom session executor, or another off-main-thread mutator added later).
- `JellyfinApi.kt` `auth()` hand-builds the `Authorization` header string that the Jellyfin SDK
  already builds correctly (and with proper value-encoding) internally for every SDK-mediated call —
  two independent implementations of the same scheme that can silently drift apart. Consumed by
  `JellyfinMusicService.onLogin()` for the raw audio-streaming data source (not album art, which
  doesn't use this header).
- `SettingsFragmentViewModel.kt`: `api.clientLogApi.logFile(content)` has no try/catch (unlike the
  `Runtime.exec` call just above it) and no `CoroutineExceptionHandler` exists anywhere — a failed
  log upload (expired token, dropped connection) propagates uncaught.

**Low severity**
- `JellyfinMediaTree.kt`/`getArtistAlbums`/`getFavourite`/generic children: missing `limit` (see
  above) is also just a general inconsistency vs. the rest of the file.
- `JellyfinMediaLibrarySessionCallback.kt` `onGetLibraryRoot`: `tree`/`itemFactory` (and the art-size
  hint baked into it) are built exactly once per process, guarded only by an unsynchronized
  `if (!::tree.isInitialized)` check-then-act — from whichever controller connects first. A second
  controller with a different art-size hint (e.g. phone vs. car head unit) is served art sized for
  the first controller for the rest of the process lifetime; if two controllers' first calls ever
  truly race, the unsynchronized guard could also construct the tree twice, with the second
  constructor call's result silently winning.
- `JellyfinMediaTree.kt` `getChildren()`: ~~child *lists* for a given parent are never memoized~~ —
  addressed by the disk cache (see "Caching" below): child lists are now served from disk with a
  background revalidation. There's still no *RAM* memoization of lists, so each browse does one
  small-file disk read, which is fine.
- `JellyfinHiltModule.kt`: `provideJellyfin()`/`provideAccountManager()` are unscoped `@Provides`
  methods in `SingletonComponent` — Hilt constructs a new instance per injection site, not one shared
  instance. Harmless today (both are effectively stateless), but a maintainability trap if either
  ever gains mutable state.
- `JellyfinMusicService.kt`: `currentPlaybackTime`/`currentTrack` are plain (non-`@Volatile`) fields
  written every second by the main-thread playback poller and read from `reportPlayback()`, which
  runs via `SuspendToFutureAdapter.launchFuture` and may resume on a different thread once its
  network call suspends. No lock or `@Volatile` guarantees the reading thread sees the latest write.
  Flagged with lower confidence — the exact dispatch behavior of `SuspendToFutureAdapter` wasn't
  independently confirmed — but if it races, the practical effect is a stale track ID/position
  reported to the Jellyfin server on transition (a scrobbling-accuracy bug, not a crash).

## Recommended direction: Artists → Albums → Songs drilldown

> **Status (2026-07-05): IMPLEMENTED** on the `browse-redesign` branch (built, adversarially
> verified, installed to the emulator). The section below is the spec it was built from, with one
> post-implementation amendment from the app owner: the **"▶ Play album" row was dropped** —
> tapping any track already queues the whole album in order via the PARENT_KEY expansion, so the
> row was redundant with tapping track 1. Only artists get a pinned row ("▶ Shuffle all songs",
> which has no equivalent path). The album flavor of `playAllRow()`/`resolvePlayAll()`/
> `getItem(PLAY_ALL:...)` is kept so a host holding a stale cached row still resolves gracefully. Known
> accepted residuals from verification: `resolvePlayAll` also flips shuffle mode on the
> add-to-queue (`onAddMediaItems`) path; the revalidation `notifyChildrenChanged` itemCount is
> one lower than what browse returns for parents with a synthetic row (hint-only, harmless);
> shuffle-all gathers albums sequentially (slow first tap on huge artists, disk-cached after);
> a host-passed mixed list containing a Play All row would have its startIndex shift by one when
> the row is filtered. Note: the `limit` originally added to `fetchItemChildren` was removed
> during verification — capping an album/playlist's *playback* fetch at 120 silently truncates
> queues, which is worse than the unbounded fetch; real pagination remains the follow-up.

The app owner wants a more traditional Artists → Albums → Songs curation/drilldown. This section
started from a 3-proposal judged comparison (see git history of this file for that fuller
analysis) and has since been narrowed down across several rounds of direct feedback from the app
owner about what they'd actually use. The current, narrowed-down direction:

**Root menu — 4 tabs.** The organizing principle (app owner's framing): frequently-used destinations
get their own one-tap root tab; everything else lives inside **Browse**, which is deliberately the
overflow/catch-all tab for lower-priority categorizations. **Artist** is important enough to get
both — a fast direct root tab *and* a Browse category — since it's both heavily used (deserves the
one-tap path) and a natural entry in the general categorization list. This isn't duplicated code,
just two `MediaItem` tree node IDs that both delegate to the same underlying `getArtists()`.

1. **Random** (existing `RANDOM_ALBUMS`, unchanged) — serendipity.
2. **Artists** (new, direct/fast path) — `artistsApi.getAlbumArtists()` (already used by `search()`)
   → existing `getArtistAlbums()`/`forArtist()` plumbing, unchanged. Same underlying data as
   Browse's "Artist" category below, just reachable in one tap instead of two.
3. **Favourites** (existing `getFavourite()`, unchanged) — back in, per direct request.
4. **Browse** (new) — a category picker. Opening it shows a small set of categorization choices,
   and picking one drills into that categorization's own hierarchy down to tracks:
   - **Artist** — same plumbing as the root **Artists** tab above (see rationale above for why
     both exist).
   - **Genre** — `MusicGenresApi.getMusicGenres()` to list genres, then
     `itemsApi.getItems(includeItemTypes=[MUSIC_ALBUM], genreIds=[...])` for that genre's albums —
     the exact same filter-by-id shape `getArtistAlbums()` already uses with `albumArtistIds`.
     (Confirmed present in the `org.jellyfin.sdk:jellyfin-api-jvm:1.8.6` dependency this project
     already uses, not assumed.)
   - **Album** — flat listing, `itemsApi.getItems(includeItemTypes=[MUSIC_ALBUM], sortBy=SORT_NAME)`
     (same call shape as `getRandomAlbums()`, different sort). Lives in Browse specifically because
     it's lower-priority than Artist — the app owner doesn't navigate by remembering album names —
     but still worth having available.
   - **Recents** — absorbs today's *Latest Albums* (`userLibraryApi.getLatestMedia()`, unchanged) as
     a Browse category instead of its own root tab.
   - **Playlists** (existing `getPlaylists()`, unchanged) — back in, but demoted from root to a
     Browse category rather than dropped.

   `Year` was suggested in an earlier round as a possible additional category (`YearsApi.getYears()`
   is real, confirmed SDK support) but isn't in the app owner's most recent list above — left out
   for now; trivial to add later using the same `itemsApi.getItems(years=[...])` shape if wanted.

**Depth check.** Google's stated rationale for "avoid browsable content more than three levels
deep" is driver distraction (confirmed verbatim from developer.android.com: *"minimize distraction
for drivers by making it easy to quickly find their favorite media while driving"*), not a
technical limit. Root → **Artists** (direct tab) → Artist → Albums → Track is 3 levels, matching
the guidance — the direct root tab gives Artist a compliant fast path. Root → **Favourites** →
Artist → Albums → Track is a pre-existing 4-level exception (unrelated to this redesign — it's live
in the app today). Root → **Browse** → {Genre, Album, Recents, Playlists} → ... is 3 levels for
Album/Recents/Playlists (flat lists, no extra "pick one" step), but *4* levels for **Browse →
Artist** and **Browse → Genre** specifically (picker → list → that entity's albums → tracks) — one
level past the guidance. Worth noting this is now a smaller practical concern than before: since
Artist already has a compliant, one-tap-faster path via the direct root tab, nobody's forced through
the deeper Browse route for Artist specifically — Genre remains the one category with no
fast-path alternative and a genuine 4-level depth.

**Playback affordance: pinned "▶ Play All" row, not a preference or a dual-flag tap gesture.**
Earlier drafts of this doc had `forAlbum()` set `isBrowsable=true`/`isPlayable=true` simultaneously
(a real, officially-supported media3 combination — see Architecture section above) and relies on
the *host* to render some kind of distinct play affordance for that combination. That reliance
turned out to be the crux problem with the "play button on a tile" idea below: **this app doesn't
render its own browse tiles at all** — the car's own media UI does (confirmed: `androidx.car:car`
is a declared dependency but is never actually imported/used anywhere in this codebase — there is
no Car App Library screen, no custom rendering surface, nothing). So whether a host draws a visible
play affordance for a dual-flag item, and where, is unverifiable and inconsistent across this app's
actual compatible-car list (Polestar, Volvo, Chevy, etc.) — there's also no touch-coordinate data of
any kind available to this app; the whole `MediaBrowserService`/`MediaLibraryService` protocol is
purely semantic (browse this ID / play this ID), by design, so it works identically whether the
host is a phone, a watch, or a car head unit.

**Resolution:** make Artist and Album nodes purely browsable (`isBrowsable=true`, `isPlayable=false`
— no change needed for Artist, `forArtist()` is already this way today) and prepend an explicit,
ordinary playable `MediaItem` as the first child when browsing into one — "▶ Shuffle All Songs" for
an artist, "▶ Play Album" for an album. This is just a normal list row, not a special flag
combination or an icon, so it renders identically on every host, with no ambiguity about where a
play affordance is or whether it's visible at all. This is a strictly more reliable mechanism than
depending on host-rendered dual-flag affordances, and it obsoletes `PREF_ALBUM_BEHAVIOUR` entirely
rather than "retiring" it — there's no longer a Play-vs-Expand choice to make: browsing always shows
the list, and playing is always an explicit, visible, ordinary first row.

- For **Artist**, this is a functionally new capability, not just a UX nicety: today there is no
  code path anywhere that plays "everything by this artist" — the pinned row needs to gather all of
  that artist's tracks across all their albums (compose `getArtistAlbums(artistId)` with a
  per-album `getChildren()` call and flatten) and turn shuffle mode on
  (`player.shuffleModeEnabled = true`, the same flag `CommandButtons.kt`'s existing shuffle toggle
  uses) before queuing.
- For **Album**, this is mostly a discoverability nicety: tapping any individual track already
  queues the rest of that album in order today, via the existing
  `isSingleItemWithParent()`/`expandSingleItem()`/`PARENT_KEY` mechanism in
  `JellyfinMediaLibrarySessionCallback.kt`. The pinned row just makes "play the whole thing, in
  order, from the top" an explicit, unambiguous first tap instead of an implicit side-effect of
  tapping track 1.
- **Implementation note:** the synthetic row needs a recognizable `mediaId` (e.g. a
  `"PLAY_ALL:<parentId>"` prefix) that `onSetMediaItems`/`onAddMediaItems` intercepts *before* the
  existing generic `isSingleItemWithParent`/`PARENT_KEY` path, resolving it to "all real
  (non-synthetic) descendant tracks of `<parentId>`" — with shuffle forced on only for the
  artist-flavored variant. It must be filtered out of `resolveMediaItems()`'s normal recursion so it
  never itself ends up queued as a fake "track."

**Files that change:** `MediaItemFactory.kt` (new `ARTISTS`/`BROWSE` root-level consts + builders,
plus one builder per Browse category node — Genre/Album/Recents; `forAlbum()` becomes
`isBrowsable=true`/`isPlayable=false` unconditionally, no more preference read; `forFavourite()`/
`forPlaylist()`-equivalent builders unchanged), `JellyfinMediaTree.kt` (root's `getChildren(ROOT_ID)`
gains `ARTISTS`/`BROWSE` alongside existing `RANDOM_ALBUMS`/`FAVOURITES`; new `getBrowseCategories()`
for the picker's children (Artist/Genre/Album/Recents); a new root-level `getArtists()` shared by
both the direct **Artists** tab and Browse's **Artist** category (same function, two tree node IDs
pointing at it); new `getGenres()` plus a generic "albums filtered by genreId" helper reusing the
`getArtistAlbums()`-style `itemsApi.getItems(...)` shape; `getAlbums()` (flat, sorted) for Browse's
Album category; prepend the synthetic "▶ Play All" `MediaItem` in `getArtistAlbums()` and in the
album branch of `getItemChildren()`; **keep** `getFavourite()`/`getPlaylists()`/`getLatestAlbums()`
as-is — `getFavourite()` stays wired to the root `FAVOURITES` tab unchanged, `getPlaylists()`'s call
site moves from root to Browse's Playlists category, `getLatestAlbums()`'s call site moves from root
to Browse's Recents category), `JellyfinMediaLibrarySessionCallback.kt` (intercept `PLAY_ALL:` synthetic
ids in `onSetMediaItems`/`onAddMediaItems`; narrow/remove the `prefListener`'s album-preference
branch), `SharkMarmaladeConstants.kt` (remove `PREF_ALBUM_BEHAVIOUR`/`EXPAND`/`PLAY`),
`SharkMarmaladeApplication.kt` (one-time purge of the orphaned `"album_behaviour"` key),
`preferences.xml`/`arrays.xml`/`strings.xml` (delete the `Library` preference category and its
array/string), plus new drawables for the Artists and Browse tabs and Browse's category nodes.

**Voice search shortcut (new, addresses a separate friction point).** Confirmed via grep: this app
has zero Google Assistant **App Actions** integration today — no `shortcuts.xml`, no `actions.xml`,
nothing in the manifest. That means the only way to search by voice today is host-UI-mediated (tap
search, tap the mic/speech-to-text icon, then speak). Declaring an `actions.intent.PLAY_MEDIA`
capability (via `shortcuts.xml`, tied to the existing `onSearch`/`onGetSearchResult` callback) would
let a user say *"Hey Google, play [artist] on Shark Marmalade"* directly, with no in-app navigation
at all — this is a real, addable feature, not a design change to the browse tree.

**Explicit tradeoffs, not hidden:**
- Root grows from today's 4 tabs to 4 different tabs (Random/Artists/Favourites/Browse, replacing
  Random/Latest/Favourites/Playlists) — same count, so no new tension with AAOS host root-tab
  budgets, but Playlists moving from root into Browse is a real demotion (one more tap than today)
  in exchange for Artists getting promoted to a direct tab.
- Bringing back the `mediaItems` cache-clobbering bug's most concrete repro scenario (see Known
  Issues): with both Favourites and Playlists live again, a track cached with `parent=FAVOURITES`
  can still be overwritten by `parent=<playlistId>` from an unrelated playlist browse, same as
  originally found — this is a pre-existing bug, not introduced by this redesign, but worth fixing
  before/alongside it rather than after, since this design keeps the exact repro path alive.
- The unpaginated `maxItemsPerPage=120` cap (see Known Issues) becomes more consequential once
  Artists/Browse's category lists are permanent, prominent tabs — file as a follow-up, not a
  blocker.
- The pinned "▶ Play All" row needs to be excluded from `resolveMediaItems()`'s generic recursion
  and from anywhere a caller might treat "every child of this node" as real content (e.g. any future
  pagination/count logic) — it's a real `MediaItem` in the list, not metadata, so anything iterating
  `getChildren()` needs to know it's there.

## Requested future features (not yet designed)

Flagged by the app owner as worth doing, deliberately **not** designed or implemented yet — noted
here so the context isn't lost, not as a spec:

- **Downloads (offline playback).** No download/offline path exists today — `JellyfinMediaTree`
  always streams via `MediaItemFactory.forTrack()`'s `universalAudioApi.getUniversalAudioStreamUrl`,
  and `JellyfinMusicService` builds one plain `ExoPlayer` with no `DownloadManager`. Media3 has a
  dedicated offline module (`media3-exoplayer-workmanager` + `androidx.media3.exoplayer.offline.DownloadManager`)
  built for exactly this; Jellyfin's own API supports fetching original/transcoded files for local
  storage. Needs real design work: which node types are downloadable (tracks only, or whole
  albums/playlists), where files live relative to `AlbumArtContentProvider`'s existing cache-dir use,
  how a downloaded track's `MediaItem` differs from a streamed one (local `Uri` vs. the current
  streaming `Uri`), and how download state interacts with the existing per-item Guava cache in
  `JellyfinMediaTree`.
- **Caching (implemented 2026-07, this session).** Two disk caches were added, both zero-new-runtime-dependency
  (`media3-datasource` was already transitive; `kotlinx-serialization-json` was already on the
  runtime classpath via the Jellyfin SDK and only needed an explicit `implementation()` line for
  compile-time visibility):
  - **Audio**: `AudioCache.kt` holds a process-singleton `SimpleCache` (`cacheDir/audio`,
    `LeastRecentlyUsedCacheEvictor` sized to `min(StorageManager.getCacheQuotaBytes(), 5 GiB)` —
    the quota is usage-weighted and dynamic, so it grows as the app gets used; staying under it
    means the OS clears this app's cache last under storage pressure. Falls back to 2 GiB if the
    quota is unreadable. `StandaloneDatabaseProvider`), wired in
    `JellyfinMusicService.onLogin()` via `CacheDataSource.Factory` around the authed
    `DefaultHttpDataSource.Factory`, with `FLAG_IGNORE_CACHE_ON_ERROR` so cache I/O failures degrade
    to plain streaming. The singleton is deliberately never released (`SimpleCache` throws on
    double-open of the same dir, and the Service can be recreated within a process). A defensive
    `CacheKeyFactory` strips session-volatile query params — though inspection of the SDK 1.8.6
    bytecode confirmed the universal-audio URL currently has none (auth is header-only; params are
    just container/bitrate/transcoding, so a bitrate pref change naturally yields distinct keys).
    Note (2026-07): `forTrack()` now requests **always-transcode to AAC 256 over HLS**
    (`container=ts` — matches no music file, so nothing direct-plays; `audioCodec=aac`,
    `transcodingContainer=ts`, `transcodingProtocol=MediaStreamProtocol.HLS`, `audioBitRate` from
    the bitrate pref or `TRANSCODE_BITRATE=256_000`), with `media3-exoplayer-hls` added and an
    explicit `APPLICATION_M3U8` MIME type on track MediaItems (the `/universal` URL gives ExoPlayer
    no hint the response is a playlist). Why HLS: a plain transcoded HTTP stream is an unseekable
    chunked pipe (an Opus-over-HTTP variant was tried first — hosts hide the seek slider entirely);
    HLS playlists carry duration and the server transcodes from any seek point on demand. Why AAC:
    the owner's server ffmpeg has `libfdk_aac` (verified via ssh — Jellyfin auto-prefers it), so
    quality is transparent at 256 kbps, equal to Opus. Verified live: AAC decoder initializes,
    real position/bufferedPosition in the session state (the seekability signal hosts need),
    ~1 s start vs ~35 s for the Opus pipe. HLS segment URLs' volatile params (`PlaySessionId`)
    are already stripped by `AudioCache`'s cache-key factory.
  - **Media tree (stale-while-revalidate)**: `MediaTreeDiskCache.kt` persists raw `BaseItemDto`
    JSON (never `MediaItem`s — those embed stream URLs and preference-dependent values and are
    always rebuilt through `MediaItemFactory`) under `cacheDir/tree/<sha256(baseUrl|accessToken)
    prefix>/`, atomic-ish temp+rename writes, corrupt reads deleted and treated as misses.
    `JellyfinMediaTree.childrenWithCache()` serves disk hits immediately and kicks a throttled
    (30 s, in-flight-deduped) background revalidation; a fingerprint diff (`id|name|isFavorite`
    per item, order-sensitive, deliberately ignoring playCount churn) triggers
    `onChildrenUpdated` → `notifyChildrenChanged` (posted to the main looper in the session
    callback, mirroring the prefListener pattern) only on real changes. Loop-safety invariant:
    fresh data is written to disk *before* the notify, and notify is skipped entirely if the disk
    write failed. `RANDOM_ALBUMS` and `search()` are deliberately uncached. Tracks are also
    persisted individually so playback resumption works from disk after a cold start; `getItem()`
    disk hits get their own throttled background refresh. `evictCache()` stays RAM-only on
    purpose: pref changes only affect `MediaItem` construction, so rebuilt items pick up new prefs
    from disk DTOs without a network round-trip.
  - **Known accepted residuals**: the namespace uses `accessToken` because SDK 1.8.6's `ApiClient`
    has no `userId` property — so a re-login to the *same* account issues a new token → one cold
    cache + an orphaned namespace dir (bounded garbage, OS-clearable). Two concurrent cold-miss
    `getChildren` for the same key can still double-fetch (miss path has no in-flight guard —
    same as the pre-existing `getItem()` behavior). The art cache (`AlbumArtContentProvider`)
    still has no size cap/eviction — that's now the only unbounded cache left; worth doing
    together with Downloads.
