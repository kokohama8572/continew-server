package com.continew.summary.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class SummaryControllerTest {

	private static final String FOUR_ITEMS = """
			{
			  "scope": "shared",
			  "from": "2026-11-09T21:00:00+09:00",
			  "to": "2026-11-12T15:00:00+09:00",
			  "items": [
			    {"id": "post_812", "kind": "post", "post_type": "event", "title": "치과 진료", "event_at": "2026-11-13T14:00:00+09:00"},
			    {"id": "post_809", "kind": "post", "post_type": "caution", "title": "회사 출입증은 가방 안쪽 주머니에 있음"},
			    {"id": "post_810", "kind": "post", "post_type": "todo", "title": "택배 반품 접수", "due_at": "2026-11-20T00:00:00+09:00"},
			    {"id": "msg_3290", "kind": "message", "body": "주말 일정은 아직 정하지 않음", "author_label": "작성자2"}
			  ]
			}
			""";

	@Autowired
	MockMvc mvc;

	private ResultActions call(String installationId, String json) throws Exception {
		var request = post("/api/v1/summaries").contentType(MediaType.APPLICATION_JSON).content(json);
		if (installationId != null) {
			request.header("X-Installation-Id", installationId);
		}
		return mvc.perform(request);
	}

	@Test
	void 요약_응답은_명세서_6_3의_구조를_따른다() throws Exception {
		call("install-structure", FOUR_ITEMS)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.range.scope").value("shared"))
				.andExpect(jsonPath("$.events[0].title").value("치과 진료"))
				.andExpect(jsonPath("$.events[0].date").value("2026-11-13"))
				.andExpect(jsonPath("$.events[0].time").value("14:00"))
				.andExpect(jsonPath("$.events[0].date_confidence").value("high"))
				.andExpect(jsonPath("$.events[0].source_ids[0]").value("post_812"))
				.andExpect(jsonPath("$.todos[0].due").value("2026-11-20"))
				.andExpect(jsonPath("$.cautions[0].source_ids[0]").value("post_809"))
				.andExpect(jsonPath("$.conversation[0].author_labels[0]").value("작성자2"))
				.andExpect(jsonPath("$.conversation[0].source_ids[0]").value("msg_3290"))
				.andExpect(jsonPath("$.needs_original").isEmpty());
	}

	@Test
	void 새_글이_3개_이하면_요약하지_않고_원문_목록만_돌려준다() throws Exception {
		String json = """
				{"scope": "shared", "from": "2026-11-09T21:00:00+09:00", "to": "2026-11-12T15:00:00+09:00",
				 "items": [
				   {"id": "post_1", "kind": "post", "post_type": "note", "title": "메모"},
				   {"id": "msg_1", "kind": "message", "body": "안녕", "author_label": "작성자1"}
				 ]}
				""";
		call("install-small", json)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events").isEmpty())
				.andExpect(jsonPath("$.conversation").isEmpty())
				.andExpect(jsonPath("$.needs_original[0]").value("post_1"))
				.andExpect(jsonPath("$.needs_original[1]").value("msg_1"));
	}

	@Test
	void 개인_메모_범위는_아직_지원하지_않는다() throws Exception {
		call("install-memo", FOUR_ITEMS.replace("\"shared\"", "\"memo\""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SCOPE_NOT_SUPPORTED"));
	}

	@Test
	void 글이_300개를_넘으면_기간을_줄이라고_안내한다() throws Exception {
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < 301; i++) {
			items.append(i == 0 ? "" : ",")
					.append("{\"id\":\"m").append(i).append("\",\"kind\":\"message\",\"body\":\"x\"}");
		}
		String json = "{\"scope\":\"shared\",\"from\":\"2026-11-09T21:00:00+09:00\",\"to\":\"2026-11-12T15:00:00+09:00\",\"items\":["
				+ items + "]}";
		call("install-many", json)
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("TOO_MANY_ITEMS"));
	}

	@Test
	void 설치_id_헤더가_없으면_거절한다() throws Exception {
		call(null, FOUR_ITEMS)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_INSTALLATION_ID"));
	}

	@Test
	void 같은_id의_글이_중복되면_거절한다() throws Exception {
		call("install-dup", FOUR_ITEMS.replace("post_810", "post_809"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("DUPLICATE_ITEM_ID"));
	}

	@Test
	void 잘못된_요청의_오류_응답에는_글_내용이_들어가지_않는다() throws Exception {
		String json = FOUR_ITEMS.replace("\"kind\": \"message\"", "\"kind\": \"unknown\"");
		call("install-bad", json)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(jsonPath("$.detail").value("요청 본문을 읽을 수 없습니다."));
	}
}
