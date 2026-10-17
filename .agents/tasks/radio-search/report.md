# Report: radio station search (ค้นหาคลื่นวิทยุ)

Branch `feature/radio-search`, feature commit `c96d2e6` (before rebase onto main).

## What changed

Before this change, radio reused the TV channel search. That search was a raw `contains`, only
reachable after opening one source, and offline only. Queries like "FM 95.5", "955" and "95,5" found
nothing, and a station outside the loaded playlists could not be found at all.

Decision: local AND online, both.
- Local: every loaded radio source is searched. Results are instant and work offline.
- Online: the radio-browser name search (`de1.api.radio-browser.info`, the host the seeded lists
  already use). It runs 600 ms after typing stops (or on submit), off the main thread. Stale answers
  are dropped.
- Local results are listed first, then online results, deduplicated by stream URL. TV search is unchanged.

Files:
- `iptv/RadioStationMatcher.kt` (new): pure-Kotlin matcher shared by car and phone.
- `iptv/RadioBrowserApi.kt` (new): URL building, blocking HTTP and JSON parsing. Skips broken,
  duplicate and non-http(s) stations.
- `iptv/RadioBrowserSearch.kt` (new): executor and main-thread delivery, plus a 5-minute cache (20 keys).
  Online results sit under the synthetic source `radio-browser-search`, so history and playback work unchanged.
- `car/CarRadioSearchScreen.kt` (new): a `SearchTemplate`. It shows local rows, online rows and one
  status row (prompt / searching / failed / no matches), within `CarListPaging.limit`.
- `car/CarIptvScreens.kt`: the car Radio screen gets a search icon in its header. With no source, the
  message gets a "Search stations" action. A radio source's categories search icon opens the new screen.
- `library/LibraryActivity.kt`: phone radio search uses the matcher and adds an
  "Online · radio-browser" section with searching/failed status. The search is debounced, and the page
  is not redrawn after the user leaves it.
- `res/values{,-th}/strings_library.xml`: 7 `radio_search*` strings in English and Thai.
- `scripts/check-i18n.sh`: allowlists the matcher's Thai band words.
- Tests: `RadioStationMatcherTest` (13 tests), `RadioBrowserApiTest` (7 tests).

## Query matching and normalization

`normalize` makes both the station name and the query comparable:
1. Applies NFKC and `lowercase(Locale.ROOT)`. This makes Thai SARA AM match whether it was typed
   composed or decomposed.
2. Converts Thai and other Unicode digits to ASCII (๙๕.๕ becomes 95.5).
3. Joins `digit [.,] digit` across spaces ("95 , 5" becomes 95.5).
4. Keeps letters, digits, '.' and combining marks (so Thai vowels and tone marks survive). Everything
   else becomes a space, then whitespace is collapsed.

Query tokens:
- Band words are removed: fm, am, mhz, khz, คลื่น, วิทยุ, เอฟเอ็ม. This covers band words glued to a
  number too ("fm95.5" and "95.5mhz" become 95.5). If the query is only a band word, it is kept, so
  "fm" alone still searches.
- Every token must match:
  - A word token must appear as a substring of the name, so "cool 93" finds "Cool Fahrenheit 93".
  - A number token is compared with the numbers in the name, including numbers glued to letters as in
    "Cool93" and "97qfm". It matches when the two are numerically equal, or when "95" equals the
    integer part of 95.5, or when "955" (3 or more digits) is the frequency without its dot.
    "95" does not match "1095".
- Ranking: exact name first, then names that start with the first token, then the rest. Order is
  stable within each tier.
- Online: the API receives only the meaningful tokens ("FM 95.5" is sent as "95.5"). Its results are
  re-filtered locally only when the query contains a number.

## Verified

- `./gradlew :app:assembleLabDebug`: BUILD SUCCESSFUL.
- `./gradlew :app:testLabDebugUnitTest --rerun`: 1062 tests, 0 failures.
- `scripts/check-i18n.sh`: OK.

Not run: DHU or device checks, and a live radio-browser call.

## How to test on DHU (from `docs/DHU.md`)

One-time setup:
1. In Android Studio → SDK Manager → SDK Tools, install "Android Auto Desktop Head Unit Emulator".
   It installs to `~/Library/Android/sdk/extras/google/auto/`.
2. In Android Auto on the phone, go to Settings and tap Version repeatedly to unlock Developer mode.
3. In the same app, set video resolution to allow car and phone to negotiate. Otherwise the ini
   resolution may be ignored.

Each run:
1. Connect the phone over adb (USB, or `adb tcpip 5555` then `adb connect <ip>:5555`).
2. On the phone, open Android Auto → ⋮ → Start head unit server.
3. In a real Terminal.app or iTerm window (the DHU needs Screen Recording permission, so not an IDE
   task runner), run from the repo root:
   - `scripts/dhu-run.sh` (safe debug, 720, touch), or
   - `scripts/dhu-run.sh lab 720 touch` for the lab build that was verified.

   The script builds, installs, runs `adb forward tcp:5277 tcp:5277` and launches the DHU.
   - Manual alternative: `adb forward tcp:5277 tcp:5277`, then
     `cd ~/Library/Android/sdk/extras/google/auto && ./desktop-head-unit -c "$REPO/docs/dhu/720.ini" -i touch`.
4. If the DHU disconnects, or shows "connected" with no picture: stop and start the head unit server
   again, then run `scripts/dhu-run.sh --no-build`. Close the DHU with Ctrl+C.

Known limitation: DHU 2.0 with Android Auto 17.7+ disconnects after the TLS handshake
(`USB_ISSUE_PROJECTION_NOT_STARTED`). In that case, test on a real head unit.

Radio search checks on the DHU:
1. Car flow: Home → Radio → search icon in the header. The keyboard opens with the hint
   "Station name or frequency".
2. Type `95.5`. The prompt row changes to your matching stations. A "Searching online…" status row
   shows, then rows labelled "Online · radio-browser" appear.
3. Repeat with `FM 95.5`, `955`, `95,5` and a Thai station name such as `ลูกทุ่ง`. Each query should
   return results.
4. Tap a local row and an online row. Each should play audio and open Now Playing.
5. Radio → open a source → search icon. This opens the same radio search, not the TV search.
6. With no radio source configured, the message screen offers "Search stations", and online search works.
7. Offline: turn off the phone's data. Local results still show, and the row reads "Online search failed: …".
8. Phone flow: Library → Radio. Type `FM 95.5`. Local matches appear first, then the
   "Online · radio-browser" section fills in. Tap an online station and it plays.
