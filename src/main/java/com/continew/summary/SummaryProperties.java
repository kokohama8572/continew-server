package com.continew.summary;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "continew.summary")
public record SummaryProperties(
		@DefaultValue("20") int dailyLimit,
		@DefaultValue("300") int maxItems,
		@DefaultValue("3") int originalsOnlyThreshold,
		@DefaultValue("5") int maxPerGroup,
		@DefaultValue("5") int maxConversationLines,
		@DefaultValue("60") int maxTextLength,
		@DefaultValue("Asia/Seoul") ZoneId zone) {
}
