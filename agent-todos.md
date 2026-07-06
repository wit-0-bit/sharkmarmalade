# Agent TODOs — overnight autonomous run (2026-07-06 ~02:00)

Code-only subset of `TODO.md`, done autonomously on branch `overnight-fixes` (base = `main` at
b95d625). Each group was implemented, built green (`./gradlew :automotive:assembleDebug`), and
committed locally. **Not pushed** (1Password SSH prompt unavailable overnight) — the owner pushes
in the morning. **Excluded** (need the owner): Play Console / release-signing, in-car testing, the
placeholder-hint UI bug.

## §1 Car-blockers

- [x] **QuickConnect sign-in batch** (`4c8a50c`)
  - [x] try/catch so an expired code / network blip can't crash the sign-in screen
  - [x] exit the poll loop on successful auth
  - [x] Job guard against double-started loops
  - [x] `SignInActivity`: release the MediaController; guard `future.await()`
  - [x] QuickConnect code kept a String end-to-end
- [x] **Empty-container-tap guard** in `onSetMediaItems` (`7599786`) — empty resolve now fails the
      request (queue + resumption preserved) instead of wiping them.
- [x] **Playlist audio-type filter** (`f8197d2`) — `fetchItemChildren` restricted to AUDIO + a
      `buildItem` belt that skips (not throws on) an unsupported child.
- [x] **slf4j** — first removed the deps as "dead" (`e6780ac`), then **runtime testing caught a
      fatal `NoClassDefFoundError`** and I restored them (`<restore>`). The SDK's okhttp engine logs
      via kotlin-logging → slf4j, so `slf4j-api` is a required runtime dep (loaded transitively, not
      visible in the jellyfin jars). Net effect: no dep change, plus a load-bearing comment so it
      can't be removed again. The original "binding is NO-OP" finding is true but harmless.

## §2 Robustness

- [x] **PARENT_KEY twins** (`f8197d2`) — track play-all context now derived from the intrinsic
      `albumId`, fixing both cold-path loss and cross-context clobbering. **Behavioral change:**
      tapping a track (from Favourites, search, or a playlist) now queues its **album** rather than
      the browse container — cleaner and deterministic; flagged for the owner to confirm.
- [x] **try/catch around `clientLogApi.logFile`** (`03bd4d8`).
- [x] **Foreground fetch error surfacing** (`7599786`) — onGetChildren/onGetItem/onSearch/
      onGetSearchResult map a failure to a distinguishable result (401 → sign-in, else logged
      generic error) instead of an opaque failed future.
- [x] **Log `reportPlayback` failures** (`abd5244`).
- [x] **`limit` on `fetchFavourites`** (`f8197d2`).
- [x] **Pagination** (`7599786`) — onGetChildren/onGetSearchResult honour the host's page/pageSize
      by slicing. NOTE: reachability past the 120 fetch cap (true server-side windowing) is still a
      follow-up; this stops the "ignore paging → duplicate pages" bug within the fetched set.
- [x] **`storeAccount` addAccountExplicitly-returns-false handling** (`6a6f59c`).

## §3 Nice-to-have

- [x] **AlbumArtContentProvider** (`a7741a5`) — disk-first lookup, `createTempFile` inside the try,
      256 MiB size cap in a dedicated `albumart/` subdir.
- [x] **`ensureTree()` + `isAuthenticated`** on the four browse/search entry points (`7599786`).
- [x] **`onSetRating`** (`7599786`) — type-checked cast, stamps the correct queue item.
- [x] **Atomic `getItem` cold-miss** (`f8197d2`) — per-id Mutex + double-check.
- [x] **Bitrate pref relabel** (`b33292e`) — "Audio quality", dropped the phantom "Direct stream",
      default 256 kbps, value migration. No real direct-play path (deliberate).
- [x] **`JellyfinApi.auth`** via the SDK's `AuthorizationHeaderBuilder` (`abd5244`).
- [x] **`Authenticator` `getAuthToken`/`addAccount`** contract fix (`6add677`, latent).
- [x] **`JellyfinHiltModule`** `@Singleton` scoping (`abd5244`).
- [ ] **Per-art-size `MediaItemFactory`** — **deferred, deliberately.** The tree/factory bake one
      art size and MediaItems are cached/shared across controllers; a correct per-controller fix
      needs a bigger refactor or throws away the cache on every differing hint. Near-zero value for
      a single-head-unit car. Left as-is (first hint wins) with this note.
- [ ] **`ARTISTS`/`BROWSE_ARTISTS` disk-key aliasing** — **deferred, deliberately.** Owner already
      flagged it negligible; a shared key needs notify fan-out to both parent ids. Cost today is one
      duplicate ≤120-item fetch + one small JSON file. Not worth the added complexity.

## Verification

Two passes:

1. **Runtime smoke test on the emulator** (elizardbeth.dorsal). Caught a **fatal regression the
   source review missed**: removing slf4j crashed on the first SDK HTTP call
   (`NoClassDefFoundError` — the SDK logs through kotlin-logging → slf4j). Restored the deps. Also
   caught a swallowed `CancellationException` logging a misleading warning on login teardown.
   End-to-end sign-in (ping → QuickConnect poll → username/password auth → service start → audio
   cache sizing → LOGIN command → clean teardown) verified clean.

2. **Adversarial workflow** (4 area reviewers → 2 independent verification lenses per finding).
   Four regressions confirmed and **fixed**:
   - QuickConnect guard blocked a newly-chosen server → skip only same-server re-entry, else restart.
   - Track tap could silently no-op / seek to index -1 → fall back to the tapped track, coerce index.
   - `itemLocks` grew unbounded → fixed 64-way striped locks.
   - Art-cache trim could delete a file mid-read → catch `FileNotFoundException`, re-download.
   One finding rejected (QuickConnect "expiry dead-end" — login still works via password; pre-change
   crashed). One design change flagged for the owner (below).

## For the owner to confirm

- **Behavioral change — RESOLVED (owner decision 2026-07-06).** The overnight PARENT_KEY=albumId
  fix briefly made *every* track tap queue the album. Owner: a tap should only queue the album
  *inside an album view*. Final behaviour:
  - Album view: tap a track → queue the whole album (positioned at the track). Unchanged.
  - Favourites / search: tap a track → play **just that track**. Rows are tagged `SINGLE_PREFIX`
    so the tap path skips album expansion; the queue strips the tag back to raw ids.
  - Favourites gained a pinned **"Play all favourites"** row (in order), like Artists' shuffle-all,
    shown only when the list has loose tracks.

  Voice search shares this path, and now honours a **spoken type keyword**: "play **album**
  Polygondwanaland" targets albums, "play **song** X" plays just that track (also artist/playlist).
  Falls back gracefully if the type is absent/mis-heard. Real-Assistant behaviour in the car is the
  open question — whether it passes the keyword through or pre-strips it and sets the focus extra
  (the code handles both).
