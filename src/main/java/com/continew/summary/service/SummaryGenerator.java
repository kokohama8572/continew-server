package com.continew.summary.service;

import com.continew.summary.api.SummaryRequest;
import com.continew.summary.api.SummaryResponse;

/**
 * 요약 초안을 만드는 구현체의 경계. 결과는 곧바로 사용자에게 가지 않고 {@link SummaryGuard}를 반드시 거칩니다.
 *
 * <p>AI 처리 위치(열린 질문 Q3)가 정해지면 이 인터페이스의 AI 구현체를 추가합니다.
 */
public interface SummaryGenerator {

	SummaryResponse generate(SummaryRequest request);
}
