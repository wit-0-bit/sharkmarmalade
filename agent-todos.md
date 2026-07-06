# Agent TODOs — overnight autonomous run (2026-07-06 ~02:00)

Code-only subset of `TODO.md` that can be done without the owner. **Excluded** (need the owner):
Play Console / release-signing setup, in-car testing, the placeholder-hint UI bug (owner said
skip; couldn't reproduce). Verified file:line detail for each item lives in `CLAUDE.md` "Known
issues". Work on branch `overnight-fixes`; commit locally per group; **no push/fetch/pull** (1Password).
Build (`./gradlew :automotive:assembleDebug`) must stay green at every commit.

## §1 Car-blockers

- [ ] **QuickConnect sign-in batch** (`signin/`)
  - [ ] Wrap the polling coroutine's API calls in try/catch — an expired code (Jellyfin expires
        after 10 min) or a network blip must not crash the app on the sign-in screen.
  - [ ] Exit the poll loop on successful auth (today it re-authenticates every second forever).
  - [ ] Guard against double-started loops sharing the single `quickConnectSecret` field.
  - [ ] `SignInActivity`: release the MediaController; guard `future.await()`.
  - [ ] Keep the QuickConnect code a String end-to-end (SDK type is String; drop `Integer.valueOf`).
- [ ] **Empty-container-tap guard** (`JellyfinMediaLibrarySessionCallback.onSetMediaItems`) — an
      empty `resolveMediaItems` result must not `setMediaItems([])` (wipes queue) + `savePlaylist("")`
      (destroys resumption). Mirror the throw-instead guard the voice path already has.
- [ ] **Playlist audio-type filter** (`JellyfinMediaTree.fetchItemChildren`/`fetchPlaylists`) — a
      non-audio child currently throws in `MediaItemFactory.create`, breaking the whole browse/play.
- [ ] **slf4j binding fix** (`build.gradle.kts`) — `slf4j-api` 2.0.17 can't bind `slf4j-android`
      1.7.36, so all SDK logs are NOP'd (kills car diagnostics).

## §2 Robustness

- [ ] **PARENT_KEY twins** (`JellyfinMediaTree` + `Callback` + `MediaItemFactory`)
  - [ ] cold-path `getItem`/`revalidateItem` rebuild tracks with `parent=null` → lost album context.
  - [ ] RAM cache keyed by raw id → cross-browse-context `PARENT_KEY` clobbering.
- [ ] **try/catch around `clientLogApi.logFile`** (`settings/SettingsFragmentViewModel`).
- [ ] **try/catch on `JellyfinMediaTree` foreground fetches** (cold-miss browse / random / search /
      item resolution) — surface a distinguishable error instead of an opaque failed future.
- [ ] **Log `reportPlayback` failures** (`JellyfinMusicService`) — currently completely silent.
- [ ] **`limit` on `fetchFavourites`** (`JellyfinMediaTree`).
- [ ] **Real pagination** — respect `page`/`pageSize` + `startIndex` in
      `onGetChildren`/`onGetSearchResult`; leave `fetchItemChildren` unbounded (it's the play queue).
- [ ] **`storeAccount` addAccountExplicitly-returns-false handling** (`JellyfinAccountManager`) —
      same-username/new-server re-login currently bricks auth permanently.

## §3 Nice-to-have

- [ ] **AlbumArtContentProvider**: check disk file before requiring a `uriMap` entry;
      `createTempFile` inside the try; art-cache size cap/eviction.
- [ ] **`ensureTree()` + `isAuthenticated`** on `onGetChildren`/`onGetItem`/`onSearch`/`onGetSearchResult`.
- [ ] **`onSetRating`**: type-check the `HeartRating` cast; stamp the rating onto the correct queue item.
- [ ] **Atomic `getItem` cold-miss** (Mutex / Guava loader) to avoid redundant concurrent fetches.
- [ ] **Bitrate pref rename/relabel** — "Audio quality"; drop/relabel the misleading "Direct stream"
      entry (transcode stays default; do NOT add a real direct-play path — deprioritized per owner).
- [ ] **`JellyfinApi.auth`** via the SDK's own header builder.
- [ ] **`Authenticator` `getAuthToken`/`addAccount`** contract fix (latent).
- [ ] **`JellyfinHiltModule`** — `@Singleton`-scope the providers.
- [ ] **Per-art-size `MediaItemFactory`** instead of first-caller-wins freeze.
- [ ] **`ARTISTS`/`BROWSE_ARTISTS` disk-key aliasing** (negligible; do if cheap).

## Orchestration note

Implemented sequentially on `overnight-fixes` with a green build gated per commit, rather than
massively-parallel worktree agents: the three hub files (`JellyfinMediaTree`,
`JellyfinMediaLibrarySessionCallback`, `MediaItemFactory`) are touched by most items and can't be
safely split, and gitignored `local.properties` means isolated worktrees can't build. Fan-out
workflows are used for adversarial verification of the finished changes.
