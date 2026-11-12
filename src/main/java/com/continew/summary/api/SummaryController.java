package com.continew.summary.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.continew.summary.ratelimit.DailyLimitInterceptor;
import com.continew.summary.service.SummaryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "AI 요약", description = "이어받기 요약(F-05-2). 공용 영역 글만 받으며 결과는 저장하지 않습니다.")
@RestController
@RequestMapping("/api/v1/summaries")
public class SummaryController {

	private final SummaryService service;

	public SummaryController(SummaryService service) {
		this.service = service;
	}

	@Operation(summary = "공용 영역 이어받기 요약",
			description = "요청한 기간의 공용 글(게시물·메시지)을 일정·할 일·주의사항·대화 주요 내용으로 정리합니다. "
					+ "모든 항목에 source_ids(원문 id)가 붙고, 요약하지 못한 글은 needs_original에 담깁니다. "
					+ "작성자 표기는 '작성자1' 같은 가명으로 보내 주세요.")
	@PostMapping
	public SummaryResponse create(
			@Parameter(description = "앱 설치마다 만든 익명 id(UUID). 하루 요청 상한 계산에만 쓰이며 저장하지 않습니다.")
			@RequestHeader(DailyLimitInterceptor.HEADER) String installationId,
			@Valid @RequestBody SummaryRequest request) {
		return service.summarize(request);
	}
}
