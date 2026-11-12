# 잠깐만 (정차 순간 위반 예방 도우미)

길가에 서는 순간 그 자리가 정차도 금지된 곳(소화전 5m, 버스정류소 10m, 횡단보도 10m)이면 차가 한 문장으로 먼저 알리고, "응" 한마디에 가까운 공영주차장으로 내비 목적지를 바꿔 주는 Pleos Connect 앱입니다.
신호 대기처럼 세우려는 신호가 없을 때는 말하지 않습니다.

- 앱 이름: 잠깐만, 패키지 `app.jamkkanman`, 버전 0.1.0
- 공모전: 현대자동차그룹 차량용 앱 개발 공모전 (Pleos App Market)
- APK: [`apk/jamkkanman_잠깐만_release.apk`](apk/) (release 빌드, 서명 CN=jamkkanman)

## 1. 실행 방법

### APK로 바로 실행

1. Pleos Connect 에뮬레이터 2.1.0을 Cold boot로 실행합니다. ([에뮬레이터 준비](docs/emulator-setup.md))
2. 설치하면서 런타임 권한(속도, 위치)을 허용하고, 정차 중 확인 화면을 띄우는 "다른 앱 위에 표시"를 허용합니다.
   ```
   adb install -r -g apk/jamkkanman_잠깐만_release.apk
   adb shell appops set --user 10 app.jamkkanman SYSTEM_ALERT_WINDOW allow
   ```
   `-g` 없이 설치하면 처음 실행할 때 권한 창이 뜹니다. 허용을 누르면 됩니다.
   "다른 앱 위에 표시"를 허용하지 않아도 음성 안내와 응답은 그대로 되고, 확인 화면만 앱 아이콘으로 엽니다.
   (Pleos Connect는 차가 움직이면 오른쪽 화면을 내비로 바꿉니다. 앱은 상주 서비스로 신호를 계속 받고, 정차 중 안내할 때만 확인 화면을 앞으로 띄웁니다.)
3. 앱 목록에서 "잠깐만"을 엽니다. 화면 아래의 버튼으로 동작을 고릅니다.
   - **실차 신호**: 에뮬레이터의 실제 차량 신호로 동작합니다. Virtual Console과 `adb emu geo fix`로 입력합니다(아래 3절).
   - **신호 대기, 소화전 앞 정차, 버스정류소 앞 하차**: 같은 엔진에 녹화된 신호를 실제 시간으로 재생합니다.
   - **지표**: 시연 시나리오와 생성 시나리오 200개를 이 기기에서 가상 시계로 재생해 결과와 판정 시간을 보여 줍니다.
   - **신호 점검**: 앱이 쓰는 SDK와 차량 신호를 하나씩 호출해 지금 에뮬레이터에서 되는지 보여 줍니다.

### 소스로 빌드

- JDK 17 이상, Android SDK(플랫폼 35, 빌드 도구 35), 인터넷(Pleos Maven 저장소)이 필요합니다.
- `./gradlew :core:test` 엔진 단위 시험(JVM)
- `./gradlew :core:metrics` 지표를 다시 재서 `docs/metrics/metrics.md`를 씁니다
- `./gradlew :app:assembleDebug` 디버그 APK
- `./gradlew :app:assembleRelease` release APK. 서명 값은 저장소 밖 `keystore.properties`에서 읽고, 없으면 서명 없는 APK를 만듭니다.

## 2. 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator 2.1.0 (시스템 이미지 `system-images;android-34;pleos_car_emulator;x86_64`, 빌드 `RELEASE.release_connect_v2.1.0`) |
| 화면 규격 | 2560x1440, 160dpi(mdpi), 12.8인치, 가로. 앱은 Pleos Connect 화면 오른쪽 앱 영역에 뜹니다 |
| AVD 설정 | RAM 4GB, Default boot Cold, GPU host |
| Android | API 34 (minSdk 28, targetSdk 34, compileSdk 35) |
| SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1, FusedLocation 1.1.3 |
| 개발 PC | Windows 11, JDK 21, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.0 |
| 네트워크 | 앱 실행에는 필요 없습니다. TTS와 STT는 ON_DEVICE 모드라 앱 등록과 서버 연결이 없습니다 |

### Virtual Console로 신호 넣기 (실차 신호 모드)

```
adb forward tcp:8088 tcp:8088
python tools/vc_drive.py "geo <위도> <경도>" "gear Drive" "speed 30" "sleep 6" "speed 0" "sleep 4" "gear Park"
```

Virtual Console은 브라우저 세션 신호가 1초마다 와야 쓰기를 받습니다. 브라우저로 `http://127.0.0.1:8088/`을 열어 직접 눌러도 되고,
`tools/vc_drive.py`가 같은 신호를 보내며 명령을 차례로 실행합니다. 시연 위치 좌표는 `app/src/main/assets/data/demo_points.json`에 있습니다.

## 3. 무엇을 만들었나

```
차량 신호 ──> 정차 상태 기계 ──> 금지 구역 판정 ──> 발화 정책 ──> TTS 한 문장 ──> STT 또는 화면 버튼 응답 ──> NaviHelper 경로 변경
(AAOS 속도, 기어                주행, 멈춤,           격자 색인 +        정차당 최대 2번,    ON_DEVICE ko-KR    "응", "금방 가", "괜찮아"     공영주차장
 Vehicle SDK 문, 벨트            세우려는 의도,        법정 거리           10분 쿨다운,                         키워드 규칙
 FusedLocation 위치              내리기 직전                               30일 침묵 목록
 NaviHelper 도착)
```

- `core/`: 순수 Kotlin 엔진(안드로이드 의존 없음). 정차 상태 기계, 판정, 발화 정책, 응답 분류, 시나리오 재생기, 지표 측정.
  같은 신호 열과 같은 시계를 넣으면 같은 결과가 나옵니다(`replayIsDeterministic` 시험).
- `app/`: 상주 서비스(`EngineService.kt`, 포그라운드 서비스), 실차 신호 원천(`live/VehicleSignalSource.kt`), TTS와 STT(`live/Voice.kt`), 내비(`live/Navigator.kt`), 화면(`ui/`).
- 실행 중 LLM 호출은 0회입니다. 판정, 정책, 응답 분류가 모두 규칙과 표입니다. 위치와 주행 기록을 차 밖으로 보내지 않습니다.

### 실제로 쓰는 신호와 막힌 신호 (에뮬레이터 2.1.0에서 확인)

| 쓰임 | 신호 | API | 상태 |
|---|---|---|---|
| 주행과 멈춤 | 차량 속도 | AAOS `CarPropertyManager` `PERF_VEHICLE_SPEED` (`android.car.permission.CAR_SPEED`) | 사용 |
| 세우려는 의도 | 기어 P | AAOS `GEAR_SELECTION` (`android.car.permission.CAR_POWERTRAIN`) | 사용 |
| 세우려는 의도 | 비상등 | AAOS `HAZARD_LIGHTS_STATE` | **막힘**: `CAR_EXTERIOR_LIGHTS`가 signature 권한이라 일반 앱에 보이지 않음. 기획서 5번 표의 대체 경로(기어 P, 목적지 도착)로 바꿈. 엔진에는 입력 자리가 남아 있어 권한이 열리면 그대로 씀 |
| 세우려는 의도 | 목적지 도착 | NaviHelper `onDestinationArrived` | 사용 |
| 내리기 직전 | 운전석 문 열림 | Vehicle SDK `registerDoorPosition` (`pleos.car.permission.CAR_DOORS`) | 사용 |
| 내리기 직전 | 운전석 벨트 해제 | Vehicle SDK `registerSeatBeltBuckledState` (`CAR_SEATS`) | 구독은 됨. 에뮬레이터 값이 false로 고정이라 해제 순간(매고 있다가 풀림)만 신호로 씀 |
| 위치 | 위치 | FusedLocation `registerFusedLocationCallback` (`pleos.car.permission.FUSED_LOCATION`) | 사용 |
| 안내 | 음성 | TextToSpeech ON_DEVICE ko-KR (`TTS_SERVICE`) | 사용 |
| 응답 | 음성 인식 | SpeechToText ON_DEVICE (`STT_SERVICE`). 마이크(`request`)와 녹음 파일(`sendAudio`) | 사용 |
| 주차장 안내 | 경로 변경 | NaviHelper `requestRoute` (`NAVI_ROUTE_SEARCH`). poiId에 공공데이터 주차장 관리번호 | 사용 |

### 정차 상태 기계

| 단계 | 들어가는 조건 | 동작 |
|---|---|---|
| 주행 | 속도 5km/h 초과가 5초 | 정차별 상태 초기화 |
| 멈춤 | 속도 0이 3초, 또는 기어 P | 말하지 않음. 판정을 미리 계산 |
| 세우려는 의도 | 멈춤 중 기어 P, 비상등, 목적지 도착 | 금지 구역이면 첫 안내 |
| 내리기 직전 | 멈춤 중 운전석 문 열림, 벨트 해제 | 첫 안내 전이면 첫 안내, 응답이 없었으면 짧은 확인 |

앱을 켰을 때 이미 서 있던 차는 첫 정차에서 말하지 않습니다(출발 전 차를 안내하지 않기 위해).

### 판정 규칙과 발화 정책

- d는 가장 가까운 시설까지 거리, R은 법정 거리, m은 위치 오차 여유(기본 5m, FusedLocation confidence가 1~50이면 그 값).
  d <= R이면 "안이에요", R < d <= R + m이면 "가까이일 수 있어요", 그 밖은 침묵. 여러 구역이면 "안"이 먼저, 그다음 과태료가 큰 구역.
- 횡단보도는 점 데이터라 법정 10m에 횡단보도 길이의 절반을 더해 가장자리에서 재는 것에 가깝게 했습니다(길이 정보가 없으면 8m로 봄).
- 정차 한 번에 안내는 최대 2번, 같은 장소는 10분 쿨다운, "괜찮아"는 그 장소를 30일 침묵 목록에 넣음, 주차장 30m 안에서는 침묵,
  내비 음성 직후 5초는 기다림(엔진과 재생기에는 있고, 실차 신호 원천은 내비 음성 시점을 받을 API가 없어 연결하지 않음).
- "금방 가"는 50초 뒤에도 서 있으면 한 번만 더 알립니다. 주차장 안내와 응답 확인 문장은 운전자가 요청한 대답이라 횟수에서 뺍니다.

### 시나리오 재생기

- 실차 신호 원천과 재생기가 같은 `Signal` 객체를 엔진에 넣습니다. 디버그 빌드의 실차 신호 모드는 받은 신호를 그대로 JSON 줄 형식으로
  `files/recordings/live-*.jsonl`(앱 전용 저장소)에 기록하고, 이 파일은 `Scenario.fromRecording`으로 다시 재생됩니다.
  release 빌드는 위치 기록을 남기지 않고, logcat에도 좌표와 인식된 말을 쓰지 않습니다.
- 시연 시나리오: `app/src/main/assets/scenarios/*.json` (`./gradlew :core:demoScenarios`로 시연 지점에서 생성)
- 형식 예: `{"t": 11.5, "type": "GEAR", "gear": "P"}`, `{"t": 19.5, "type": "REPLY", "text": "응", "audio": "demo_voice/reply_yes.wav"}`

## 4. 데이터

서울 노원구 노원역 남쪽 "로그인 편의점 앞" 지상식 소화전을 중심으로 반경 600m의 공공데이터를 내장했습니다.
소화전 103곳(OA-21306), 버스정류소 24곳(OA-15067), 횡단보도 106곳(OA-15554, T-GIS), 노상 공영주차장 5곳(전국주차장정보표준데이터).
출처, 기준일, 이용 조건, 시연 지점은 [docs/data.md](docs/data.md)에 있습니다.
공영주차장 5곳이 평일 09:00~19:00 운영이라 19시 이후와 공휴일에는 주차장 안내 대신 "운영 중인 공영주차장을 찾지 못했어요"라고 답합니다.

## 5. 지표

| 지표 | 목표 | 측정값 |
|---|---|---|
| 신호 대기 오경보 (금지 거리 안 정지선 100개) | 0회 | 0회 |
| 반응 시간 (세우려는 신호에서 첫 음성까지, 에뮬레이터) | 1초 이내 | 9회 중앙값 45ms, 최대 499ms |
| 판정 시간 (격자 조회와 거리 계산, 에뮬레이터) | 10ms 이내 | 평균 0.06~0.16ms, p99 0.4~2.0ms (서울 규모 6만 곳 평균 0.24~0.33ms, p99 2.1~4.1ms) |
| 발화 예산 | 최대 2회 | 최대 2회 (생성 정차 200개 평균 0.75회) |
| 구역 판정 (위치 잡음 5m, 오차 여유 5m) | 곡선 공개 | "안" 정밀도 0.84, 재현율 0.59, "가까이일 수 있어요"까지 재현율 0.90 |

전체 표는 [docs/metrics/metrics.md](docs/metrics/metrics.md)(`./gradlew :core:metrics`로 다시 생성)와 [docs/metrics/device.md](docs/metrics/device.md)에 있습니다.

## 6. APK 위치

- [`apk/jamkkanman_잠깐만_release.apk`](apk/)
- 개인 참가라 파일명 규칙의 팀명 자리는 실명 대신 별칭 `jamkkanman`을 썼습니다(접수 양식의 실명 비노출 규칙).

## 7. 시연용 adb 명령 (디버그 빌드만)

```
adb shell am start -n app.jamkkanman/.MainActivity --es action replay --es asset scenarios/02_hydrant_gear_p.json
adb shell am start -n app.jamkkanman/.MainActivity --es action driver_voice --es asset demo_voice/reply_yes.wav
adb shell am start -n app.jamkkanman/.MainActivity --es action live
adb shell am start -n app.jamkkanman/.MainActivity --es action metrics
```

이 명령은 디버그 빌드(`./gradlew :app:assembleDebug`)에서만 동작합니다. release APK는 다른 앱의 인텐트로 응답이나 경로 변경을 받지 않도록 막았고, 화면 아래 버튼으로 같은 시나리오를 재생할 수 있습니다.
`driver_voice`는 운전자 대답을 녹음한 파일(Windows 한국어 TTS로 만든 16kHz 음성)을 스피커로 재생하면서 같은 파일을 SpeechToText `sendAudio`로 넣습니다.
에뮬레이터 마이크 대신 쓰는 시연 장치이고, 인식 결과는 실제 STT 엔진이 낸 것입니다.

## 8. 에뮬레이터에서 확인한 한계

| 항목 | 내용 | 앱에서 한 일 |
|---|---|---|
| 비상등 | `HAZARD_LIGHTS_STATE`는 signature 권한이라 일반 앱에 보이지 않음 | 기어 P와 목적지 도착을 세우려는 의도로 씀. 화면에 "읽을 수 없음"으로 표시 |
| 안전벨트 | 읽기와 구독은 되지만 에뮬레이터 값이 false로 고정이고 Virtual Console 카드가 없음 | 매고 있다가 풀린 순간만 신호로 씀(고정값으로 오경보가 나지 않음) |
| 주행 중 화면 | 차가 움직이면 Pleos가 오른쪽 화면을 내비로 바꾸고, 앱 프로세스는 백그라운드에서 얼려져 신호가 늦게 몰려 옴 | 상주 포그라운드 서비스로 신호를 계속 받고, 정차 중 안내할 때만 확인 화면을 앞으로 띄움 |
| 내비 현재 위치 | 내비 앱의 현재 위치가 GPS(`geo fix`)와 무관하게 성남시 수정구 한 지점으로 고정 | 경로 변경 자체는 됨. 화면에 보이는 경로 거리만 시연 위치와 다름 |
| 경로 취소 | NaviHelper `cancelRoute` 요청이 내비에 전달되지만 안내가 끝나지 않음 | 시연 스크립트는 장면 사이에 내비를 다시 열고 "안내 종료"를 누름 |

## 9. 시연 영상 만들기

- `tools/demo_live.py`: Virtual Console(속도, 기어, 문), `adb emu geo fix`(위치), 녹음한 운전자 대답으로 장면을 돌리며 에뮬레이터 자체 녹화(`adb emu screenrecord`, 화면과 소리)로 녹화합니다.
- `tools/make_demo_video.py`: 녹화본을 잘라 자막을 넣고 하나의 MP4로 합칩니다(ffmpeg).
- `tools/device_metrics.py`: 녹화 때 남긴 앱 로그에서 반응 시간을 모아 `docs/metrics/device.md`를 씁니다.

## 라이선스와 유의 사항

- Pleos Connect SDK는 42dot의 SDK License Agreement를 따르며 이 저장소에 포함하지 않습니다(Gradle이 Pleos Maven 저장소에서 받음).
- 공공데이터는 각 출처의 이용 조건(공공누리 등)을 따릅니다. [docs/data.md](docs/data.md) 참조.
