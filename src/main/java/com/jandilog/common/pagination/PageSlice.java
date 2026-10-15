package com.jandilog.common.pagination;

import java.util.List;
import java.util.function.Function;

// 한 건 더 읽은 목록을 한 페이지로 자르고 다음 커서를 만든다 (Q-06).
// rows는 잘라낸 한 페이지, nextCursor는 다음 페이지가 없으면 null
public record PageSlice<T>(List<T> rows, String nextCursor) {

	public static <T> PageSlice<T> of(List<T> fetched, Function<T, String> cursorOf) {
		return of(fetched, CursorCodec.PAGE_SIZE, cursorOf);
	}

	public static <T> PageSlice<T> of(List<T> fetched, int pageSize, Function<T, String> cursorOf) {
		boolean hasNext = fetched.size() > pageSize;
		List<T> rows = hasNext ? fetched.subList(0, pageSize) : fetched;
		return new PageSlice<>(rows, hasNext ? cursorOf.apply(rows.get(rows.size() - 1)) : null);
	}

}
