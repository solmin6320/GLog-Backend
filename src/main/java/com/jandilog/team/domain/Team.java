package com.jandilog.team.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// V1 team 매핑 (DB명세서 1-2). 팀은 지우지 않고 deleted_at만 찍는다: "삭제된 팀" 표시와 경고 복구에 행이 필요하다.
// 초대코드는 생성할 때 한 번 정해지고 바뀌지 않는다(재발급 불가). 평문 보관 (Q-01)
@Entity
@Table(name = "team")
public class Team {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(length = 200)
	private String description;

	@Column(name = "leader_id", nullable = false)
	private long leaderId;

	@Column(name = "invite_code", nullable = false, updatable = false)
	private String inviteCode;

	@Column(name = "is_public", nullable = false)
	private boolean publicTeam;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	protected Team() {
	}

	public static Team create(String name, String description, long leaderId, String inviteCode, boolean publicTeam,
			LocalDateTime now) {
		Team team = new Team();
		team.name = name;
		team.description = description;
		team.leaderId = leaderId;
		team.inviteCode = inviteCode;
		team.publicTeam = publicTeam;
		team.createdAt = now;
		return team;
	}

	public void changeInfo(String name, String description) {
		this.name = name;
		this.description = description;
	}

	public void changeVisibility(boolean publicTeam) {
		this.publicTeam = publicTeam;
	}

	public void changeLeader(long newLeaderId) {
		this.leaderId = newLeaderId;
	}

	public void markDeleted(LocalDateTime now) {
		this.deletedAt = now;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public boolean isLeader(long memberId) {
		return leaderId == memberId;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public long getLeaderId() {
		return leaderId;
	}

	public String getInviteCode() {
		return inviteCode;
	}

	public boolean isPublic() {
		return publicTeam;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getDeletedAt() {
		return deletedAt;
	}

}
