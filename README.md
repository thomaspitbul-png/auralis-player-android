# Auralis

Auralis is an Android music player built with Kotlin, Jetpack Compose and Media3.

## Current player

- Redesigned dark interface with Home, Search, Library, Playlists and Settings screens.
- Full-track search across Internet Archive open-license audio and Jamendo's Creative Commons catalogue.
- Direct playback URLs from Jamendo (not 30-second previews).
- Local audio file import using Android's document picker.
- Background playback and Android media-session integration.
- Seek bar, elapsed/total time, volume, previous/next, shuffle and repeat modes.
- Persistent queue, favorites, listening history and playlists.
- Sleep timer.
- Gestures: swipe left/right on player artwork to change tracks, swipe down to close the full player, swipe up on the mini-player to expand it.
- Track source, creator and license links are shown in the UI.

## Jamendo setup

Jamendo requires an application-specific Client ID. Auralis does not ship a shared/test Client ID.

1. Register an application in the Jamendo Developer Portal: https://devportal.jamendo.com/signup
2. Copy the application's Client ID (not your account password).
3. Open Auralis → Settings → Jamendo API and paste the Client ID.
4. Save. Search will then combine Jamendo results with Internet Archive results.

Auralis stores this ID locally on the device. Jamendo's API documentation says its track endpoint returns a stream URL, track duration, artwork and Creative Commons license information. Auralis displays a link to the track page and its license so the artist/provider is credited.

Jamendo's API is a catalogue of independent music published under Creative Commons licenses; it is not a replacement for the full commercial catalogues of Spotify, VK Music, Apple Music or other subscription services. Jamendo's terms currently allow non-commercial API use, require attribution and a direct track-page backlink, prohibit designing the app for offline access, and require contacting Jamendo for commercial use. Check the current provider terms before distributing or monetizing the app.

## Search and playback notes

- Apple Music preview snippets have been removed. They are not presented as full tracks.
- Internet Archive search only keeps records with a recognized Creative Commons or Creative Commons public-domain license URL, then resolves a compatible audio file when playback starts.
- The availability and completeness of an open catalogue depend on what creators have published there. Commercial chart music may not be available.
- The app does not download music for offline playback.

## Build

The GitHub Actions workflow builds a debug APK and publishes a versioned GitHub Release. The APK signing key is cached by the workflow so later builds can be installed as updates where Android sees the same signing certificate.