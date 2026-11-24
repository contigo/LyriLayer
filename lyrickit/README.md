# LyricKit (development)
Direct Kotlin lyric engine. No UI or server. Genie and Bugs are implemented; Musixmatch is optional via createLyricKit(musixmatchApiKey = ...). Supply an authorized key with the necessary lyrics/subtitle rights at runtime; never commit or embed a shared key. The LyriLayer app does not configure one. Restricted and preview lyrics are not registered. Based on https://github.com/oneulddu/musicxmatch-api (MIT; see license). Matching is conservative; duration missing may prevent automatic selection. The app keeps its existing LRCLIB fallback and manual search. AI queue integration is implemented. Live provider validation remains pending.

## Verified integration

GitHub Actions run 45455890706: Android unit tests and debug APK passed. Live diagnostic for I Really Want to Stay at Your House: Genie returned 73 timed lines (90 matching score), Bugs returned 69 timed lines (95 matching score). The decorated raw filename with duration 246000 ms matched Bugs at 90. Results are specific to this test track; other songs and real devices are not yet validated. Musixmatch remains opt-in and unverified with live credentials.
