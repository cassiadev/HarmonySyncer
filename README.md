# HarmonySyncer

English | [한국어](README.ko.md)

HarmonySyncer is an Android prototype for aligning and mixing vocal recordings.
Its goal is to let people compare the sound before and after timing correction,
then check the result by recording the device's audio output.

**Development status: the app currently opens a `Hello Android!` placeholder screen.
Audio playback and interactive controls are not implemented yet.** Developers can build
the project and run its audio processing tests. Listening to or correcting recordings
through the app is not available to end users yet.

## Current capabilities

| Capability | Status |
| --- | --- |
| Align two mono PCM16 tracks using manually supplied reference points | Implemented in Kotlin |
| Mix two tracks with independent gains and PCM16 clipping protection | Implemented in Kotlin |
| Load and mix bundled soprano and alto recordings | Implemented; eight seconds per track, 48 kHz, gains of 0.5 each |
| Verify alignment, mixing, and bundled audio with JUnit 4 | Implemented |
| Play, stop, restart, and compare correction on/off in the app | Planned |
| Measure relative timing error from external recordings | Planned; no physical output results are available |

Alignment currently uses known reference points. It does not automatically detect the
delay between different voices. File importing, pitch changes, and drift correction
during playback are outside the current scope. The repository currently contains an
Android app only.

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
4. Run the app. The expected result is the placeholder screen described above; no audio
   plays in the current build.

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
See the [audio asset documentation](app/src/main/assets/vocals/README.md) for sources,
attribution, reference points, format details, and regeneration instructions.

## Working with the code

- [Audio processing](app/src/main/java/com/cassiadev/harmonysyncer/audio): alignment,
  mixing, and preparation of the bundled recordings.
- [App entry point](app/src/main/java/com/cassiadev/harmonysyncer/MainActivity.kt):
  the Compose screen, currently a placeholder.
- [Audio tests](app/src/test/java/com/cassiadev/harmonysyncer/audio): JUnit 4 regression
  tests, including a known 20 ms delayed copy and the complete bundled audio mix.
- [Asset preparation script](tools/prepare_demo_vocals.py): conversion of the fixed
  source recordings outside the app.

Loading and mixing must run on a worker thread. The processing code returns PCM samples;
it does not own a playback session. Unit tests verify calculations and data integrity,
not the timing of sound emitted by a speaker.

## Planned work

1. Connect the prepared audio to a single AudioTrack with playback, stop, restart,
   and cleanup when playback ends or the screen is left.
2. Add playback controls, a correction toggle, and playback status to the Compose screen.
3. Add measurement clicks and a known delay, then record and report correction results
   for a specific device and output path.

## Licensing

The bundled recordings are licensed under **CC BY 4.0**. Their creators, source links,
and the changes made to the recordings are listed in the
[audio attribution](app/src/main/assets/vocals/README.md#attribution-and-license).

A project-wide software license has not been added. The audio license applies to the
recordings and does not establish licensing terms for the app's source code.
See GitHub's [repository licensing guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)
for the distinction between publishing source code and licensing it for reuse.
