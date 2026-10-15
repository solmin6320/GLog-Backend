package com.jandilog.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

// 한 건 더 읽은 목록을 한 페이지로 자르고 다음 커서를 만드는 규칙 (Q-06 커서 기반 20개)
class PageSliceTest {

	private static List<Integer> numbers(int count) {
		return IntStream.rangeClosed(1, count).boxed().toList();
	}

	@Test
	void 기본_페이지_크기는_20이다() {
		PageSlice<Integer> slice = PageSlice.of(numbers(21), last -> "c" + last);

		assertThat(slice.rows()).hasSize(20);
		assertThat(slice.nextCursor()).isEqualTo("c20");
	}

	@Test
	void 한_건_더_읽었으면_페이지만_남기고_그_페이지_마지막_행으로_커서를_만든다() {
		PageSlice<Integer> slice = PageSlice.of(numbers(4), 3, last -> "c" + last);

		assertThat(slice.rows()).containsExactly(1, 2, 3);
		// 덤으로 읽은 4번째 행이 아니라 3번째 행이 커서가 된다
		assertThat(slice.nextCursor()).isEqualTo("c3");
	}

	@Test
	void 정확히_페이지_크기만큼이면_다음_페이지가_없다() {
		PageSlice<Integer> slice = PageSlice.of(numbers(3), 3, last -> "c" + last);

		assertThat(slice.rows()).containsExactly(1, 2, 3);
		assertThat(slice.nextCursor()).isNull();
	}

	@Test
	void 페이지_크기보다_적으면_다음_페이지가_없다() {
		PageSlice<Integer> slice = PageSlice.of(numbers(2), 3, last -> "c" + last);

		assertThat(slice.rows()).containsExactly(1, 2);
		assertThat(slice.nextCursor()).isNull();
	}

	@Test
	void 빈_목록이면_빈_페이지와_null_커서다() {
		PageSlice<Integer> slice = PageSlice.of(List.of(), 3, last -> "c" + last);

		assertThat(slice.rows()).isEmpty();
		assertThat(slice.nextCursor()).isNull();
	}

	@Test
	void 다음_페이지가_없으면_커서_함수를_부르지_않는다() {
		List<Integer> called = new ArrayList<>();

		PageSlice.of(numbers(3), 3, last -> {
			called.add(last);
			return "c" + last;
		});

		assertThat(called).isEmpty();
	}

	@Test
	void 커서_함수는_마지막_행에_한_번만_불린다() {
		List<Integer> called = new ArrayList<>();

		PageSlice.of(numbers(4), 3, last -> {
			called.add(last);
			return "c" + last;
		});

		assertThat(called).containsExactly(3);
	}

}
