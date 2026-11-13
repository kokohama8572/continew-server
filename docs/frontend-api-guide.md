# 프론트엔드 API 연동 가이드

contiNEW 서버의 AI 요약 API를 앱에서 쓰는 방법입니다. 기능 규칙의 근거는 [기능 명세서](../spec/continew-feature-spec.md)(F-05)입니다.

## 1. 한눈에 보기

- 서버가 하는 일은 **공용 영역 글의 요약 하나**입니다. 메모·게시판·대화는 서버에 저장하지 않고 앱(기기)에 저장합니다.
- 엔드포인트는 하나입니다: `POST /api/v1/summaries`
- 로그인은 없습니다. 앱 설치마다 만든 익명 id를 헤더로 보냅니다.
- **개인 메모는 절대 보내지 않습니다.** 서버도 `scope: memo`를 거절합니다(P1에서 지원 예정).

| 환경 | 주소 |
| --- | --- |
| 로컬 서버 (macOS) | `http://localhost:8080` |
| iOS 시뮬레이터 | `http://localhost:8080` (같은 맥의 서버에 그대로 닿습니다) |
| Android 에뮬레이터 | `http://10.0.2.2:8080` |
| 실제 기기 | 맥의 같은 네트워크 IP, 예: `http://192.168.0.10:8080` |
| API 문서(Swagger UI) | `http://localhost:8080/swagger-ui/index.html` |
| OpenAPI JSON (코드 생성용) | `http://localhost:8080/v3/api-docs` |

서버 실행 방법은 [README](../README.md)를 보세요. AI 없이도(`structured` 생성기, API 키 불필요) 같은 형식으로 응답하므로 연동 개발은 키 없이 시작할 수 있습니다.

## 2. 요청

`POST /api/v1/summaries` · `Content-Type: application/json`

**필수 헤더**

| 헤더 | 설명 |
| --- | --- |
| `X-Installation-Id` | 앱 설치마다 한 번 만들어 저장해 두는 익명 id(UUID 권장, 최대 64자). 하루 요청 상한 계산에만 쓰고 서버는 저장·기록하지 않습니다. 없으면 400 |

**본문** (JSON 키는 모두 snake_case)

```json
{
  "scope": "shared",
  "from": "2026-11-09T21:00:00+09:00",
  "to": "2026-11-12T15:00:00+09:00",
  "items": [
    {"id": "post_1", "kind": "post", "post_type": "event", "title": "치과 진료",
     "event_at": "2026-11-13T14:00:00+09:00", "created_at": "2026-11-10T09:00:00+09:00"},
    {"id": "msg_2", "kind": "message", "body": "내일 저녁 7시에 친구랑 약속 있음",
     "author_label": "작성자1", "created_at": "2026-11-12T09:30:00+09:00"}
  ]
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `scope` | O | 지금은 `"shared"`만 지원합니다 |
| `from`, `to` | O | 요약할 기간. 시간대 오프셋을 포함한 ISO 8601 (예: `+09:00`). `to`가 `from`보다 앞서면 400 |
| `items` | O | 기간 안의 공용 글. 최대 300개(넘으면 422), 요청 형식 상한은 1000개 |

**`items[]` 필드**

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `id` | O | 글의 id(최대 64자). 요청 안에서 **중복되면 안 됩니다**. 응답의 `source_ids`가 이 값을 가리킵니다 |
| `kind` | O | `"post"`(공유 게시판) 또는 `"message"`(대화 메시지) |
| `post_type` | post만 O | `"event"`, `"todo"`, `"caution"`, `"note"` |
| `title` | | 제목(최대 500자) |
| `body` | | 본문(최대 5,000자). 메시지는 `body`에 담습니다 |
| `author_label` | | 작성자 표기(최대 40자). **반드시 가명으로 보내세요**(아래 4번) |
| `event_at` | | 일정 시각(일정 게시물). 오프셋 포함 |
| `due_at` | | 마감 시각(할 일 게시물). 오프셋 포함 |
| `created_at` | 강력 권장 | 글이 올라온 시각. "내일 2시" 같은 상대 표현을 **글이 올라온 날 기준**으로 계산하는 데 씁니다. 없으면 상대 날짜는 항상 `low`가 됩니다 |

## 3. 응답 (200)

```json
{
  "range": {"scope": "shared", "from": "2026-11-09T12:00:00Z", "to": "2026-11-12T06:00:00Z"},
  "events": [
    {"title": "치과 진료", "date": "2026-11-13", "time": "14:00",
     "original_phrase": null, "date_confidence": "high", "source_ids": ["post_1"]}
  ],
  "todos": [
    {"title": "택배 반품 접수", "due": "2026-11-20", "source_ids": ["post_5"]}
  ],
  "cautions": [
    {"text": "회사 출입증은 가방 안쪽 주머니에 있음", "source_ids": ["post_4"]}
  ],
  "conversation": [
    {"text": "주말 일정은 아직 정하지 않음", "author_labels": ["작성자2"], "source_ids": ["msg_8"]}
  ],
  "needs_original": ["msg_7", "post_9"]
}
```

| 필드 | 설명 |
| --- | --- |
| `range` | 요청한 기간을 되돌려 줍니다. **시각이 UTC(`Z`)로 바뀌어 올 수 있습니다**(같은 시각입니다). 화면에 쓸 때는 기기 시간대로 바꿔 표시하세요 |
| `events[]` | 일정. `date`는 `YYYY-MM-DD`, `time`은 `HH:mm` 또는 `null` |
| `events[].date_confidence` | `"high"` 또는 `"low"`. `low`이면 **"날짜 확인 필요"를 붙여 보여 주세요** |
| `events[].original_phrase` | 원문의 상대 표현(예: `"내일"`). 없으면 `null`. 있으면 계산한 날짜와 함께 보여 주세요: "11/13(금) 오후 7시 · 원문: '내일'" |
| `todos[]` | 해야 할 일. `due`는 `YYYY-MM-DD` 또는 `null` |
| `cautions[]` | 주의사항 |
| `conversation[]` | 대화 주요 내용. `author_labels`는 요청에 보낸 가명 그대로 옵니다 |
| `needs_original[]` | 요약하지 못했거나 확신할 수 없는 글의 id. **"원문 확인 필요"로 원문 링크만** 보여 주세요 |

**알아 둘 점**

- 모든 항목에는 `source_ids`(원문 id)가 있습니다. 항목마다 **'원문 보기'**를 눌러 해당 글로 이동하게 하세요(F-05-2e).
- 항목 하나가 빈 묶음이면 배열이 비어 옵니다. **빈 묶음은 화면에서 숨기세요**(F-05-2c).
- 묶음 순서는 고정입니다: 다가오는 일정 → 해야 할 일 → 주의사항 → 대화 주요 내용(F-05-2c).
- 같은 글이 `needs_original`과 다른 묶음에 함께 나올 수 있습니다. 필요하면 앱에서 중복을 정리하세요.
- **같은 입력이라도 글이 어느 묶음에 들어가는지는 호출마다 조금 달라질 수 있습니다.** 글이 사라지거나 원문에 없는 내용이 생기지는 않습니다(서버가 검사합니다). 화면 테스트 데이터를 만들 때 이 점을 고려하세요.
- 글이 **3개 이하**이면 서버는 요약하지 않고 모든 id를 `needs_original`에 담아 돌려줍니다. 이때는 요약 화면 대신 **원문 목록을 바로 보여 주세요**(F-05-2j).

## 4. 앱이 해야 하는 일

서버가 대신하지 않는, 앱 쪽 책임입니다.

1. **가명화와 복원 (F-05-6b)**: 서버로 보낼 때 실제 공간 이름 대신 `작성자1`, `작성자2`로 바꾸고, 대응표를 기기에만 보관합니다. 응답을 받으면 `author_labels`를 원래 표기로 되돌려 보여 주세요. 원래 `이름 없이`로 쓴 글은 `author_label`을 비우고 보내세요(F-05-2f).
2. **개인 메모 제외 (원칙1)**: `items`에는 공용 영역(게시판·대화)의 글만 넣습니다.
3. **결과를 저장하지 않습니다 (F-05-2i)**: 화면에만 표시합니다. 같은 범위를 다시 볼 때는 기기 캐시를 쓰고, 원문이 바뀌면 버리세요.
4. **안내 문구 (F-05-2h)**: 결과 화면 하단에 고정으로 넣습니다. "AI가 만든 요약이라 빠지거나 틀린 내용이 있을 수 있어요. 중요한 일정은 원문을 확인해 주세요."
5. **AI 요약 설정이 꺼져 있으면 요약 버튼을 보여 주지 않습니다 (F-05-1a)**. 켤 때 동의 화면이 필요합니다(F-05-1b).
6. **분량**: 기간 안 글이 300개를 넘지 않도록 기간을 줄이게 안내하세요.
7. **'확인했어요'**: 요약 화면에서 대신 확인 처리하지 마세요. 원문으로 가서 직접 누르게 합니다(F-05-2k).
8. **지표화 금지 (원칙6)**: 요약 횟수나 확인 여부를 점수나 통계로 보여 주지 마세요.

## 5. 오류 처리

오류 응답은 `application/problem+json` 형식이고, 판단에는 `code`를 쓰세요. 응답에는 보낸 글 내용이 들어가지 않습니다.

```json
{"status": 503, "code": "SUMMARY_UNAVAILABLE", "detail": "지금은 요약할 수 없어요. 원문 목록으로 확인해 주세요."}
```

| HTTP | `code` | 앱에서 할 일 |
| --- | --- | --- |
| 400 | `MISSING_INSTALLATION_ID` | `X-Installation-Id` 헤더 누락. 개발 중 실수입니다 |
| 400 | `SCOPE_NOT_SUPPORTED` | `scope`가 `shared`가 아님. 개인 메모 요약은 아직 없습니다 |
| 400 | `INVALID_REQUEST` | 형식 오류. 응답의 `fields`에 문제 필드명이 옵니다 |
| 400 | `INVALID_RANGE` | `to`가 `from`보다 앞섭니다 |
| 400 | `DUPLICATE_ITEM_ID` | `items`에 같은 `id`가 있습니다 |
| 400 | `INVALID_ITEM` | `kind`가 `post`인데 `post_type`이 없습니다 |
| 422 | `TOO_MANY_ITEMS` | 글이 300개 초과. **"기간을 줄여 주세요"** 안내 |
| 429 | `DAILY_LIMIT_EXCEEDED` | 오늘 요약 횟수(기본 20회) 소진. **"오늘 쓸 수 있는 요약 횟수를 모두 썼어요"** + 원문 목록 |
| 503 | `SUMMARY_UNAVAILABLE` | AI 호출 실패·시간 초과·응답 오류. **"지금은 요약할 수 없어요"** + 원문 목록 보기 버튼 |

- 네트워크가 없을 때도 같은 화면(요약 불가 + 원문 목록)을 보여 주세요(F-05 실패 처리).
- 하루 상한은 서버 메모리에 있어서 서버가 재시작되면 초기화됩니다. 상한 계산 기준은 한국 시간(Asia/Seoul) 날짜입니다.

## 6. 시간과 로딩 UX

- **응답 시간은 보통 1.5~3초이고, 가끔 5~20초까지 걸립니다.** 서버가 20초를 넘기면 스스로 503을 돌려줍니다.
- 앱의 요청 타임아웃은 **25초 이상**으로 잡으세요(서버 20초 + 여유). 그보다 짧으면 서버가 아직 처리 중인데 앱이 먼저 포기합니다.
- 3초가 넘으면 진행 표시와 **취소 버튼**을 보여 주세요(F-05 실패 처리).
- 요약 중 원문이 수정·삭제되면 결과를 버리고 다시 요청할지 물으세요.
- 응답이 느릴 수 있으니 같은 범위를 연속으로 누르지 못하게 버튼을 잠그세요(하루 상한을 아낍니다).

## 7. 연동 예시

### curl

```bash
curl -X POST http://localhost:8080/api/v1/summaries \
  -H 'Content-Type: application/json' \
  -H 'X-Installation-Id: 123e4567-e89b-12d3-a456-426614174000' \
  -d '{
    "scope": "shared",
    "from": "2026-11-09T21:00:00+09:00",
    "to": "2026-11-12T15:00:00+09:00",
    "items": [
      {"id": "post_1", "kind": "post", "post_type": "event", "title": "치과 진료",
       "event_at": "2026-11-13T14:00:00+09:00", "created_at": "2026-11-10T09:00:00+09:00"},
      {"id": "post_4", "kind": "post", "post_type": "caution", "title": "회사 출입증은 가방 안쪽 주머니에 있음"},
      {"id": "post_5", "kind": "post", "post_type": "todo", "title": "택배 반품 접수",
       "due_at": "2026-11-20T00:00:00+09:00"},
      {"id": "msg_8", "kind": "message", "body": "주말 일정은 아직 정하지 않음", "author_label": "작성자2"}
    ]
  }'
```

### TypeScript (타입 정의 예)

```ts
type Item = {
  id: string;
  kind: "post" | "message";
  post_type?: "event" | "todo" | "caution" | "note";
  title?: string;
  body?: string;
  author_label?: string;     // 가명('작성자1')으로 보낼 것
  event_at?: string;         // ISO 8601, 오프셋 포함
  due_at?: string;
  created_at?: string;
};

type SummaryRequest = {
  scope: "shared";
  from: string;
  to: string;
  items: Item[];
};

type SummaryResponse = {
  range: { scope: "shared"; from: string; to: string };
  events: { title: string; date: string; time: string | null; original_phrase: string | null;
            date_confidence: "high" | "low"; source_ids: string[] }[];
  todos: { title: string; due: string | null; source_ids: string[] }[];
  cautions: { text: string; source_ids: string[] }[];
  conversation: { text: string; author_labels: string[]; source_ids: string[] }[];
  needs_original: string[];
};

async function summarize(installationId: string, body: SummaryRequest): Promise<SummaryResponse> {
  const res = await fetch("http://localhost:8080/api/v1/summaries", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Installation-Id": installationId },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(25_000),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw Object.assign(new Error(err.detail ?? "요약 실패"), { code: err.code, status: res.status });
  }
  return res.json();
}
```

OpenAPI JSON(`/v3/api-docs`)에서 타입을 자동 생성하는 도구(예: openapi-typescript, openapi-generator)를 써도 됩니다.

## 8. 웹 앱이라면 (CORS)

서버는 기본적으로 CORS를 **닫아 둡니다.** 프론트가 브라우저에서 도는 웹 앱이면 다른 주소(예: 배포된 프론트 주소)에서 서버를 부를 때 막힙니다. 모바일 앱(iOS·Android)은 해당되지 않습니다.

- 개발 중에는 Vite 프록시(`/api` → `localhost:8080`)를 쓰면 CORS가 필요 없습니다.
- 배포하거나 폰에서 테스트할 때는 서버 환경 변수 `CONTINEW_CORS_ALLOWED_ORIGINS`에 프론트 주소를 넣어 허용합니다(서버 담당에게 요청). 자세한 방법은 [배포 가이드](deploy.md)의 4번을 보세요.

## 9. 아직 없는 것

- 개인 메모 요약, 공유 초안 만들기, 주기 요약(P1) — 명세서 F-05-3·4·7
- 사용자 인증·계정 — MVP는 로컬 우선이라 서버에 사용자 기록이 없습니다.
- 응답 시각 형식이 UTC로 바뀌는 점은 알려진 동작입니다. 한국 시간 그대로가 필요하면 서버 담당에게 요청하세요.
