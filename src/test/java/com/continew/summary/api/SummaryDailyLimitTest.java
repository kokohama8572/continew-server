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

@SpringBootTest(properties = "continew.summary.daily-limit=2")
@AutoConfigureMockMvc
class SummaryDailyLimitTest {

	private static final String SMALL = """
			{"scope": "shared", "from": "2026-11-09T21:00:00+09:00", "to": "2026-11-12T15:00:00+09:00",
			 "items": [{"id": "m1", "kind": "message", "body": "안녕"}]}
			""";

	@Autowired
	MockMvc mvc;

	private ResultActions call(String installationId) throws Exception {
		return mvc.perform(post("/api/v1/summaries").header("X-Installation-Id", installationId)
				.contentType(MediaType.APPLICATION_JSON).content(SMALL));
	}

	@Test
	void 하루_상한을_넘으면_429를_돌려준다() throws Exception {
		call("limit-a").andExpect(status().isOk());
		call("limit-a").andExpect(status().isOk());
		call("limit-a").andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"));
	}

	@Test
	void 상한은_설치마다_따로_센다() throws Exception {
		call("limit-b").andExpect(status().isOk());
		call("limit-b").andExpect(status().isOk());
		call("limit-c").andExpect(status().isOk());
	}
}
