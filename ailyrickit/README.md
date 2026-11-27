# AILyricKit

Android library for explicit, offline lyric extraction, using Whisper Small Q5_1
(190085487 bytes) and the V16-inspired bounded language vote and V14 raw-context
filter. Kotlin, C++/JNI, arm64-v8a, API 26+, SDK 36, NDK 28.2.13676358.

Call AiLyricKit.open(context, modelFile) on a worker thread, extract a content URI,
and close the caller-owned session in finally or use. The caller provides the
model; the library does not download it or schedule background jobs. Multiple
songs may reuse a session serially. Cancellation is cooperative between decode
calls, not a native mid-inference abort.

The result includes segment-level LRC, quarantined candidates, unresolved windows,
and probe counts. Preview returns the currently merged LRC after each decode
window. Auto chooses one representative language; mixed-language accuracy is
not guaranteed. No automatic retry, vocal separation, translation or invented
word-level timestamps. Only the active V16 policy components are included.

Build: gradle :ailyrickit:testDebugUnitTest :ailyrickit:assembleRelease.
Android SDK/NDK, native inference and real-device validation are required before
shipping; JVM smoke tests do not establish Android performance or lyric accuracy.
