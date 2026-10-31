# X 키워드 가리개 (안드로이드)

X 앱 / 브라우저(x.com, twitter.com)에서 키워드가 들어간 게시물을 빨간 **BLOCKED**(Bebas Neue)로 덮고, 덮인 부분은 눌러도 반응하지 않게 막습니다.

## 설치
1. `XKeywordHider-debug.apk`를 폰으로 옮겨 실행 → "출처를 알 수 없는 앱 설치" 허용 → 설치.
   (PC라면 `adb install -r XKeywordHider-debug.apk`)
2. 앱 열기 → 키워드 입력(한 줄에 하나, `/정규식/i` 가능) → **저장**.

## 권한 켜기
1. **다른 앱 위에 표시**: 앱의 "1. 다른 앱 위에 표시 권한" 버튼 → *X 키워드 가리개* → 허용.
2. **접근성 서비스**: "2. 접근성 서비스 켜기" 버튼 → *설치된 앱*(삼성: 설치된 앱 / 다운로드한 앱) → *X 키워드 가리개* → 사용 켜기 → 허용.
   - 안드로이드 13 이상에서 "제한된 설정" 때문에 회색으로 막히면: 설정 → 애플리케이션 → X 키워드 가리개 → 우측 상단 ⋮ → **제한된 설정 허용** 후 다시 켜기.
3. X 앱이나 크롬에서 x.com을 열면 걸린 게시물이 BLOCKED로 덮입니다.

## 빌드
`JAVA_HOME=JDK17 gradle :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
