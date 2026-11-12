package com.continew.summary.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.continew.summary.SummaryProperties;
import com.continew.summary.ai.AiTextClient.AiReply;
import com.continew.summary.api.SummaryRequest;
import com.continew.summary.api.SummaryRequest.Kind;
import com.continew.summary.api.SummaryRequest.PostType;
import com.continew.summary.api.SummaryRequest.Scope;
import com.continew.summary.api.SummaryRequest.SourceItem;
import com.continew.summary.api.SummaryResponse;
import com.continew.summary.api.SummaryResponse.DateConfidence;
import com.continew.summary.service.SummaryException;
import com.continew.summary.service.SummaryGuard;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class AiSummaryGeneratorTest {

	private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-09T21:00:00+09:00");
	private static final OffsetDateTime TO = OffsetDateTime.parse("2026-11-12T15:00:00+09:00");

	private final SummaryProperties props = new SummaryProperties(20, 300, 3, 5, 5, 60, ZoneId.of("Asia/Seoul"));
	private final JsonMapper mapper = JsonMapper.builder()
			.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

	/** 마지막으로 받은 입력을 기억하고, 정해 둔 답을 돌려주는 가짜 모델. */
	private static class FakeClient implements AiTextClient {
		private final AiReply reply;
		String system;
		String user;

		FakeClient(AiReply reply) {
			this.reply = reply;
		}

		@Override
		public AiReply complete(String system, String user) {
			this.system = system;
			this.user = user;
			return reply;
		}
	}

	private SummaryRequest request() {
		List<SourceItem> items = new ArrayList<>();
		items.add(new SourceItem("msg_1", Kind.MESSAGE, null, null, "치과는 내일 2시에 가기로 함", "작성자1", null, null,
				OffsetDateTime.parse("2026-11-12T10:00:00+09:00")));
		items.add(new SourceItem("post_1", Kind.POST, PostType.CAUTION, "출입증은 가방 주머니", null, null, null, null, null));
		items.add(new SourceItem("post_2", Kind.POST, PostType.NOTE, "메모", "잡담", null, null, null, null));
		items.add(new SourceItem("msg_2", Kind.MESSAGE, null, null, "주말 일정은 미정", "작성자2", null, null, null));
		return new SummaryRequest(Scope.SHARED, FROM, TO, items);
	}

	private AiSummaryGenerator generator(FakeClient client) {
		return new AiSummaryGenerator(client, mapper, props);
	}

	@Test
	void 모델의_JSON_답을_요약_구조로_바꾸고_범위를_채운다() {
		FakeClient client = new FakeClient(AiReply.of("""
				{"events":[{"title":"치과","date":"2026-11-13","time":"14:00","original_phrase":"내일 2시",
				  "date_confidence":"high","source_ids":["msg_1"]}],
				 "todos":[],
				 "cautions":[{"text":"출입증은 가방 주머니","source_ids":["post_1"]}],
				 "conversation":[{"text":"주말 일정은 미정","author_labels":["작성자2"],"source_ids":["msg_2"]}],
				 "needs_original":["post_2"]}
				"""));

		SummaryResponse result = generator(client).generate(request());

		assertThat(result.range().scope()).isEqualTo(Scope.SHARED);
		assertThat(result.events()).hasSize(1);
		assertThat(result.events().get(0).dateConfidence()).isEqualTo(DateConfidence.HIGH);
		assertThat(result.events().get(0).sourceIds()).containsExactly("msg_1");
		assertThat(result.cautions().get(0).text()).isEqualTo("출입증은 가방 주머니");
		assertThat(result.conversation().get(0).authorLabels()).containsExactly("작성자2");
		assertThat(result.needsOriginal()).containsExactly("post_2");
	}

	@Test
	void 코드_블록과_앞뒤_문장이_섞여_와도_JSON만_꺼내_읽는다() {
		FakeClient client = new FakeClient(AiReply.of("""
				요약 결과입니다.
				```json
				{"events":[],"todos":[],"cautions":[],"conversation":[],"needs_original":["post_2"]}
				```
				"""));

		SummaryResponse result = generator(client).generate(request());

		assertThat(result.needsOriginal()).containsExactly("post_2");
	}

	@Test
	void JSON이_아니면_요약할_수_없다고_답한다() {
		FakeClient client = new FakeClient(AiReply.of("죄송하지만 요약할 수 없습니다."));

		assertThatThrownBy(() -> generator(client).generate(request()))
				.isInstanceOfSatisfying(SummaryException.class, e -> {
					assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(e.code()).isEqualTo("SUMMARY_UNAVAILABLE");
				});
	}

	@Test
	void 깨진_JSON의_오류_메시지에는_모델이_쓴_글이_들어가지_않는다() {
		FakeClient client = new FakeClient(AiReply.of("{\"events\": [비밀스러운내용 이것은 깨진 JSON]}"));

		assertThatThrownBy(() -> generator(client).generate(request()))
				.isInstanceOf(SummaryException.class)
				.hasMessageNotContaining("비밀스러운내용");
	}

	@Test
	void 모델이_거절하면_모든_글을_원문_확인으로_돌린다() {
		FakeClient client = new FakeClient(AiReply.refusal());

		SummaryResponse result = generator(client).generate(request());

		assertThat(result.events()).isEmpty();
		assertThat(result.needsOriginal()).containsExactly("msg_1", "post_1", "post_2", "msg_2");
	}

	@Test
	void 모델에_보내는_입력은_글_목록과_규칙을_담고_공간_정보는_담지_않는다() {
		FakeClient client = new FakeClient(AiReply.of("{\"needs_original\":[]}"));

		generator(client).generate(request());

		assertThat(client.system).contains("해석하지 않습니다").contains("source_ids").contains("needs_original");
		assertThat(client.user).contains("\"id\":\"msg_1\"").contains("치과는 내일 2시에 가기로 함")
				.contains("\"author_label\":\"작성자1\"").contains("\"created_at\":\"2026-11-12T10:00+09:00\"");
		assertThat(client.user).doesNotContain("space");
	}

	@Test
	void 모델이_규칙을_어겨도_가드가_걸러낸다() {
		// 모델이 원문에 없는 숫자를 넣고, 상태를 추정하고, 없는 id를 인용한 경우
		FakeClient client = new FakeClient(AiReply.of("""
				{"events":[],"todos":[],
				 "cautions":[{"text":"출입증 보관함 4821번","source_ids":["post_1"]},
				             {"text":"지친 상태로 보입니다","source_ids":["msg_2"]},
				             {"text":"존재하지 않는 글","source_ids":["ghost"]}],
				 "conversation":[],"needs_original":[]}
				"""));
		SummaryRequest req = request();

		SummaryResponse result = new SummaryGuard(props).apply(req, generator(client).generate(req));

		assertThat(result.cautions()).isEmpty();
		assertThat(result.needsOriginal()).containsExactlyInAnyOrder("post_1", "msg_2");
	}
}
