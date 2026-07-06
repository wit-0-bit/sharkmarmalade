# TODO

Actionable tracker, reordered 2026-07-06 around the current goal: **get this build running in the
actual Polestar 3.** `CLAUDE.md`'s "Known issues" section is the verified reference state (with
file:line evidence and how each item was verified); this file is what to do about it, in order.

## 1. Road to the car

- [ ] **Fix the QuickConnect sign-in flow** (`signin/` — one contained batch, and the gate for
      everything else: in-car sign-in *is* QuickConnect on a screen you can't type on easily).
      - [ ] Wrap the polling coroutine's API calls in try/catch — today a >10-minute-old code
            (Jellyfin expires them) or any network blip **crashes the app** on the sign-in screen.
            Expired code should restart with a fresh code; transient errors should keep polling.
      - [ ] Exit the poll loop on successful authentication (today it re-authenticates every
            second forever, minting a new server session each time and re-firing `loggedIn`).
      - [ ] Guard against double-started loops (fires on every `onViewCreated`; back-and-forward
            navigation runs two loops sharing one `quickConnectSecret` field).
      - [ ] `SignInActivity`: release the MediaController, guard `future.await()`.
      - [ ] While in there: keep the QuickConnect code a String end-to-end (SDK type is String;
            `Integer.valueOf` works only because current servers generate 100000–999999).
- [ ] **Guard the empty-container tap** in `onSetMediaItems`: an empty `resolveMediaItems` result
      currently wipes the live queue AND overwrites saved resumption state (`savePlaylist([])`).
      Apply the same throw-instead guard the voice path already has.
- [ ] **Filter playlist children to audio**: `fetchItemChildren` + `MediaItemFactory.create()`
      make any video/mixed playlist deterministically unbrowsable/unplayable (one non-audio child
      → `UnsupportedOperationException`). Either request `includeItemTypes=[AUDIO]` for playlist
      children or skip unknown kinds in the builders instead of throwing.
- [ ] **Fix the slf4j binding** (`slf4j-api` 2.0.17 can't bind 1.7-style `slf4j-android` → all
      Jellyfin SDK logs are silently NOP'd). Cheap, and car debugging depends on logs: either pin
      `slf4j-api` 1.7.36 or use a 2.x-compatible Android provider.
- [ ] **Release signing + Play Console setup** (manual steps, owner's account):
      - [ ] Generate an upload keystore; add a `release` `signingConfig`.
      - [ ] Play Console developer account ($25 one-time) → new app → enable the Android
            Automotive OS release type (Advanced Settings, "Automotive only") → **closed testing**
            track (forums report Polestars need closed, not internal) → upload the signed build →
            add the car's Google account as a tester.
      - [ ] Verify whether the automotive closed track triggers driver-distraction review (policy
            has shifted over the years); if so it's a delay, not a blocker.
- [ ] **In-car test checklist** (what the emulator can't prove): real Assistant voice queries
      end-to-end; seek slider behavior on the actual head unit; streaming + cache behavior on
      cellular; scrobbling/playstate reporting to the server from the car; QuickConnect sign-in
      on the car screen.

## 2. Robustness batch (after the car works, unless something bites first)

- [ ] `JellyfinMediaTree.kt` — the **PARENT_KEY twins**, best fixed together:
      - [ ] cold-path `getItem()`/`revalidateItem()` rebuild tracks with `parent=null`, so a tap
            after RAM eviction / process restart queues a single track with no album context;
      - [ ] the RAM cache is keyed by raw id, so an unrelated browse (e.g. a playlist containing
            a favourited track) clobbers another context's `PARENT_KEY` (wrong tracklist queued).
      Likely shape: stop storing browse context in the shared cache — resolve the parent at tap
      time (e.g. from the DTO's own albumId) or namespace cached entries by context.
- [ ] `SettingsFragmentViewModel.kt` — try/catch around `clientLogApi.logFile` (expired token →
      crash from a Settings tap today).
- [ ] `JellyfinMediaTree.kt` — try/catch + distinguishable errors on the foreground fetch paths
      (cold-miss browse, random, search, item resolution); background revalidation already copes.
- [ ] `JellyfinMusicService.kt` — at minimum log `reportPlayback` failures (verified completely
      silent today: failed future is discarded, scrobbles vanish without trace).
- [ ] `JellyfinMediaTree.kt` — add a `limit` to `fetchFavourites` (the one remaining unbounded
      listing without an in-code justification).
- [ ] Real pagination: respect `page`/`pageSize` in `onGetChildren`/`onGetSearchResult`, add
      `startIndex` paging past the 120 cap (matters more now that Artists/Browse are permanent
      tabs). Deliberate exception: `fetchItemChildren` stays unbounded — those children are the
      playback queue, and truncating them changes what plays.
- [ ] `JellyfinAccountManager.storeAccount` — handle `addAccountExplicitly` returning false
      (same-username/new-server re-login currently bricks auth permanently, and there's no
      sign-out anywhere to recover). Cheap correct fix: update the existing account's userdata,
      or remove+re-add.

## 3. Nice to have

- [ ] `AlbumArtContentProvider.kt` — check the disk file **before** requiring a `uriMap` entry in
      `openFile` (art for host-persisted URIs — e.g. the resumption card after process death —
      currently throws despite cached bytes); move `createTempFile` inside the try (a disk-full
      throw currently leaves a dead latch that breaks that URI until process death); give the art
      cache a size cap (last unbounded cache; pairs well with the Downloads design).
- [ ] `JellyfinMediaLibrarySessionCallback.kt` — extend `ensureTree()` + the `isAuthenticated`
      guard to `onGetChildren`/`onGetItem`/`onSearch`/`onGetSearchResult` (verified non-crashing
      and unreachable from legacy hosts, so cosmetic today); type-check `onSetRating`'s cast and
      only stamp the rating onto the matching queue item.
- [ ] `JellyfinMediaTree.getItem()` — make the cold-miss check-then-act atomic (Mutex or Guava
      `get(key, loader)`); revalidation paths already dedup in-flight work.
- [ ] Rename the bitrate pref to "Audio quality" and drop or relabel the misleading
      "Direct stream" entry (it silently delivers the same AAC-256 transcode as "256 kbps").
      A true direct-play option is **deliberately deprioritized** (owner: always-transcode is the
      right behavior for the one real user; server has libfdk_aac) — only worth building if the
      server can't transcode someday. If it lands, remap existing stored `"Direct stream"` values
      so current installs keep transcoding.
- [ ] `JellyfinApi.auth()` — replace the hand-rolled Authorization header with the SDK's own
      builder. `auth/Authenticator.kt` — fix `getAuthToken`'s token type mismatch + `addAccount`
      contract (latent; the app only uses `peekAuthToken`). `JellyfinHiltModule` —
      `@Singleton`-scope the providers.
- [ ] `onGetLibraryRoot` — per-art-size `MediaItemFactory` instead of first-caller-wins freeze
      (now includes `ensureTree()`'s 512 default when voice/resumption arrives first).
- [ ] `ARTISTS`/`BROWSE_ARTISTS` disk-key aliasing (verified negligible: one duplicate ≤120-item
      fetch + small JSON file; a shared key would need notify fan-out to both parent ids).
- [ ] Confirm Genre stays as a Browse category (suggested, SDK-backed, never explicitly
      requested). `Year` remains available via `YearsApi` if ever wanted.

## Done / settled (keep for the record, don't re-report)

- [x] 2026-07-05..06: browse redesign (Random/Artists/Favourites/Browse + shuffle-all row), voice
      search (was actively broken — blank-id crash), disk caches (tree SWR + quota-sized audio),
      always-transcode AAC-256-over-HLS, seven-bug review batch (prefListener guard, resumption
      empty-pref, 4× AlbumArtContentProvider, sendLogs deadlock), Gradle wrapper committed,
      **Dorsal rebrand** (`elizardbeth.dorsal`, identity derived from applicationId everywhere).
- [x] Settled by verification (2026-07-06): `currentTrack`/`currentPlaybackTime` race and
      `resolveParentTracks` player-thread access are non-issues (everything runs on main —
      confirmed from concurrent-futures-ktx 1.3.0 bytecode); partial-cache HLS replay cannot fail
      mid-track (cached playlists carry no session ids — verified against real cached payloads
      from the emulator + Jellyfin server source); resumption `IllegalSeekPositionException`
      claim refuted (media3 catches it); null-Snackbar claim unresolved (conflicting verdicts),
      not tracked.
- Owner decisions on record: no upstreaming/publishing (fork is personal); no PR/issues to
  upstream for now — car first; transcode-always stays the default behavior; "Dorsal" chosen as
  the app name (2026-07-05).
