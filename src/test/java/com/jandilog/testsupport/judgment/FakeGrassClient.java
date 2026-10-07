package com.jandilog.testsupport.judgment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.jandilog.judgment.domain.HoldReason;
import com.jandilog.judgment.dto.GrassDay;
import com.jandilog.judgment.service.GrassClient;
import com.jandilog.judgment.service.GrassFetchException;

// GitHub를 부르지 않는 가짜 잔디 클라이언트. 호출 횟수와 인자를 남기고, 응답·실패·지연을 테스트가 정한다
public class FakeGrassClient implements GrassClient {

	public record Call(String githubLogin, LocalDate from, LocalDate to) {
	}

	// callNumber는 1부터. 호출마다 값이 달라지게 해서 다시 불렀는지 가려낼 때 쓴다
	@FunctionalInterface
	public interface Responder {

		List<GrassDay> respond(String githubLogin, LocalDate from, LocalDate to, int callNumber);

	}

	private final AtomicInteger callCount = new AtomicInteger();
	private final List<Call> calls = new CopyOnWriteArrayList<>();
	private volatile Responder responder = byCallNumber();
	private volatile long delayMillis;

	// 기본 응답: 구간의 모든 날이 "몇 번째 호출이냐"만큼의 기여를 가진다
	public static Responder byCallNumber() {
		return (login, from, to, callNumber) -> uniform(from, to, callNumber);
	}

	public static List<GrassDay> uniform(LocalDate from, LocalDate to, int count) {
		List<GrassDay> days = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			days.add(new GrassDay(day, count));
		}
		return days;
	}

	public void reset() {
		callCount.set(0);
		calls.clear();
		responder = byCallNumber();
		delayMillis = 0;
	}

	public void respondWith(Responder newResponder) {
		this.responder = newResponder;
	}

	// 지정한 날만 기여 수를 주고 나머지는 0
	public void respondWithCounts(Map<LocalDate, Integer> countsByDay) {
		this.responder = (login, from, to, callNumber) -> {
			List<GrassDay> days = new ArrayList<>();
			for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
				days.add(new GrassDay(day, countsByDay.getOrDefault(day, 0)));
			}
			return days;
		};
	}

	public void failWith(HoldReason reason) {
		this.responder = (login, from, to, callNumber) -> {
			throw new GrassFetchException(reason, "가짜 클라이언트 실패 " + reason);
		};
	}

	public void delayMillis(long millis) {
		this.delayMillis = millis;
	}

	public int callCount() {
		return callCount.get();
	}

	public List<Call> calls() {
		return List.copyOf(calls);
	}

	@Override
	public List<GrassDay> fetchDailyContributions(String githubLogin, LocalDate from, LocalDate to) {
		int callNumber = callCount.incrementAndGet();
		calls.add(new Call(githubLogin, from, to));
		long delay = delayMillis;
		if (delay > 0) {
			try {
				Thread.sleep(delay);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("가짜 클라이언트 대기 중 인터럽트", e);
			}
		}
		return responder.respond(githubLogin, from, to, callNumber);
	}

}
