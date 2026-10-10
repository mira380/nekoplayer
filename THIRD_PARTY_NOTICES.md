# Third-party notices

## Yomitan Japanese deinflection rules

The Japanese inflection condition hierarchy, transform table, and deinflection behavior in `app/src/main/java/com/nekoplayer/app/media/JapaneseDeinflector.kt` are adapted from Yomitan.

- Copyright: Copyright (C) 2024-2026 Yomitan Authors
- License: GNU General Public License, version 3 or (at your option) any later version (GPL-3.0-or-later); see `LICENSE`.
- Pinned source revision: `d34832d756e05dc00945e5b7d7ebc80963299a7a`.
- Source file: https://github.com/yomidevs/yomitan/blob/d34832d756e05dc00945e5b7d7ebc80963299a7a/ext/js/language/ja/japanese-transforms.js
- Scope: the Japanese condition hierarchy, suffix/whole-word conjugation rules (including generated irregular and honorific rules), and rule-chain semantics.

This Kotlin implementation is a compatibility adaptation; it does not include Yomitan's JavaScript runtime or UI.
