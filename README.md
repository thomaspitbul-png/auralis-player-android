# Auralis

Auralis is an Android music player built with Kotlin, Jetpack Compose and AndroidX Media3.

## Music search and playback

- **Audius** is the main online catalogue. Auralis searches public tracks and resolves the official Audius stream endpoint to a playable full-track URL. It does not extract audio from video platforms or use unofficial download sites.
- **Internet Archive** is a secondary source for open-licensed audio. Search is broader than before, while the app still checks the returned licence metadata before showing a record.
- Search results are merged and ranked by title, artist, tags and the selected search scope.
- Audius tracks link back to their original Audius pages for creator/source attribution.
- Local audio import works through Android's document picker.
- No online tracks are downloaded for offline playback.

Audius provides a public read-only API. An API key is optional for basic public discovery and can be entered in **Settings → Audius API** to use the free API plan's higher request limits. The API key is suitable for client-side use; do not paste an Audius Bearer Token into the app. The free plan currently documents 10 requests/second and 500,000 requests/month. See the [official Audius developer guide](https://docs.audius.co/sdk/).

**Catalogue limitation:** Audius is an open creator-uploaded catalogue, not a licensed replacement for Spotify, Apple Music or VK Music. Mainstream commercial tracks may be missing, and some uploads may be unavailable, gated or region-restricted. Auralis filters explicitly non-streamable and stream-gated results, but the provider can still change track availability.

## Player

- Dark interface with Home, Search, Library, Playlists and Settings.
- Background playback and Android media-session integration.
- Seek bar, elapsed/total time, volume, previous/next, shuffle and repeat.
- Persistent queue, favorites, listening history and playlists.
- Sleep timer.
- Gestures: swipe left/right on player artwork to change tracks, swipe down to close the full player, and swipe up on the mini-player to expand it.
- Track source and original-page links are shown in the UI.

## Search behavior

- Audius search uses the provider's native relevance ranking and supports title, artist, and genre-oriented sorting in the app.
- Internet Archive search tries phrase and broader word matches, then keeps records with a recognised Creative Commons or Creative Commons public-domain licence URL.
- The app uses official provider APIs and stream URLs; it does not scrape unofficial audio links.

## Build

The GitHub Actions workflow builds a debug APK and publishes a versioned GitHub Release. The APK signing key is cached by the workflow so later builds can be installed as updates where Android sees the same signing certificate.
