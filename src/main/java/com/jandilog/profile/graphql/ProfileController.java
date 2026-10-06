package com.jandilog.profile.graphql;

import java.util.List;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.profile.dto.ProfileGrass;
import com.jandilog.profile.dto.ProfileJudgmentPage;
import com.jandilog.profile.dto.ProfileTeam;
import com.jandilog.profile.dto.ProfileView;
import com.jandilog.profile.dto.ProfileWarning;
import com.jandilog.profile.service.ProfileJudgmentHistoryService;
import com.jandilog.profile.service.ProfileService;

// 프로필 조회 (PR-01, 기능명세서 8장). 사진 변경(S3 업로드)은 보류 중이라 여기에 없다.
// 잔디 · 경고 · 활동 수 · 판정 이력 · 소속 팀은 요청한 필드만 계산하도록 SchemaMapping으로 나눴다
@Controller
public class ProfileController {

	private final ProfileService profileService;
	private final ProfileJudgmentHistoryService judgmentHistoryService;

	public ProfileController(ProfileService profileService, ProfileJudgmentHistoryService judgmentHistoryService) {
		this.profileService = profileService;
		this.judgmentHistoryService = judgmentHistoryService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('MEMBER')")
	public ProfileView profile(@AuthenticationPrincipal AuthenticatedMember viewer, @Argument String memberId) {
		return profileService.view(viewer.id(), memberId);
	}

	@SchemaMapping(typeName = "Profile", field = "postCount")
	public int postCount(ProfileView profile) {
		return profileService.postCount(profile.id());
	}

	@SchemaMapping(typeName = "Profile", field = "commentCount")
	public int commentCount(ProfileView profile) {
		return profileService.commentCount(profile.id());
	}

	@SchemaMapping(typeName = "Profile", field = "grass")
	public ProfileGrass grass(ProfileView profile) {
		return profileService.grass(profile.id());
	}

	@SchemaMapping(typeName = "Profile", field = "warning")
	public ProfileWarning warning(ProfileView profile) {
		return profileService.warning(profile.id());
	}

	@SchemaMapping(typeName = "Profile", field = "judgmentHistory")
	public ProfileJudgmentPage judgmentHistory(ProfileView profile, @Argument String after) {
		return judgmentHistoryService.history(profile.id(), after);
	}

	@SchemaMapping(typeName = "Profile", field = "teams")
	public List<ProfileTeam> teams(ProfileView profile) {
		return profileService.publicTeams(profile.id());
	}

}
