package com.continew.summary.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.continew.summary.SummaryProperties;
import com.continew.summary.api.SummaryRequest;
import com.continew.summary.api.SummaryRequest.Kind;
import com.continew.summary.api.SummaryRequest.PostType;
import com.continew.summary.api.SummaryRequest.Scope;
import com.continew.summary.api.SummaryRequest.SourceItem;
import com.continew.summary.api.SummaryResponse;
import com.continew.summary.api.SummaryResponse.Caution;
import com.continew.summary.api.SummaryResponse.ConversationLine;
import com.continew.summary.api.SummaryResponse.DateConfidence;
import com.continew.summary.api.SummaryResponse.Event;
import com.continew.summary.api.SummaryResponse.Range;
import com.continew.summary.api.SummaryResponse.Todo;

class SummaryGuardTest {

	private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-09T21:00:00+09:00");
	private static final OffsetDateTime TO = OffsetDateTime.parse("2026-11-12T15:00:00+09:00");

	private final SummaryProperties props = new SummaryProperties(20, 300, 3, 5, 5, 60, ZoneId.of("Asia/Seoul"));
	/** 테스트 속 '지금'은 2026-11-12 10:00(한국 시간)으로 고정합니다. */
	private static final Clock NOW = Clock.fixed(OffsetDateTime.parse("2026-11-12T10:00:00+09:00").toInstant(),
			ZoneId.of("Asia/Seoul"));
	private final SummaryGuard guard = new SummaryGuard(props, NOW);

	private static SourceItem post(String id, PostType type, String title, String body, OffsetDateTime eventAt) {
		return new SourceItem(id, Kind.POST, type, title, body, null, eventAt, null, null);
	}

	private static SourceItem message(String id, String body, String author) {
		return message(id, body, author, null);
	}

	private static SourceItem message(String id, String body, String author, OffsetDateTime createdAt) {
		return new SourceItem(id, Kind.MESSAGE, null, null, body, author, null, null, createdAt);
	}

	private static SummaryRequest request(SourceItem... items) {
		return new SummaryRequest(Scope.SHARED, FROM, TO, List.of(items));
	}

	private static SummaryResponse draft(List<Event> events, List<Todo> todos, List<Caution> cautions,
			List<ConversationLine> conversation) {
		return new SummaryResponse(new Range(Scope.SHARED, FROM, TO), events, todos, cautions, conversation, List.of());
	}

	private static SummaryResponse cautions(Caution... cautions) {
		return draft(List.of(), List.of(), List.of(cautions), List.of());
	}

	@Test
	void 출처가_없거나_존재하지_않는_항목은_버린다() {
		SummaryRequest req = request(post("p1", PostType.CAUTION, "출입증은 가방 주머니", null, null));

		SummaryResponse result = guard.apply(req, cautions(
				new Caution("출입증은 가방 주머니", List.of()),
				new Caution("출입증은 가방 주머니", List.of("ghost"))));

		assertThat(result.cautions()).isEmpty();
		assertThat(result.needsOriginal()).isEmpty();
	}

	@Test
	void 금지_표현이_있으면_그_줄을_원문_확인으로_바꾼다() {
		SummaryRequest req = request(post("p1", PostType.CAUTION, "메모", "오늘 좀 힘들었다", null));

		SummaryResponse result = guard.apply(req, cautions(new Caution("불안해 보입니다", List.of("p1"))));

		assertThat(result.cautions()).isEmpty();
		assertThat(result.needsOriginal()).containsExactly("p1");
	}

	@Test
	void 진단명과_상태_추정과_조언도_걸러낸다() {
		SummaryRequest req = request(post("p1", PostType.CAUTION, "메모", "x", null));
		for (String text : List.of("우울증 증상이 있음", "지친 상태로 보입니다", "상담을 받아 보세요")) {
			SummaryResponse result = guard.apply(req, cautions(new Caution(text, List.of("p1"))));
			assertThat(result.cautions()).as(text).isEmpty();
			assertThat(result.needsOriginal()).as(text).containsExactly("p1");
		}
	}

	@Test
	void 원문에_없는_숫자가_들어간_항목은_뺀다() {
		SummaryRequest req = request(post("p1", PostType.CAUTION, "출입증", "가방 안쪽 주머니에 있음", null));

		SummaryResponse result = guard.apply(req, cautions(new Caution("출입증 보관함 4821번", List.of("p1"))));

		assertThat(result.cautions()).isEmpty();
		assertThat(result.needsOriginal()).containsExactly("p1");
	}

	@Test
	void 원문에_있는_숫자는_통과한다() {
		SummaryRequest req = request(post("p1", PostType.CAUTION, "3번 출구에서 만남", null, null));

		SummaryResponse result = guard.apply(req, cautions(new Caution("3번 출구에서 만남", List.of("p1"))));

		assertThat(result.cautions()).hasSize(1);
		assertThat(result.needsOriginal()).isEmpty();
	}

	@Test
	void 일정_날짜가_원문과_같으면_high로_통과한다() {
		OffsetDateTime at = OffsetDateTime.parse("2026-11-13T14:00:00+09:00");
		SummaryRequest req = request(post("p1", PostType.EVENT, "치과 진료", null, at));

		SummaryResponse result = guard.apply(req, draft(List.of(new Event("치과 진료", LocalDate.of(2026, 11, 13),
				LocalTime.of(14, 0), null, DateConfidence.HIGH, List.of("p1"))), List.of(), List.of(), List.of()));

		assertThat(result.events()).hasSize(1);
		assertThat(result.events().get(0).dateConfidence()).isEqualTo(DateConfidence.HIGH);
	}

	@Test
	void 일정_날짜가_원문과_다르면_원문_확인으로_돌린다() {
		OffsetDateTime at = OffsetDateTime.parse("2026-11-13T14:00:00+09:00");
		SummaryRequest req = request(post("p1", PostType.EVENT, "치과 진료", null, at));

		SummaryResponse result = guard.apply(req, draft(List.of(new Event("치과 진료", LocalDate.of(2026, 11, 14),
				LocalTime.of(14, 0), null, DateConfidence.HIGH, List.of("p1"))), List.of(), List.of(), List.of()));

		assertThat(result.events()).isEmpty();
		assertThat(result.needsOriginal()).containsExactly("p1");
	}

	@Test
	void 원문_표현만으로_연결된_날짜는_low로_표시한다() {
		SummaryRequest req = request(message("m1", "치과는 내일 2시에 가기로 함", "작성자1"));

		SummaryResponse result = guard.apply(req, draft(List.of(new Event("치과", LocalDate.of(2026, 11, 13),
				LocalTime.of(14, 0), "내일 2시", DateConfidence.HIGH, List.of("m1"))), List.of(), List.of(), List.of()));

		assertThat(result.events()).hasSize(1);
		assertThat(result.events().get(0).dateConfidence()).isEqualTo(DateConfidence.LOW);
	}

	@Test
	void 내일_같은_표현은_작성일_기준으로_계산한_날짜와_맞으면_high다() {
		OffsetDateTime written = OffsetDateTime.parse("2026-11-12T10:00:00+09:00");
		SummaryRequest req = request(message("m1", "치과는 내일 2시에 가기로 함", "작성자1", written));

		SummaryResponse result = guard.apply(req, draft(List.of(new Event("치과", LocalDate.of(2026, 11, 13),
				LocalTime.of(14, 0), "내일 2시", DateConfidence.HIGH, List.of("m1"))), List.of(), List.of(), List.of()));

		assertThat(result.events()).hasSize(1);
		assertThat(result.events().get(0).dateConfidence()).isEqualTo(DateConfidence.HIGH);
		assertThat(result.events().get(0).time()).isEqualTo(LocalTime.of(14, 0));
	}

	@Test
	void 모델이_계산한_날짜가_작성일_기준과_다르면_low로_낮춘다() {
		OffsetDateTime written = OffsetDateTime.parse("2026-11-12T10:00:00+09:00");
		SummaryRequest req = request(message("m1", "치과는 내일 2시에 가기로 함", "작성자1", written));

		// 내일이면 11/13인데 모델이 11/14로 계산
		SummaryResponse result = guard.apply(req, draft(List.of(new Event("치과", LocalDate.of(2026, 11, 14),
				LocalTime.of(14, 0), "내일 2시", DateConfidence.HIGH, List.of("m1"))), List.of(), List.of(), List.of()));

		assertThat(result.events()).hasSize(1);
		assertThat(result.events().get(0).dateConfidence()).isEqualTo(DateConfidence.LOW);
	}

	@Test
	void 원문에서_확인되지_않는_시각은_빼고_low로_표시한다() {
		OffsetDateTime written = OffsetDateTime.parse("2026-11-12T10:00:00+09:00");
		SummaryRequest req = request(message("m1", "치과는 내일 가기로 함", "작성자1", written));

		SummaryResponse result = guard.apply(req, draft(List.of(new Event("치과", LocalDate.of(2026, 11, 13),
				LocalTime.of(15, 30), "내일", DateConfidence.HIGH, List.of("m1"))), List.of(), List.of(), List.of()));

		assertThat(result.events()).hasSize(1);
		assertThat(result.events().get(0).time()).isNull();
		assertThat(result.events().get(0).dateConfidence()).isEqualTo(DateConfidence.LOW);
	}

	@Test
	void 원문에_없는_마감일은_원문_확인으로_돌린다() {
		SummaryRequest req = request(post("p1", PostType.TODO, "택배 반품 접수", null, null));

		SummaryResponse result = guard.apply(req, draft(List.of(),
				List.of(new Todo("택배 반품 접수", LocalDate.of(2026, 11, 20), List.of("p1"))), List.of(), List.of()));

		assertThat(result.todos()).isEmpty();
		assertThat(result.needsOriginal()).containsExactly("p1");
	}

	// ---- 지난 일정 제외 (지금은 2026-11-12 10:00) ----

	/** 일정 게시물 하나와, 그 일정을 그대로 옮긴 요약 항목을 만들어 가드에 통과시킵니다. */
	private SummaryResponse eventsAfterGuard(String... whens) {
		List<SourceItem> items = new ArrayList<>();
		List<Event> events = new ArrayList<>();
		for (int i = 0; i < whens.length; i++) {
			OffsetDateTime at = OffsetDateTime.parse(whens[i]);
			items.add(post("p" + i, PostType.EVENT, "일정" + i, null, at));
			events.add(new Event("일정" + i, at.toLocalDate(), at.toLocalTime(), null, DateConfidence.HIGH,
					List.of("p" + i)));
		}
		return guard.apply(request(items.toArray(SourceItem[]::new)), draft(events, List.of(), List.of(), List.of()));
	}

	@Test
	void 지난_일정은_빼고_원문_확인_대상에도_넣지_않는다() {
		SummaryResponse result = eventsAfterGuard("2026-11-11T17:00:00+09:00", "2026-11-13T14:00:00+09:00");

		assertThat(result.events()).extracting(Event::title).containsExactly("일정1");
		assertThat(result.needsOriginal()).isEmpty();
	}

	@Test
	void 오늘이라도_이미_지난_시각의_일정은_뺀다() {
		SummaryResponse result = eventsAfterGuard("2026-11-12T09:59:00+09:00", "2026-11-12T10:01:00+09:00");

		assertThat(result.events()).extracting(Event::title).containsExactly("일정1");
	}

	@Test
	void 시각이_없는_오늘_일정은_오늘_안에는_남긴다() {
		// 원문의 일정 시각이 오늘 00:00이고 요약에는 시각 없이 날짜만 있는 종일 일정
		OffsetDateTime allDay = OffsetDateTime.parse("2026-11-12T00:00:00+09:00");
		SummaryRequest req = request(post("p1", PostType.EVENT, "종일 행사", null, allDay));

		SummaryResponse today = guard.apply(req, draft(List.of(new Event("종일 행사", LocalDate.of(2026, 11, 12), null,
				null, DateConfidence.HIGH, List.of("p1"))), List.of(), List.of(), List.of()));

		assertThat(today.events()).extracting(Event::title).containsExactly("종일 행사");
		assertThat(today.needsOriginal()).isEmpty();
	}

	@Test
	void 시각_없는_일정도_날짜가_어제면_뺀다() {
		OffsetDateTime allDay = OffsetDateTime.parse("2026-11-11T00:00:00+09:00");
		SummaryRequest req = request(post("p1", PostType.EVENT, "어제 행사", null, allDay));

		SummaryResponse result = guard.apply(req, draft(List.of(new Event("어제 행사", LocalDate.of(2026, 11, 11), null,
				null, DateConfidence.HIGH, List.of("p1"))), List.of(), List.of(), List.of()));

		assertThat(result.events()).isEmpty();
		assertThat(result.needsOriginal()).isEmpty();
	}

	@Test
	void 일정은_가까운_순으로_정렬한다() {
		SummaryResponse result = eventsAfterGuard("2026-11-20T10:00:00+09:00", "2026-11-13T09:00:00+09:00",
				"2026-11-13T08:00:00+09:00");

		assertThat(result.events()).extracting(Event::title).containsExactly("일정2", "일정1", "일정0");
	}

	@Test
	void 지난_일정은_묶음_상한에_자리를_차지하지_않는다() {
		// 지난 일정 3개 + 다가오는 일정 7개 → 다가오는 일정 중 가까운 5개만 남고, 나머지 2개는 원문 확인으로 갑니다
		SummaryResponse result = eventsAfterGuard(
				"2026-11-01T10:00:00+09:00", "2026-11-02T10:00:00+09:00", "2026-11-03T10:00:00+09:00",
				"2026-11-20T10:00:00+09:00", "2026-11-19T10:00:00+09:00", "2026-11-18T10:00:00+09:00",
				"2026-11-17T10:00:00+09:00", "2026-11-16T10:00:00+09:00", "2026-11-15T10:00:00+09:00",
				"2026-11-14T10:00:00+09:00");

		assertThat(result.events()).hasSize(5);
		assertThat(result.events().get(0).date()).isEqualTo(LocalDate.of(2026, 11, 14));
		assertThat(result.events().get(4).date()).isEqualTo(LocalDate.of(2026, 11, 18));
		assertThat(result.needsOriginal()).containsExactlyInAnyOrder("p3", "p4");
	}

	@Test
	void 메모_글은_주의사항에_넣지_못한다() {
		SummaryRequest req = request(post("p1", PostType.NOTE, "냉장고에 우유 있음", null, null));

		SummaryResponse result = guard.apply(req, cautions(new Caution("냉장고에 우유 있음", List.of("p1"))));

		assertThat(result.cautions()).isEmpty();
		assertThat(result.needsOriginal()).containsExactly("p1");
	}

	@Test
	void 모레까지_같은_상대_마감일은_작성일_기준이_맞으면_할_일로_인정한다() {
		OffsetDateTime written = OffsetDateTime.parse("2026-11-12T09:40:00+09:00");
		SummaryRequest req = request(message("m1", "모레까지 도서관 책 반납해야 해", "작성자2", written));

		SummaryResponse ok = guard.apply(req, draft(List.of(),
				List.of(new Todo("도서관 책 반납", LocalDate.of(2026, 11, 14), List.of("m1"))), List.of(), List.of()));
		SummaryResponse wrong = guard.apply(req, draft(List.of(),
				List.of(new Todo("도서관 책 반납", LocalDate.of(2026, 11, 20), List.of("m1"))), List.of(), List.of()));

		assertThat(ok.todos()).hasSize(1);
		assertThat(wrong.todos()).isEmpty();
		assertThat(wrong.needsOriginal()).containsExactly("m1");
	}

	@Test
	void 요청에_없는_작성자_표기는_빼고_있는_것만_남긴다() {
		SummaryRequest req = request(message("m1", "주말 일정은 아직 안 정함", "작성자2"));

		SummaryResponse result = guard.apply(req, draft(List.of(), List.of(), List.of(),
				List.of(new ConversationLine("주말 일정은 아직 안 정함", List.of("작성자2", "김철수"), List.of("m1")))));

		assertThat(result.conversation()).hasSize(1);
		assertThat(result.conversation().get(0).authorLabels()).containsExactly("작성자2");
	}

	@Test
	void 묶음당_5개를_넘는_항목은_원문_확인으로_돌린다() {
		List<SourceItem> items = new ArrayList<>();
		List<Caution> drafts = new ArrayList<>();
		for (int i = 1; i <= 7; i++) {
			items.add(post("p" + i, PostType.CAUTION, "주의 항목", null, null));
			drafts.add(new Caution("주의 항목", List.of("p" + i)));
		}

		SummaryResponse result = guard.apply(request(items.toArray(SourceItem[]::new)),
				cautions(drafts.toArray(Caution[]::new)));

		assertThat(result.cautions()).hasSize(5);
		assertThat(result.needsOriginal()).containsExactly("p6", "p7");
	}

	@Test
	void 항목은_60자로_줄이고_말줄임표를_붙인다() {
		String longText = "가".repeat(100);
		SummaryRequest req = request(post("p1", PostType.CAUTION, longText, null, null));

		SummaryResponse result = guard.apply(req, cautions(new Caution(longText, List.of("p1"))));

		assertThat(result.cautions().get(0).text()).hasSize(60).endsWith("…");
	}
}
