package com.continew.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.continew.summary.ratelimit.DailyLimitInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final DailyLimitInterceptor dailyLimitInterceptor;

	public WebConfig(DailyLimitInterceptor dailyLimitInterceptor) {
		this.dailyLimitInterceptor = dailyLimitInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(dailyLimitInterceptor).addPathPatterns("/api/v1/summaries/**", "/api/v1/summaries");
	}
}
