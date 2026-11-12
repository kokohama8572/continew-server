package com.continew.summary.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 호출 설정. API 키는 설정 파일에 두지 않고 환경 변수 {@code ANTHROPIC_API_KEY}로 읽습니다(SDK 기본 동작).
 */
@ConfigurationProperties(prefix = "continew.summary.ai")
public record AiProperties(
		@DefaultValue("claude-opus-5-5") String model,
		@DefaultValue("medium") String effort,
		@DefaultValue("16000") long maxTokens,
		/** 이 시간을 넘기면 실패로 처리합니다(6.2 실패·예외 처리: 20초). */
		@DefaultValue("20") long timeoutSeconds) {
}
