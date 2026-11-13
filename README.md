# 잠깐만 (정차 순간 위반 예방 도우미)

길가에 차를 세우는 순간, 그 자리가 정차도 금지된 곳이면 차가 한 문장으로 먼저 알리고 "응" 한마디에 가까운 공영주차장으로 안내하는 Pleos Connect 앱입니다.

| 항목 | 내용 |
|---|---|
| 앱 | 잠깐만 (패키지 `app.jamkkanman`, 버전 0.1.0) |
| APK | [`apk/jamkkanman_잠깐만_release.apk`](apk/) (release 빌드) |
| 에뮬레이터 | Pleos Connect Emulator 2.1.0 |
| 화면 규격 | 2560x1440, 160dpi, 가로 (앱은 오른쪽 앱 영역에 뜸) |
| 시연 영상 | [`docs/video/잠깐만_앱동작_재현데모.mp4`](docs/video/) (2분 9초) |

## 이런 앱입니다

- **신호 대기에서는 조용합니다.** 잠깐 멈춘 것만으로는 말하지 않고, 기어 P, 목적지 도착, 운전석 문 열림처럼 차를 세우려는 신호가 올 때만 그 자리를 봅니다.
- **한 문장으로 먼저 알립니다.** 소화전 5m, 버스정류장 10m, 횡단보도 10m 안이면 "소화전 5미터 안이에요. 잠깐 정차도 안 되는 곳입니다. 근처 공영주차장으로 안내할까요?"처럼 말합니다.
- **"응"이면 주차장으로 안내합니다.** 지금 운영 중인 가장 가까운 공영주차장으로 내비 목적지를 바꿉니다. "금방 가"면 50초 뒤 한 번만 더 알리고, "괜찮아"면 그 장소는 30일 동안 조용히 있습니다.
- **판단은 차 안에서 끝납니다.** 실행 중 LLM 호출과 네트워크 통신이 없고, 위치와 주행 기록을 차 밖으로 보내지 않습니다.

## 실행 방법

1. Pleos Connect 에뮬레이터 2.1.0을 Cold boot로 켭니다. ([에뮬레이터 준비](docs/emulator-setup.md))
2. APK를 설치하며 권한(속도, 위치)과 "다른 앱 위에 표시"를 허용합니다.
   ```
   adb install -r -g apk/jamkkanman_잠깐만_release.apk
   adb shell appops set --user 10 app.jamkkanman SYSTEM_ALERT_WINDOW allow
   ```
3. 앱 목록에서 "잠깐만"을 열고, 화면 아래 버튼으로 동작을 고릅니다.
   - **신호 대기, 소화전 앞 정차, 버스정류장 앞 하차**: 녹화된 차량 신호로 세 장면을 재생합니다. 가장 빠르게 확인하는 방법입니다.
   - **실차 신호**: Virtual Console로 넣은 실제 차량 신호로 동작합니다. ([신호 넣는 법](docs/emulator-notes.md#virtual-console로-신호-넣기))
   - **지표**: 시나리오 200여 개를 이 기기에서 재생해 결과와 판단 속도를 보여 줍니다.
   - **신호 점검**: 앱이 쓰는 SDK와 차량 신호가 이 에뮬레이터에서 되는지 하나씩 확인합니다.

소스로 빌드하려면 JDK 17 이상, Android SDK(플랫폼 35, 빌드 도구 35), 인터넷(Pleos Maven 저장소)이 필요합니다.

```
./gradlew :core:test             # 엔진 시험
./gradlew :app:assembleRelease   # release APK (서명 값은 저장소 밖 keystore.properties에서 읽음)
```

## 테스트 환경과 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator 2.1.0 (`system-images;android-34;pleos_car_emulator;x86_64`, 빌드 `RELEASE.release_connect_v2.1.0`) |
| 화면 규격 | 2560x1440, 160dpi(mdpi), 12.8인치, 가로 |
| AVD 설정 | RAM 4GB, Cold boot, GPU host |
| Android | API 34 (minSdk 28, targetSdk 34, compileSdk 35) |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1, FusedLocation 1.1.3 |
| 권한 | 속도와 위치(설치할 때 `-g`로 허용), 다른 앱 위에 표시(정차 중 확인 화면용, 없어도 음성 안내는 동작) |
| 네트워크 | 필요 없음 (TTS와 STT 모두 ON_DEVICE) |
| 개발 PC | Windows 11, JDK 21, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.0 |

## 핵심 지표

| 지표 | 결과 |
|---|---|
| 신호 대기 100번 | 잘못 알림 0번 |
| 정차하면 안 되는 곳 100번 | 100번 모두 알림 (한 번 설 때 최대 2번) |
| 세우려는 신호에서 첫 음성까지 | 중앙값 45ms (에뮬레이터 9회, 최대 499ms) |
| 판단 시간 | 평균 0.2ms 이내, 느린 경우(p99)도 2ms 이내 |

자세한 측정 방법과 전체 표는 [docs/metrics/metrics.md](docs/metrics/metrics.md)와 [docs/metrics/device.md](docs/metrics/device.md)에 있습니다.

## 더 보기

- [동작 방식](docs/how-it-works.md): 신호에서 안내까지의 흐름, 실제로 쓰는 SDK 신호, 정차 상태 기계, 판정 규칙과 발화 정책, 시나리오 재생기
- [데이터](docs/data.md): 노원역 주변 공공데이터 238곳(소화전, 버스정류장, 횡단보도, 공영주차장)의 출처와 이용 조건, 시연 지점
- [에뮬레이터 메모](docs/emulator-notes.md): 에뮬레이터에서 확인한 한계와 우회, Virtual Console 신호 입력, 디버그 빌드용 명령
- [시연 영상](docs/video/README.md): 영상 세 편 설명과 녹화 도구

## 저장소 구성

| 폴더 | 내용 |
|---|---|
| `core/` | 순수 Kotlin 엔진: 정차 상태 기계, 판정, 발화 정책, 응답 분류, 시나리오 재생기, 지표 |
| `app/` | Android 앱: 상주 서비스, 차량 신호, 음성 안내와 인식, 내비 연결, 화면 |
| `apk/` | release APK |
| `docs/` | 문서와 시연 영상 |
| `tools/` | 시연과 녹화 도구 |

## 라이선스와 유의 사항

- Pleos Connect SDK는 42dot의 SDK License Agreement를 따르며 이 저장소에 포함하지 않습니다(Gradle이 Pleos Maven 저장소에서 받습니다).
- 공공데이터는 각 출처의 이용 조건(공공누리 등)을 따릅니다. ([docs/data.md](docs/data.md))
- APK 파일명의 팀명 자리는 개인 참가라 실명 대신 별칭 `jamkkanman`을 썼습니다.
