package com.continew.summary.service;

import org.springframework.http.HttpStatus;

/** 사용자에게 보여 줄 수 있는 요약 오류. 메시지에는 글 내용을 넣지 않습니다. */
public class SummaryException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public SummaryException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}
}
