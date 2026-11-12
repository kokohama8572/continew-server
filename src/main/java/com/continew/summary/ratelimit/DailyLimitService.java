package com.continew.summary.ratelimit;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import com.continew.summary.SummaryProperties;

/**
 * 설치 단위 하루 요청 상한 (F-05-6f). 회원가입이 없는 MVP라 앱 설치마다 만든 익명 id로 셉니다.
 *
 * <p>메모리에만 보관하므로 서버를 다시 시작하면 초기화되고, 여러 서버로 늘리면 공유 저장소가 필요합니다.
 * id는 로그에 남기지 않습니다.
 */
@Service
public class DailyLimitService {

	private static final int PURGE_THRESHOLD = 10_000;

	private record Usage(LocalDate day, int count) {
	}

	private final ConcurrentMap<String, Usage> usage = new ConcurrentHashMap<>();
	private final SummaryProperties props;
	private final Clock clock;

	public DailyLimitService(SummaryProperties props, Clock clock) {
		this.props = props;
		this.clock = clock;
	}

	/** 오늘 남은 횟수가 있으면 하나 쓰고 true. */
	public boolean tryAcquire(String installationId) {
		LocalDate today = LocalDate.now(clock.withZone(props.zone()));
		AtomicBoolean allowed = new AtomicBoolean();
		usage.compute(installationId, (id, current) -> {
			int used = current != null && current.day().equals(today) ? current.count() : 0;
			if (used >= props.dailyLimit()) {
				allowed.set(false);
				return new Usage(today, used);
			}
			allowed.set(true);
			return new Usage(today, used + 1);
		});
		if (usage.size() > PURGE_THRESHOLD) {
			usage.entrySet().removeIf(e -> !e.getValue().day().equals(today));
		}
		return allowed.get();
	}
}
