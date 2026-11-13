# 배포 가이드

백엔드는 Docker 이미지 하나로 배포합니다. Docker를 받는 호스팅(Render, Railway, Fly.io 등)이면 어디서든 같은 방식입니다.

## 1. 준비되어 있는 것

- `Dockerfile`: 빌드(JDK 21) → 실행(JRE 21) 2단계. non-root 사용자로 실행합니다.
- `PORT` 환경 변수를 읽습니다(없으면 8080). 호스팅이 포트를 정해 주는 방식에 맞습니다.
- 상태 확인 주소: `/actuator/health`
- 프록시 뒤의 `X-Forwarded-*` 헤더를 읽습니다.
- CORS는 기본이 **닫힘**입니다. 환경 변수로 허용할 프론트 주소를 넣어야 열립니다(4번).

로컬에서 컨테이너로 확인하려면:

```bash
docker build -t continew-server .
docker run --rm -p 8080:8080 continew-server
```

512MB 메모리 제한에서 기동 2초, 사용 약 255MB로 확인했습니다(무료 플랜에서도 동작).

## 2. 환경 변수

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `CONTINEW_SUMMARY_GENERATOR` | AI 요약을 쓰려면 | `ai`로 설정합니다. 없으면 AI 없이 게시물 구조만으로 정리합니다(`structured`) |
| `GEMINI_API_KEY` | `ai`일 때 | Google AI Studio에서 발급한 키. **호스팅의 비밀 값(secret) 항목에 넣고, 저장소에 올리지 마세요.** 없으면 서버가 시작을 거부합니다 |
| `CONTINEW_CORS_ALLOWED_ORIGINS` | 웹 프론트가 다른 주소일 때 | 허용할 프론트 주소. 쉼표로 여러 개. 예: `https://continew-web.vercel.app` |
| `PORT` | | 호스팅이 자동으로 넣어 줍니다. 직접 설정하지 않아도 됩니다 |
| `CONTINEW_SUMMARY_DAILY_LIMIT` | | 설치당 하루 요약 횟수(기본 20) |
| `CONTINEW_SUMMARY_AI_GEMINI_MODEL` | | 기본 `gemini-3.5-flash-lite` |

## 3. Render에서 배포하기 (예시)

화면 문구는 바뀔 수 있습니다. 같은 항목을 찾아 설정하세요.

1. [Render](https://render.com)에 가입하고 GitHub 계정을 연결해 `continew-server` 저장소를 허용합니다.
2. **New → Web Service**에서 저장소를 고릅니다.
3. **Runtime(언어)은 Docker**를 고릅니다. 저장소 루트의 `Dockerfile`을 자동으로 씁니다.
4. **Health Check Path**에 `/actuator/health`를 넣습니다.
5. **Environment Variables**에 2번 표의 값을 넣습니다. `GEMINI_API_KEY`는 비밀 값으로 넣습니다.
6. 배포가 끝나면 `https://<서비스이름>.onrender.com`이 서버 주소입니다.
7. 확인: `https://<서비스이름>.onrender.com/actuator/health`가 `{"status":"UP"}`을 돌려주면 성공입니다.

**무료 플랜의 특성:** 한동안 요청이 없으면 서버가 잠들고, 첫 요청이 30~60초 걸릴 수 있습니다. 앱의 요약 요청 타임아웃(25초)보다 길 수 있어서, **발표 직전에 한 번 깨워 두세요**(`/actuator/health`를 한 번 열기).

## 4. 프론트와 연결하기

개발 중에는 프론트의 Vite가 `/api`를 `localhost:8080`으로 넘겨서 CORS가 필요 없습니다. 배포하면 이 우회가 사라지므로 둘 중 하나를 택합니다.

- **A. 서버가 프론트 주소를 허용한다(CORS).** 서버에 `CONTINEW_CORS_ALLOWED_ORIGINS=<프론트 배포 주소>`를 넣고, 프론트는 요청 주소를 배포된 서버 주소로 바꿉니다.
- **B. 프론트 호스팅이 `/api`를 서버로 넘겨 준다(rewrite/proxy).** Vercel·Netlify·Cloudflare Pages 모두 설정할 수 있고, 이 방식이면 브라우저 입장에서 같은 주소라 CORS가 필요 없습니다.

프론트 담당자가 어느 방식을 쓸지 정해서 알려 주세요. 로컬에서 폰으로 테스트할 때도 폰이 연 프론트 주소(예: `http://192.168.0.10:5173`)를 A 방식으로 허용해야 합니다.

## 5. 공개 주소의 보안과 비용 (꼭 읽어 주세요)

이 서버에는 로그인이 없습니다. 주소를 아는 누구나 요약 API를 부를 수 있고, 호출마다 **Gemini 사용량(비용·무료 한도)**이 나갑니다.

- 설치당 하루 상한(20회)은 요청 헤더 값(`X-Installation-Id`)만 바꾸면 우회됩니다. 안전 장치가 아니라 정상 사용자의 과다 사용을 막는 장치입니다.
- 웹 프론트에 비밀 키를 넣어 보내는 방식(공유 비밀 헤더)은 **브라우저 개발자 도구에서 누구나 볼 수 있어** 실질적인 보호가 되지 않습니다.
- 그래서 비용은 구글 쪽에서 막는 것이 가장 확실합니다. Google AI Studio·Cloud에서 이 키의 **일일 요청 한도나 예산 알림**을 설정하세요.
- 배포 주소는 발표 때만 공유하고, 발표가 끝나면 서비스를 중지하거나 키를 폐기(재발급)하세요.
- 더 필요하면 서버에 IP별 상한이나 전체 일일 상한을 추가할 수 있습니다.

## 6. 배포 후 점검

```bash
curl https://<서버주소>/actuator/health
curl -X POST https://<서버주소>/api/v1/summaries \
  -H 'Content-Type: application/json' -H 'X-Installation-Id: deploy-check' \
  -d '{"scope":"shared","from":"2026-11-09T21:00:00+09:00","to":"2026-11-12T15:00:00+09:00","items":[{"id":"a","kind":"post","post_type":"caution","title":"확인용"},{"id":"b","kind":"message","body":"안녕"},{"id":"c","kind":"message","body":"네"},{"id":"d","kind":"message","body":"좋아요"}]}'
```

- 두 번째 호출이 `200`이고 `source_ids`가 담긴 JSON이 오면 서버와 AI 연결이 모두 정상입니다.
- 브라우저에서 부르는 경우에는 개발자 도구 Network 탭에서 `OPTIONS` 요청이 `200`인지 확인하세요. 막히면 `CONTINEW_CORS_ALLOWED_ORIGINS`의 주소가 정확한지(끝 슬래시 없이, `https://` 포함) 확인하세요.
