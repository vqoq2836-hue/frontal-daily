# 전두엽 데일리 (안드로이드)

웹 버전 화면을 그대로 쓰면서, 안드로이드에서만 되는 세 가지를 붙인 앱입니다.

- **삼성헬스 자동 연동**: 헬스 커넥트에서 오늘 걸음 수와 어젯밤 수면 시간을 읽어 자동 채점
- **체크할 때까지 다시 묻는 알림**: 미션 시간에 알림 → 체크 안 하면 30분 간격으로 최대 3번 다시
- **알림창에서 바로 체크**: [작게 했어요] [제대로 했어요] [30분 뒤]

## 폴더 구조

```
web/index.html                  화면 원본 (웹 버전과 같은 파일)
tools/wrap_asset.py             web/index.html → 앱용 assets/index.html 변환
app/src/main/assets/index.html  앱 안에서 보이는 화면 (변환 결과)
app/src/main/java/.../          안드로이드 코드
  MainActivity.kt               화면(WebView)과 브리지(window.NativeApp)
  HealthReader.kt               헬스 커넥트에서 걸음·수면 읽기
  Reminders.kt                  알림 예약, 다시 묻기, 알림 버튼 처리
  AlarmReceiver.kt              알림 시간이 되면 실행되는 부분
.github/workflows/build.yml     GitHub에서 APK 자동 빌드
app/debug.keystore              서명 키 (업데이트해도 기록이 지워지지 않게 고정)
```

화면을 고친 뒤에는 꼭 변환을 다시 하세요.

```
python3 tools/wrap_asset.py web/index.html app/src/main/assets/index.html
```

## 빌드 방법 1: GitHub (추천, 설치 프로그램 없음)

1. GitHub에서 새 저장소를 만들고(Private 가능) 이 폴더 전체를 올립니다. `.github` 폴더도 꼭 포함하세요.
2. 저장소의 **Actions** 탭에서 `Build APK`가 자동으로 돌아갑니다(약 5~8분).
3. 초록 체크가 뜨면 실행 기록을 눌러 아래 **Artifacts**의 `frontal-daily-apk`를 받습니다(zip 안에 `app-debug.apk`).
4. 빨간 X가 뜨면 로그를 Claude에게 보여 주세요.

## 빌드 방법 2: Android Studio

1. Android Studio에서 이 폴더를 엽니다(Open).
2. 동기화가 끝나면 상단의 ▶ Run, 또는 Build → Build APK(s).

## 갤럭시에 설치

1. APK 파일을 폰으로 옮겨 누릅니다. '출처를 알 수 없는 앱 설치'를 허용합니다.
2. 처음 열면 알림 권한을 허용합니다.
3. 수면·걸음 카드의 **삼성헬스 연결하기** → 헬스 커넥트에서 걸음 수·수면 읽기를 허용합니다.
4. 삼성헬스 → 설정 → 헬스 커넥트에서 삼성헬스가 걸음 수·수면을 **쓰기** 하도록 허용합니다.
5. 설정 → 애플리케이션 → 전두엽 데일리 → 배터리 → **제한 없음**.
6. 루틴·알림 탭의 **알림 테스트**로 알림이 뜨는지 확인합니다.

## 알아 둘 점

- 기록은 이 폰 안에만 저장됩니다(웹 버전 기록과는 따로).
- 걸음 수·수면은 앱을 열 때마다 새로 불러옵니다. 앱이 꺼져 있을 때 걸음 수로 알림을 판단하지는 않습니다.
- 헬스 커넥트 라이브러리는 `1.1.0-alpha11`을 쓰고 있습니다. 빌드에서 버전 오류가 나면 이 줄을 최신 버전으로 바꾸면 됩니다.
