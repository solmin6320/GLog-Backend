package com.jandilog.post.service;

import java.util.List;
import java.util.function.Function;

import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import com.jandilog.post.domain.Comment;
import com.jandilog.post.domain.Post;
import com.jandilog.post.dto.CommentResponse;
import com.jandilog.post.dto.CursorPage;
import com.jandilog.post.dto.PostResponse;
import com.jandilog.post.repository.CommentRepository;
import com.jandilog.post.repository.PostRepository;
import com.jandilog.team.domain.Team;
import com.jandilog.team.service.TeamAccessService;

// 팀장의 팀 글·댓글 관리 목록 (기능명세서 2장, TM-06 ⑧). 그 팀의 팀장만 부를 수 있고 관리자도 예외가 없다.
// 댓글에는 teamId를 복제하지 않아서 글을 먼저 찾고 그 글의 댓글을 찾는다 (DB명세서 4-6)
@Service
public class TeamPostManageService {

	private final TeamAccessService teamAccess;
	private final PostRepository postRepository;
	private final CommentRepository commentRepository;

	public TeamPostManageService(TeamAccessService teamAccess, PostRepository postRepository,
			CommentRepository commentRepository) {
		this.teamAccess = teamAccess;
		this.postRepository = postRepository;
		this.commentRepository = commentRepository;
	}

	// 팀에 연결된 삭제되지 않은 글, 최신순 커서 20개
	public CursorPage<PostResponse> posts(long memberId, long teamId, String cursor) {
		requireLeader(memberId, teamId);
		ObjectId after = PostService.decodeCursor(cursor);
		List<Post> rows = postRepository.findPageByTeam(teamId, after, PostService.PAGE_SIZE + 1);
		return page(rows, Post::getId, PostResponse::from);
	}

	// 팀에 연결된 삭제되지 않은 글에 달린 삭제되지 않은 댓글, 최신순 커서 20개
	public CursorPage<CommentResponse> comments(long memberId, long teamId, String cursor) {
		requireLeader(memberId, teamId);
		ObjectId after = PostService.decodeCursor(cursor);
		List<ObjectId> postIds = postRepository.findAliveIdsByTeam(teamId);
		if (postIds.isEmpty()) {
			return new CursorPage<>(List.of(), null);
		}
		List<Comment> rows = commentRepository.findAlivePageByPostIds(postIds, after, PostService.PAGE_SIZE + 1);
		return page(rows, Comment::getId, CommentResponse::from);
	}

	// 없거나 삭제된 팀은 E-53, 그 팀의 현재 팀장이 아니면 E-09
	private void requireLeader(long memberId, long teamId) {
		Team team = teamAccess.requireAlive(teamId);
		teamAccess.requireLeader(team, memberId);
	}

	// 한 건 더 읽어서 다음 페이지가 있는지 판단한다
	private static <R, T> CursorPage<T> page(List<R> rows, Function<R, ObjectId> idOf, Function<R, T> mapper) {
		boolean hasNext = rows.size() > PostService.PAGE_SIZE;
		List<R> page = hasNext ? rows.subList(0, PostService.PAGE_SIZE) : rows;
		String nextCursor = hasNext ? PostService.encodeCursor(idOf.apply(page.get(page.size() - 1))) : null;
		return new CursorPage<>(page.stream().map(mapper).toList(), nextCursor);
	}

}
