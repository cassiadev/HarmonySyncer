package com.cassiadev.harmonysyncer.audio

const val AUDIO_MAX_DURATION_SECONDS = 60
const val AUDIO_MAX_FILE_BYTES = 32 * 1024 * 1024

// Shared by the WAV and M4A readers: say what is wrong with the file, then what to choose instead.
const val AUDIO_TOO_LONG_MESSAGE =
    "${AUDIO_MAX_DURATION_SECONDS}초가 넘는 파일입니다. ${AUDIO_MAX_DURATION_SECONDS}초 이하 파일을 선택해 주세요."
const val AUDIO_TOO_LARGE_MESSAGE =
    "파일이 너무 큽니다. ${AUDIO_MAX_FILE_BYTES / (1024 * 1024)} MiB 이하 파일을 선택해 주세요."
