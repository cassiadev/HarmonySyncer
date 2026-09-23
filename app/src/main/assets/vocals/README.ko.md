# 포함된 음성 녹음

[English](README.md) | 한국어

이 디렉터리에는 HarmonySyncer의 음성 처리 코드와 테스트에 사용하는 고정 소프라노·알토
발췌 음원이 들어 있다. 이 문서는 파일 형식, 음악적 기준점, 출처, 재생성 방법을 설명한다.
앱의 목적, 개발 상태, 빌드 방법은 [프로젝트 README](../../../../../README.ko.md)를 참고한다.

## 파일과 기준점

| 항목 | A | B |
| --- | --- | --- |
| 파일 | `locus_iste_soprano.pcm` | `locus_iste_alto.pcm` |
| 성부 / 원본 채널 | 소프라노 / S2_DYN | 알토 / A1_DYN |
| 길이 | 8초, 384,000프레임 | 8초, 384,000프레임 |
| 크기 | 768,000바이트 | 768,000바이트 |
| 앱 파일의 음악적 기준점 | 0프레임 | 0프레임 |

두 파일의 형식은 **48,000 Hz, signed 16-bit, little-endian, mono, 헤더 없는 PCM**이다.
원본은 22,050 Hz 모노 MP3이며 앱 밖에서 변환했다. 앱은 MP3/WAV 디코더를 사용하지 않는다.

두 성부는 같은 연주 `DCS_LI_QuartetA_Take01`의 공통 시간축을 사용한다.
공개된 [마디 시각 자료](https://www.audiolabs-erlangen.de/resources/MIR/2020-DagstuhlChoirSet/data/DCS_LI_QuartetA_Take01/DCS_LI_QuartetA_Take01_Stereo_STM_measures.json)의
**1마디 시작 0.121904762초**를 기준으로, 48 kHz 변환 후
`round(0.121904762 × 48000) = 5851`프레임에서 두 파일을 똑같이 잘랐다.
각각 `[5851, 389851)` 구간을 포함하므로 파일의 0프레임이 같은 음악적 위치다.
가수별 첫 유성음이나 첫 비영 샘플을 따로 찾아 맞추지 않았으며, 연주 자체의
자연스러운 발음·진입 차이는 보존한다. 이 기준은 샘플 단위의 가창 동시성을 보장하지 않는다.

## 처리 경로

`BundledVocalDemo.loadAligned()`는 두 파일을 읽어
`alignFromOnsets(trackA, 0, trackB, 0, 48000)`에 전달한다.
`loadMixed()`는 그 결과의 두 배열을 `mixPcm16()`에 전달하며
`gainA = 0.5f`, `gainB = 0.5f`를 명시한다.
기준점을 이미 맞춘 음원이므로 보고되는 원래 상대 오프셋은 0이다.
이 값은 실제 출력의 보정 성능을 측정한 결과가 아니다.

로딩과 혼합은 작업 스레드에서 호출한다. 코루틴에서 호출하는 예시는 다음과 같다.

```kotlin
val pcm = withContext(Dispatchers.IO) {
    BundledVocalDemo.loadMixed(context.assets::open)
}
// pcm: 384,000 mono PCM16 frames; playback sample rate: BundledVocalDemo.SAMPLE_RATE
```

스트림은 로더에서 닫는다. 파일 길이가 기대값과 다르면 준비를 실패시키고,
부호와 바이트 순서를 명시해서 읽는다. 앱 실행 중에는 네트워크가 필요하지 않다.
현재 화면에는 재생기를 연결하지 않았다.

`BundledVocalDemoTest`는 APK에 넣는 것과 동일한 원본 asset을 테스트 리소스로
읽어 전체 8초 혼합 결과를 독립적으로 계산한 SHA-256과 비교한다.

## 출처와 이용 조건

음원: **Dagstuhl ChoirSet — Locus Iste (Anton Bruckner, WAB 23),
QuartetA, Take01**, 소프라노 S2 및 알토 A1의 다이내믹 마이크 녹음.

- [원본 녹음 페이지](https://www.audiolabs-erlangen.de/resources/MIR/2020-DagstuhlChoirSet/data/DCS_LI_QuartetA_Take01)
- [소프라노 MP3](https://www.audiolabs-erlangen.de/content/resources/MIR/00_2020-DagstuhlChoirSet/data/00_DCS_LI_QuartetA_Take01/DCS_LI_QuartetA_Take01_S2_DYN.mp3)
- [알토 MP3](https://www.audiolabs-erlangen.de/content/resources/MIR/00_2020-DagstuhlChoirSet/data/00_DCS_LI_QuartetA_Take01/DCS_LI_QuartetA_Take01_A1_DYN.mp3)
- [데이터셋과 라이선스](https://zenodo.org/records/4607766)

데이터셋 제작자: Sebastian Rosenzweig, Helena Cuesta, Christof Weiß,
Frank Scherbaum, Emilia Gómez, Meinard Müller.
음원 라이선스: [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).
이 디렉터리의 PCM 발췌 음원에도 같은 라이선스를 적용하며, 재배포할 때 출처와
라이선스 링크 및 변경 내역을 함께 제공한다. 제작자가 이 앱을 보증한다는 의미는 아니다.
변경 내역: MP3 디코딩, 48 kHz 리샘플링, 동일한 시간 구간의 8초 발췌,
16비트 모노 PCM 변환. 피치·속도·음량 정규화는 적용하지 않았다.

인용: Rosenzweig et al. (2020), *Dagstuhl ChoirSet: A Multitrack Dataset for MIR
Research on Choral Singing*, Transactions of the International Society for Music
Information Retrieval, 3(1), 98–110. [DOI: 10.5334/tismir.48](https://doi.org/10.5334/tismir.48).

## 앱 밖에서 다시 만들기

위 두 MP3를 원래 파일명으로 같은 디렉터리에 내려받은 뒤 프로젝트 루트에서 실행한다.
원본 전체 녹음과 변환 도구는 앱에 포함하지 않는다.

```sh
python3 tools/prepare_demo_vocals.py /path/to/source-mp3s --ffmpeg /path/to/ffmpeg
```

포함된 파일은 FFmpeg 7.1로 생성했다. 다른 디코더/리샘플러 버전은 PCM의 최하위
비트가 달라질 수 있으므로 재생성 시 파일과 혼합 결과를 확인한 뒤 해시를 갱신한다.

| 파일 | SHA-256 |
| --- | --- |
| 원본 S2_DYN.mp3 | `56e3939d19a44d6a0d95cb78548b9b25073623d0353e1e157cf594eab94d1c88` |
| 원본 A1_DYN.mp3 | `2fbd87112fd1eb65146c4e9cc557abbfe04e3c8b4a5f668af20ca6613247cded` |
| soprano.pcm | `03e4cb504a6304360080cccfd21d9a6442f738245779cb740291d1b105aa15fd` |
| alto.pcm | `4c66f940eafe91ed9d58217755b22e724abda8ae3619c29308f89e570aed7069` |
| 0.5 / 0.5 혼합 PCM | `ccde988c3668069d82acd48c525c33e4b58febcefde86a4e7f67843824186a98` |
