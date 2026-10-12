# HarmonySyncer

English | [한국어](README.ko.md)

HarmonySyncer is an Android prototype for aligning and mixing vocal recordings.
The app now supports **file selection → existing alignment → volume and start-position
adjustment → mixed audio preview**. Choose two recordings through Android's system file
picker, adjust each track, and listen to the resulting mix.

**Development status: file loading, preview controls, and playback are implemented.**
Automatic onset detection and measurement of the device's actual audio timing remain
future work.

## Current capabilities

| Capability | Status |
| --- | --- |
| Select A and B through the system file picker | Implemented; WAV PCM16 mono, up to 15 seconds and 8 MiB per file |
| Validate sample rates before mixing | Implemented; positive rates up to 192 kHz, with matching rates required |
| Reuse the existing supplied-reference-point alignment | Implemented; external files use reference point 0 for both tracks |
| Adjust A/B volume and B's relative start position | Implemented; 0–100% per track and −300 to +300 ms |
| Mix into one PCM16 buffer, play, stop, and restart | Implemented; playback resources are released on completion or cancellation |
| Verify file parsing, alignment, mixing, and controller behavior with JUnit 4 | Implemented |
| Load and mix bundled soprano and alto recordings | Existing code and assets retained; no bundled-demo button in the current app |
| Measure relative timing error from external recordings | Planned; no physical output results are available |

The existing alignment accepts supplied onset indices. The app passes `0` for both
selected files, preserving their original beginnings; it does **not** automatically
detect or estimate the delay between voices. Pitch changes, playback drift correction,
and audio export are outside the current scope. The repository contains an Android app.

## Using the preview

1. Select a WAV file for A and another for B. Each must contain nonempty, uncompressed,
   16-bit mono PCM at a positive sample rate no higher than 192 kHz, be no longer than
   15 seconds, and be no larger than 8 MiB. Both files must have the same sample rate;
   the app does not resample them. The device must support that rate for playback.
2. Set each volume between 0 and 100% and adjust B's additional offset between −300
   and +300 ms. A positive offset starts B later than A. A negative offset starts B
   earlier relative to A by adding silence before A. Padding preserves both tracks'
   beginnings and tails; the loaded source buffers remain unchanged.
3. Play the mix. Stop it before changing files or settings, then play again to rebuild
   the mix and restart from the beginning. Controls are disabled during loading and
   playback; changes are not applied in real time.

Each volume is multiplied by `0.5` before mixing, so a 100% setting gives that track a
linear gain of `0.5`. This preserves mixing headroom, with PCM16 saturation protection.
Setting B to 0% mutes B while leaving A's gain unchanged.

The offset reset button returns B's additional offset to 0 ms. Successfully replacing
either file also resets the offset to 0 ms and keeps the volume settings. Canceling the
picker or failing to load a replacement retains the previous file and settings. A
sample-rate mismatch shows an error and disables preview until a compatible file is
selected. Playback stops when the app goes into the background; leaving the screen
releases playback resources and cancels pending work. There is no export action.

## Build and run

### Requirements

- Android Studio compatible with the project's Android Gradle Plugin 9.4.0.
- JDK 21, matching the app's Java source and bytecode compatibility settings in
  [app/build.gradle.kts](app/build.gradle.kts) and the Gradle daemon version in
  [gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties).
- Android SDK Platform 37 for building.
- An Android device or emulator running API 29 or later to launch the app.

The Gradle wrapper selects Gradle 9.6.0; a separate Gradle installation is unnecessary.
The first build needs internet access to download build dependencies.

### Android Studio

1. Clone or download this repository and open its root directory in Android Studio.
2. Install SDK Platform 37 through SDK Manager and configure Gradle to use JDK 21.
3. Let Gradle sync, select the `app` run configuration, and choose a device or emulator.
4. Run the app, select compatible A and B WAV files, and use the preview controls.

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
The current app selects external WAV files and does not expose a bundled-demo button.
See the [audio asset documentation](app/src/main/assets/vocals/README.md) for sources,
attribution, reference points, format details, and regeneration instructions.

## Working with the code

- [App entry point](app/src/main/java/com/cassiadev/harmonysyncer/MainActivity.kt) and
  [preview screen](app/src/main/java/com/cassiadev/harmonysyncer/ui/AudioPreviewScreen.kt):
  system file pickers, lifecycle handling, and Compose controls.
- [File loader](app/src/main/java/com/cassiadev/harmonysyncer/audio/AudioFileLoader.kt) and
  [WAV reader](app/src/main/java/com/cassiadev/harmonysyncer/audio/WavPcmReader.kt):
  URI access on a worker thread and bounded, validated PCM16 decoding.
- [Preview processing](app/src/main/java/com/cassiadev/harmonysyncer/audio/PcmPreview.kt):
  supplied-onset alignment, manual offset padding, and volume-scaled mixing.
- [Preview controller](app/src/main/java/com/cassiadev/harmonysyncer/audio/AudioPreviewController.kt):
  selected clips, settings, loading/playback status, and canceled-session handling.
- [Preview player](app/src/main/java/com/cassiadev/harmonysyncer/audio/PcmPreviewPlayer.kt):
  one static AudioTrack, completion tracking, and resource cleanup.
- [Audio tests](app/src/test/java/com/cassiadev/harmonysyncer/audio): JUnit 4 regression
  tests for WAV validation, offsets, gains, lifecycle behavior, existing alignment,
  and bundled audio.
- [Asset preparation script](tools/prepare_demo_vocals.py): conversion of the fixed
  source recordings outside the app.

Loading, rendering, and playback run off the UI thread. Unit tests verify calculations,
data integrity, and controller state; they do not measure the timing of sound emitted
by a speaker.

## Manual acceptance checklist

Use two short PCM16 mono WAV files with matching sample rates. A clear click or onset
in each makes relative timing easier to hear.

- Select A and B and confirm their filenames, durations, and rates appear. Preview
  becomes available only after both compatible files have loaded.
- Cancel a replacement selection, then try a malformed, unsupported, overlong, or
  oversized WAV. Confirm the previous file, volumes, and offset are retained, with a
  readable error for failed loading. Successfully replace a file and confirm only
  the offset resets to 0 ms.
- Select files with different rates and confirm preview is disabled with a mismatch
  message. Replace one with a matching-rate file and confirm preview becomes available.
- Play at the default settings, then stop. Set B to 0%, play again, and confirm only A
  is audible. Restore B's volume before checking offsets.
- Set B's offset to +100 ms and confirm B starts later. Check a negative offset too:
  B starts earlier relative to A, with neither track's beginning or tail discarded.
  Reset to 0 ms and confirm the baseline timing returns.
- Stop and replay several times, including shortly after starting. Confirm every
  replay starts at the beginning and no previous playback continues alongside it.
- Confirm file selection and sliders are disabled while loading or playing, and
  become available when playback ends or is stopped.
- Send the app to the background during playback and confirm audio stops. Leave the
  screen and confirm no playback remains active.

## Planned work

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
