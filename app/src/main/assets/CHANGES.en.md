# What's new

English version of CHANGES.md: the "What's new?" card shows it when the app
is in English. Same versions, same order; a version missing here falls back
to the Russian section.

## 1.12.3

- Added bringing over playlists from a Spotify data export. But it’s much more complicated. There’s a guide right in the app

## 1.12.2

- Added importing playlists and albums from Spotify. Paste the “Share” link into “Search”, in the link field
- For now, only the first 100 tracks of a playlist come over through a link

## 1.12.1

- Organized the settings into groups

## 1.12.0

- Added English localization

## 1.11.6

- “Share” now gives a link to the Lazy Music page: if the app is installed, the track opens in it; if not, on YouTube
- With such links (and the “Open track” button in Discord), Lazy Music on the phone opens the track right away

## 1.11.5

- Playlist downloads no longer stop after a few errors: a track is tried up to 4 times, and after a series of failures downloading pauses and then continues on its own
- The log now records which tracks were downloaded and why the others were not

## 1.11.4

- Fixed a bug: the app crashed after removing a track from a long playlist
- After a restart the app remembers that YouTube asked to confirm you’re not a bot and goes straight in signed in — the track starts a few seconds faster

## 1.11.3

- You can now download a whole playlist. The playlist header has a new “Download playlist” button, like in Spotify
- Tracks are downloaded one by one into the app cache and don’t end up in “Music”
- New tracks in such a playlist download on their own: from imports, 👍 and sync with the PC
- Under the title you can see how many are already downloaded
- By default it downloads only over Wi-Fi. You can allow mobile data in Settings, in the “Downloads” section
- If you turn it off, downloading stops, and what was downloaded stays
- If the app was closed in the middle of a download, it continues at the next start
- Logs. At the bottom of Settings there is a new button that collects a file with records of how the app worked and opens “Share”
- A new track in a playlist goes to the top, not the end. Before, in a long list, like a thousand-track “Liked” from Yandex, you couldn’t find it. Same on the PC
- “Share” in the track menu. Sends the title and the YouTube link through the Android “Share” window: to Telegram, as a message, anywhere. On the PC there is “Copy link” for this

## 1.11.2

- Ran into a problem and I’m trying to solve it. YouTube sometimes asks to confirm you’re not a bot, so I added a mini browser where you sign in to Google. The sign-in is used only when YouTube demands the check. The catch is that with sign-in a track takes longer to start, about 15 seconds
- Fix: the player no longer skips tracks, it retries the track every short while instead. If you change the VPN, it retries right away
- After the app restarts, a track that was stuck on an error starts trying to play again
- You can sign out of Google in the same mini browser

## 1.11.1

- Added search inside a playlist
- Search now shows tracks already in the playlist first
- Fixed a bug: pressing “Back” no longer resets the search
- Added scrolling text for long track titles
- Improved import for “The”, hieroglyphs, special characters and notes like “(from the movie)”. What wasn’t found last time should be searched for again
- Fixed another 403 error (not for sure)

## 1.11.0

- Added importing tracks from Yandex Music
- Changed the look of some interface elements
