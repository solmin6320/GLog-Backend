package com.jandilog.team.board.dto;

import java.time.LocalDate;

import com.jandilog.team.board.domain.BoardDay;

// GraphQL TeamBoardDay 타입. hasGrass·verified가 null이면 알 수 없는 날
public record TeamBoardDayResponse(String date, Boolean hasGrass, boolean hasRecord, Boolean verified) {

	public static TeamBoardDayResponse from(BoardDay day) {
		LocalDate date = day.date();
		return new TeamBoardDayResponse(date.toString(), day.hasGrass(), day.hasRecord(), day.verified());
	}

}
