package com.continew.summary.ai;

import java.time.Duration;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.continew.summary.service.SummaryException;

/**
 * 공식 Anthropic Java SDK로 Claude를 호출합니다. 자격 증명은 SDK 기본 순서(환경 변수 {@code ANTHROPIC_API_KEY} 등)로 읽습니다.
 *
 * <p>글 내용과 응답은 로그에 남기지 않습니다. 실패 로그에는 예외 종류만 적습니다(F-05-6c).
 */
@Component
@ConditionalOnExpression("'${continew.summary.generator:structured}' == 'ai' and '${continew.summary.ai.provider:gemini}' == 'anthropic'")
class AnthropicTextClient implements AiTextClient {

	private static final Logger log = LoggerFactory.getLogger(AnthropicTextClient.class);

	private final AnthropicClient client;
	private final AiProperties props;

	AnthropicTextClient(AiProperties props) {
		this.props = props;
		// 재시도는 끕니다: 20초 안에 끝나지 않으면 실패로 처리하는 것이 명세(6.2)이고, 재시도하면 그 시간이 배로 늘어납니다.
		this.client = AnthropicOkHttpClient.builder()
				.fromEnv()
				.timeout(Duration.ofSeconds(props.timeoutSeconds()))
				.maxRetries(0)
				.build();
	}

	@Override
	public AiReply complete(String system, String user) {
		MessageCreateParams params = MessageCreateParams.builder()
				.model(props.anthropic().model())
				.maxTokens(props.maxTokens())
				.system(system)
				.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(props.anthropic().effort())).build())
				.addUserMessage(user)
				.build();
		Message response;
		try {
			response = client.messages().create(params);
		} catch (AnthropicException e) {
			log.warn("AI 요약 호출 실패: {}", e.getClass().getSimpleName());
			throw new SummaryException(HttpStatus.SERVICE_UNAVAILABLE, "SUMMARY_UNAVAILABLE",
					"지금은 요약할 수 없어요. 원문 목록으로 확인해 주세요.");
		}
		if (response.stopReason().filter(r -> r == StopReason.REFUSAL).isPresent()) {
			return AiReply.refusal();
		}
		String text = response.content().stream()
				.flatMap(block -> block.text().stream())
				.map(TextBlock::text)
				.collect(Collectors.joining());
		return AiReply.of(text);
	}
}
