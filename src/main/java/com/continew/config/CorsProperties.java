package com.continew.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 브라우저(웹 프론트)에서 서버를 부를 수 있는 주소. 비어 있으면 CORS를 열지 않습니다. */
@ConfigurationProperties(prefix = "continew.cors")
public record CorsProperties(@DefaultValue List<String> allowedOrigins) {
}
