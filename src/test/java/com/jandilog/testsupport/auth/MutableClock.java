package com.jandilog.testsupport.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

// 테스트용 시계. 기본은 시스템 시각(KST), fixAt으로 고정하고 reset으로 되돌린다
public class MutableClock extends Clock {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private volatile Instant fixed;

	public void fixAt(Instant instant) {
		this.fixed = instant;
	}

	public void reset() {
		this.fixed = null;
	}

	@Override
	public ZoneId getZone() {
		return KST;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		Instant current = fixed;
		return current != null ? Clock.fixed(current, zone) : Clock.system(zone);
	}

	@Override
	public Instant instant() {
		Instant current = fixed;
		return current != null ? current : Instant.now();
	}

}
