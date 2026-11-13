# contiNEW
학교 수업

여러 정체성 상태가 각자의 개인 공간을 쓰면서, 필요할 때 공용 공간에서 메시지와 생활 정보를 나누는 서비스입니다.
이 저장소는 백엔드(Spring Boot)입니다. 기능 정의는 기능 명세서를 따릅니다.

## 문서

- [기능 명세서](spec/continew-feature-spec.md)
- [프론트엔드 API 연동 가이드](docs/frontend-api-guide.md)
- [배포 가이드](docs/deploy.md)

## 요구 사항

- JDK 21 이상 (Gradle toolchain이 21을 사용합니다)

## 실행

```bash
./gradlew bootRun
```

- 상태 확인: http://localhost:8080/actuator/health
- API 문서(Swagger UI): http://localhost:8080/swagger-ui/index.html

## 테스트·빌드

```bash
./gradlew test
./gradlew build
```

## API

MVP는 로컬 우선이라 서버가 하는 일은 AI 요약 중계(명세서 F-05)뿐입니다. 사용자 기록은 서버에 저장하지 않습니다.

`POST /api/v1/summaries` : 공용 영역 이어받기 요약 (F-05-2)

- 헤더 `X-Installation-Id`: 앱 설치마다 만든 익명 id. 하루 요청 상한(기본 20회) 계산에만 쓰고 저장·로그에 남기지 않습니다.
- 본문은 `scope`(`shared`만 지원), `from`, `to`, `items`로 구성합니다. JSON 키는 snake_case입니다.
- 작성자 표기는 앱이 `작성자1` 같은 가명으로 바꿔 보내고, 응답을 받은 뒤 되돌립니다 (F-05-6b).
- 응답은 명세서 6.3 구조입니다. 모든 항목에 `source_ids`가 붙고, 요약하지 못한 글은 `needs_original`에 담깁니다.
- 글이 3개 이하면 요약하지 않고 전부 `needs_original`로 돌려줍니다. 300개를 넘으면 422(`TOO_MANY_ITEMS`)입니다.
- 각 글의 `created_at`을 보내 주세요. '내일 2시' 같은 표현을 글이 올라온 날을 기준으로 계산하는 데 씁니다.

### AI 요약 켜기

기본값은 AI를 호출하지 않는 `structured` 생성기입니다(API 키 불필요). AI로 요약하려면 키를 환경 변수로 넣고 생성기를 바꿉니다. 기본 공급자는 Google Gemini입니다.

```bash
export GEMINI_API_KEY=발급받은_키
CONTINEW_SUMMARY_GENERATOR=ai ./gradlew bootRun
```

- 키가 없으면 서버가 시작 단계에서 `GEMINI_API_KEY`가 필요하다고 알리며 멈춥니다.
- 모델은 `continew.summary.ai.gemini.model`(기본 `gemini-3.5-flash-lite`)에서 바꿉니다. 이 모델은 빠르지만(보통 1.5~2.3초) 글을 어느 묶음에 넣을지가 호출마다 조금 달라질 수 있습니다. 없는 내용이 생기거나 글이 사라지지는 않는데, `SummaryGuard`가 막기 때문입니다. Gemini 모델 ID는 자주 바뀌고 오래된 것은 중단되므로 [공식 모델 목록](https://aistudio.google.com/docs/models)을 확인하세요. 생각 수준은 `thinking-level`(MINIMAL~HIGH)로 정하고, 비우면 모델 기본값입니다.
- 구글이 일시적으로 과부하(503·429 등)일 때는 같은 모델에 한 번 재시도하고, 그래도 안 되면 예비 모델(`fallback-model`, 기본 `gemini-3.6-flash`)로 요약합니다. 예비 모델은 더 능력 있지만 느립니다(10초 이상 걸릴 수 있음). 전체 시간은 20초 안에서만 씁니다. 모델이 느리면 이 시간을 기본 모델이 다 쓸 수 있습니다.
- Claude를 쓰려면 `CONTINEW_SUMMARY_AI_PROVIDER=anthropic`과 `ANTHROPIC_API_KEY`를 설정합니다(기본 `claude-opus-5-5`, 설정은 `continew.summary.ai.anthropic.*`).
- 타임아웃(기본 20초)과 최대 출력 토큰은 `continew.summary.ai.*`에서 바꿉니다.
- 키는 설정 파일에 적지 않고 환경 변수로만 읽으며, Gemini 키는 주소가 아니라 `x-goog-api-key` 헤더로 보냅니다.
- 모델이 실패하거나 20초를 넘기면 503(`SUMMARY_UNAVAILABLE`)을 돌려주고, 앱은 원문 목록을 보여 줍니다. 모델이 요청을 거절하면 모든 글을 `needs_original`로 돌려줍니다.
- 모델이 낸 결과는 `SummaryGuard`가 다시 검사합니다. 출처가 없거나, 원문에 없는 날짜·숫자가 있거나, 금지 표현이 있는 항목은 요약에서 빠집니다.
- 출시 전에 외부 AI API의 입력 보관·학습 미사용 조건을 계약·설정으로 확인해야 합니다(명세서 F-05-6c).

오류 응답은 `code` 필드를 가지며 요청 내용을 되돌려 주지 않습니다.

| 상태 | code | 의미 |
| --- | --- | --- |
| 400 | `MISSING_INSTALLATION_ID` | 설치 id 헤더 누락 |
| 400 | `SCOPE_NOT_SUPPORTED` | 개인 메모 요약(`memo`)은 P1 |
| 400 | `INVALID_REQUEST`, `INVALID_RANGE`, `DUPLICATE_ITEM_ID`, `INVALID_ITEM` | 요청 형식 오류 |
| 422 | `TOO_MANY_ITEMS` | 기간을 줄여야 함 |
| 429 | `DAILY_LIMIT_EXCEEDED` | 하루 요청 상한 초과 |
| 503 | `SUMMARY_UNAVAILABLE` | AI 호출 실패·시간 초과·응답 형식 오류. 원문 목록으로 안내 |

## 구조

```
src/main/java/com/continew
├── ContiNewApplication.java
├── config                      # Clock, 인터셉터 등록
└── summary
    ├── SummaryProperties.java  # continew.summary.* 설정 (상한값 등)
    ├── api                     # 컨트롤러, 요청·응답(6.3), 오류 처리
    ├── service
    │   ├── SummaryService      # 요청 검증과 흐름 제어
    │   ├── SummaryGenerator    # 요약 초안을 만드는 경계
    │   ├── StructuredSummaryGenerator  # AI 없이 게시물 구조만으로 정리 (기본)
    │   ├── SummaryGuard        # 출처·날짜·숫자·금지 표현·분량 검사 (F-05-2, F-05-5)
    │   └── RelativeDates       # '오늘·내일·모레' 날짜 검증
    ├── ai
    │   ├── AiSummaryGenerator  # Claude로 요약 초안 생성 (generator=ai)
    │   ├── AiTextClient        # 모델 호출 경계. 테스트에서는 가짜 응답으로 대체
    │   ├── GeminiTextClient    # Google Gemini 호출 (provider=gemini, 기본)
    │   ├── AnthropicTextClient # 공식 Anthropic Java SDK 호출 (provider=anthropic)
    │   └── SummaryPrompt       # 요약 지시문
    └── ratelimit               # 설치 단위 하루 요청 상한 (F-05-6f)
```

요약 초안은 어떤 생성기를 쓰든 `SummaryGuard`를 반드시 거칩니다. 생성기는 `continew.summary.generator`(`structured` 또는 `ai`)로 고릅니다.

## 아직 없는 것

- 개인 메모 요약·공유 초안·주기 요약: P1(F-05-3·4·7)
- 유효한 키로 한 실제 AI 호출은 아직 시험하지 못했습니다. 로컬 가짜 서버로 Gemini 요청 형식(주소·헤더·본문)과 응답 해석을, 가짜 응답으로 입출력 처리와 안전 검사를 검증했습니다.
- DB·인증: 서버 저장 방식을 정한 뒤 추가합니다.
- 하루 요청 상한은 서버 메모리에만 있어 재시작하면 초기화됩니다. 서버를 여러 대로 늘리면 공유 저장소가 필요합니다.
