package com.cassiadev.harmonysyncer.audio

// Copy from the in-app recording design, section 7.
internal const val MICROPHONE_UNAVAILABLE_MESSAGE = "마이크를 사용할 수 없습니다. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요."
internal const val MICROPHONE_DENIED_MESSAGE = "녹음하려면 마이크 권한이 필요합니다. 설정에서 마이크를 허용해 주세요."
internal const val RECORDING_TIME_LIMIT_NOTICE = "${AUDIO_MAX_DURATION_SECONDS}초가 되어 녹음을 멈추고 저장했습니다."
internal const val RECORDING_INTERRUPTED_NOTICE = "녹음이 중단되어 여기까지 저장했습니다."
internal const val RECORDING_SAVE_RETRY_MESSAGE = "녹음을 파일로 저장하지 못했습니다. 앱을 다시 열면 저장을 다시 시도합니다."
internal const val RECORDING_SAVE_LOST_MESSAGE = "녹음을 파일로 저장하지 못했습니다. 앱을 닫으면 이 녹음은 사라집니다."

internal fun buildRecoveredNotice(count: Int) = "중단된 녹음 ${count}개를 복구해 저장했습니다. 녹음 폴더에서 고를 수 있습니다."
