# LyriLayer 0.1.3

삼성 뮤직 전용 읽기 전용 가사 도우미. 재생/정지/탐색 등 음악 제어 없음.

## APK 빌드
GitHub Actions의 LyriLayer APK Build 워크플로에서 빌드 결과를 확인할 수 있습니다. 성공한 실행의 Artifacts에서 LyriLayer-APK를 내려받습니다. Debug APK이며 별도 고정 서명 키를 사용하지 않습니다.

## 실행
1. Android Studio에서 이 폴더를 Open. JDK 17, Android SDK 36, Gradle 8.13을 설치/선택한다. 이 배포본에는 Gradle wrapper JAR이 포함되지 않는다. 로컬 Gradle 8.13을 선택하거나 gradle wrapper --gradle-version 8.13 실행 후 gradlew.bat :app:assembleDebug (Windows).
2. Gradle Sync 후 갤럭시 실기기에 Run.
3. 라이브러리에서 음악 파일 권한 허용. 가사 일괄 수집 또는 곡별 검색.
4. 설정에서 알림 접근 허용 및 다른 앱 위에 표시 권한 허용, 플로팅 가사 ON.
5. 삼성 뮤직에서 음악 재생. 실시간 가사 탭과 오버레이 확인.

## 구현
- Kotlin, Compose Material 3, Room, WorkManager, OkHttp.
- MediaStore 공유 음악 목록, LRCLIB 검색, DB 우선 조회, 미검색 결과 24시간 캐시, 수동 편집 보호.
- Samsung Music 패키지 com.sec.android.app.music의 MediaSession 읽기 전용 폴링. 마지막 재생 위치와 elapsedRealtime 기반 보간, 150ms 갱신. LRC 구간 탐색은 이진 검색. 일시 정지 시 오버레이 숨김.
- 일반 가사 표시, LRC 입력 및 ms 보정, Google 검색 브라우저 열기 (자동 스크래핑하지 않음).
- WorkManager 일괄 검색 취소 및 재실행, 저장된 가사 재사용. 백그라운드 작업은 OS 제한 적용.

## 아직 미구현 / 검증 필요
- 삼성 갤럭시 실기기 테스트 필요.
- MP3 내장 가사 추출, LRC 파일 자동 탐색/가져오기/내보내기, 백업/복원, 새 파일 자동 감지, 오버레이 세부 스타일, 추가 온라인 검색 제공처는 후속 단계.
- 삼성 뮤직 MediaSession metadata 및 재생 위치 제공 여부, 앱 절전/화면 잠금/탐색 시 동작, 실제 권한과 터치 통과를 실기기에서 확인해야 한다.
- LRCLIB에서 찾지 못하는 곡이 있을 수 있으며 일반 가사는 시간 정보가 없어 자동 동기화되지 않는다. 온라인 제공처의 이용 조건과 가사 저작권 검토 후 배포할 것.
- 온라인 검색 시 제목, 가수, 앨범, 재생 시간 전송. 알림 내용과 음원 자체는 전송하지 않음.

## 테스트 체크리스트
- 권한 거부/허용/회수, 음악 없음, 동일 제목 다른 버전, 곡 이동, 일시 정지 및 탐색, 오프라인 재생, 검색 실패 및 재시도, 일괄 수집 중단, 수동 편집 보호, 화면 회전 및 화면 꺼짐, 오버레이 터치 통과.
- gradle :app:testDebugUnitTest

공식 문서: https://developer.android.com/reference/android/media/session/MediaSessionManager ; https://lrclib.net/docs

## License
Apache License 2.0 (LICENSE).

## v0.1.1
- 삼성 통화/음성 녹음 및 알림음 제외, 원본 파일 변경 없음.
- LRCLIB 정확 검색 + 제목/가수/키워드/파일명 대체 검색, 후보 검증, 실패 캐시 갱신 및 오류 표시.

## v0.1.2
- TPhoneCallRecords 명시적 제외 및 삼성 Recordings 하위 폴더 회귀 테스트.
- 저장 경로 누락 시 통화녹음 파일명 패턴으로 추가 필터링, 가능한 경우 절대 경로도 검사.
- 통화녹음 원본은 변경하지 않으며 일반 M4A 음악은 유지합니다.

## v0.1.3
- 전체 파일 경로에 Recording 문자열이 포함되면 대소문자 구분 없이 목록과 가사 수집에서 제외. 기존 녹음 필터 유지.
