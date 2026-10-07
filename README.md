# Borderline Fluent

2035. AI companies own the only software anyone needs, and every translated word is billed. Most countries can't
pay, so their borders went cheap: every booth has an android that speaks one language, the country's own.

You had no job and you needed money. So you took the job some strange people offered: a chip behind your left eye,
the Neural Retina Implant, build zero-point-nine. It listens, it translates, it whispers what to say. Most of the
time it's right. The assignment: cross border after border with a passport that says you're coming home, and answer
the officers out loud, in their language.

An Android game you play by speaking:
- Officers in 13 languages at airports in 31 countries, with fixed, translated lines.
- They check your trip, your passport stamps and your date of birth, press on slips and notice odd answers.
- A behavioral assessment watches your feelings, your speed and your face.
- Difficulty per language, and contracts once you've proven yourself.

Everything runs on the phone: speech recognition, the officers' minds (Gemini Nano, Android's built-in AI), their
voices and the face reading. The app has no internet access.

Copyright (C) 2026 Fluffy Pants Studio LLC. Borderline Fluent and Lampensoft are trademarks of Fluffy Pants Studio LLC.
This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License v3 (see `LICENSE`). It is distributed WITHOUT ANY WARRANTY.

## Requirements
The game needs a phone with Gemini Nano (ML Kit GenAI Prompt API), for example Pixel 9/10/11, Galaxy S26, Galaxy Z
Fold7/Fold8, OnePlus 15. On other phones it shows an "unsupported" screen. Speech models: Whisper turbo is downloaded
only for languages the system speech recognition lacks (today Arabic and Ukrainian).

## Build
- Needs Java 21 and the Android SDK.
- Build and unit tests: `JAVA_HOME=/path/to/java21 ./gradlew -q testDebugUnitTest bundleDebug`
- The Whisper model is not in the repo: `scripts/fetch-models.sh` fetches it (pinned, sha256-checked).
- Install on a device with its language packs: `scripts/install.sh` (bundletool local testing; set `ANDROID_SERIAL`).
- A full release bundle: `scripts/release-local.sh X.Y.Z` (fetches the models, runs the tests, builds an unsigned AAB).

## Licenses
- Code: GPL-3.0 (`LICENSE`).
- Art and audio: all rights reserved, not under the GPL (`ASSETS-LICENSE.md`). Forks must replace them.
- Third-party components: `NOTICE.md`.
