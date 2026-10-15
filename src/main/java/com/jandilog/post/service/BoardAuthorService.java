package com.jandilog.post.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jandilog.member.domain.Member;
import com.jandilog.member.repository.MemberRepository;
import com.jandilog.post.dto.BoardAuthor;

// 글·댓글 작성자 표시 정보를 id 묶음으로 한 번에 읽는다 (@BatchMapping용, 기능명세서 10장)
@Service
public class BoardAuthorService {

	private final MemberRepository memberRepository;

	public BoardAuthorService(MemberRepository memberRepository) {
		this.memberRepository = memberRepository;
	}

	@Transactional(readOnly = true)
	public Map<Long, BoardAuthor> findByIds(Collection<Long> ids) {
		Map<Long, BoardAuthor> authors = new HashMap<>();
		for (Member member : memberRepository.findAllById(ids)) {
			authors.put(member.getId(), BoardAuthor.from(member));
		}
		return authors;
	}

}
