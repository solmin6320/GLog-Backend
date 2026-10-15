package com.jandilog.post.domain;

// 종류별 본문 항목. 트러블슈팅은 problem·cause·solution, 개발일지는 did·learned만 채우고 나머지는 null (DB명세서 3-1)
public record PostSections(String problem, String cause, String solution, String did, String learned) {

	// 해당 종류의 필수 항목을 모두 채웠는가 = 기록글 인정 여부 (기능명세서 3·5장)
	public boolean isComplete(PostType type) {
		return switch (type) {
			case TROUBLESHOOTING -> filled(problem) && filled(cause) && filled(solution);
			case DEVLOG -> filled(did) && filled(learned);
		};
	}

	private static boolean filled(String value) {
		return value != null && !value.isBlank();
	}

}
