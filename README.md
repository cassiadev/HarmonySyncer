# HarmonySyncer

English | [한국어](README.ko.md)

HarmonySyncer is an Android prototype for aligning and mixing vocal recordings.
Choose two recordings through the system file picker, adjust their volumes and
relative start positions, and listen to the resulting mix.

The app aligns both files at supplied onsets of `0`, preserving each file's beginning. It does **not** detect delays automatically.
Pitch changes and playback drift correction are outside the current scope.

## Using the preview

1. Select a Waveform Audio File Format (WAV) or MPEG-4 Audio (M4A) file for each track.
   WAV supports uncompressed 16-bit mono pulse-code modulation (PCM). M4A supports
   Advanced Audio Coding (AAC) mono or stereo recordings, decoded on the device;
   stereo channels are averaged into mono. Other M4A codecs, including Apple Lossless,
   are unsupported. Each file must contain audio, be no longer than 60 seconds, be no
   larger than 32 MiB, and have a positive sample rate no higher than 192 kHz.
   Different rates are automatically matched to the higher rate before mixing using
   a windowed-sinc filter, preserving duration and pitch. The device must support
   that rate for playback. Original files are not modified or uploaded.
2. Set each volume between 0 and 100%. Open **Timing** (타이밍 맞추기) to adjust B's
   additional offset between −300 and +300 ms. A positive offset starts B later than A. A negative offset starts B
   earlier relative to A by adding silence before A. Padding preserves both tracks'
   beginnings and tails; the loaded source buffers remain unchanged.
3. Play the mix. Stop it before changing files or settings, then play again to rebuild
   the mix and restart from the beginning. Controls are locked during loading and
   playback, and the screen says why: the loading card shows progress, the status line
   names the current step, and a lock notice appears while mixing or playing. Changes
   are not applied in real time.
4. Tap **Save mix** (합본 저장) to keep the mix with the current volumes and timing. It is
   saved beside the app's recordings, in `Recordings/HarmonySyncer/` on Android 12 or later
   and `Music/HarmonySyncer/` on Android 10 and 11, as `HarmonySyncer-Mix-<date>-<time>.wav`:
   16-bit mono PCM at the higher of the two sample rates. The file holds the same samples the
   preview plays, so it can sound quieter than its sources (see the gain note below), and it
   is never trimmed. Timing offsets can make a mix up to 60.3 seconds long, and only a mix of
   60 seconds or less can be chosen again as a track. Saving continues while the app is in
   the background.
   WAV is uncompressed: one minute at 48 kHz takes about 5.8 MB, several times the size of a
   typical M4A.

To hear the app without your own files, tap **Try the sample** (샘플로 들어보기) on the start
screen. It loads an eight-second soprano (A) and alto (B) excerpt of Bruckner's *Locus iste*
from the Dagstuhl ChoirSet, cut at the same measure, and you can adjust it like any pair.
The recordings are licensed under CC BY 4.0; the app shows the credit and dataset link while
a sample track is loaded. See [the bundled vocals notes](app/src/main/assets/vocals/README.md).
A saved mix that contains a sample track must credit its source and license, and say that it
was changed, when you publish or distribute it; the app reminds you when it saves one.

Each volume is multiplied by `0.5` before mixing, so a 100% setting gives that track a
linear gain of `0.5`. This preserves mixing headroom, with PCM16 saturation protection.
Setting B to 0% mutes B while leaving A's gain unchanged.

The offset reset button returns B's additional offset to 0 ms. Successfully replacing
either file also resets the offset to 0 ms and keeps the volume settings. Canceling the
picker or failing to load a replacement retains the previous file and settings.
WAV and M4A may be used together, including recordings with different sample rates.
Playback stops when the app goes into the background; leaving the screen
releases playback resources and cancels pending work. Leaving the screen while a mix is
being saved cancels the mixing, but a file write that has started finishes.

## Build and run

### Requirements

- Android Studio compatible with the project's Android Gradle Plugin 9.4.1.
- JDK 21, matching the app's Java source and bytecode compatibility settings in
  [app/build.gradle.kts](app/build.gradle.kts) and the Gradle daemon version in
  [gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties).
- Android SDK Platform 37 for building.
- An Android device or emulator running API 29 or later to launch the app.

The Gradle wrapper selects Gradle 9.8.1; a separate Gradle installation is unnecessary.
The first build needs internet access to download build dependencies.

### Android Studio

1. Clone or download this repository and open its root directory in Android Studio.
2. Install SDK Platform 37 through SDK Manager and configure Gradle to use JDK 21.
3. Let Gradle sync, select the `app` run configuration, and choose a device or emulator.
4. Run the app, select A and B WAV or M4A recordings, and use the preview controls.

### Command line

Set `JAVA_HOME` to a valid JDK 21 installation. Configure the SDK location through the
untracked `local.properties` file created by Android Studio, or through
[`ANDROID_HOME`](https://developer.android.com/tools/variables).
From the repository root, run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.
The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The JUnit report is written to `app/build/reports/tests/testDebugUnitTest/index.html`.
Unit tests run on the development machine and do not require an Android device.

## Bundled recordings

The project includes real soprano and alto excerpts from Dagstuhl ChoirSet's recording
of *Locus Iste*. Each file is eight seconds of 48 kHz, 16-bit, mono PCM. Both tracks use
the same musical reference from the same performance.

The PCM files are included in the project. Python and FFmpeg are only used by developers
who want to regenerate those files; they are not required to build or run the Android app.
The current app selects external WAV or M4A files and does not expose a bundled-demo button.
See the [audio asset documentation](app/src/main/assets/vocals/README.md) for sources,
attribution, reference points, format details, and regeneration instructions.

## Working with the code

| Area | Files |
| --- | --- |
| Screen and lifecycle | [MainActivity](app/src/main/java/com/cassiadev/harmonysyncer/MainActivity.kt), [AudioPreviewScreen](app/src/main/java/com/cassiadev/harmonysyncer/ui/AudioPreviewScreen.kt) |
| File loading and validation | [AudioFileLoader](app/src/main/java/com/cassiadev/harmonysyncer/audio/AudioFileLoader.kt), [WavPcmReader](app/src/main/java/com/cassiadev/harmonysyncer/audio/WavPcmReader.kt), [M4aPcmReader](app/src/main/java/com/cassiadev/harmonysyncer/audio/M4aPcmReader.kt) |
| Mixing and preview state | [PcmPreview](app/src/main/java/com/cassiadev/harmonysyncer/audio/PcmPreview.kt), [PcmResampler](app/src/main/java/com/cassiadev/harmonysyncer/audio/PcmResampler.kt), [AudioPreviewController](app/src/main/java/com/cassiadev/harmonysyncer/audio/AudioPreviewController.kt) |
| Playback | [PcmPreviewPlayer](app/src/main/java/com/cassiadev/harmonysyncer/audio/PcmPreviewPlayer.kt): releases resources after completion or cancellation |
| Regression tests | [Audio tests](app/src/test/java/com/cassiadev/harmonysyncer/audio), [Device tests](app/src/androidTest/java/com/cassiadev/harmonysyncer) |
| Asset preparation | [prepare_demo_vocals.py](tools/prepare_demo_vocals.py) |

Loading, rendering, and playback run off the UI thread. Unit tests verify calculations,
data integrity, and controller state; they do not measure the timing of sound emitted
by a speaker.

## Manual acceptance checklist

Use two short compatible files with clear clicks or onsets to check the
[preview behavior](#using-the-preview):

- Select A and B; verify filenames, durations, rates, and preview availability.
- Cancel a replacement and try malformed, unsupported, overlong, and oversized files;
  verify readable errors and retained files/settings. A valid replacement resets only the offset.
- Mix a 44.1 kHz M4A with a 48 kHz WAV and verify unchanged duration and pitch.
- Mix two M4A recordings, including stereo, and verify both channels are retained in mono.
- Play, stop, mute B, and replay; confirm only A is audible. Restore B before checking offsets.
- Try +100 ms and negative offsets, then reset to 0; verify timing without cutting either track.
- Stop and replay repeatedly, including shortly after starting; confirm restarts without overlap.
- Check controls lock during loading/playback, the status line and lock notice explain why,
  and controls unlock on completion or stop.
- Tap Try the sample, play it, then change volumes and timing and play again; confirm the
  credit line opens the dataset page and disappears once both tracks are replaced.
- Turn on system dark mode and the largest font size; confirm nothing is clipped and text stays
  readable.
- Background the app and leave the screen; confirm playback stops.
- Save a mix, find it in the recordings folder through the file picker, and play it; confirm it
  sounds like the preview.

## Planned work

Deferred interface improvements and automatic timing ideas are recorded in the
[follow-up notes](FOLLOW_UPS.md) (Korean).

1. Add measurement clicks and a known delay, then record a 30-second test and report
   timing precision for a specific device and output path.
2. Evaluate automatic onset detection and further correction behavior separately
   from the current supplied-onset and manual-offset preview.

## Licensing

The bundled recordings are licensed under **CC BY 4.0**. Their creators, source links,
and the changes made to the recordings are listed in the
[audio attribution](app/src/main/assets/vocals/README.md#attribution-and-license).

A project-wide software license has not been added. The audio license applies to the
recordings and does not establish licensing terms for the app's source code.
See GitHub's [repository licensing guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)
for the distinction between publishing source code and licensing it for reuse.
