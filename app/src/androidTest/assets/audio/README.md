# Synthetic import fixtures

These files contain generated tones or silence, with no third-party recordings.
They are test-only assets and are not included in the application package.

| File | Source audio before encoding |
| --- | --- |
| mono-44100.m4a | 1 second, 44,100 Hz mono, 1,000 Hz sine with amplitude 12,000 |
| stereo-48000.m4a | 1 second, 48,000 Hz stereo; left: 1,000 Hz sine with amplitude 12,000; right: 3,000 Hz sine with amplitude 6,000 |
| sixty-seconds.m4a | Exactly 60 seconds of 48,000 Hz mono silence |
| over-sixty-seconds.m4a | Exactly 61 seconds of 48,000 Hz mono silence |
| unsupported-alac.m4a | Same source as mono-44100, encoded as Apple Lossless instead of AAC |

Source samples use `round(amplitude * sin(2 * pi * frequency * frame / rate))`
written as signed little-endian 16-bit WAV, with no leading or trailing padding.
Encoded using the macOS system `afconvert` utility with `-f m4af -d 'aac '`.
Mono/stereo tones use `-b 96000`/`-b 128000`; silence uses `-s 3 -q 40`.
The unsupported fixture uses `-f m4af -d alac`.

The tests assert the original frame counts after decoding, so encoder priming and
padding must be handled by the decoder through the extractor's gapless metadata.
