package com.continew.summary.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.continew.summary.ai.AiTextClient.AiReply;
import com.continew.summary.service.SummaryException;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 로컬 가짜 서버를 세워 실제 HTTP 요청의 모양(주소·헤더·본문)과 응답 해석, 재시도·예비 모델을 확인합니다. */
class GeminiTextClientTest {

	private static final String PRIMARY = "gemini-3.6-flash";
	private static final String FALLBACK = "gemini-3.5-flash-lite";

	private record Reply(int status, String body) {
	}

	private final JsonMapper mapper = JsonMapper.builder().build();
	private HttpServer server;
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final List<String> apiKeys = new CopyOnWriteArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	/** 요청이 올 때마다 하나씩 꺼내 쓰는 응답. 비면 마지막으로 정한 기본 응답을 씁니다. */
	private final Deque<Reply> queue = new ArrayDeque<>();
	private volatile Reply defaultReply = new Reply(200, "{}");

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> {
			paths.add(exchange.getRequestURI().getRawPath());
			apiKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("x-goog-api-key")));
			bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			Reply reply;
			synchronized (queue) {
				reply = queue.isEmpty() ? defaultReply : queue.poll();
			}
			byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(reply.status(), bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private void respond(int status, String body) {
		synchronized (queue) {
			queue.add(new Reply(status, body));
		}
	}

	private GeminiTextClient client(String apiKey, String thinkingLevel, String fallbackModel) {
		String baseUrl = "http://localhost:" + server.getAddress().getPort();
		AiProperties props = new AiProperties("gemini", 16000, 10,
				new AiProperties.Anthropic("claude-opus-5-5", "medium"),
				new AiProperties.Gemini(apiKey, PRIMARY, baseUrl, thinkingLevel, fallbackModel));
		return new GeminiTextClient(props, mapper);
	}

	private GeminiTextClient client() {
		return client("test-key", "", "");
	}

	private static String answer(String text) {
		return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + quote(text) + "}]},\"finishReason\":\"STOP\"}]}";
	}

	private static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	private static String pathOf(String model) {
		return "/v1beta/models/" + model + ":generateContent";
	}

	// ---- 요청·응답 형식 ----

	@Test
	void 요청은_명세된_주소와_헤더와_본문_형식으로_나간다() {
		respond(200, answer("{\"needs_original\":[]}"));

		AiReply reply = client().complete("시스템 지시문", "사용자 입력");

		assertThat(reply.refused()).isFalse();
		assertThat(reply.text()).isEqualTo("{\"needs_original\":[]}");
		assertThat(paths).containsExactly(pathOf(PRIMARY));
		assertThat(apiKeys).containsExactly("test-key");

		JsonNode body = mapper.readTree(bodies.get(0));
		assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString()).isEqualTo("시스템 지시문");
		assertThat(body.path("contents").path(0).path("role").asString()).isEqualTo("user");
		assertThat(body.path("contents").path(0).path("parts").path(0).path("text").asString()).isEqualTo("사용자 입력");
		assertThat(body.path("generationConfig").path("responseMimeType").asString()).isEqualTo("application/json");
		assertThat(body.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(16000);
		assertThat(body.path("generationConfig").has("thinkingConfig")).isFalse();
	}

	@Test
	void API_키는_주소에_들어가지_않는다() {
		respond(200, answer("{}"));

		client("secret-key-123", "", "").complete("s", "u");

		assertThat(paths.get(0)).doesNotContain("secret-key-123").doesNotContain("key=");
	}

	@Test
	void 생각_수준을_정하면_요청에_담는다() {
		respond(200, answer("{}"));

		client("k", "LOW", "").complete("s", "u");

		assertThat(mapper.readTree(bodies.get(0)).path("generationConfig").path("thinkingConfig")
				.path("thinkingLevel").asString()).isEqualTo("LOW");
	}

	@Test
	void 생각_요약_조각은_답에서_뺀다() {
		respond(200, """
				{"candidates":[{"content":{"parts":[
				  {"text":"내부 생각 요약","thought":true},
				  {"text":"{\\"events\\":[]}"}]},"finishReason":"STOP"}]}
				""");

		assertThat(client().complete("s", "u").text()).isEqualTo("{\"events\":[]}");
	}

	@Test
	void 요청이_차단되면_거절로_처리한다() {
		respond(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");

		assertThat(client().complete("s", "u").refused()).isTrue();
	}

	@Test
	void 안전_정책으로_답이_끊기면_거절로_처리한다() {
		respond(200, "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}");

		assertThat(client().complete("s", "u").refused()).isTrue();
	}

	@Test
	void 답이_길이_제한으로_잘리면_요약할_수_없다고_답한다() {
		respond(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"events\\\":[\"}]},\"finishReason\":\"MAX_TOKENS\"}]}");

		assertThatThrownBy(() -> client().complete("s", "u"))
				.isInstanceOfSatisfying(SummaryException.class, e -> {
					assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(e.code()).isEqualTo("SUMMARY_UNAVAILABLE");
				});
		// 잘린 답은 다시 물어도 같으므로 재시도하지 않습니다.
		assertThat(paths).hasSize(1);
	}

	@Test
	void API_키가_없으면_시작할_때_알려_준다() {
		assertThatThrownBy(() -> client("", "", ""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GEMINI_API_KEY");
	}

	// ---- 실패 처리 ----

	@Test
	void 서버_오류의_응답_본문은_오류_메시지에_들어가지_않는다() {
		defaultReply = new Reply(500, "{\"error\":{\"message\":\"비밀스러운내용XYZ\"}}");

		assertThatThrownBy(() -> client().complete("s", "u"))
				.isInstanceOf(SummaryException.class)
				.hasMessageNotContaining("XYZ");
	}

	@Test
	void 인증_실패는_다시_시도하지_않는다() {
		defaultReply = new Reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}");

		assertThatThrownBy(() -> client("bad-key", "", FALLBACK).complete("s", "u"))
				.isInstanceOfSatisfying(SummaryException.class, e -> assertThat(e.code()).isEqualTo("SUMMARY_UNAVAILABLE"));
		assertThat(paths).hasSize(1);
	}

	@Test
	void 잘못된_요청_400도_다시_시도하지_않는다() {
		defaultReply = new Reply(400, "{}");

		assertThatThrownBy(() -> client("k", "", FALLBACK).complete("s", "u")).isInstanceOf(SummaryException.class);
		assertThat(paths).hasSize(1);
	}

	// ---- 재시도와 예비 모델 ----

	@Test
	void 과부하_503이면_같은_모델에_한_번_더_시도한다() {
		respond(503, "{}");
		respond(200, answer("{\"needs_original\":[]}"));

		AiReply reply = client("k", "", FALLBACK).complete("s", "u");

		assertThat(reply.text()).isEqualTo("{\"needs_original\":[]}");
		assertThat(paths).containsExactly(pathOf(PRIMARY), pathOf(PRIMARY));
	}

	@Test
	void 기본_모델이_계속_실패하면_예비_모델로_넘어간다() {
		respond(503, "{}");
		respond(503, "{}");
		respond(200, answer("{\"needs_original\":[]}"));

		AiReply reply = client("k", "LOW", FALLBACK).complete("s", "u");

		assertThat(reply.text()).isEqualTo("{\"needs_original\":[]}");
		assertThat(paths).containsExactly(pathOf(PRIMARY), pathOf(PRIMARY), pathOf(FALLBACK));
		// 생각 수준은 기본 모델에만 보내고 예비 모델에는 보내지 않습니다.
		assertThat(mapper.readTree(bodies.get(0)).path("generationConfig").has("thinkingConfig")).isTrue();
		assertThat(mapper.readTree(bodies.get(2)).path("generationConfig").has("thinkingConfig")).isFalse();
	}

	@Test
	void 모두_실패하면_요약할_수_없다고_답한다() {
		defaultReply = new Reply(503, "{}");

		assertThatThrownBy(() -> client("k", "", FALLBACK).complete("s", "u"))
				.isInstanceOfSatisfying(SummaryException.class, e -> assertThat(e.code()).isEqualTo("SUMMARY_UNAVAILABLE"));
		assertThat(paths).hasSize(3);
	}

	@Test
	void 예비_모델을_끄면_기본_모델만_두_번_시도한다() {
		defaultReply = new Reply(503, "{}");

		assertThatThrownBy(() -> client("k", "", "").complete("s", "u")).isInstanceOf(SummaryException.class);
		assertThat(paths).containsExactly(pathOf(PRIMARY), pathOf(PRIMARY));
	}
}
