package com.continew.summary.ai;

/**
 * 모델에 글을 보내고 텍스트 답을 받는 경계. 구현체를 바꾸면 다른 모델·공급자나 가짜 응답(테스트)을 쓸 수 있습니다.
 * 구현체는 요청·응답 내용을 로그에 남기지 않습니다(F-05-6c).
 */
public interface AiTextClient {

	AiReply complete(String system, String user);

	/**
	 * @param text 모델이 낸 텍스트 (거절된 경우 빈 문자열)
	 * @param refused 안전 분류기가 요청을 거절했는지
	 */
	record AiReply(String text, boolean refused) {

		public static AiReply of(String text) {
			return new AiReply(text, false);
		}

		public static AiReply refusal() {
			return new AiReply("", true);
		}
	}
}
