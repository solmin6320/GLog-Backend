package com.jandilog.team.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 추방자의 초대코드 재참가 차단: Redis ban:{teamId}:{memberId}, TTL 1년, 값 추방 시각 (DB명세서 2장).
// 아이디 초대는 이 키와 무관하다. Redis가 안 되면 예외가 그대로 올라가 참가·추방 모두 실패한다(차단이 조용히 풀리지 않게)
@Service
public class TeamBanService {

	private static final String KEY_PREFIX = "ban:";
	static final Duration TTL = Duration.ofDays(365);

	private final StringRedisTemplate redis;

	public TeamBanService(StringRedisTemplate redis) {
		this.redis = redis;
	}

	static String key(long teamId, long memberId) {
		return KEY_PREFIX + teamId + ":" + memberId;
	}

	// DB 변경과 같은 트랜잭션 안에서 부른다. 키를 먼저 쓰고, 트랜잭션이 롤백되면 키를 지워 추방하지 않은 사람이 막히지 않게 한다
	public void ban(long teamId, long memberId, LocalDateTime kickedAt) {
		String key = key(teamId, memberId);
		redis.opsForValue().set(key, kickedAt.toString(), TTL);
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					if (status != STATUS_COMMITTED) {
						redis.delete(key);
					}
				}
			});
		}
	}

	public boolean isBanned(long teamId, long memberId) {
		return Boolean.TRUE.equals(redis.hasKey(key(teamId, memberId)));
	}

}
