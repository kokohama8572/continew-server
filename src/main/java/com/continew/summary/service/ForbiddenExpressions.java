package com.continew.summary.service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 요약에 나오면 안 되는 표현 (명세서 F-05-5b): 진단명, 증상 판단, 상태 추정, 감정 평가, 조언.
 *
 * <p>걸리면 그 줄은 요약에서 빠지고 원문 링크로 대체됩니다. 목록은 평가 세트(10장)로 보완해 나갑니다.
 */
final class ForbiddenExpressions {

	private static final List<Pattern> PATTERNS = List.of(
			// 상태 추정: "~한 상태로 보입니다", "~인 상태입니다"
			Pattern.compile("상태(로|인 것으로|인 듯)\\s*(보|판단|추정)"),
			// 감정·기분 평가: "불안해 보입니다", "우울해 보여요"
			Pattern.compile("[가-힣]+해\\s*보(입니다|여요|이네요|인다)"),
			// 진단명·임상 용어
			Pattern.compile("(진단|증상|우울증|불안장애|공황|해리|인격|교대|전환\\s*현상)"),
			// 조언·권고
			Pattern.compile("(권합니다|권장합니다|추천합니다|하시길\\s*바랍니다|받아\\s*보세요|상담을\\s*받)"));

	private ForbiddenExpressions() {
	}

	static boolean matches(String text) {
		if (text == null || text.isBlank()) {
			return false;
		}
		return PATTERNS.stream().anyMatch(p -> p.matcher(text).find());
	}
}
