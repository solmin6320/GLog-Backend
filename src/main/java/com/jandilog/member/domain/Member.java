package com.jandilog.member.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// V1 member 테이블 매핑. 바뀐 컬럼만 UPDATE해서 로그인 동기화가 관리자 상태 변경을 덮어쓰지 않게 한다
@Entity
@Table(name = "member")
@DynamicUpdate
public class Member {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "github_id", nullable = false, updatable = false)
	private long githubId;

	@Column(name = "github_login", nullable = false, length = 39)
	private String githubLogin;

	@Column(nullable = false, length = 30)
	private String nickname;

	@Column(name = "profile_image_url", length = 512)
	private String profileImageUrl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MemberStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MemberRole role;

	@Column(name = "first_team_joined_at")
	private LocalDateTime firstTeamJoinedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "approved_at")
	private LocalDateTime approvedAt;

	protected Member() {
	}

	// 첫 로그인: 승인 대기 회원으로 만든다 (기능명세서 1장)
	public static Member createPending(long githubId, String githubLogin, String nickname, LocalDateTime now) {
		Member member = new Member();
		member.githubId = githubId;
		member.githubLogin = githubLogin;
		member.nickname = nickname;
		member.status = MemberStatus.PENDING;
		member.role = MemberRole.MEMBER;
		member.createdAt = now;
		return member;
	}

	// ADMIN_GITHUB_IDS에 있는 첫 로그인: 승인 절차 없이 활성 관리자로 만든다
public static Member createAdmin(long githubId, String githubLogin, String nickname, LocalDateTime now) {
	Member member = createPending(githubId, githubLogin, nickname, now);
	member.status = MemberStatus.ACTIVE;
	member.role = MemberRole.ADMIN;
	member.approvedAt = now;
	return member;
}

// 로그인마다 GitHub 최신값으로 덮어쓴다 (DB명세서 1-1)
	public void syncGithubProfile(String githubLogin, String nickname) {
		this.githubLogin = githubLogin;
		this.nickname = nickname;
	}

	public Long getId() {
		return id;
	}

	public long getGithubId() {
		return githubId;
	}

	public String getGithubLogin() {
		return githubLogin;
	}

	public String getNickname() {
		return nickname;
	}

	public String getProfileImageUrl() {
		return profileImageUrl;
	}

	public MemberStatus getStatus() {
		return status;
	}

	public MemberRole getRole() {
		return role;
	}

	public LocalDateTime getFirstTeamJoinedAt() {
		return firstTeamJoinedAt;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getApprovedAt() {
		return approvedAt;
	}

}
