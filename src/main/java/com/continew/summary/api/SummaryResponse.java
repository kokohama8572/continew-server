package com.continew.summary.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.continew.summary.api.SummaryRequest.Scope;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 요약 응답 구조 (명세서 6.3). 화면은 앱이 그리므로 자유 서술형 응답은 두지 않습니다(F-05-5d).
 * 모든 항목은 {@code sourceIds}로 원문과 연결되며, 연결할 수 없는 항목은 응답에 포함되지 않습니다(F-05-2e).
 */
public record SummaryResponse(
		Range range,
		List<Event> events,
		List<Todo> todos,
		List<Caution> cautions,
		List<ConversationLine> conversation,
		List<String> needsOriginal) {

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
