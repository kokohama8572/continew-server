package com.continew.summary.ai;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.continew.summary.SummaryProperties;
import com.continew.summary.ai.AiTextClient.AiReply;
import com.continew.summary.api.SummaryRequest;
import com.continew.summary.api.SummaryRequest.SourceItem;
import com.continew.summary.api.SummaryResponse;
import com.continew.summary.api.SummaryResponse.Caution;
import com.continew.summary.api.SummaryResponse.ConversationLine;
import com.continew.summary.api.SummaryResponse.Event;
import com.continew.summary.api.SummaryResponse.Range;
import com.continew.summary.api.SummaryResponse.Todo;
import com.continew.summary.service.SummaryException;
import com.continew.summary.service.SummaryGenerator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * Claude로 요약 초안을 만드는 생성기. 결과는 곧바로 사용자에게 가지 않고 {@code SummaryGuard}를 거칩니다.
 *
 * <p>한 요청에는 한 요약 범위의 글만 보냅니다(F-05-6a). 작성자 표기는 앱이 이미 '작성자1' 같은 가명으로
 * 바꿔 보낸 값이며, 서버는 공간 이름이나 공간 id를 받지 않습니다(F-05-6b).
 */
@Component
@ConditionalOnProperty(name = "continew.summary.generator", havingValue = "ai")
public class AiSummaryGenerator implements SummaryGenerator {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiSummaryGenerator.class);

	private final AiTextClient ai;
	private final JsonMapper mapper;
	private final SummaryProperties props;

	public AiSummaryGenerator(AiTextClient ai, JsonMapper mapper, SummaryProperties props) {
		this.ai = ai;
		this.mapper = mapper;
		this.props = props;
	}

	@Override
	public SummaryResponse generate(SummaryRequest request) {
		AiReply reply = ai.complete(SummaryPrompt.SYSTEM, userMessage(request));
		Range range = new Range(request.scope(), request.from(), request.to());

		if (reply.refused()) {
			// 모델이 요청을 거절하면 요약하지 않고 원문 확인으로 넘깁니다(F-05-5c).
			return new SummaryResponse(range, List.of(), List.of(), List.of(), List.of(),
					request.items().stream().map(SourceItem::id).toList());
		}
		ModelOutput out = parse(reply.text());
		return new SummaryResponse(range, out.events(), out.todos(), out.cautions(), out.conversation(),
				out.needsOriginal());
	}

	// ---- 입력 ----

	private String userMessage(SummaryRequest request) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (SourceItem item : request.items()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", item.id());
			m.put("kind", item.kind() == SummaryRequest.Kind.POST ? "post" : "message");
			put(m, "post_type", item.postType() == null ? null : item.postType().name().toLowerCase());
			put(m, "title", item.title());
			put(m, "body", item.body());
			put(m, "author_label", item.authorLabel());
			put(m, "created_at", local(item.createdAt()));
			put(m, "event_at", local(item.eventAt()));
			put(m, "due_at", local(item.dueAt()));
			items.add(m);
		}
		// 글은 JSON 문자열로 감싸 '자료'로 전달합니다. 글 안의 지시문이 지시로 읽히지 않게 하기 위함입니다.
		return "요약할 글 목록입니다. 기간은 " + local(request.from()) + " 부터 " + local(request.to()) + " 까지입니다.\n"
				+ mapper.writeValueAsString(items);
	}

	private String local(OffsetDateTime at) {
		return at == null ? null : at.atZoneSameInstant(props.zone()).toOffsetDateTime().toString();
	}

	private static void put(Map<String, Object> map, String key, Object value) {
		if (value != null) {
			map.put(key, value);
		}
	}

	// ---- 출력 ----

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ModelOutput(List<Event> events, List<Todo> todos, List<Caution> cautions,
			List<ConversationLine> conversation, List<String> needsOriginal) {
	}

	private ModelOutput parse(String text) {
		// 코드 블록 표시나 앞뒤 문장이 섞여 와도 JSON 본문만 꺼냅니다.
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start < 0 || end <= start) {
			log.warn("AI 응답에서 JSON을 찾지 못함 (응답 길이 {}자)", text.length());
			throw unavailable();
		}
		try {
			return mapper.readValue(text.substring(start, end + 1), ModelOutput.class);
		} catch (RuntimeException e) {
			// 오류 메시지에는 모델이 쓴 글 조각이 담길 수 있어 남기지 않습니다. 예외 종류와 필드 경로만 남깁니다.
			log.warn("AI 응답 해석 실패: {} (경로 {})", e.getClass().getSimpleName(), fieldPath(e));
			throw unavailable();
		}
	}

	/** 어느 필드에서 해석이 실패했는지만 알려 줍니다. 값은 포함하지 않습니다. */
	private static String fieldPath(RuntimeException e) {
		if (e instanceof tools.jackson.core.JacksonException je) {
			return je.getPath().stream().map(r -> r.getPropertyName() != null ? r.getPropertyName() : "[" + r.getIndex() + "]")
					.collect(java.util.stream.Collectors.joining("."));
		}
		return "?";
	}

	private static SummaryException unavailable() {
		return new SummaryException(HttpStatus.SERVICE_UNAVAILABLE, "SUMMARY_UNAVAILABLE",
				"지금은 요약할 수 없어요. 원문 목록으로 확인해 주세요.");
	}
}
