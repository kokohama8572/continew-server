package com.continew.summary.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.continew.summary.SummaryProperties;
import com.continew.summary.api.SummaryRequest;
import com.continew.summary.api.SummaryRequest.Kind;
import com.continew.summary.api.SummaryRequest.PostType;
import com.continew.summary.api.SummaryRequest.SourceItem;
import com.continew.summary.api.SummaryResponse;
import com.continew.summary.api.SummaryResponse.Caution;
import com.continew.summary.api.SummaryResponse.ConversationLine;
import com.continew.summary.api.SummaryResponse.DateConfidence;
import com.continew.summary.api.SummaryResponse.Event;
import com.continew.summary.api.SummaryResponse.Range;
import com.continew.summary.api.SummaryResponse.Todo;

/**
 * AI를 호출하지 않고, 게시물이 이미 가진 구조(유형·일정·마감)를 그대로 4개 묶음으로 옮기는 구현체입니다.
 * 처리 위치(Q3)가 정해지기 전에 앱이 요약 화면을 연동해 볼 수 있게 하는 용도입니다.
 *
 * <p>원문에 없는 내용을 만들지 않으므로 해석·추정이 생기지 않습니다.
 */
@Component
@ConditionalOnProperty(name = "continew.summary.generator", havingValue = "structured", matchIfMissing = true)
public class StructuredSummaryGenerator implements SummaryGenerator {

	private final SummaryProperties props;

	public StructuredSummaryGenerator(SummaryProperties props) {
		this.props = props;
	}

	@Override
	public SummaryResponse generate(SummaryRequest request) {
		List<Event> events = new ArrayList<>();
		List<Todo> todos = new ArrayList<>();
		List<Caution> cautions = new ArrayList<>();
		List<ConversationLine> conversation = new ArrayList<>();
		List<String> needsOriginal = new ArrayList<>();

		for (SourceItem item : request.items()) {
			if (item.kind() == Kind.MESSAGE) {
				conversation.add(new ConversationLine(headline(item),
						item.authorLabel() == null || item.authorLabel().isBlank() ? List.of() : List.of(item.authorLabel()),
						List.of(item.id())));
				continue;
			}
			PostType type = item.postType();
			if (type == PostType.EVENT && item.eventAt() != null) {
				OffsetDateTime at = item.eventAt().atZoneSameInstant(props.zone()).toOffsetDateTime();
				events.add(new Event(headline(item), at.toLocalDate(), at.toLocalTime(), null,
						DateConfidence.HIGH, List.of(item.id())));
			} else if (type == PostType.TODO) {
				todos.add(new Todo(headline(item),
						item.dueAt() == null ? null : item.dueAt().atZoneSameInstant(props.zone()).toLocalDate(),
						List.of(item.id())));
			} else if (type == PostType.CAUTION) {
				cautions.add(new Caution(headline(item), List.of(item.id())));
			} else {
				// 메모 유형이거나 일정 날짜가 없는 글은 요약하지 않고 원문 확인으로 넘깁니다.
				needsOriginal.add(item.id());
			}
		}
		return new SummaryResponse(new Range(request.scope(), request.from(), request.to()),
				events, todos, cautions, conversation, needsOriginal);
	}

	private static String headline(SourceItem item) {
		String title = item.title();
		if (title != null && !title.isBlank()) {
			return title.strip();
		}
		String body = item.body() == null ? "" : item.body().strip();
		int newline = body.indexOf('\n');
		return newline < 0 ? body : body.substring(0, newline).strip();
	}
}
