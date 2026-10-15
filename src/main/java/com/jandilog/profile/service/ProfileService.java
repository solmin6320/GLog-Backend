package com.jandilog.profile.service;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.jandilog.common.exception.ApiException;
import com.jandilog.common.exception.ErrorCode;
import com.jandilog.common.validation.InputRules;
import com.jandilog.judgment.dto.GrassSnapshot;
import com.jandilog.judgment.service.GrassCacheService;
import com.jandilog.member.domain.Member;
import com.jandilog.member.domain.MemberStatus;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.profile.dto.ProfileGrass;
import com.jandilog.profile.dto.ProfileTeam;
import com.jandilog.profile.dto.ProfileView;
import com.jandilog.profile.dto.ProfileWarning;
import com.jandilog.profile.repository.ProfileActivityRepository;
import com.jandilog.team.query.TeamReadModel;
import com.jandilog.warning.service.WarningRecalculationService;

// 프로필 조회 (기능명세서 8장, PR-01). 본인 · 타인이 같은 모양이고 승인된 회원만 볼 수 있다.
// 잔디는 하루 한 번 갱신한 캐시를 그대로 보여주고 GitHub를 부르지 않는다 (기능명세서 5장)
@Service
public class ProfileService {

	private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

	private final MemberRepository memberRepository;
	private final ProfileActivityRepository activityRepository;
	private final GrassCacheService grassCacheService;
	private final WarningRecalculationService recalculationService;
	private final TeamReadModel teamReadModel;

	public ProfileService(MemberRepository memberRepository, ProfileActivityRepository activityRepository,
			GrassCacheService grassCacheService, WarningRecalculationService recalculationService,
			TeamReadModel teamReadModel) {
		this.memberRepository = memberRepository;
		this.activityRepository = activityRepository;
		this.grassCacheService = grassCacheService;
		this.recalculationService = recalculationService;
		this.teamReadModel = teamReadModel;
	}

	// 없는 회원과 승인되지 않은 회원(승인 대기 · 거절)은 모두 없는 내용(E-53)으로 본다
	public ProfileView view(long viewerId, String memberId) {
		Member member = memberRepository.findById(InputRules.id(memberId))
				.filter(found -> found.getStatus() == MemberStatus.ACTIVE)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return ProfileView.of(member, viewerId);
	}

	public int postCount(long memberId) {
		return (int) activityRepository.countPosts(memberId);
	}

	public int commentCount(long memberId) {
		return (int) activityRepository.countComments(memberId);
	}

	// 캐시가 없거나 Redis를 읽지 못하면 null: 화면이 "잔디를 불러오지 못했어요"(E-32)를 보여준다
	public ProfileGrass grass(long memberId) {
		try {
			Optional<GrassSnapshot> snapshot = grassCacheService.findLatest(memberId);
			return snapshot.map(ProfileGrass::from).orElse(null);
		}
		catch (DataAccessException e) {
			log.warn("프로필 잔디 캐시를 읽지 못했어요 memberId={} type={}", memberId, e.getClass().getSimpleName());
			return null;
		}
	}

	public ProfileWarning warning(long memberId) {
		return ProfileWarning.of(recalculationService.calculate(memberId));
	}

	// 지금 소속인 공개 팀만. 비공개 팀은 목록에서 뺀다
	public List<ProfileTeam> publicTeams(long memberId) {
		return teamReadModel.findCurrentTeams(memberId).stream()
				.filter(TeamReadModel.TeamName::publicTeam)
				.map(team -> new ProfileTeam(team.id(), team.name()))
				.toList();
	}

}
