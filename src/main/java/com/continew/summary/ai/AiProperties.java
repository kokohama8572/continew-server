package com.continew.summary.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 호출 설정. API 키는 설정 파일에 값을 적지 않고 환경 변수로 읽습니다
 * (Gemini는 {@code GEMINI_API_KEY}, Claude는 {@code ANTHROPIC_API_KEY}).
 */
@ConfigurationProperties(prefix = "continew.summary.ai")
public record AiProperties(
		/** gemini 또는 anthropic. */
		@DefaultValue("gemini") String provider,
		@DefaultValue("16000") long maxTokens,
		/** 이 시간을 넘기면 실패로 처리합니다(6.2 실패·예외 처리: 20초). */
		@DefaultValue("20") long timeoutSeconds,
		@DefaultValue Anthropic anthropic,
		@DefaultValue Gemini gemini) {

	public record Anthropic(
			@DefaultValue("claude-opus-5-5") String model,
			@DefaultValue("medium") String effort) {
	}

	public record Gemini(
			/** 환경 변수 GEMINI_API_KEY에서 채워집니다. */
			@DefaultValue("") String apiKey,
			@DefaultValue("gemini-3.6-flash") String model,
			@DefaultValue("https://generativelanguage.googleapis.com") String baseUrl,
			/** 비워 두면 모델 기본값을 씁니다. MINIMAL, LOW, MEDIUM, HIGH. 기본 모델에만 적용됩니다. */
			@DefaultValue("") String thinkingLevel,
			/**
			 * 기본 모델이 과부하(503·429) 등으로 실패하면 이 모델로 한 번 더 시도합니다. 비우면 쓰지 않습니다.
			 * 결과는 어느 모델이든 SummaryGuard를 거칩니다.
			 */
			@DefaultValue("gemini-3.5-flash-lite") String fallbackModel) {
	}
}
