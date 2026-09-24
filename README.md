# Universal MP3 Player

A responsive, mobile-friendly Universal MP3 Player for music the user owns, is licensed to use, or that is legally available for playback/download.

## Current implementation
- Modern dark U-branded music-player interface
- Mobile-responsive layout
- Local MP3/audio playback
- Play, pause, previous, next, seek and volume controls
- Library search and favorites
- Home, Discover, My Music, Favorites and Downloads views
- Online catalogue discovery using Apple's iTunes Search API
- Preview playback and source links for catalogue results
- Supported free-download discovery through the Internet Archive
- MP3 download button only when a supported source exposes a downloadable MP3
- Download history stored locally in the browser
- No upload of local audio files

## Important download rule

The player does **not** rip Spotify, Apple Music, YouTube, or other protected streaming services. Catalogue metadata and preview URLs do not grant a right to download the full recording. Apple's Search API provides catalogue metadata and promotional previews under its own terms. The app therefore keeps catalogue previews as previews and only exposes a download action for supported sources that actually provide a downloadable audio file.

## Current status

The project is a working web application/prototype. It is **not yet an Android APK/release build**. The next engineering stage is packaging the player as a proper Android app, adding persistent local media storage, queue/playlist management, metadata/artwork handling, and automated build/release workflows.

## Roadmap
- Android app packaging
- Persistent local media library
- ID3 metadata and embedded album artwork
- Queue and playlists
- Full-screen Now Playing
- Audio visualizer
- Sleep timer
- Sort/filter by artist, album and genre
- PWA/offline support
- More verified legal music sources

The project is intended for lawful music playback and downloading only.
