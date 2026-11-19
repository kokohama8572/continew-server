package com.continew.summary.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.continew.summary.api.SummaryRequest.Scope;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 요약 응답 구조 (명세서 6.3). 항목(일정·할 일·주의사항·대화)이 기본이고, 줄글({@code summaryText})은 그 항목을
 * 이어 쓴 읽기용 문단입니다.
 * 모든 항목은 {@code sourceIds}로 원문과 연결되며, 연결할 수 없는 항목은 응답에 포함되지 않습니다(F-05-2e).
 */
public record SummaryResponse(
		Range range,
		List<Event> events,
		List<Todo> todos,
		List<Caution> cautions,
		List<ConversationLine> conversation,
		List<String> needsOriginal,
		/**
		 * 위 항목을 이어 쓴 줄글 요약 한 단락. 검사를 통과하지 못했거나 만들지 못했으면 null이며,
		 * 앱은 이때 항목별 화면으로 보여 줍니다. 근거 글은 위 항목의 {@code sourceIds}로 확인합니다.
		 */
		String summaryText) {

	/** 줄글 없이 항목만 있는 응답. */
	public SummaryResponse(Range range, List<Event> events, List<Todo> todos, List<Caution> cautions,
			List<ConversationLine> conversation, List<String> needsOriginal) {
		this(range, events, todos, cautions, conversation, needsOriginal, null);
	}

	public record Range(Scope scope, OffsetDateTime from, OffsetDateTime to) {
	}

	public enum DateConfidence {
		@JsonProperty("high") HIGH,
		/** 앱이 '날짜 확인 필요'를 붙입니다(F-05-2g). */
		@JsonProperty("low") LOW
	}

	public record Event(
			String title,
			LocalDate date,
			@JsonFormat(pattern = "HH:mm") LocalTime time,
			String originalPhrase,
			DateConfidence dateConfidence,
			List<String> sourceIds) {
	}

	public record Todo(String title, LocalDate due, List<String> sourceIds) {
	}

	public record Caution(String text, List<String> sourceIds) {
	}

	public record ConversationLine(String text, List<String> authorLabels, List<String> sourceIds) {
	}
}
