# X 키워드 가리개

X(트위터)에서 지정한 키워드가 들어간 글을 가리는 도구입니다. 두 가지로 나뉩니다.

- **크롬 확장** (`extension/`): PC 브라우저의 x.com / twitter.com에서 걸린 글을 큰 빨간 **BLOCKED**로 바꿉니다. 글꼴은 Bebas Neue입니다.
- **안드로이드 APK** (`android/XKeywordHider-debug.apk`): X 앱과 브라우저의 x.com 위에 같은 **BLOCKED** 오버레이를 띄우고, 그 영역은 눌러도 통과하지 않습니다.

키워드는 한 줄에 하나 (`ㅋㅋ`, `분탕`, `@계정` 등)입니다. 매칭과 가리기는 기기 안에서만 처리합니다.

## 크롬 확장 설치

크롬, 엣지, 웨일 등 크로미움 브라우저에서 압축을 풀지 않은 폴더를 그대로 불러옵니다.

1. 이 저장소를 받습니다.
2. 주소창에 `chrome://extensions` 를 입력합니다. 엣지는 `edge://extensions`, 웨일은 `whale://extensions` 입니다.
3. 오른쪽 위의 **개발자 모드**를 켭니다.
4. **압축해제된 확장 프로그램을 로드합니다**를 누르고, 저장소의 `extension/` 폴더를 선택합니다.
5. 퍼즐 아이콘에서 확장을 고정합니다.

아이콘을 열어 키워드를 한 줄에 하나씩 적으면 바로 저장됩니다. 예:

```
ㅋㅋ
분탕
@계정아이디
```

`@아이디`는 그 계정, `/정규식/i`는 정규식, `//`로 시작하는 줄은 메모입니다.

걸린 글은 내용 대신 Bebas Neue로 쓴 큰 빨간 **BLOCKED**가 남습니다. 접어두기에서는 그 자리를 누르면 그 글만 다시 열리고, 완전히 숨기기를 고르면 흔적 없이 빠집니다.

## 안드로이드 APK

디버그 APK는 `android/XKeywordHider-debug.apk` 입니다.

1. APK를 폰으로 옮겨 실행합니다. “출처를 알 수 없는 앱” 설치를 허용한 뒤 설치합니다. PC에서는 `adb install -r android/XKeywordHider-debug.apk` 입니다.
2. 앱을 열고 키워드를 한 줄에 하나씩 입력한 다음 **저장**을 누릅니다. `/정규식/i`와 `//` 주석도 됩니다.
3. **다른 앱 위에 표시**(오버레이)를 허용합니다. 앱의 “1. 다른 앱 위에 표시 권한”으로 들어갑니다.
4. **접근성 서비스**를 켭니다. “2. 접근성 서비스 켜기” → 설치된 앱(삼성: 설치된 앱 / 다운로드한 앱) → X 키워드 가리개 → 사용. 안드로이드 13 이상에서 제한된 설정으로 막히면, 설정 → 애플리케이션 → X 키워드 가리개 → 오른쪽 위 ⋮ → **제한된 설정 허용** 후 다시 켭니다.

X 앱을 열거나, 크롬 등 브라우저에서 x.com / twitter.com을 열면 키워드가 있는 글 위에 빨간 **BLOCKED**(Bebas Neue)가 덮입니다. 오버레이가 터치를 받아서 그 자리는 눌리지 않습니다. “분 탕”, “분.탕”처럼 끼워 넣은 표기도 기본으로 잡습니다.

소스와 빌드 방법은 `android/`에 있습니다. 디버그 APK는 `JAVA_HOME`을 JDK 17로 두고 `gradle :app:assembleDebug`로 만듭니다.

## 경로

| 경로 | 내용 |
| --- | --- |
| `extension/` | 크롬 확장. 개발자 모드에서 이 폴더를 로드합니다. |
| `android/` | 안드로이드 프로젝트. 설치 파일은 `android/XKeywordHider-debug.apk` 입니다. |

## 글꼴 라이선스

저장소에 포함된 글꼴은 SIL Open Font License 1.1 (OFL)입니다.

- **Bebas Neue** (Dharma Type): BLOCKED 라벨. `extension/fonts/BebasNeue.ttf`, `android/app/src/main/assets/fonts/BebasNeue.ttf`
- **Inter** (Rasmus Andersson, Inter Display Black): `extension/fonts/InterDisplay-Black.ttf`

OFL 조건으로 사용·재배포할 수 있습니다. 글꼴 자체의 예약된 이름은 라이선스의 이름을 그대로 씁니다.

## English

X Keyword Hider covers posts that match your keywords. The Chrome extension in `extension/` is loaded unpacked (developer mode) and replaces a hit with a large red **BLOCKED** label in Bebas Neue. Put one keyword per line, for example `ㅋㅋ`.

The Android debug APK is `android/XKeywordHider-debug.apk`. Install it, grant overlay and accessibility, then save keywords. On the X app or a browser open to x.com, matching posts get a red BLOCKED overlay that does not pass taps through.

Bebas Neue and Inter are bundled under the SIL Open Font License 1.1.
