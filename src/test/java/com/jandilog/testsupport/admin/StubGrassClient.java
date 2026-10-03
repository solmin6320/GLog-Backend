package com.jandilog.testsupport.admin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.service.GrassClient;
import com.jandilog.judgment.service.GrassFetchException;

// GitHub 대신 쓰는 가짜 잔디 클라이언트. 아이디별로 실패를 지정하고, 지정이 없으면 매일 기여가 있는 것으로 답한다.
// 테스트가 GitHub를 호출하지 않게 하려고 @Primary로 갈아끼운다
public class StubGrassClient implements GrassClient {

	private final Map<String, HoldReason> failures = new ConcurrentHashMap<>();
	private final AtomicInteger calls = new AtomicInteger();

	public void failFor(String githubLogin, HoldReason reason) {
		failures.put(githubLogin, reason);
	}

	public void clear() {
		failures.clear();
		calls.set(0);
	}

	// GitHub 대신 이 클라이언트가 불린 횟수. 화면 조회가 GitHub를 부르지 않는지 확인하는 데 쓴다
	public int calls() {
		return calls.get();
	}

	@Override
	public List<GrassDay> fetchDailyContributions(String githubLogin, LocalDate from, LocalDate to) {
		calls.incrementAndGet();
		HoldReason reason = failures.get(githubLogin);
		if (reason != null) {
			throw new GrassFetchException(reason, "시험용 잔디 조회 실패");
		}
		List<GrassDay> days = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			days.add(new GrassDay(day, 1));
		}
		return days;
	}

}
