package com.continew.summary.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.continew.summary.SummaryProperties;
import com.continew.summary.api.SummaryRequest;
import com.continew.summary.api.SummaryRequest.PostType;
import com.continew.summary.api.SummaryRequest.SourceItem;
import com.continew.summary.api.SummaryResponse;
import com.continew.summary.api.SummaryResponse.Caution;
import com.continew.summary.api.SummaryResponse.ConversationLine;
import com.continew.summary.api.SummaryResponse.DateConfidence;
import com.continew.summary.api.SummaryResponse.Event;
import com.continew.summary.api.SummaryResponse.Todo;

/**
 * 요약 초안이 사용자에게 가기 전의 안전 검사 (명세서 F-05-2, F-05-5).
 *
 * <ul>
 *   <li>모든 항목은 요청에 실제로 있는 원문과 연결되어야 합니다. 연결할 수 없으면 버립니다(F-05-2e).
 *   <li>원문에 없는 날짜·숫자가 있으면 그 항목을 뺍니다(F-05-5a).
 *   <li>금지 표현이 있으면 그 줄을 원문 확인({@code needs_original})으로 바꿉니다(F-05-5b).
 *   <li>묶음당 항목 수와 글자 수 상한을 지키고, 넘치는 글은 원문 확인으로 돌립니다(F-05-2d).
 * </ul>
 *
 * 빠진 항목의 출처 id는 {@code needs_original}에 모아, 앱이 '원문 확인 필요' 링크로 보여 줍니다(F-05-5c).
 */
@Component
public class SummaryGuard {

	private static final Pattern DIGITS = Pattern.compile("\\d+");

	private final SummaryProperties props;

	public SummaryGuard(SummaryProperties props) {
		this.props = props;
	}

	public SummaryResponse apply(SummaryRequest request, SummaryResponse draft) {
		Map<String, SourceItem> byId = new HashMap<>();
		for (SourceItem item : request.items()) {
			byId.put(item.id(), item);
		}
		Set<String> allowedLabels = new HashSet<>();
		for (SourceItem item : request.items()) {
			if (item.authorLabel() != null && !item.authorLabel().isBlank()) {
				allowedLabels.add(item.authorLabel());
			}
		}

		Set<String> needs = new LinkedHashSet<>();
		if (draft.needsOriginal() != null) {
			draft.needsOriginal().stream().filter(byId::containsKey).forEach(needs::add);
		}

		List<Event> events = new ArrayList<>();
		for (Event e : nullSafe(draft.events())) {
			List<String> sources = knownSources(e.sourceIds(), byId);
			if (sources.isEmpty()) {
				continue;
			}
			DateCheck check = checkEventDate(e, sources, byId);
			if (check == null || forbiddenOrUngrounded(e.title(), sources, byId)) {
				needs.addAll(sources);
				continue;
			}
			DateConfidence merged = e.dateConfidence() == DateConfidence.LOW ? DateConfidence.LOW : check.confidence();
			// 시각이 원문에서 확인되지 않으면 시각은 빼고 날짜 확인 필요로 보여 줍니다(F-05-5a).
			events.add(new Event(truncate(e.title()), e.date(), check.timeOk() ? e.time() : null, e.originalPhrase(),
					merged, sources));
		}

		List<Todo> todos = new ArrayList<>();
		for (Todo t : nullSafe(draft.todos())) {
			List<String> sources = knownSources(t.sourceIds(), byId);
			if (sources.isEmpty()) {
				continue;
			}
			if (forbiddenOrUngrounded(t.title(), sources, byId) || !dueMatches(t.due(), sources, byId)) {
				needs.addAll(sources);
				continue;
			}
			todos.add(new Todo(truncate(t.title()), t.due(), sources));
		}

		List<Caution> cautions = new ArrayList<>();
		for (Caution c : nullSafe(draft.cautions())) {
			List<String> sources = knownSources(c.sourceIds(), byId);
			if (sources.isEmpty()) {
				continue;
			}
			// 메모(note) 글은 주의사항이 아닙니다. 모델이 분류를 어기면 원문 확인으로 돌립니다.
			boolean fromNote = sources.stream().anyMatch(id -> byId.get(id).postType() == PostType.NOTE);
			if (fromNote || forbiddenOrUngrounded(c.text(), sources, byId)) {
				needs.addAll(sources);
				continue;
			}
			cautions.add(new Caution(truncate(c.text()), sources));
		}

		List<ConversationLine> conversation = new ArrayList<>();
		for (ConversationLine l : nullSafe(draft.conversation())) {
			List<String> sources = knownSources(l.sourceIds(), byId);
			if (sources.isEmpty()) {
				continue;
			}
			if (forbiddenOrUngrounded(l.text(), sources, byId)) {
				needs.addAll(sources);
				continue;
			}
			// 작성자 표기는 요청에 적힌 것만 허용합니다. 앱이 원래 이름으로 되돌립니다(F-05-6b).
			List<String> labels = nullSafe(l.authorLabels()).stream().filter(allowedLabels::contains).distinct().toList();
			conversation.add(new ConversationLine(truncate(l.text()), labels, sources));
		}

		return new SummaryResponse(draft.range(),
				capped(events, props.maxPerGroup(), Event::sourceIds, needs),
				capped(todos, props.maxPerGroup(), Todo::sourceIds, needs),
				capped(cautions, props.maxPerGroup(), Caution::sourceIds, needs),
				capped(conversation, props.maxConversationLines(), ConversationLine::sourceIds, needs),
				List.copyOf(needs));
	}

	// ---- 출처 ----

	private static List<String> knownSources(List<String> ids, Map<String, SourceItem> byId) {
		if (ids == null) {
			return List.of();
		}
		return ids.stream().filter(byId::containsKey).distinct().toList();
	}

	// ---- 날짜 ----

	private record DateCheck(DateConfidence confidence, boolean timeOk) {
	}

	/**
	 * HIGH: 원문의 일정 시각과 일치하거나, '오늘·내일·모레'를 작성일 기준으로 계산한 값과 일치 /
	 * LOW: 원문 표현만으로 연결 / null: 근거 없음.
	 */
	private DateCheck checkEventDate(Event e, List<String> sources, Map<String, SourceItem> byId) {
		if (e.date() == null) {
			return null;
		}
		for (String id : sources) {
			OffsetDateTime at = byId.get(id).eventAt();
			if (at == null) {
				continue;
			}
			OffsetDateTime local = at.atZoneSameInstant(props.zone()).toOffsetDateTime();
			boolean sameDate = local.toLocalDate().equals(e.date());
			boolean sameTime = e.time() == null
					|| local.toLocalTime().truncatedTo(ChronoUnit.MINUTES).equals(e.time().truncatedTo(ChronoUnit.MINUTES));
			if (sameDate && sameTime) {
				return new DateCheck(DateConfidence.HIGH, true);
			}
		}
		String phrase = e.originalPhrase();
		if (phrase != null && !phrase.isBlank()) {
			for (String id : sources) {
				SourceItem item = byId.get(id);
				String text = sourceText(item);
				if (!text.contains(phrase)) {
					continue;
				}
				boolean timeOk = e.time() == null || timeAppears(e.time(), text);
				boolean dateOk = item.createdAt() != null && RelativeDates
						.resolve(phrase, item.createdAt().atZoneSameInstant(props.zone()).toLocalDate())
						.filter(e.date()::equals).isPresent();
				return new DateCheck(dateOk && timeOk ? DateConfidence.HIGH : DateConfidence.LOW, timeOk);
			}
		}
		return null;
	}

	/** 시각의 시·분 숫자가 원문에 나오는지 확인합니다. 12시간제('2시')와 24시간제('14시')를 모두 허용합니다. */
	private static boolean timeAppears(LocalTime time, String text) {
		Set<String> digits = new HashSet<>();
		Matcher m = DIGITS.matcher(text);
		while (m.find()) {
			digits.add(m.group());
		}
		int hour = time.getHour();
		int hour12 = hour % 12 == 0 ? 12 : hour % 12;
		boolean hourOk = digits.contains(Integer.toString(hour)) || digits.contains(Integer.toString(hour12))
				|| digits.contains(String.format("%02d", hour));
		int minute = time.getMinute();
		boolean minuteOk = minute == 0 || digits.contains(Integer.toString(minute))
				|| digits.contains(String.format("%02d", minute));
		return hourOk && minuteOk;
	}

	private boolean dueMatches(LocalDate due, List<String> sources, Map<String, SourceItem> byId) {
		if (due == null) {
			return true;
		}
		for (String id : sources) {
			SourceItem item = byId.get(id);
			OffsetDateTime at = item.dueAt();
			if (at != null && at.atZoneSameInstant(props.zone()).toLocalDate().equals(due)) {
				return true;
			}
			// '모레까지'처럼 본문의 상대 표현이 작성일 기준으로 계산한 날짜와 같으면 인정합니다.
			if (item.createdAt() != null && RelativeDates
					.resolve(sourceText(item), item.createdAt().atZoneSameInstant(props.zone()).toLocalDate())
					.filter(due::equals).isPresent()) {
				return true;
			}
		}
		return false;
	}

	// ---- 금지 표현과 근거 없는 숫자 ----

	private boolean forbiddenOrUngrounded(String text, List<String> sources, Map<String, SourceItem> byId) {
		if (text == null || text.isBlank()) {
			return true;
		}
		if (ForbiddenExpressions.matches(text)) {
			return true;
		}
		Set<String> allowed = allowedNumbers(sources, byId);
		Matcher m = DIGITS.matcher(text);
		while (m.find()) {
			if (!allowed.contains(m.group())) {
				return true;
			}
		}
		return false;
	}

	/** 출처 글의 본문·제목에 나온 숫자와, 일정·마감 시각의 구성 요소(0 채움 유무 모두). */
	private Set<String> allowedNumbers(List<String> sources, Map<String, SourceItem> byId) {
		Set<String> allowed = new HashSet<>();
		for (String id : sources) {
			SourceItem item = byId.get(id);
			Matcher m = DIGITS.matcher(sourceText(item));
			while (m.find()) {
				allowed.add(m.group());
			}
			addTimeParts(allowed, item.eventAt());
			addTimeParts(allowed, item.dueAt());
		}
		return allowed;
	}

	private void addTimeParts(Set<String> allowed, OffsetDateTime at) {
		if (at == null) {
			return;
		}
		OffsetDateTime local = at.atZoneSameInstant(props.zone()).toOffsetDateTime();
		LocalDate d = local.toLocalDate();
		LocalTime t = local.toLocalTime();
		for (int part : new int[] { d.getYear(), d.getMonthValue(), d.getDayOfMonth(), t.getHour(), t.getMinute() }) {
			allowed.add(Integer.toString(part));
			allowed.add(String.format("%02d", part));
		}
	}

	private static String sourceText(SourceItem item) {
		return (item.title() == null ? "" : item.title()) + "\n" + (item.body() == null ? "" : item.body());
	}

	// ---- 분량 ----

	private String truncate(String text) {
		String t = text.strip();
		int max = props.maxTextLength();
		return t.length() <= max ? t : t.substring(0, max - 1) + "…";
	}

	private static <T> List<T> capped(List<T> items, int max, java.util.function.Function<T, List<String>> sourcesOf,
			Set<String> needs) {
		if (items.size() <= max) {
			return List.copyOf(items);
		}
		items.subList(max, items.size()).forEach(extra -> needs.addAll(sourcesOf.apply(extra)));
		return List.copyOf(items.subList(0, max));
	}

	private static <T> List<T> nullSafe(List<T> list) {
		return list == null ? List.of() : list;
	}
}
