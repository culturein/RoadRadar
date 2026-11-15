# 로드레이더 (RoadRadar) 프로토타입

내 차의 센서 데이터만으로 빗길·눈길·블랙아이스 위험을 판단해 **운전자에게만** 알려 주는 Pleos Connect 앱입니다.
현대자동차그룹 차량용 앱 개발 공모전 출품용 프로토타입입니다.

- APK: [`apk/로드레이더_로드레이더_release.apk`](apk/)
- 기본 버전은 **위치 정보 없이** 동작합니다.
- **INTERNET 권한이 없습니다.** 서버, 계정, 외부 전송이 없고 기록은 차량 안(앱 전용 저장소)에만 남습니다.

## 주요 기능

| 기능 | 내용 |
| --- | --- |
| 노면 신호등 | 정상·주의·경계·위험 4단계와 권장 감속률(20%·20~50%·50%)을 큰 색상으로 표시 |
| 음성 선제 경고 | 출발 순간 위험 단계라면 한 번, 주행 중에는 단계가 올라갈 때만 한 문장으로 안내 |
| 출발 전 브리핑 | 정차 중 외기온도, 최근 강수(와이퍼) 기록, 타이어 공기압으로 오늘의 노면 위험 안내 |
| 개인화 경고 | 이전 주행에서 미끄럼 제어가 작동했던 조건(기온 ±2℃, 같은 시간대)이 다시 맞으면 한 단계 일찍 경고 |
| 악천후 운전 리포트 | 주차 후 위험 구간 시간, 감속 정도, 미끄럼 제어·급제동·급조향 횟수 표시. 기록 전체 삭제 가능 |
| 주행 중 화면 제한 | 주행 중에는 신호등과 신호 요약만 보여주고 브리핑·리포트·설정은 정차 중에만 표시 |

## 판단 규칙 (기획서 4장)

위에서부터 차례로 확인해 처음 해당하는 단계로 정합니다.

1. **위험**: ABS·TCS·ESC 작동, 또는 와이퍼 고속 + 0℃ 이하
2. **경계**: 와이퍼 작동 + 4℃ 이하, 4℃ 이하 + 최근 6시간 강수 기록, 0℃ 이하 + 새벽(5~10시)
3. **주의**: 와이퍼 작동 + 4℃ 초과
4. **정상**

- 개인화 조건이 맞으면 한 단계 올립니다. 개인화에는 이전 주행의 기록만 씁니다.
- 단계는 올라갈 때 즉시 바뀌고, 내려갈 때는 60초(데모 10초) 동안 낮은 판단이 유지되어야 바뀝니다.
- 수치(4℃, 6시간, ±2℃, 60초, 타이어 200kPa, 급제동 10km/h/s, 급조향 90°/s)는 **초기 가정값**입니다. `EngineConfig`에서 조정합니다.

## 사용하는 차량 데이터

| 신호 | 출처 | 코드 |
| --- | --- | --- |
| 외기온도, 차속, ABS·TCS, ESC, 와이퍼 스위치, 기어 | AAOS 표준 `CarPropertyManager` | `signals/CarPropertySource.kt` |
| 와이퍼, 타이어 공기압, 조향각, 브레이크 페달 | Pleos Vehicle SDK | `signals/PleosVehicleSource.kt` |
| 시연용 신호 | 데모 시나리오 / 수동 조작 | `signals/DemoSource.kt` |

Pleos Connect SDK는 AAOS 표준에 같은 기능이 있으면 표준 API 사용을 권장하므로, 표준 속성이 있는 신호는 `CarPropertyManager`로 읽습니다.
차종마다 지원 신호가 다르므로 지원되지 않거나 권한이 없는 신호는 건너뛰고, 나머지 신호로 판단을 계속합니다. 화면의 '내 차 신호' 카드에 지원 여부가 표시됩니다.

## 프로젝트 구조

```
app/src/main/java/kr/roadradar/app/
├── engine/        판단 엔진 (Android 의존성 없는 순수 Kotlin, 단위 테스트 대상)
│   ├── Model.kt          위험 단계, 신호 스냅샷, 판단 기준값(EngineConfig)
│   ├── RiskEngine.kt     3단계 판단 규칙 + 개인화, 단계 상태 기계
│   ├── TripRecorder.kt   주행 기록 → 악천후 운전 리포트
│   └── Messages.kt       음성 안내·브리핑 문구
├── signals/       신호 수집 (AAOS 표준, Pleos Vehicle SDK, 데모)
├── data/          차량 내 로컬 저장 (JSON 파일, 데모는 별도 파일)
├── voice/         음성 안내 (Android TTS → 출시 시 Pleos TextToSpeech SDK로 교체)
├── ui/            Compose 화면, ViewModel (1초 주기 판단 루프)
└── MainActivity.kt
app/src/test/.../RiskEngineTest.kt   판단 규칙 단위 테스트 13개
```

## 테스트 환경

| 항목 | 내용 |
| --- | --- |
| 에뮬레이터 | Pleos Connect Emulator **v2.1.0** (AVD `Pleos_Connect_v2.1.0`, Android 14 / API 34, x86_64) |
| 화면 규격 | **2560 × 1440 px, 가로(landscape), 160 dpi** |
| 개발 도구 | Android Studio (Windows), JDK 17 이상 |
| 앱 | `kr.roadradar.app`, minSdk 29 / targetSdk 34 / compileSdk 35, Kotlin + Jetpack Compose |
| Pleos SDK | `ai.pleos.playground:Vehicle:2.3.2` (Pleos 공개 Maven `nexus-playground.pleos.ai`) |

## 필요한 설정

- Pleos Connect Emulator 설치: Android Studio > SDK Manager > SDK Update Sites에 Pleos Connect System Image 주소를 추가하고, SDK Platforms의 Android 14.0(API 34) 아래 Pleos Connect 이미지를 설치합니다. ([Pleos 공식 가이드](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
- AVD를 만들 때 Additional settings > Default boot를 **Cold**로 설정합니다. Quick boot에서는 차량 신호(VHAL)를 쓸 수 없습니다.
- 에뮬레이터 볼륨을 켜 두면 음성 안내를 들을 수 있습니다.
- 앱은 네트워크 권한이 없으므로 별도 계정·서버 설정이 필요 없습니다.

## 실행 방법

**APK로 실행**
1. Pleos Connect Emulator를 실행합니다.
2. `apk/로드레이더_로드레이더_release.apk`를 에뮬레이터 창에 끌어다 놓거나 `adb install apk/로드레이더_로드레이더_release.apk`로 설치합니다.
3. 에뮬레이터 하단 바의 앱 목록에서 **로드레이더**를 실행합니다.

**소스로 실행**
1. Android Studio에서 이 저장소 폴더(`settings.gradle.kts`가 있는 폴더)를 엽니다.
2. Gradle Sync가 끝나면 기기를 Pleos Connect Emulator로 선택하고 ▶ Run을 누릅니다.
3. 단위 테스트: `./gradlew test` / release APK: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (설치·시연용으로 디버그 키 서명)

## 시연 순서 (데모 모드)

1. 앱 실행 후 정차 상태에서 상단의 **데모** 스위치를 켭니다. 가정 조건은 오전 6시, 3시간 전 강수 기록입니다.
2. 약 70초 분량의 **12월 새벽 출근길** 시나리오가 재생됩니다.
   - 0~8초: 정차 중 출발 전 브리핑 ("외기온도 1도, 3시간 전 강수 기록… 오늘은 결빙 주의입니다")
   - 8초: 출발과 함께 **경계** 음성 안내 ("블랙아이스 주의…")
   - 32초: 다리 위에서 TCS 작동 → **위험** ("미끄럼이 감지됐습니다. 절반 속도로…"), 미끄럼 조건 저장
   - 45초: 약한 비(와이퍼 간헐) → **경계** 유지
   - 64초: 주차 → 악천후 운전 리포트 표시
3. **다음 날 아침 다시 달리기**를 누르면 전날 미끄럼 기록이 반영됩니다. 브리핑에 "예전에 내 차가 미끄러졌던 날과 조건이 비슷합니다"가 추가되고, 경계 대신 **위험**으로 한 단계 일찍 경고합니다.
4. 시나리오 중이나 후에 데모 패널에서 기온, 와이퍼, 미끄럼(TCS), 출발·주차를 직접 바꿔 볼 수 있습니다.

Virtual Console에서 같은 신호를 넣을 수 있다면 데모를 끄고 실제 수집원으로 시연하는 것이 더 설득력 있습니다.

## 구현 현황 (2026-11-15)

- ✅ Pleos Connect Emulator v2.1.0에서 빌드·실행 확인
- ✅ 외기온도를 AAOS 표준 속성으로 실제 수신해 판단·브리핑에 반영
- ✅ 판단 엔진 단위 테스트 13개 통과, 데모 시나리오(1일차·다음 날 개인화) 동작
- ⬜ 와이퍼·미끄럼 제어·차속·타이어 신호는 에뮬레이터에서 '미지원'으로 표시됨 → 런타임 권한 요청과 Vehicle SDK 기능 이름 확인으로 보완 예정 (Logcat `RoadRadar` 태그에 연결 결과 출력)
- ⬜ 악천후 공조 제안(성에 제거 실행), Pleos TextToSpeech SDK 적용은 2차 구현 항목
