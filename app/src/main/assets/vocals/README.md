# Bundled Vocal Recordings

English | [한국어](README.ko.md)

This directory contains the fixed soprano and alto excerpts used by HarmonySyncer's
audio processing code and tests. This document covers their format, musical reference,
provenance, and regeneration. For the app's purpose, development status, and build
instructions, see the [project README](../../../../../README.md).

## Files and alignment reference

| Property | A | B |
| --- | --- | --- |
| File | `locus_iste_soprano.pcm` | `locus_iste_alto.pcm` |
| Voice / source channel | Soprano / S2_DYN | Alto / A1_DYN |
| Duration | 8 seconds, 384,000 frames | 8 seconds, 384,000 frames |
| Size | 768,000 bytes | 768,000 bytes |
| Musical reference in the bundled file | Frame 0 | Frame 0 |

Both files contain **48,000 Hz, signed 16-bit, little-endian, mono, headerless PCM**.
The sources are 22,050 Hz mono MP3 files, converted outside the app. The app does not use
an MP3/WAV decoder.

Both voices share the timeline of the same performance, `DCS_LI_QuartetA_Take01`.
The published [measure timestamps](https://www.audiolabs-erlangen.de/resources/MIR/2020-DagstuhlChoirSet/data/DCS_LI_QuartetA_Take01/DCS_LI_QuartetA_Take01_Stereo_STM_measures.json)
place the **start of measure 1 at 0.121904762 seconds**. After conversion to 48 kHz,
both tracks were cropped at the same frame: `round(0.121904762 × 48000) = 5851`.
Each file contains the interval `[5851, 389851)`, so frame 0 refers to the same musical
position. The first voiced sound or nonzero sample of each singer was not independently
detected or aligned. Natural differences in pronunciation and entrances are preserved.
This reference does not guarantee that the singers' vocal onsets coincide at the sample level.

## Processing path

`BundledVocalDemo.loadAligned()` reads both files and passes them to
`alignFromOnsets(trackA, 0, trackB, 0, 48000)`.
`loadMixed()` passes the two resulting arrays to `mixPcm16()`, explicitly setting
`gainA = 0.5f` and `gainB = 0.5f`.
Since the inputs already share a reference point, the reported original relative offset
is 0 for these assets. This value is not a measurement of correction performance at the
physical audio output.

Invoke loading and mixing on a worker thread. For example, from a coroutine:

```kotlin
val pcm = withContext(Dispatchers.IO) {
    BundledVocalDemo.loadMixed(context.assets::open)
}
// pcm: 384,000 mono PCM16 frames; playback sample rate: BundledVocalDemo.SAMPLE_RATE
```

The loader closes each stream, fails preparation if a file has an unexpected length,
and reads samples with explicit signedness and byte order. No network access is needed
at runtime. The current screen is not connected to a player yet.

`BundledVocalDemoTest` loads the same source assets that are packaged in the APK as test
resources and compares the complete eight-second mix against an independently calculated
SHA-256 digest.

## Attribution and license

Audio: **Dagstuhl ChoirSet — Locus Iste (Anton Bruckner, WAB 23), QuartetA, Take01**,
dynamic microphone recordings of soprano S2 and alto A1.

- [Original recording page](https://www.audiolabs-erlangen.de/resources/MIR/2020-DagstuhlChoirSet/data/DCS_LI_QuartetA_Take01)
- [Soprano MP3](https://www.audiolabs-erlangen.de/content/resources/MIR/00_2020-DagstuhlChoirSet/data/00_DCS_LI_QuartetA_Take01/DCS_LI_QuartetA_Take01_S2_DYN.mp3)
- [Alto MP3](https://www.audiolabs-erlangen.de/content/resources/MIR/00_2020-DagstuhlChoirSet/data/00_DCS_LI_QuartetA_Take01/DCS_LI_QuartetA_Take01_A1_DYN.mp3)
- [Dataset and license](https://zenodo.org/records/4607766)

Dataset creators: Sebastian Rosenzweig, Helena Cuesta, Christof Weiß,
Frank Scherbaum, Emilia Gómez, and Meinard Müller.
Audio license: [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).
The PCM excerpts in this directory are distributed under the same license. Include the
attribution, license link, and description of changes when redistributing them. This does
not imply that the creators endorse this app.
Changes made: MP3 decoding, resampling to 48 kHz, extraction of the same eight-second
interval, and conversion to 16-bit mono PCM. No pitch shifting, speed changes, or loudness
normalization were applied.

Citation: Rosenzweig et al. (2020), *Dagstuhl ChoirSet: A Multitrack Dataset for MIR
Research on Choral Singing*, Transactions of the International Society for Music
Information Retrieval, 3(1), 98–110. [DOI: 10.5334/tismir.48](https://doi.org/10.5334/tismir.48).

## Regenerating the assets outside the app

Download the two MP3 files linked above into the same directory, keeping their original
filenames. Then run the following command from the project root. The full source recordings
and conversion tools are not included in the app.

```sh
python3 tools/prepare_demo_vocals.py /path/to/source-mp3s --ffmpeg /path/to/ffmpeg
```

The bundled files were generated with FFmpeg 7.1. Different decoder or resampler versions
may produce different least significant PCM bits. When regenerating the assets, verify
the files and the mixed output before updating the hashes.

| File | SHA-256 |
| --- | --- |
| Source S2_DYN.mp3 | `56e3939d19a44d6a0d95cb78548b9b25073623d0353e1e157cf594eab94d1c88` |
| Source A1_DYN.mp3 | `2fbd87112fd1eb65146c4e9cc557abbfe04e3c8b4a5f668af20ca6613247cded` |
| soprano.pcm | `03e4cb504a6304360080cccfd21d9a6442f738245779cb740291d1b105aa15fd` |
| alto.pcm | `4c66f940eafe91ed9d58217755b22e724abda8ae3619c29308f89e570aed7069` |
| PCM mixed with gains of 0.5 / 0.5 | `ccde988c3668069d82acd48c525c33e4b58febcefde86a4e7f67843824186a98` |
