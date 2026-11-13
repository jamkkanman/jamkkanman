# 에뮬레이터 메모

[README로 돌아가기](../README.md)

## 설치와 화면

- `adb install`에 `-g`를 빼면 처음 실행할 때 권한 창이 뜹니다. 허용을 누르면 됩니다.
- "다른 앱 위에 표시"를 허용하지 않아도 음성 안내와 응답은 그대로 되고, 확인 화면만 앱 아이콘으로 엽니다.
- Pleos Connect는 차가 움직이면 오른쪽 화면을 내비로 바꿉니다. 앱은 상주 서비스로 신호를 계속 받고, 정차 중 안내할 때만 확인 화면을 앞으로 띄웁니다.

## Virtual Console로 신호 넣기

앱의 "실차 신호" 모드는 에뮬레이터의 실제 차량 신호로 동작합니다. 신호는 Virtual Console과 `adb emu geo fix`로 넣습니다.

```
adb forward tcp:8088 tcp:8088
python tools/vc_drive.py "geo <위도> <경도>" "gear Drive" "speed 30" "sleep 6" "speed 0" "sleep 4" "gear Park"
```

Virtual Console은 브라우저 세션 신호가 1초마다 와야 쓰기를 받습니다. 브라우저로 `http://127.0.0.1:8088/`을 열어 직접 눌러도 되고,
`tools/vc_drive.py`가 같은 신호를 보내며 명령을 차례로 실행합니다. 시연 위치 좌표는 `app/src/main/assets/data/demo_points.json`에 있습니다.

## 확인한 한계와 앱에서 한 일

| 항목 | 내용 | 앱에서 한 일 |
|---|---|---|
| 비상등 | `HAZARD_LIGHTS_STATE`는 signature 권한이라 일반 앱에 보이지 않음 | 기어 P와 목적지 도착을 세우려는 의도로 씀. 화면에 "읽을 수 없음"으로 표시 |
| 안전벨트 | 읽기와 구독은 되지만 에뮬레이터 값이 false로 고정이고 Virtual Console 카드가 없음 | 매고 있다가 풀린 순간만 신호로 씀(고정값으로 오경보가 나지 않음) |
| 주행 중 화면 | 차가 움직이면 Pleos가 오른쪽 화면을 내비로 바꾸고, 앱 프로세스는 백그라운드에서 얼려져 신호가 늦게 몰려 옴 | 상주 포그라운드 서비스로 신호를 계속 받고, 정차 중 안내할 때만 확인 화면을 앞으로 띄움 |
| 내비 현재 위치 | 내비 앱의 현재 위치가 GPS(`geo fix`)와 무관하게 성남시 수정구 한 지점으로 고정 | 경로 변경 자체는 됨. 화면에 보이는 경로 거리만 시연 위치와 다름 |
| 경로 취소 | NaviHelper `cancelRoute` 요청이 내비에 전달되지만 안내가 끝나지 않음 | 시연 스크립트는 장면 사이에 내비를 다시 열고 "안내 종료"를 누름 |

## 시연용 adb 명령 (디버그 빌드만)

```
adb shell am start -n app.jamkkanman/.MainActivity --es action replay --es asset scenarios/02_hydrant_gear_p.json
adb shell am start -n app.jamkkanman/.MainActivity --es action driver_voice --es asset demo_voice/reply_yes.wav
adb shell am start -n app.jamkkanman/.MainActivity --es action live
adb shell am start -n app.jamkkanman/.MainActivity --es action metrics
```

이 명령은 디버그 빌드(`./gradlew :app:assembleDebug`)에서만 동작합니다. release APK는 다른 앱의 인텐트로 응답이나 경로 변경을 받지 않도록 막았고, 화면 아래 버튼으로 같은 시나리오를 재생할 수 있습니다.
`driver_voice`는 운전자 대답을 녹음한 파일(Windows 한국어 TTS로 만든 16kHz 음성)을 스피커로 재생하면서 같은 파일을 SpeechToText `sendAudio`로 넣습니다.
에뮬레이터 마이크 대신 쓰는 시연 장치이고, 인식 결과는 실제 STT 엔진이 낸 것입니다.
