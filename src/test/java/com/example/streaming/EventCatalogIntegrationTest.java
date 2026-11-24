package com.example.streaming;

import com.example.streaming.Entity.EventEntity;
import com.example.streaming.Entity.SeatEntity;
import com.example.streaming.Repository.EventRepository;
import com.example.streaming.Repository.SeatRepository;
import com.example.streaming.Service.RedisSeatHoldService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:event-catalog;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventCatalogIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired EventRepository eventRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean RedisSeatHoldService redisSeatHoldService;

    @AfterEach
    void catalogDoesNotReadRedis() {
        verifyNoInteractions(redisSeatHoldService);
    }

    @Test
    @DisplayName("기본 목록은 시작 시각 순으로 정렬하고 전체 좌석 수와 페이지 정보를 반환한다")
    void listDefaults() throws Exception {
        EventEntity later = event("늦은 공연", "2099-12-26T19:00:00", 2);
        EventEntity earlier = event("이른 공연", "2099-12-25T19:00:00", 3);

        mockMvc.perform(get("/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(earlier.getId()))
                .andExpect(jsonPath("$.content[0].seatCount").value(3))
                .andExpect(jsonPath("$.content[1].id").value(later.getId()))
                .andExpect(jsonPath("$.content[1].seatCount").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("제목 검색과 날짜 필터를 함께 적용하고 시작일·종료일의 공연을 포함한다")
    void combinedFiltersIncludeBothDates() throws Exception {
        event("ROCK 콘서트", "2099-12-24T23:59:59", 1);
        EventEntity first = event("Rock 첫 공연", "2099-12-25T00:00:00", 1);
        EventEntity last = event("rock 마지막 공연", "2099-12-26T23:59:59.999999", 1);
        event("Rock 다음 공연", "2099-12-27T00:00:00", 1);
        event("재즈 공연", "2099-12-25T19:00:00", 1);

        mockMvc.perform(get("/events").with(jwt()).param("keyword", "  rOcK  ")
                        .param("from", "2099-12-25").param("to", "2099-12-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(first.getId()))
                .andExpect(jsonPath("$.content[1].id").value(last.getId()));
    }

    @Test
    @DisplayName("시작일만 또는 종료일만 지정할 수도 있다")
    void oneSidedDateFilters() throws Exception {
        EventEntity before = event("이전 공연", "2099-12-24T23:59:59", 0);
        EventEntity after = event("이후 공연", "2099-12-25T00:00:00", 0);

        mockMvc.perform(get("/events").with(jwt()).param("from", "2099-12-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(after.getId()));
        mockMvc.perform(get("/events").with(jwt()).param("to", "2099-12-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(before.getId()));
    }

    @Test
    @DisplayName("검색어의 퍼센트·밑줄·느낌표는 제목의 문자 그대로 찾는다")
    void literalSearchCharacters() throws Exception {
        event("할인 100% 공연", "2099-12-25T19:00:00", 0);
        event("공연_A", "2099-12-25T19:00:00", 0);
        event("공연!", "2099-12-25T19:00:00", 0);
        event("일반 공연", "2099-12-25T19:00:00", 0);

        for (String keyword : new String[]{"%", "_", "!"}) {
            mockMvc.perform(get("/events").with(jwt()).param("keyword", keyword))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }

    @Test
    @DisplayName("공백 검색어는 전체 목록으로 처리한다")
    void blankKeyword() throws Exception {
        event("공연", "2099-12-25T19:00:00", 0);
        mockMvc.perform(get("/events").with(jwt()).param("keyword", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("시작 시각이 같아도 ID 순으로 페이지를 나눠 중복과 누락을 방지한다")
    void stablePagination() throws Exception {
        EventEntity first = event("첫 공연", "2099-12-25T19:00:00", 1);
        EventEntity second = event("두 번째 공연", "2099-12-25T19:00:00", 2);
        EventEntity third = event("세 번째 공연", "2099-12-25T19:00:00", 3);

        mockMvc.perform(get("/events").with(jwt()).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(first.getId()));
        mockMvc.perform(get("/events").with(jwt()).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(second.getId()))
                .andExpect(jsonPath("$.content[0].seatCount").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get("/events").with(jwt()).param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(third.getId()))
                .andExpect(jsonPath("$.hasNext").value(false));
        mockMvc.perform(get("/events").with(jwt()).param("page", "3").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 페이지를 반환한다")
    void emptySearchResults() throws Exception {
        event("콘서트", "2099-12-25T19:00:00", 1);
        mockMvc.perform(get("/events").with(jwt()).param("keyword", "없는 제목"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    @DisplayName("공연 상세는 해당 공연 정보와 전체 좌석 수를 반환한다")
    void detail() throws Exception {
        EventEntity event = event("상세 공연", "2099-12-25T19:00:00", 3);
        mockMvc.perform(get("/events/{eventId}", event.getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(event.getId()))
                .andExpect(jsonPath("$.title").value("상세 공연"))
                .andExpect(jsonPath("$.startTime").value("2099-12-25T19:00:00"))
                .andExpect(jsonPath("$.seatCount").value(3));
    }

    @Test
    @DisplayName("좌석이 없는 공연은 목록과 상세에서 좌석 수 0을 반환한다")
    void eventWithoutSeats() throws Exception {
        EventEntity event = event("빈 공연", "2099-12-25T19:00:00", 0);
        mockMvc.perform(get("/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].seatCount").value(0));
        mockMvc.perform(get("/events/{eventId}", event.getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatCount").value(0));
    }

    @Test
    @DisplayName("없는 공연 상세 조회는 404를 반환한다")
    void missingEvent() throws Exception {
        mockMvc.perform(get("/events/{eventId}", Long.MAX_VALUE).with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("이벤트가 존재하지 않습니다."));
    }

    @Test
    @DisplayName("잘못된 페이지·검색어·날짜는 400을 반환한다")
    void invalidFilters() throws Exception {
        for (String[] param : new String[][]{
                {"page", "-1"}, {"page", "abc"}, {"size", "0"}, {"size", "101"},
                {"keyword", "a".repeat(256)}, {"from", "2099-02-30"}, {"to", "invalid"}
        }) {
            mockMvc.perform(get("/events").with(jwt()).param(param[0], param[1]))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/events").with(jwt()).param("from", "2099-12-26").param("to", "2099-12-25"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("시작일은 종료일보다 늦을 수 없습니다."));
    }

    @Test
    @DisplayName("목록과 상세 모두 로그인하지 않으면 401을 반환한다")
    void authenticationRequired() throws Exception {
        mockMvc.perform(get("/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/events/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("한 페이지의 공연 수가 늘어도 좌석 집계 쿼리가 공연마다 늘어나지 않는다")
    void boundedQueryCount() throws Exception {
        for (int i = 0; i < 7; i++) {
            event("공연 " + i, "2099-12-25T19:00:00", 2);
        }
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        for (int size : new int[]{2, 6}) {
            statistics.clear();
            mockMvc.perform(get("/events").with(jwt()).param("size", String.valueOf(size)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(size))
                    .andExpect(jsonPath("$.content[0].seatCount").value(2));
            // 목록, 전체 개수, 현재 페이지의 좌석 집계까지 최대 3회.
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        }
    }

    private EventEntity event(String title, String startTime, int seatCount) {
        EventEntity event = eventRepository.save(new EventEntity(title, LocalDateTime.parse(startTime)));
        seatRepository.saveAll(IntStream.rangeClosed(1, seatCount)
                .mapToObj(number -> new SeatEntity(event, number)).toList());
        return event;
    }
}
