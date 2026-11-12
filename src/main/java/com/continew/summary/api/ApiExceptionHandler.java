package com.continew.summary.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.continew.summary.service.SummaryException;

/**
 * 오류 응답에는 요청에 담긴 글 내용을 되돌려 주지 않습니다. 어느 필드가 잘못됐는지만 알려 줍니다(F-05-6c).
 */
@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(SummaryException.class)
	ProblemDetail handle(SummaryException e) {
		return problem(e.status(), e.code(), e.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handle(MethodArgumentNotValidException e) {
		List<String> fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().toList();
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식이 올바르지 않습니다.");
		problem.setProperty("fields", fields);
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 본문을 읽을 수 없습니다.");
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ProblemDetail handle(MissingRequestHeaderException e) {
		return problem(HttpStatus.BAD_REQUEST, "MISSING_INSTALLATION_ID", "X-Installation-Id 헤더가 필요합니다.");
	}

	private static ProblemDetail problem(HttpStatus status, String code, String message) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
		problem.setProperty("code", code);
		return problem;
	}
}
