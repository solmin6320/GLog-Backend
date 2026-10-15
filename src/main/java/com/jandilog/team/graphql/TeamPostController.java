package com.jandilog.team.graphql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

import com.jandilog.post.dto.PostResponse;
import com.jandilog.team.service.TeamNameService;

// 글에 붙는 팀 이름. 목록에서도 쿼리 한 번으로 채운다. 비공개·삭제된 팀과 연결 없는 글은 null (E-23)
@Controller
public class TeamPostController {

	private final TeamNameService nameService;

	public TeamPostController(TeamNameService nameService) {
		this.nameService = nameService;
	}

	@BatchMapping(typeName = "Post", field = "teamName")
	public Map<PostResponse, String> teamName(List<PostResponse> posts) {
		List<Long> teamIds = posts.stream().map(PostResponse::teamId).filter(Objects::nonNull).distinct().toList();
		Map<Long, String> names = nameService.visibleNames(teamIds);
		Map<PostResponse, String> result = new HashMap<>();
		for (PostResponse post : posts) {
			result.put(post, post.teamId() == null ? null : names.get(post.teamId()));
		}
		return result;
	}

}
