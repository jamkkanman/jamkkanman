# 시연 영상

[README로 돌아가기](../../README.md)

| 파일 | 길이 | 내용 |
|---|---|---|
| `잠깐만_앱동작_재현데모.mp4` | 2분 9초 | 앱 동작 재현 데모(접수 메일 첨부본). 왼쪽 주행 화면은 Pleos Connect 에뮬레이터 화면 모양을 본떠 장면에 맞게 다시 그렸고(차 모델과 계기 표시는 에뮬레이터 녹화에서 가져옴), 오른쪽 앱 화면은 실측 기록(판정 거리, 안내 문장, 반응 시간, 시뮬레이션 결과)으로 다시 그린 UI 시안입니다(다음에 앱에 반영). 주차장 안내 지도는 실제 도로(© OpenStreetMap 기여자)로 그렸고, 나레이션이 장면마다 설명합니다 |
| `잠깐만_시연영상_실제화면.mp4` | 2분 39초 | Pleos Connect 에뮬레이터 2.1.0 화면을 그대로 녹화한 시연 |
| `잠깐만_콘셉트목업.mp4` | 1분 10초 | 앱을 만들기 전에 만든 콘셉트 영상. 실제 차량 화면이 아니며, 영상 속 비상등 신호는 앱에서 기어 P와 목적지 도착이 대신합니다 |

## 실제 화면 녹화를 만든 도구

- `tools/demo_live.py`: Virtual Console(속도, 기어, 문), `adb emu geo fix`(위치), 녹음한 운전자 대답으로 장면을 돌리며 `tools/record_window.py`로 녹화하고, 장면마다 앱 로그를 남깁니다.
- `tools/record_window.py`: 에뮬레이터 창을 Windows Graphics Capture(ffmpeg `gfxcapture`)로 받아 GPU 인코더(NVENC)로 녹화하고, 소리는 에뮬레이터 gRPC(`streamAudio`)로 받습니다.
  에뮬레이터 자체 녹화(`adb emu screenrecord`)는 왼쪽 Pleos 주행 화면의 새 장면을 초당 약 5장만 담아 끊겨 보여서 바꿨습니다(창 녹화는 에뮬레이터가 그린 장면을 빠짐없이 담습니다).
- `tools/align_audio.py`: gRPC로 받은 소리가 화면보다 0.7~4초 늦고 그 폭이 문장마다 달라서, 문장마다 앱 로그의 음성 시작 시각(TextToSpeech onStart)에 맞춰 놓습니다. 운전자 대답은 음성 인식 시작 직전에 놓습니다.
- `tools/make_demo_video.py`: 녹화본을 잘라 자막을 넣고 하나의 MP4로 합칩니다(ffmpeg).
- `tools/device_metrics.py`: 녹화 때 남긴 앱 로그에서 반응 시간을 모아 `docs/metrics/device.md`를 씁니다.
- 필요한 것: ffmpeg 8 이상, Python 패키지 `grpcio`와 `grpcio-tools`.
