package com.jandilog.team.support;

// 팀 GraphQL 호출문 모음 (team.graphqls). 변수 이름은 id/teamId/input처럼 짧게 맞춘다
public final class TeamGql {

	private TeamGql() {
	}

	public static final String TEAM_FIELDS = "id name description isPublic isLeader memberCount joinedAt createdAt "
			+ "leader { id nickname githubLogin }";

	public static final String MY_TEAMS = "{ myTeams { " + TEAM_FIELDS + " } }";

	public static final String TEAM = "query($id: ID!) { team(id: $id) { " + TEAM_FIELDS + " } }";

	public static final String TEAM_MEMBERS = "query($id: ID!, $after: String) { teamMembers(teamId: $id, after: $after) { "
			+ "items { id nickname githubLogin isLeader joinedAt } nextCursor } }";

	public static final String INVITE_CODE = "query($id: ID!) { teamInviteCode(teamId: $id) }";

	public static final String RECEIVED = "query($after: String) { receivedTeamInvitations(after: $after) { "
			+ "items { id teamId teamName createdAt inviter { id githubLogin } } nextCursor } }";

	public static final String SENT = "query($id: ID!, $after: String) { sentTeamInvitations(teamId: $id, after: $after) { "
			+ "items { id createdAt invitee { id githubLogin } } nextCursor } }";

	public static final String CREATE = "mutation($input: CreateTeamInput!) { createTeam(input: $input) { inviteCode team { "
			+ TEAM_FIELDS + " } } }";

	public static final String UPDATE = "mutation($id: ID!, $input: UpdateTeamInput!) { updateTeam(teamId: $id, input: $input) { "
			+ TEAM_FIELDS + " } }";

	public static final String JOIN = "mutation($code: String!) { joinTeamByCode(code: $code) { " + TEAM_FIELDS + " } }";

	public static final String INVITE = "mutation($id: ID!, $login: String!) { inviteToTeam(teamId: $id, githubLogin: $login) { id createdAt invitee { id githubLogin } } }";

	public static final String CANCEL = "mutation($id: ID!) { cancelTeamInvitation(invitationId: $id) }";

	public static final String ACCEPT = "mutation($id: ID!) { acceptTeamInvitation(invitationId: $id) { " + TEAM_FIELDS + " } }";

	public static final String DECLINE = "mutation($id: ID!) { declineTeamInvitation(invitationId: $id) }";

	public static final String KICK = "mutation($teamId: ID!, $memberId: ID!) { kickTeamMember(teamId: $teamId, memberId: $memberId) }";

	public static final String LEAVE = "mutation($teamId: ID!) { leaveTeam(teamId: $teamId) }";

	public static final String TRANSFER = "mutation($teamId: ID!, $newLeaderId: ID!) { transferTeamLeadership(teamId: $teamId, newLeaderId: $newLeaderId) { "
			+ TEAM_FIELDS + " } }";

	public static final String DELETE = "mutation($teamId: ID!) { deleteTeam(teamId: $teamId) }";

}
