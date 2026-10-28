# HarmonySyncer

[English](README.md) | 한국어

HarmonySyncer는 음성 녹음의 타이밍을 맞추고 혼합하는 Android 프로토타입입니다.
시스템 파일 선택기로 두 녹음을 고르고 음량과 상대 시작 위치를 조절해 혼합 결과를 들을 수 있습니다.

앱은 두 파일의 지정 시작 지점을 모두 `0`으로 두고 정렬해 각 파일의 원래 시작 부분을
유지합니다. 지연을 **자동으로 감지하지 않습니다.** 피치 변경과 재생 중 드리프트 보정은
현재 범위에 포함되지 않습니다.

## 미리 듣기 사용법

1. A와 B에 Waveform Audio File Format(WAV, 오디오 파일 형식) 또는
   MPEG-4 Audio(M4A, 압축 오디오 파일 형식) 파일을 하나씩 선택합니다.
   WAV는 비압축 16비트 모노 Pulse-Code Modulation(PCM, 펄스 부호 변조)을 지원합니다.
   M4A는 Advanced Audio Coding(AAC, 오디오 압축 방식)의 모노·스테레오 녹음을
   기기에서 변환하며, 스테레오의 두 채널은 평균을 내어 모노로 합칩니다.
   Apple Lossless를 비롯한 다른 M4A 압축 방식은 지원하지 않습니다.
   오디오 데이터가 있어야 하며, 파일별 최대 길이는 1분(60초), 최대 크기는 32 MiB입니다.
   샘플레이트는 0보다 크고 192 kHz 이하여야 합니다. 두 파일의 샘플레이트가 다르면
   혼합 전에 windowed-sinc 필터로 더 높은 쪽에 맞춰 소리의 길이·속도·음높이를 유지합니다.
   재생하려면 기기에서도 해당 샘플레이트를 지원해야 합니다. 원본 파일을 변경하거나
   외부로 전송하지 않습니다.
2. 트랙별 음량을 0~100%로 설정합니다. **타이밍 맞추기**를 열어 B 추가 이동을
   −300~+300 ms로 조절합니다.
   양수는 B를 A보다 늦게 시작합니다. 음수는 A 앞에 무음을 넣어 B를 A보다 상대적으로
   먼저 시작합니다. 무음 추가로 두 트랙의 앞부분과 꼬리를 모두 유지하며, 읽어 둔 원본
   샘플 버퍼는 변경하지 않습니다.
3. 합친 음원을 재생합니다. 파일이나 설정을 바꾸려면 먼저 정지한 뒤 다시 재생합니다.
   다시 재생하면 새 설정으로 혼합해 처음부터 시작합니다. 로딩·재생 중에는 조작이
   잠기고, 화면이 그 이유를 알려줍니다. 읽는 중인 카드에는 진행 표시가, 상태 줄에는
   현재 단계가 나오고, 합치거나 재생하는 동안에는 잠금 안내가 표시됩니다.
   설정을 실시간으로 적용하지 않습니다.
4. **합본 저장**을 누르면 지금의 음량과 타이밍으로 합친 소리를 남깁니다. 앱의 녹음과 같은
   폴더(Android 12 이상 `Recordings/HarmonySyncer/`, Android 10·11 `Music/HarmonySyncer/`)에
   `HarmonySyncer-Mix-<날짜>-<시각>.wav`로 저장하며, 두 파일 중 높은 샘플레이트의 16비트 모노
   PCM입니다. 미리 듣기가 재생하는 샘플과 같으므로 원본보다 작게 들릴 수 있고(아래 음량 계수
   설명 참고), 자르지 않습니다. 타이밍 이동으로 합본은 최대 60.3초가 될 수 있으며, 60초 이하인
   합본만 다시 트랙으로 고를 수 있습니다. 앱이 백그라운드로 가도 저장은 계속됩니다.
   WAV는 압축하지 않아 48 kHz에서 1분에 약 5.8 MB로, 흔한 M4A보다 몇 배 큽니다.

직접 고른 파일 없이 들어보려면 시작 화면에서 **샘플로 들어보기**를 누릅니다.
Dagstuhl ChoirSet의 브루크너 「Locus iste」에서 같은 마디로 자른 8초 소프라노(A)·알토(B)
발췌가 들어가며, 일반 파일처럼 음량과 타이밍을 조절할 수 있습니다. 음원은 CC BY 4.0
라이선스이며, 샘플 트랙이 들어 있는 동안 앱에 출처와 데이터셋 링크를 표시합니다.
자세한 내용은 [포함된 음성 녹음 설명](app/src/main/assets/vocals/README.ko.md)을 참고합니다.
샘플 트랙이 든 합본을 공개하거나 배포할 때는 샘플 출처와 라이선스, 편집 사실을 함께 표시해야
하며, 앱은 그런 합본을 저장할 때 이를 안내합니다.

각 음량에 `0.5`를 곱해 혼합하므로 100% 설정은 해당 트랙에 진폭 계수 `0.5`를 적용합니다.
이는 혼합 시 여유를 유지하며, PCM16 범위 초과도 제한합니다. B를 0%로 설정하면 B만
음소거되고 A의 음량 계수는 유지됩니다.

이동 초기화 버튼은 B 추가 이동을 0 ms로 되돌립니다. 어느 파일이든 성공적으로 교체하면
이동은 0 ms로 초기화되고 음량 설정은 유지됩니다. 파일 선택을 취소하거나 교체 파일을
읽는 데 실패하면 이전 파일과 설정을 유지합니다. WAV와 M4A를 섞어 선택할 수 있으며,
샘플레이트가 달라도 함께 재생할 수 있습니다. 앱이 백그라운드로 이동하면
재생을 정지하고, 화면을 벗어나면 재생 자원을 해제하고 진행 중인 작업을 취소합니다.
합본을 저장하는 중에 화면을 벗어나면 합치는 단계는 취소되지만, 시작된 파일 쓰기는 끝까지 마칩니다.

## 빌드와 실행

### 필요한 환경

- 프로젝트의 Android Gradle Plugin 9.4.1을 지원하는 Android Studio.
- JDK 21. [app/build.gradle.kts](app/build.gradle.kts)의 Java 소스·바이트코드 호환성 설정과
  [gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties)의 Gradle 데몬 버전에
  맞춘 환경입니다.
- 빌드용 Android SDK Platform 37.
- 앱 실행용 Android API 29 이상의 기기 또는 에뮬레이터.

Gradle 래퍼가 Gradle 9.8.1을 선택하므로 Gradle을 별도로 설치할 필요는 없습니다.
첫 빌드에서는 빌드 의존성을 내려받기 위한 인터넷 연결이 필요합니다.

### Android Studio

1. 이 저장소를 복제하거나 내려받고, 루트 디렉터리를 Android Studio에서 엽니다.
2. SDK Manager에서 SDK Platform 37을 설치하고 Gradle이 JDK 21을 사용하도록 설정합니다.
3. Gradle 동기화를 마친 뒤 `app` 실행 구성을 선택하고 기기 또는 에뮬레이터를 지정합니다.
4. 앱을 실행하고 A·B WAV 또는 M4A 녹음 파일을 선택해 미리 듣기를 사용합니다.

### 명령줄

`JAVA_HOME`을 유효한 JDK 21 설치 경로로 설정합니다. SDK 경로는 Android Studio가
생성하는 Git 추적 대상이 아닌 `local.properties` 또는
[`ANDROID_HOME`](https://developer.android.com/tools/variables)으로 지정합니다.
저장소 루트에서 다음 명령을 실행합니다.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`를 사용합니다.
디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.
JUnit 결과 보고서는 `app/build/reports/tests/testDebugUnitTest/index.html`에 생성됩니다.
단위 테스트는 개발 컴퓨터에서 실행하며 Android 기기가 필요하지 않습니다.

## 포함된 음원

프로젝트에는 Dagstuhl ChoirSet의 *Locus Iste* 녹음에서 발췌한 실제 소프라노·알토
음원이 포함돼 있습니다. 각 파일은 8초 길이의 48 kHz·16비트·모노 PCM입니다.
두 트랙은 같은 연주의 같은 음악적 기준점을 사용합니다.

PCM 파일은 프로젝트에 포함돼 있습니다. Python과 FFmpeg는 개발자가 이 파일들을
다시 만들 때만 사용하며, Android 앱의 빌드나 실행에는 필요하지 않습니다.
현재 앱은 외부 WAV·M4A 파일을 선택하는 UI를 제공하며, 포함 음원 데모 버튼은 없습니다.
출처, 저작자 표시, 기준점, 파일 형식, 재생성 방법은
[음원 설명서](app/src/main/assets/vocals/README.ko.md)에 정리돼 있습니다.

## 코드 안내

| 역할 | 파일 |
| --- | --- |
| 화면과 생명주기 | [MainActivity](app/src/main/java/com/cassiadev/harmonysyncer/MainActivity.kt), [AudioPreviewScreen](app/src/main/java/com/cassiadev/harmonysyncer/ui/AudioPreviewScreen.kt) |
| 파일 읽기와 검증 | [AudioFileLoader](app/src/main/java/com/cassiadev/harmonysyncer/audio/AudioFileLoader.kt), [WavPcmReader](app/src/main/java/com/cassiadev/harmonysyncer/audio/WavPcmReader.kt), [M4aPcmReader](app/src/main/java/com/cassiadev/harmonysyncer/audio/M4aPcmReader.kt) |
| 혼합과 미리 듣기 상태 | [PcmPreview](app/src/main/java/com/cassiadev/harmonysyncer/audio/PcmPreview.kt), [PcmResampler](app/src/main/java/com/cassiadev/harmonysyncer/audio/PcmResampler.kt), [AudioPreviewController](app/src/main/java/com/cassiadev/harmonysyncer/audio/AudioPreviewController.kt) |
| 재생 | [PcmPreviewPlayer](app/src/main/java/com/cassiadev/harmonysyncer/audio/PcmPreviewPlayer.kt): 완료·취소 시 재생 자원 해제 |
| 회귀 테스트 | [음성 테스트](app/src/test/java/com/cassiadev/harmonysyncer/audio), [실단말 테스트](app/src/androidTest/java/com/cassiadev/harmonysyncer) |
| 음원 준비 | [prepare_demo_vocals.py](tools/prepare_demo_vocals.py) |

로딩·혼합·재생은 UI 스레드 밖에서 실행합니다. 단위 테스트는 계산, 데이터 무결성,
컨트롤러 상태를 검증하며, 스피커로 출력된 소리의 타이밍을 측정하지 않습니다.

## 수동 확인 목록

뚜렷한 클릭이나 시작 소리가 있는 짧은 호환 파일 두 개로 [미리 듣기 동작](#미리-듣기-사용법)을 확인합니다.

- A·B를 선택하고 파일명, 길이, 샘플레이트, 미리 듣기 활성화를 확인합니다.
- 교체 선택을 취소하고 잘못된 형식·지원하지 않는 형식·길이 초과·크기 초과 파일을 시도합니다.
  오류 메시지와 이전 파일·설정 유지를 확인합니다. 정상 교체 시 이동만 초기화돼야 합니다.
- 44.1 kHz M4A와 48 kHz WAV를 함께 재생해 소리의 길이·속도·음높이가 유지되는지 확인합니다.
- 스테레오를 포함해 M4A 두 파일을 혼합하고, 두 채널이 모노에 모두 반영되는지 확인합니다.
- 재생·정지 후 B를 음소거하고 다시 재생해 A만 들리는지 확인합니다. 이동 확인 전에 B 음량을 복원합니다.
- +100 ms와 음수 이동을 적용하고 0으로 초기화해 타이밍과 두 트랙의 앞부분·꼬리 보존을 확인합니다.
- 재생 직후 정지를 포함해 정지·재생을 반복하고, 겹침 없이 처음부터 재생되는지 확인합니다.
- 로딩·재생 중 조작이 잠기고 상태 줄과 잠금 안내가 이유를 알려주는지, 완료·정지 후
  다시 활성화되는지 확인합니다.
- 샘플로 들어보기 → 재생 → 음량·타이밍 조절 후 다시 재생을 확인하고, 출처 줄이 데이터셋
  페이지를 열며 두 트랙을 모두 바꾸면 사라지는지 확인합니다.
- 시스템 다크 모드와 가장 큰 글꼴 크기에서 잘리는 요소 없이 읽을 수 있는지 확인합니다.
- 앱을 백그라운드로 보내고 화면을 벗어나 재생 정지를 확인합니다.
- 합본을 저장하고 파일 선택기의 녹음 폴더에서 찾아 재생해, 미리 듣기와 같은 소리인지 확인합니다.

## 개발 예정 기능

사용자가 미뤄 둔 화면 개선과 자동 타이밍 검토 메모는 [후속 작업 메모](FOLLOW_UPS.md)에 있습니다.

1. 측정용 클릭과 알고 있는 지연을 추가한 뒤 30초 테스트를 녹음하고, 특정 기기와
   출력 경로에서 타이밍 정밀도를 기록합니다.
2. 현재의 지정 시작 지점·수동 이동 미리 듣기와 별도로 시작 지점 자동 감지와
   추가 보정 동작을 검토합니다.

## 라이선스

포함된 녹음의 라이선스는 **CC BY 4.0**입니다. 제작자, 원본 링크, 녹음에 적용한 변경 사항은
[음원 출처와 이용 조건](app/src/main/assets/vocals/README.ko.md#출처와-이용-조건)에 있습니다.

프로젝트 전체에 적용되는 소프트웨어 라이선스는 아직 추가되지 않았습니다.
음원 라이선스는 녹음에 적용되며 앱 소스 코드의 이용 조건을 정하지는 않습니다.
소스 공개와 재사용을 위한 라이선스 지정의 차이는
GitHub의 [저장소 라이선스 안내](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)에서 확인할 수 있습니다.
