package com.jandilog.admin.graphql;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.jandilog.common.security.AuthenticatedMember;
import com.jandilog.exemption.dto.CorrectedJudgment;
import com.jandilog.exemption.dto.CorrectionPreview;
import com.jandilog.exemption.dto.JudgmentCorrectionInput;
import com.jandilog.exemption.service.JudgmentCorrectionService;
import com.jandilog.judgment.domain.JudgmentStatus;

// 관리자 대시보드의 판정 정정 패널 (AD-01 ⑩): 재계산 미리보기 → 정정하고 재계산 (기능명세서 7·9장)
@Controller
public class AdminCorrectionController {

	private final JudgmentCorrectionService correctionService;

	public AdminCorrectionController(JudgmentCorrectionService correctionService) {
		this.correctionService = correctionService;
	}

	@QueryMapping
	@PreAuthorize("hasRole('ADMIN')")
	public CorrectionPreview previewJudgmentCorrection(@Argument String judgmentId, @Argument JudgmentStatus status) {
		return correctionService.preview(judgmentId, status);
	}

	@MutationMapping
	@PreAuthorize("hasRole('ADMIN')")
	public CorrectedJudgment correctJudgment(@AuthenticationPrincipal AuthenticatedMember admin,
			@Argument JudgmentCorrectionInput input) {
		return correctionService.correct(admin.id(), input);
	}

}
