# HarmonySyncer

[English](README.md) | 한국어

HarmonySyncer는 음성 녹음의 타이밍을 맞추고 혼합하는 Android 프로토타입입니다.
보정 전후의 소리를 번갈아 듣고, 기기의 실제 출력을 녹음해 보정 결과를 확인할 수 있는
앱을 목표로 개발하고 있습니다.

**현재 개발 상태: 앱을 실행하면 `Hello Android!` 예제 화면이 표시됩니다.
음악 재생과 조작 UI는 아직 구현되지 않았습니다.** 개발자는 프로젝트를 빌드하고
음성 처리 테스트를 실행할 수 있습니다. 일반 사용자가 앱에서 녹음을 듣거나
보정하는 기능은 아직 제공하지 않습니다.

## 현재 기능

| 기능 | 상태 |
| --- | --- |
| 수동으로 지정한 기준점으로 모노 PCM16 두 트랙 정렬 | Kotlin 코드로 구현 |
| 트랙별 음량 계수와 PCM16 범위 초과 처리를 적용해 혼합 | Kotlin 코드로 구현 |
| 포함된 소프라노·알토 녹음 읽기와 혼합 | 구현됨; 각 8초, 48 kHz, 음량 계수 각각 0.5 |
| JUnit 4로 정렬·혼합·포함 음원 검증 | 구현됨 |
| 앱에서 재생·정지·재시작 및 보정 켜기/끄기 비교 | 예정 |
| 외부 녹음으로 상대 타이밍 오차 측정 | 예정; 실제 출력 측정 결과는 아직 없음 |

현재 정렬은 알고 있는 기준점을 사용합니다. 서로 다른 음성의 지연을 자동으로
추정하지는 않습니다. 파일 가져오기, 피치 변경, 재생 중 드리프트 보정은 현재 범위에
포함되지 않습니다. 저장소에는 현재 Android 앱만 있습니다.

## 빌드와 실행

### 필요한 환경

- 프로젝트의 Android Gradle Plugin 9.4.0을 지원하는 Android Studio.
- JDK 21. [app/build.gradle.kts](app/build.gradle.kts)의 Java 소스·바이트코드 호환성 설정과
  [gradle-daemon-jvm.properties](gradle/gradle-daemon-jvm.properties)의 Gradle 데몬 버전에
  맞춘 환경입니다.
- 빌드용 Android SDK Platform 37.
- 앱 실행용 Android API 29 이상의 기기 또는 에뮬레이터.

Gradle 래퍼가 Gradle 9.6.0을 선택하므로 Gradle을 별도로 설치할 필요는 없습니다.
첫 빌드에서는 빌드 의존성을 내려받기 위한 인터넷 연결이 필요합니다.

### Android Studio

1. 이 저장소를 복제하거나 내려받고, 루트 디렉터리를 Android Studio에서 엽니다.
2. SDK Manager에서 SDK Platform 37을 설치하고 Gradle이 JDK 21을 사용하도록 설정합니다.
3. Gradle 동기화를 마친 뒤 `app` 실행 구성을 선택하고 기기 또는 에뮬레이터를 지정합니다.
4. 앱을 실행합니다. 현재 빌드에서는 위에서 설명한 예제 화면이 표시되며 소리는 재생되지 않습니다.

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
출처, 저작자 표시, 기준점, 파일 형식, 재생성 방법은
[음원 설명서](app/src/main/assets/vocals/README.ko.md)에 정리돼 있습니다.

## 코드 안내

- [음성 처리](app/src/main/java/com/cassiadev/harmonysyncer/audio): 정렬, 혼합,
  포함 음원 준비 코드.
- [앱 진입점](app/src/main/java/com/cassiadev/harmonysyncer/MainActivity.kt):
  현재 예제 화면을 표시하는 Compose 코드.
- [음성 테스트](app/src/test/java/com/cassiadev/harmonysyncer/audio): 알고 있는
  20 ms 지연 복사본과 포함 음원의 전체 혼합 결과 등을 검증하는 JUnit 4 회귀 테스트.
- [음원 준비 스크립트](tools/prepare_demo_vocals.py): 앱 밖에서 고정 원본 녹음을 변환하는 도구.

로딩과 혼합은 작업 스레드에서 실행해야 합니다. 처리 코드는 PCM 샘플을 반환하며
재생 세션을 관리하지 않습니다. 단위 테스트는 계산과 데이터의 무결성을 검증하며,
스피커로 출력된 소리의 타이밍을 측정하지 않습니다.

## 개발 예정 기능

1. 준비된 음원을 단일 AudioTrack에 연결하고 재생·정지·재시작 및 재생 종료·화면 이탈 시
   자원 정리를 구현합니다.
2. Compose 화면에 재생 제어, 보정 켜기/끄기, 재생 상태를 추가합니다.
3. 측정용 클릭과 알고 있는 지연을 추가한 뒤, 특정 기기와 출력 경로에서 실제로 녹음해
   보정 결과를 기록합니다.

## 라이선스

포함된 녹음의 라이선스는 **CC BY 4.0**입니다. 제작자, 원본 링크, 녹음에 적용한 변경 사항은
[음원 출처와 이용 조건](app/src/main/assets/vocals/README.ko.md#출처와-이용-조건)에 있습니다.

프로젝트 전체에 적용되는 소프트웨어 라이선스는 아직 추가되지 않았습니다.
음원 라이선스는 녹음에 적용되며 앱 소스 코드의 이용 조건을 정하지는 않습니다.
소스 공개와 재사용을 위한 라이선스 지정의 차이는
GitHub의 [저장소 라이선스 안내](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)에서 확인할 수 있습니다.
