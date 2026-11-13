package com.continew.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "continew.cors.allowed-origins=https://continew-web.example.com, http://localhost:5173")
@AutoConfigureMockMvc
class CorsTest {

	@Autowired
	MockMvc mvc;

	private org.springframework.test.web.servlet.ResultActions preflight(String origin) throws Exception {
		return mvc.perform(options("/api/v1/summaries")
				.header("Origin", origin)
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "content-type,x-installation-id"));
	}

	@Test
	void 허용한_주소의_사전_확인은_헤더_없이도_통과한다() throws Exception {
		preflight("https://continew-web.example.com")
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "https://continew-web.example.com"))
				.andExpect(header().string("Access-Control-Allow-Methods", "POST,OPTIONS"));
	}

	@Test
	void 공백이_섞인_두_번째_주소도_허용한다() throws Exception {
		preflight("http://localhost:5173")
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
	}

	@Test
	void 허용하지_않은_주소는_거절한다() throws Exception {
		preflight("https://evil.example.com").andExpect(status().isForbidden());
	}
}
