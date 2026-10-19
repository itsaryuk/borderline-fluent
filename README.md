# Borderline Fluent

2035. AI companies own the only software anyone needs, and every translated word is billed. Borders went cheap:
every booth has an android officer that speaks one language, the country's own.

You needed money for your AI subscription, so you signed up for a "medical trial". Now there's a chip behind your
left eye, the Neural Retina Implant, build zero-point-nine. It listens, it translates, it whispers what to say. Most of
the time it's right. The AI companies keep their own machine at every border, and it tests travellers for exactly that.

An Android game you play by speaking:
- Your own passport: pick your nationality and roll your identity; every trip rolls a new itinerary to remember.
- Officers in 10 languages at 79 airports in 24 countries, with fixed, translated lines. They check your trip, notice
  odd answers, and react when you talk about your implant. Arabic, Turkish and Chinese are in the code but held back
  until a native speaker has checked them (`heldBackLanguages` in gradle.properties).
- Things happen in the queue before the desk, and the officer may ask what you saw.
- Act 2: balance facts and lies to get referred to the machine, survive its Kade-Sorensen test, and train the
  implant there. Upgrades arrive by mail.

Everything runs on the phone: speech recognition, the answer checks and the implant's suggestions (Gemini Nano, Android's built-in AI), their
voices and the face reading. The app has no internet access. A work of fiction.

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
