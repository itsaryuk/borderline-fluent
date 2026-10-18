# Third-party components

The game's code is GPL-3.0 (`LICENSE`); its art and audio are all rights reserved (`ASSETS-LICENSE.md`).

| Component | License | Source |
|---|---|---|
| ML Kit GenAI Prompt, GenAI Speech Recognition, Face Detection, Language ID | [Google ML Kit terms](https://developers.google.com/ml-kit/terms) and [GenAI additional terms](https://developers.google.com/ml-kit/genai-terms) | https://developers.google.com/ml-kit |
| CameraX, Jetpack Compose, kotlinx-coroutines, Play Asset Delivery | Apache-2.0 | https://developer.android.com/jetpack, https://github.com/Kotlin/kotlinx.coroutines |
| sherpa-onnx | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| eSpeak NG (statically linked inside sherpa-onnx 1.13.8) | GPL-3.0 | https://github.com/espeak-ng/espeak-ng |
| Whisper | MIT, Copyright (c) 2022 OpenAI | https://github.com/openai/whisper |
| Silero VAD | MIT, Copyright (c) 2020-present Silero Team | https://github.com/snakers4/silero-vad |
| Oswald font | SIL OFL 1.1, Copyright 2016 The Oswald Project Authors | https://github.com/googlefonts/OswaldFont |

## Piper voices

The officers' voices are Piper (VITS) models from the sherpa-onnx `tts-models` release
(https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models), pinned in `scripts/fetch-models.sh` and shipped in the
language asset packs. **Modified:** every voice is the sherpa-onnx int8 conversion of the original Piper model (weights
quantized to int8 ONNX); nothing else is changed. Languages and genders without a voice here use the phone's system
TTS. Licences as stated on each voice's MODEL_CARD (checked 2026-10-15).

| Voice | Officer | Dataset | Licence | Source / citation |
|---|---|---|---|---|
| ar_JO-SA_dii-high | Arabic, female | synthetic dii (TigreGotico/tts-train-synthetic-dii_ar-diacritics) | CC BY-NC-SA 4.0 | https://huggingface.co/csukuangfj/vits-piper-ar_JO-SA_dii-high |
| de_DE-thorsten-medium | German, male | Thorsten-Voice | CC0 1.0 | https://github.com/thorstenMueller/Thorsten-Voice |
| de_DE-kerstin-low | German, female | Kerstin | CC0 1.0 | https://github.com/rhasspy/dataset-voice-kerstin |
| en_US-ryan-medium | English, male | RyanSpeech (Zandie, Mahoor, Madsen, Emamian, "RyanSpeech: A Corpus for Conversational Text-to-Speech Synthesis", Interspeech 2021, https://www.isca-archive.org/interspeech_2021/zandie21_interspeech.html) | CC BY-NC-SA 4.0 | https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ryan/medium/MODEL_CARD |
| es_ES-davefx-medium | Spanish, male | davefx | CC0 1.0 | https://github.com/OHF-Voice/voice-datasets |
| es_ES-sharvard-medium | Spanish, female | Sharvard | CC BY 3.0 | Aubanel, Vincent; Garcia Lecumberri, Maria Luisa; Cooke, Martin. (2014). Sharvard_IJA [sound]. LISTA Consortium. https://doi.org/10.7488/ds/75 |
| fr_FR-tom-medium | French, male | Tom (Tjiho) | AGPL-3.0 | https://git.bksp.space/Tjiho/French-tts-model-piper |
| fr_FR-siwis-medium | French, female | SIWIS | CC BY 4.0 | Yamagishi, Junichi; Honnet, Pierre-Edouard; Garner, Philip; Lazaridis, Alexandros. (2017). The SIWIS French Speech Synthesis Database, 2016. University of Edinburgh. https://doi.org/10.7488/ds/1705 |
| it_IT-riccardo-x_low | Italian, male (also the Thalassian father without a Greek system voice) | M-AILABS Speech Dataset | **licence unverified** (the MODEL_CARD says "See URL"; the dataset page states none) | Piper MODEL_CARD |
| it_IT-paola-medium | Italian, female | Paola | CC0 1.0 | https://huggingface.co/datasets/paolapersico1/Voice-Dataset-Italian |
| pl_PL-darkman-medium | Polish, male | darkman | CC0 1.0 | https://github.com/OHF-Voice/voice-datasets |
| pl_PL-gosia-medium | Polish, female | gosia | CC0 1.0 | https://github.com/OHF-Voice/voice-datasets |
| pt_BR-faber-medium | Portuguese, male | faber | CC0 1.0 | https://github.com/OHF-Voice/voice-datasets |
| pt_PT-dii-high | Portuguese, female | synthetic dii (Jarbas/tts-train-synthetic-dii_pt-PT) | CC BY-NC-SA 4.0 | https://huggingface.co/csukuangfj/vits-piper-pt_PT-dii-high |
| tr_TR-dfki-medium | Turkish, male | DFKI | CC BY-NC-SA 4.0 | https://github.com/marytts/dfki-ot-data |
| tr_TR-fettah-medium | Turkish, female | fettah | CC0 1.0 | https://github.com/NabuCasa/voice-datasets |
| uk_UA-ukrainian_tts-medium | Ukrainian, male (speaker 1) and female (speaker 2) | ukrainian_tts | CC0 1.0 | https://github.com/OHF-Voice/voice-datasets |

The CC BY-NC-SA 4.0 voices are non-commercial: see `ASSETS-LICENSE.md`. Not shipped, because their licence does not
allow redistribution: en_US-lessac-medium (research-only dataset), ru_RU-irina-medium and ar_JO-kareem-medium (no
licence stated); English female, Russian and Arabic male officers use the system voice.

Full license texts are in `app/src/main/assets/licenses/`.
