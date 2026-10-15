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

// V2 team_invitation 매핑 (DB명세서 1-4). 초대한 사람은 따로 저장하지 않고 팀의 현재 팀장으로 보여준다
@Entity
@Table(name = "team_invitation")
public class TeamInvitation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "team_id", nullable = false, updatable = false)
	private long teamId;

	@Column(name = "invitee_id", nullable = false, updatable = false)
	private long inviteeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private InvitationStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "responded_at")
	private LocalDateTime respondedAt;

	protected TeamInvitation() {
	}

	public static TeamInvitation create(long teamId, long inviteeId, LocalDateTime now) {
		TeamInvitation invitation = new TeamInvitation();
		invitation.teamId = teamId;
		invitation.inviteeId = inviteeId;
		invitation.status = InvitationStatus.PENDING;
		invitation.createdAt = now;
		return invitation;
	}

	public void accept(LocalDateTime now) {
		this.status = InvitationStatus.ACCEPTED;
		this.respondedAt = now;
	}

	// 거절, 그리고 팀이 없어져 무효가 된 초대도 같은 상태로 정리한다 (상태 값이 PENDING·ACCEPTED·DECLINED뿐이다)
	public void decline(LocalDateTime now) {
		this.status = InvitationStatus.DECLINED;
		this.respondedAt = now;
	}

	public boolean isPending() {
		return status == InvitationStatus.PENDING;
	}

	public Long getId() {
		return id;
	}

	public long getTeamId() {
		return teamId;
	}

	public long getInviteeId() {
		return inviteeId;
	}

	public InvitationStatus getStatus() {
		return status;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getRespondedAt() {
		return respondedAt;
	}

}
