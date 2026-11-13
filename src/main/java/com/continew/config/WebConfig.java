package com.continew.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.continew.summary.ratelimit.DailyLimitInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final DailyLimitInterceptor dailyLimitInterceptor;
	private final CorsProperties cors;

	public WebConfig(DailyLimitInterceptor dailyLimitInterceptor, CorsProperties cors) {
		this.dailyLimitInterceptor = dailyLimitInterceptor;
		this.cors = cors;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(dailyLimitInterceptor).addPathPatterns("/api/v1/summaries/**", "/api/v1/summaries");
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		List<String> origins = cors.allowedOrigins().stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
		if (origins.isEmpty()) {
			return;
		}
		registry.addMapping("/api/**")
				.allowedOrigins(origins.toArray(String[]::new))
				.allowedMethods("POST", "OPTIONS")
				.allowedHeaders("Content-Type", DailyLimitInterceptor.HEADER)
				.maxAge(3600);
	}
}
