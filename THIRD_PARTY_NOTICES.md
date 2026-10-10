# Third-party notices

## Yomitan Japanese deinflection rules

The Japanese inflection condition hierarchy, transform table, and deinflection behavior in `app/src/main/java/com/nekoplayer/app/media/JapaneseDeinflector.kt` are adapted from Yomitan.

- Copyright: Copyright (C) 2024-2026 Yomitan Authors
- License: GNU General Public License, version 3 or (at your option) any later version (GPL-3.0-or-later); see `LICENSE`.
- Pinned source revision: `d34832d756e05dc00945e5b7d7ebc80963299a7a`.
- Source file: https://github.com/yomidevs/yomitan/blob/d34832d756e05dc00945e5b7d7ebc80963299a7a/ext/js/language/ja/japanese-transforms.js
- Scope: the Japanese condition hierarchy, suffix/whole-word conjugation rules (including generated irregular and honorific rules), and rule-chain semantics.

This Kotlin implementation is a compatibility adaptation; it does not include Yomitan's JavaScript runtime or UI.

## Watchbox-Android extension runtime

The extension runtime and Aniyomi-compatible source API files carrying Watchbox adaptation headers under `app/src/main/java/com/nekoplayer/app/extensions/` and `app/src/main/java/eu/kanade/tachiyomi/` are adapted and modified from Watchbox-Android.

- License: GNU GPL version 3 (GPL-3.0); see `LICENSE`.
- Upstream project: Watchbox-Android by Nicartjay — https://github.com/Nicartjay/Watchbox-Android

## QuickJS and Zipline

The Java QuickJS wrapper in `app/src/main/java/app/cash/quickjs/` retains Square's copyright and Apache-2.0 headers. The native QuickJS JNI sources are fetched at build time from Cash App's Zipline repository, pinned to commit `a738129cc4aa99206c00ae49e9c5b15cb58ad880`.

- Zipline JNI code: Copyright Square, Inc.; Apache License 2.0. See https://github.com/cashapp/zipline/blob/a738129cc4aa99206c00ae49e9c5b15cb58ad880/LICENSE
- QuickJS engine: Copyright Fabrice Bellard and Charlie Gordon; MIT License. See https://bellard.org/quickjs/
- Pinned native source: https://github.com/cashapp/zipline/tree/a738129cc4aa99206c00ae49e9c5b15cb58ad880/quickjs/common/native

The Java wrapper source files retain their upstream Apache-2.0 headers. This notice records the upstream licenses and source locations for the native files fetched at build time.
