# TODO

Bugs and cleanup surfaced during the architecture review in `CLAUDE.md` (see there for full
detail, exact line numbers, and how each finding was verified). Grouped by how much it's worth
prioritizing, not by severity alone — a high-severity bug behind a rare edge case can rank below a
medium one that's trivial to fix and hit constantly.

## Fix soon

Real, concretely-reachable bugs with contained, low-risk fixes.

All seven below are **done** — fixed, adversarially verified against the actual diff, and confirmed
against a real `./gradlew :automotive:assembleDebug` build (BUILD SUCCESSFUL). Not yet committed —
changes are sitting in the working tree. See `git diff` for the three touched files.

- [x] `JellyfinMediaLibrarySessionCallback.kt` — guard the `prefListener` against `tree` not being
      initialized yet (currently throws `UninitializedPropertyAccessException` if a preference is
      changed before any browser has connected).
- [x] `JellyfinMediaLibrarySessionCallback.kt` `onPlaybackResumption` — handle the empty
      `PLAYLIST_IDS_PREF` case (`"".split(",")` yields `listOf("")`, not `[]`) so first-run/fresh-data
      playback resumption doesn't throw.
- [x] `AlbumArtContentProvider.kt` — don't hold the `inProgress` lock while blocked on the 15s
      `.await()`; it currently serializes *all* concurrent album-art loads app-wide, not just
      repeated requests for the same URI.
- [x] `AlbumArtContentProvider.kt` — move the `inProgress.remove()`/`countDown()` cleanup inside a
      synchronized block (it currently races unsynchronized against `contains()`/`put()` for other
      URIs on the same shared `HashMap`).
- [x] `AlbumArtContentProvider.kt` — handle non-200/null-body downloads (e.g. a normal 404 for
      missing art) without throwing an uncaught `FileNotFoundException` and leaking the temp file.
- [x] `AlbumArtContentProvider.kt` — cap or evict `uriMap` (companion object, unbounded for the
      whole process lifetime today). Fixed via a 2000-entry access-order `LinkedHashMap` with
      `removeEldestEntry` eviction — note the accepted tradeoff this introduces: once a URI's
      mapping is evicted, a request for it now throws `FileNotFoundException` even if the image
      bytes are still sitting in the disk cache (rare at 2000 entries, but real).
- [x] `SettingsFragmentViewModel.kt` `sendLogs()` — fix the `Process` stream-deadlock (blocking read
      of `errorStream` without concurrently draining `inputStream`, plus a discarded `waitFor()`
      timeout result); today this can leave "Uploading..." stuck forever.

Minor residuals noted by verification, not worth separate follow-up unless they bite in practice:
`File.createTempFile()` in `AlbumArtContentProvider.kt` sits just outside the new try/catch, so if
it throws (e.g. disk full) the `inProgress` entry for that URI still leaks — narrower than the
original bug, pre-existing, not introduced by this fix. In `SettingsFragmentViewModel.kt`, an
`IOException` inside a reader thread is swallowed by the thread's default handler rather than
surfaced, and if `waitFor` itself throws the two reader threads aren't explicitly joined/destroyed
in the catch block — both low-probability and non-blocking given `logcat -t 500` is bounded.

## Worth doing

Real correctness/robustness gaps, moderate effort.

- [ ] `JellyfinMediaTree.kt` — add a `limit` to `getItemChildren`/`getArtistAlbums`/`getFavourite`
      (currently unbounded, unlike every other listing query in the file).
- [ ] `JellyfinMediaTree.kt` — fix the `mediaItems` cache clobbering a track's `PARENT_KEY` across
      unrelated browse contexts (can queue the wrong parent's tracklist for playback).
- [ ] `JellyfinMediaTree.kt` / `JellyfinMediaLibrarySessionCallback.kt` — respect the host's
      `page`/`pageSize` in `onGetChildren`/`onGetSearchResult`, and add real `startIndex`-based
      pagination instead of the flat `maxItemsPerPage=120` cutoff. Worth doing together with the
      Artists/Albums redesign, since Artists becomes a permanent, prominent tab that inherits this cap.
- [ ] `JellyfinMediaTree.kt` — wrap the Jellyfin API calls in try/catch and surface a distinguishable
      error (instead of an opaque failed future indistinguishable from a real auth/data error).
- [ ] `SettingsFragmentViewModel.kt` — wrap `api.clientLogApi.logFile(content)` in try/catch (no
      `CoroutineExceptionHandler` exists anywhere in the app today).

## Nice to have

Lower priority: real but low-impact, speculative, or purely maintainability.

- [ ] `JellyfinMediaTree.kt` `getItem()` — make the cache-aside check-then-act atomic (Guava's
      `get(key, Callable)` or a `Mutex`) to avoid redundant network calls on concurrent access.
- [ ] `JellyfinApi.kt` `auth()` — replace the hand-rolled `Authorization` header with the Jellyfin
      SDK's own `AuthorizationHeaderBuilder` (already used internally by every SDK-mediated call) to
      remove the risk of the two implementations drifting apart.
- [ ] `JellyfinMediaLibrarySessionCallback.kt` — harden or document the `subscriptions` map's
      implicit single-thread assumption (currently safe in practice, not enforced in code).
- [ ] `JellyfinMediaLibrarySessionCallback.kt` `onGetLibraryRoot` — rebuild `tree`/`itemFactory` per
      differing art-size hint instead of freezing it from whichever controller connects first.
- [x] `JellyfinMediaTree.kt` `getChildren()` — cache resolved child *lists* per parent id, not just
      individual items, so revisiting a folder doesn't always re-hit the network. Done via the
      stale-while-revalidate disk cache (`MediaTreeDiskCache.kt`, see `CLAUDE.md` "Caching").
- [ ] `JellyfinHiltModule.kt` — consider `@Singleton`-scoping `provideJellyfin()`/`provideAccountManager()`
      so future mutable state doesn't silently diverge across injection sites.
- [ ] `JellyfinMusicService.kt` — double check `currentPlaybackTime`/`currentTrack` visibility across
      threads (may need `@Volatile`); flagged with lower confidence, unconfirmed dispatch behavior.

## Design decisions to make (not bugs)

- [x] Build the redesign as currently specced in `CLAUDE.md` — **done** on the `browse-redesign`
      branch (root = Random / Artists / Favourites / Browse; Browse = Artists / Genres / Albums /
      Recents / Playlists; pinned "▶ Play All" rows; `PREF_ALBUM_BEHAVIOUR` removed with a one-time
      orphaned-key purge). See the status callout in `CLAUDE.md` for accepted residuals.
- [ ] Confirm Genre is actually wanted as a Browse category — suggested (and backed by real Jellyfin
      SDK support via `MusicGenresApi`), not explicitly requested like the others. `Year` was also
      suggested and backed by real SDK support (`YearsApi`) but didn't make the latest list — left
      out for now, trivial to add later if wanted.
- [ ] Decide how to handle the depth tradeoff: Browse → Artist and Browse → Genre are 4 levels deep
      (one past Google's "avoid >3 levels" guidance, whose stated rationale is driver distraction).
      Artist has a compliant 3-level escape hatch via the direct root tab; Genre doesn't. See
      `CLAUDE.md`'s "Depth check" callout.
- [ ] Fix (or at least prioritize) the `mediaItems` cache-clobbering bug in Known Issues before/
      alongside this redesign — its most concrete repro scenario (Favourites vs. Playlist
      `PARENT_KEY` collision) stays fully live now that both are back in the app.
- [x] Implement the pinned "▶ Play All" synthetic row — done as part of the redesign, then
      narrowed per the app owner: artists only ("Shuffle all songs"); the album row was dropped as
      redundant with tapping track 1 (PARENT_KEY expansion already queues the full album). Low-severity residuals worth a later pass:
      shuffle-mode side effect on the add-to-queue path, sequential album gathering on first
      shuffle-all tap, notify itemCount off-by-one for synthetic-row parents.
- [x] Voice search — **done** (`voice-search` branch), but NOT via App Actions/`shortcuts.xml` as
      originally guessed: on AAOS Assistant uses the media session's play-from-search, which media3
      delivers as a blank-mediaId item in `onSetMediaItems` (previously crashed in `"".toUUID()`).
      See `CLAUDE.md` for the full mechanism and residuals. Needs a real-Assistant test in the car.
- [x] Better caching — **done** (audio `SimpleCache` 1 GiB LRU + stale-while-revalidate disk cache
      for the media tree; built, adversarially verified, uncommitted — see `CLAUDE.md` "Caching"
      for design, invariants, and accepted residuals). Remaining from that theme: the album-art
      cache still has no size cap/eviction.
- [ ] Downloads (offline playback) — flagged as wanted, not yet designed (see `CLAUDE.md`).
