package com.continew.summary.ratelimit;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.continew.summary.service.SummaryException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class DailyLimitInterceptor implements HandlerInterceptor {

	public static final String HEADER = "X-Installation-Id";
	private static final int MAX_ID_LENGTH = 64;

	private final DailyLimitService limits;

	public DailyLimitInterceptor(DailyLimitService limits) {
		this.limits = limits;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		String id = request.getHeader(HEADER);
		if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH) {
			throw new SummaryException(HttpStatus.BAD_REQUEST, "MISSING_INSTALLATION_ID",
					HEADER + " 헤더가 필요합니다(최대 " + MAX_ID_LENGTH + "자).");
		}
		if (!limits.tryAcquire(id)) {
			throw new SummaryException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_LIMIT_EXCEEDED",
					"오늘 쓸 수 있는 요약 횟수를 모두 썼어요. 원문 목록으로 확인해 주세요.");
		}
		return true;
	}
}
