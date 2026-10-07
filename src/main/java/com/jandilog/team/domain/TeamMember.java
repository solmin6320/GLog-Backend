package com.jandilog.team.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// V1 team_member 매핑 (DB명세서 1-3). 행을 지우지 않고 나간 시각만 찍어 이력으로 쌓는다.
// 판정은 "주 종료 시각의 소속"을 기준으로 하므로 과거 소속을 되짚을 수 있어야 한다
@Entity
@Table(name = "team_member")
public class TeamMember {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "team_id", nullable = false, updatable = false)
	private long teamId;

	@Column(name = "member_id", nullable = false, updatable = false)
	private long memberId;

	@Column(name = "joined_at", nullable = false, updatable = false)
	private LocalDateTime joinedAt;

	@Column(name = "left_at")
	private LocalDateTime leftAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "leave_type")
	private TeamLeaveType leaveType;

	@Enumerated(EnumType.STRING)
	@Column(name = "joined_by", nullable = false, updatable = false)
	private TeamJoinedBy joinedBy;

	// 참가에 쓴 초대코드. 아이디 초대로 들어왔으면 null
	@Column(name = "used_invite_code", updatable = false)
	private String usedInviteCode;

	protected TeamMember() {
	}

	public static TeamMember join(long teamId, long memberId, LocalDateTime joinedAt, TeamJoinedBy joinedBy,
			String usedInviteCode) {
		TeamMember member = new TeamMember();
		member.teamId = teamId;
		member.memberId = memberId;
		member.joinedAt = joinedAt;
		member.joinedBy = joinedBy;
		member.usedInviteCode = usedInviteCode;
		return member;
	}

	public void leave(LocalDateTime now, TeamLeaveType type) {
		this.leftAt = now;
		this.leaveType = type;
	}

	public boolean isActive() {
		return leftAt == null;
	}

	public Long getId() {
		return id;
	}

	public long getTeamId() {
		return teamId;
	}

	public long getMemberId() {
		return memberId;
	}

	public LocalDateTime getJoinedAt() {
		return joinedAt;
	}

	public LocalDateTime getLeftAt() {
		return leftAt;
	}

	public TeamLeaveType getLeaveType() {
		return leaveType;
	}

	public TeamJoinedBy getJoinedBy() {
		return joinedBy;
	}

	public String getUsedInviteCode() {
		return usedInviteCode;
	}

}
