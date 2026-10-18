# Assets license

The source code is licensed under the GNU GPL v3 (see `LICENSE`). The following are **not** covered by the GPL:

- `art/`
- `app/src/main/assets/art/`
- `packs/lang_*/src/main/assets/art/`
- the music and sound files in `app/src/main/res/raw/`

Copyright (c) 2026 Fluffy Pants Studio LLC. All rights reserved.

The images are AI-generated. Music by The Atomic Songbirds, including the main title song "Borderline"; provided by the owner. The sound effects and ambience were synthesized for this game.

You may not copy, redistribute or reuse these assets. If you fork the game, replace them with your own.

## Piper voices

The Piper voices staged into `packs/lang_*/src/main/assets/tts/` by `scripts/fetch-models.sh` are third-party data
under their own licences, listed per voice in `NOTICE.md`; they are not covered by the GPL either. Four of them are
**CC BY-NC-SA 4.0** (non-commercial): en_US-ryan-medium, tr_TR-dfki-medium, ar_JO-SA_dii-high and pt_PT-dii-high.
They ship only because this game is given away free, without ads or purchases. They may not be redistributed
commercially: anyone selling a build, or putting one behind ads or payments, must remove them (delete their rows from
`scripts/fetch-models.sh`; those officers then speak with the system voice).
