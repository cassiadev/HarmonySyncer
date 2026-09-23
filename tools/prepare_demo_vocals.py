#!/usr/bin/env python3
"""Prepare the fixed eight-second demo from the two credited DCS MP3 sources.

Usage: python3 tools/prepare_demo_vocals.py SOURCE_DIRECTORY [--ffmpeg /path/to/ffmpeg]
See app/src/main/assets/vocals/README.md for source URLs and the musical reference.
"""

import argparse
from pathlib import Path
import subprocess


SAMPLE_RATE = 48_000
FRAME_COUNT = SAMPLE_RATE * 8
# Dataset annotation: measure 1 at 0.121904762 seconds in the shared source timeline.
REFERENCE_FRAME = 5_851
TRACKS = (
    ("DCS_LI_QuartetA_Take01_S2_DYN.mp3", "locus_iste_soprano.pcm"),
    ("DCS_LI_QuartetA_Take01_A1_DYN.mp3", "locus_iste_alto.pcm"),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_directory", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    output_directory = (
        Path(__file__).resolve().parents[1] / "app/src/main/assets/vocals"
    )
    for source_name, _ in TRACKS:
        if not (args.source_directory / source_name).is_file():
            parser.error(f"Missing source: {source_name}")

    output_directory.mkdir(parents=True, exist_ok=True)
    for source_name, output_name in TRACKS:
        output = output_directory / output_name
        subprocess.run(
            [
                args.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-i", str(args.source_directory / source_name), "-vn",
                "-af", (
                    f"aresample={SAMPLE_RATE},"
                    f"atrim=start_sample={REFERENCE_FRAME}:"
                    f"end_sample={REFERENCE_FRAME + FRAME_COUNT}"
                ),
                "-ac", "1", "-ar", str(SAMPLE_RATE),
                "-c:a", "pcm_s16le", "-f", "s16le", str(output),
            ],
            check=True,
        )
        if output.stat().st_size != FRAME_COUNT * 2:
            raise ValueError(f"Unexpected PCM length: {output}")
        print(f"{output.name}: {FRAME_COUNT} frames, 8 seconds")


if __name__ == "__main__":
    main()
