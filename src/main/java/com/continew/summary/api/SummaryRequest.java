package com.continew.summary.api;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 이어받기 요약 요청 (명세서 F-05-2, F-05-6).
 *
 * <p>한 요청에는 한 요약 범위의 글만 담습니다(F-05-6a). 작성자 표기는 앱이 '작성자1, 작성자2'로 바꿔
 * 보내고 응답을 받은 뒤 되돌립니다(F-05-6b). 서버는 실제 공간 이름이나 공간 id를 받지 않습니다.
 */
public record SummaryRequest(
		@NotNull Scope scope,
		@NotNull OffsetDateTime from,
		@NotNull OffsetDateTime to,
		@NotNull @Size(max = 1000) List<@NotNull @Valid SourceItem> items) {

	public enum Scope {
		@JsonProperty("shared") SHARED,
		/** 개인 메모 요약은 P1(F-05-3)이라 아직 지원하지 않습니다. */
		@JsonProperty("memo") MEMO
	}

	public enum Kind {
		@JsonProperty("post") POST,
		@JsonProperty("message") MESSAGE
	}

	public enum PostType {
		@JsonProperty("event") EVENT,
		@JsonProperty("todo") TODO,
		@JsonProperty("caution") CAUTION,
		@JsonProperty("note") NOTE
	}

	public record SourceItem(
			@NotBlank @Size(max = 64) String id,
			@NotNull Kind kind,
			PostType postType,
			@Size(max = 500) String title,
			@Size(max = 5000) String body,
			@Size(max = 40) String authorLabel,
			OffsetDateTime eventAt,
			OffsetDateTime dueAt) {
	}
}
