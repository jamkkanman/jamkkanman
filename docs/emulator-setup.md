# Pleos Connect 에뮬레이터 2.1.0 준비 (명령줄)

공식 안내는 Android Studio의 SDK Manager에 업데이트 사이트를 추가하는 방법이다
(https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator).
이 저장소는 Android Studio 없이 명령줄로 같은 결과를 만들었고, 그 순서를 남긴다.

## 1. 시스템 이미지

1. 저장소 XML을 받는다: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - 패키지 경로 `system-images;android-34;pleos_car_emulator;x86_64`, 압축 파일 `crp_car_emulator.zip`(4,087,751,964바이트, SHA-1 `b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa`).
   - 42dot의 Pleos Connect SDK License Agreement에 동의해야 설치할 수 있다.
2. 압축을 풀면 `crp_avd_out_x86_64/` 폴더가 나온다(풀면 약 19GB). 이를 `<SDK>/system-images/android-34/pleos_car_emulator/x86_64/` 로 옮긴다.
   - 디스크가 부족하면 다른 드라이브에 두고 `system-images/android-34` 자리에 정션을 만든다.
3. `python tools/make_pleos_package_xml.py crp-sys-img.xml <이미지 폴더>/package.xml` 로 `package.xml`을 만든다.
   SDK 도구가 수동 설치한 이미지를 패키지로 인식하게 하는 파일이다.

## 2. AVD

```
avdmanager create avd -n Pleos_Connect_v2.1.0 -k "system-images;android-34;pleos_car_emulator;x86_64" -d Pleos_Connect_v2_0
```

`config.ini`를 공식 권장값으로 고친다.

| 키 | 값 | 이유 |
|---|---|---|
| hw.ramSize | 4096M | 문서 권장 RAM 4GB |
| fastboot.forceColdBoot | yes | Quick boot는 VHAL을 끈다 |
| fastboot.forceFastBoot | no | 위와 같음 |
| hw.gpu.enabled, hw.gpu.mode | yes, host | 전용 GPU 필수 |
| disk.dataPartition.size | 6G | 앱 설치 여유 |

실행: `emulator -avd Pleos_Connect_v2.1.0 -no-snapshot -gpu host`

화면 규격: 2560x1440, 160dpi(mdpi), 12.8인치, 가로. Pleos Connect 화면 오른쪽 앱 영역에 앱이 뜬다.

## 3. Virtual Console을 스크립트로 쓰기

```
adb forward tcp:8088 tcp:8088
python tools/vc_drive.py "gear Drive" "speed 30" "sleep 6" "speed 0" "sleep 4" "gear Park"
```

- Virtual Console은 브라우저 세션 신호(`POST /browser/session?client=<id>`, 1초마다)가 있어야 쓰기를 받는다.
  신호가 없으면 모든 쓰기가 `{"error":"emulator_inactive"}`로 거절된다. `vc_drive.py`가 이 신호를 대신 보낸다.
- 쓰는 API: `PUT /vehicle/gear?gear=Drive`, `PUT /vehicle/controls?speed=30&accelerator=20&brake=0&steering=0`,
  `PUT /vehicle/body?door_fl=true`, `PUT /vehicle/lights?feature=hazard&value=true`, `PUT /vehicle/turn-signal?mode=BlinkLeft`.
- 위치는 `adb emu geo fix <경도> <위도>`로 넣는다(경도가 먼저). `vc_drive.py "geo <위도> <경도>"`로도 된다.

## 4. 이 에뮬레이터에서 확인한 신호 (2026-11-12)

| 신호 | 일반 앱에서 | Virtual Console로 바꾸기 |
|---|---|---|
| 속도 PERF_VEHICLE_SPEED | 읽힘(android.car.permission.CAR_SPEED, 런타임 허용) | 됨 (`/vehicle/controls?speed=`) |
| 기어 GEAR_SELECTION | 읽힘(CAR_POWERTRAIN) | 됨 |
| 비상등 HAZARD_LIGHTS_STATE | 안 읽힘(CAR_EXTERIOR_LIGHTS는 signature 권한) | 카드는 있으나 앱이 받을 수 없음 |
| 운전석 문 (Vehicle SDK) | 읽힘 | 됨 |
| 운전석 안전벨트 (Vehicle SDK) | 읽힘, 값은 false 고정 | 카드 없음 |
| 주차 브레이크 PARKING_BRAKE_ON | 읽힘 | 카드 없음 |
| FusedLocation | 됨 (confidence 5.0) | `adb emu geo fix` |
| TextToSpeech ON_DEVICE ko-KR | 됨 | |
| SpeechToText ON_DEVICE | 됨 (sendAudio로 녹음 파일 입력) | 마이크는 호스트 설정에 따름 |
| NaviHelper requestRoute | 됨 (임의 poiId 허용) | 내비 현재 위치는 내비 자체 값 |
