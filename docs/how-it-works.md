# 동작 방식

[README로 돌아가기](../README.md)

## 신호에서 안내까지

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

## 실제로 쓰는 신호와 막힌 신호 (에뮬레이터 2.1.0에서 확인)

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

## 정차 상태 기계

| 단계 | 들어가는 조건 | 동작 |
|---|---|---|
| 주행 | 속도 5km/h 초과가 5초 | 정차별 상태 초기화 |
| 멈춤 | 속도 0이 3초, 또는 기어 P | 말하지 않음. 그 자리를 미리 확인해 둠 |
| 세우려는 의도 | 멈춤 중 기어 P, 비상등, 목적지 도착 | 금지 구역이면 첫 안내 |
| 내리기 직전 | 멈춤 중 운전석 문 열림, 벨트 해제 | 첫 안내 전이면 첫 안내, 응답이 없었으면 짧은 확인 |

앱을 켰을 때 이미 서 있던 차는 첫 정차에서 말하지 않습니다(출발 전 차를 안내하지 않기 위해).

## 판정 규칙과 발화 정책

- d는 가장 가까운 시설까지 거리, R은 법정 거리, m은 위치 오차 여유(기본 5m, FusedLocation confidence가 1~50이면 그 값).
  d <= R이면 "안이에요", R < d <= R + m이면 "가까이일 수 있어요", 그 밖은 침묵. 여러 구역이면 "안"이 먼저, 그다음 과태료가 큰 구역.
- 횡단보도는 점 데이터라 법정 10m에 횡단보도 길이의 절반을 더해 가장자리에서 재는 것에 가깝게 했습니다(길이 정보가 없으면 8m로 봄).
- 정차 한 번에 안내는 최대 2번, 같은 장소는 10분 쿨다운, "괜찮아"는 그 장소를 30일 침묵 목록에 넣음, 주차장 30m 안에서는 침묵,
  내비 음성 직후 5초는 기다림(엔진과 재생기에는 있고, 실차 신호 원천은 내비 음성 시점을 받을 API가 없어 연결하지 않음).
- "금방 가"는 50초 뒤에도 서 있으면 한 번만 더 알립니다. 주차장 안내와 응답 확인 문장은 운전자가 요청한 대답이라 횟수에서 뺍니다.

## 시나리오 재생기

- 실차 신호 원천과 재생기가 같은 `Signal` 객체를 엔진에 넣습니다. 디버그 빌드의 실차 신호 모드는 받은 신호를 그대로 JSON 줄 형식으로
  `files/recordings/live-*.jsonl`(앱 전용 저장소)에 기록하고, 이 파일은 `Scenario.fromRecording`으로 다시 재생됩니다.
  release 빌드는 위치 기록을 남기지 않고, logcat에도 좌표와 인식된 말을 쓰지 않습니다.
- 시연 시나리오: `app/src/main/assets/scenarios/*.json` (`./gradlew :core:demoScenarios`로 시연 지점에서 생성)
- 형식 예: `{"t": 11.5, "type": "GEAR", "gear": "P"}`, `{"t": 19.5, "type": "REPLY", "text": "응", "audio": "demo_voice/reply_yes.wav"}`

## 데이터

서울 노원구 노원역 남쪽 "로그인 편의점 앞" 지상식 소화전을 중심으로 반경 600m의 공공데이터를 내장했습니다.
소화전 103곳(OA-21306), 버스정류장 24곳(OA-15067), 횡단보도 106곳(OA-15554, T-GIS), 노상 공영주차장 5곳(전국주차장정보표준데이터).
출처, 기준일, 이용 조건, 시연 지점은 [data.md](data.md)에 있습니다.
공영주차장 5곳이 평일 09:00~19:00 운영이라 19시 이후와 공휴일에는 주차장 안내 대신 "운영 중인 공영주차장을 찾지 못했어요"라고 답합니다.

## 지표

| 지표 | 목표 | 측정값 |
|---|---|---|
| 신호 대기 오경보 (금지 거리 안 정지선 100개) | 0회 | 0회 |
| 반응 시간 (세우려는 신호에서 첫 음성까지, 에뮬레이터) | 1초 이내 | 9회 중앙값 45ms, 최대 499ms |
| 판정 시간 (격자 조회와 거리 계산, 에뮬레이터) | 10ms 이내 | 평균 0.06~0.16ms, p99 0.4~2.0ms (서울 규모 6만 곳 평균 0.24~0.33ms, p99 2.1~4.1ms) |
| 발화 예산 | 최대 2회 | 최대 2회 (생성 정차 200개 평균 0.75회) |
| 구역 판정 (위치 잡음 5m, 오차 여유 5m) | 곡선 공개 | "안" 정밀도 0.84, 재현율 0.59, "가까이일 수 있어요"까지 재현율 0.90 |

전체 표는 [metrics/metrics.md](metrics/metrics.md)(`./gradlew :core:metrics`로 다시 생성)와 [metrics/device.md](metrics/device.md)에 있습니다.
