package com.continew.summary.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.continew.summary.SummaryProperties;
import com.continew.summary.api.SummaryRequest;
import com.continew.summary.api.SummaryRequest.Kind;
import com.continew.summary.api.SummaryRequest.Scope;
import com.continew.summary.api.SummaryRequest.SourceItem;
import com.continew.summary.api.SummaryResponse;
import com.continew.summary.api.SummaryResponse.Range;

/**
 * 이어받기 요약(F-05-2). 글 내용은 로그에 남기지 않고, 처리가 끝나면 어디에도 저장하지 않습니다(F-05-6c).
 */
@Service
public class SummaryService {

	private final SummaryProperties props;
	private final SummaryGenerator generator;
	private final SummaryGuard guard;

	public SummaryService(SummaryProperties props, SummaryGenerator generator, SummaryGuard guard) {
		this.props = props;
		this.generator = generator;
		this.guard = guard;
	}

	public SummaryResponse summarize(SummaryRequest request) {
		validate(request);

		// 새 글이 적으면 요약하지 않고 원문 목록을 바로 보여줍니다(F-05-2j).
		if (request.items().size() <= props.originalsOnlyThreshold()) {
			return new SummaryResponse(new Range(request.scope(), request.from(), request.to()),
					List.of(), List.of(), List.of(), List.of(),
					request.items().stream().map(SourceItem::id).toList());
		}
		return guard.apply(request, generator.generate(request));
	}

	private void validate(SummaryRequest request) {
		if (request.scope() != Scope.SHARED) {
			throw new SummaryException(HttpStatus.BAD_REQUEST, "SCOPE_NOT_SUPPORTED",
					"지금은 공용 영역 요약만 지원합니다.");
		}
		if (request.to().isBefore(request.from())) {
			throw new SummaryException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "기간의 끝이 시작보다 앞섭니다.");
		}
		if (request.items().size() > props.maxItems()) {
			throw new SummaryException(HttpStatus.UNPROCESSABLE_CONTENT, "TOO_MANY_ITEMS",
					"글이 너무 많아요. 기간을 줄여 주세요.");
		}
		Set<String> ids = new HashSet<>();
		for (SourceItem item : request.items()) {
			if (!ids.add(item.id())) {
				throw new SummaryException(HttpStatus.BAD_REQUEST, "DUPLICATE_ITEM_ID", "같은 id의 글이 두 번 들어 있습니다.");
			}
			if (item.kind() == Kind.POST && item.postType() == null) {
				throw new SummaryException(HttpStatus.BAD_REQUEST, "INVALID_ITEM", "게시물에는 post_type이 필요합니다.");
			}
		}
	}
}
