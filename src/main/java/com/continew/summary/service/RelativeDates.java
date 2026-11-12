package com.continew.summary.service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * '오늘·내일·모레'처럼 글 작성일만으로 날짜가 정해지는 표현을 서버가 직접 계산합니다.
 * 모델이 계산한 날짜가 맞는지 검증하는 데 쓰며(F-05-2g), '다음 주 금요일'처럼 모호한 표현은 다루지 않습니다.
 */
final class RelativeDates {

	private RelativeDates() {
	}

	static Optional<LocalDate> resolve(String phrase, LocalDate writtenOn) {
		if (phrase == null || writtenOn == null) {
			return Optional.empty();
		}
		// '내일모레'는 모레이므로 모레를 먼저 확인합니다.
		if (phrase.contains("모레")) {
			return Optional.of(writtenOn.plusDays(2));
		}
		if (phrase.contains("내일")) {
			return Optional.of(writtenOn.plusDays(1));
		}
		if (phrase.contains("오늘")) {
			return Optional.of(writtenOn);
		}
		return Optional.empty();
	}
}
