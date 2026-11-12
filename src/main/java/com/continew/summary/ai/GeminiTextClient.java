package com.continew.summary.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.continew.summary.service.SummaryException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Google Gemini API({@code models.generateContent})로 호출합니다. 외부 SDK 없이 JDK의 HttpClient를 씁니다.
 *
 * <p>API 키는 URL이 아니라 {@code x-goog-api-key} 헤더로 보내고, 어디에도 기록하지 않습니다.
 * 글 내용과 응답은 로그에 남기지 않으며, 실패 로그에는 HTTP 상태나 예외 종류만 적습니다(F-05-6c).
 *
 * <p>구글이 일시적으로 과부하(503·429 등)일 때를 위해 같은 모델에 한 번 재시도하고, 그래도 안 되면 예비 모델을
 * 씁니다. 전체 시간은 {@code timeoutSeconds}(기본 20초) 안에서만 씁니다(6.2: 20초가 넘으면 실패).
 */
@Component
@ConditionalOnExpression("'${continew.summary.generator:structured}' == 'ai' and '${continew.summary.ai.provider:gemini}' == 'gemini'")
class GeminiTextClient implements AiTextClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiTextClient.class);

	/** 안전 정책 때문에 답을 내지 않은 경우의 종료 사유. */
	private static final Set<String> REFUSAL_FINISH = Set.of("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII",
			"RECITATION");

	/** 잠시 뒤 다시 하면 될 수 있는 HTTP 상태. 400·401·403·404 같은 요청·권한 오류는 재시도해도 소용없습니다. */
	private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

	private static final Duration BACKOFF = Duration.ofMillis(500);
	private static final long MIN_REMAINING_NANOS = Duration.ofSeconds(1).toNanos();

	private final HttpClient http;
	private final JsonMapper mapper;
	private final AiProperties props;
	private final String baseUrl;

	GeminiTextClient(AiProperties props, JsonMapper mapper) {
		String key = props.gemini().apiKey();
		if (key == null || key.isBlank()) {
			throw new IllegalStateException("Gemini로 요약하려면 환경 변수 GEMINI_API_KEY가 필요합니다.");
		}
		this.props = props;
		this.mapper = mapper;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(props.timeoutSeconds())).build();
		this.baseUrl = props.gemini().baseUrl().replaceAll("/+$", "");
	}

	private record Attempt(AiReply reply, boolean retryable) {
	}

	@Override
	public AiReply complete(String system, String user) {
		long deadline = System.nanoTime() + Duration.ofSeconds(props.timeoutSeconds()).toNanos();
		String primary = props.gemini().model();
		String fallback = props.gemini().fallbackModel();

		// 계획: 기본 모델 → 기본 모델 재시도 → 예비 모델
		List<String> plan = new ArrayList<>(List.of(primary, primary));
		if (fallback != null && !fallback.isBlank() && !fallback.equals(primary)) {
			plan.add(fallback);
		}

		for (int i = 0; i < plan.size(); i++) {
			long remaining = deadline - System.nanoTime();
			if (remaining < MIN_REMAINING_NANOS) {
				break;
			}
			if (i == 1) {
				pause(BACKOFF);
				remaining = deadline - System.nanoTime();
				if (remaining < MIN_REMAINING_NANOS) {
					break;
				}
			}
			String model = plan.get(i);
			Attempt attempt = attempt(model, system, user, Duration.ofNanos(remaining), model.equals(primary));
			if (attempt.reply() != null) {
				if (!model.equals(primary)) {
					log.info("예비 모델로 요약함: {}", model);
				}
				return attempt.reply();
			}
			if (!attempt.retryable()) {
				break;
			}
		}
		throw unavailable();
	}

	private Attempt attempt(String model, String system, String user, Duration timeout, boolean primary) {
		URI endpoint = URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent");
		HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header("x-goog-api-key", props.gemini().apiKey())
				.POST(HttpRequest.BodyPublishers.ofString(body(system, user, primary), StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			log.warn("Gemini 호출 실패: {} (모델 {})", e.getClass().getSimpleName(), model);
			return new Attempt(null, true);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
		int status = response.statusCode();
		if (status == 200) {
			return new Attempt(read(response.body()), false);
		}
		// 응답 본문은 기록하지 않습니다.
		log.warn("Gemini 호출 실패: HTTP {} (모델 {})", status, model);
		return new Attempt(null, RETRYABLE_STATUS.contains(status));
	}

	private static void pause(Duration d) {
		try {
			Thread.sleep(d);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
	}

	private String body(String system, String user, boolean primary) {
		Map<String, Object> generationConfig = new LinkedHashMap<>();
		generationConfig.put("responseMimeType", "application/json");
		generationConfig.put("maxOutputTokens", props.maxTokens());
		String thinking = props.gemini().thinkingLevel();
		// 생각 수준은 기본 모델에만 보냅니다. 예비 모델이 같은 값을 지원하는지는 알 수 없습니다.
		if (primary && thinking != null && !thinking.isBlank()) {
			generationConfig.put("thinkingConfig", Map.of("thinkingLevel", thinking));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
		body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
		body.put("generationConfig", generationConfig);
		return mapper.writeValueAsString(body);
	}

	private AiReply read(String json) {
		JsonNode root;
		try {
			root = mapper.readTree(json);
		} catch (RuntimeException e) {
			throw unavailable();
		}

		// 요청 자체가 차단된 경우: 후보가 없고 blockReason만 옵니다.
		String blockReason = root.path("promptFeedback").path("blockReason").asString("");
		if (!blockReason.isEmpty() && !blockReason.equals("BLOCK_REASON_UNSPECIFIED")) {
			return AiReply.refusal();
		}
		JsonNode candidate = root.path("candidates").path(0);
		if (candidate.isMissingNode() || candidate.isNull()) {
			return AiReply.refusal();
		}
		String finish = candidate.path("finishReason").asString("");
		if (REFUSAL_FINISH.contains(finish)) {
			return AiReply.refusal();
		}
		if (finish.equals("MAX_TOKENS")) {
			// 답이 잘려 JSON이 완성되지 않았을 가능성이 큽니다.
			log.warn("Gemini 응답이 최대 출력 길이에서 잘림");
			throw unavailable();
		}

		StringBuilder text = new StringBuilder();
		for (JsonNode part : candidate.path("content").path("parts")) {
			// 생각 요약(thought)은 답이 아니므로 건너뜁니다.
			if (!part.path("thought").asBoolean(false)) {
				text.append(part.path("text").asString(""));
			}
		}
		return AiReply.of(text.toString());
	}

	private static SummaryException unavailable() {
		return new SummaryException(HttpStatus.SERVICE_UNAVAILABLE, "SUMMARY_UNAVAILABLE",
				"지금은 요약할 수 없어요. 원문 목록으로 확인해 주세요.");
	}
}
