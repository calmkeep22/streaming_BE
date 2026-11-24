package com.example.streaming.Repository;

import com.example.streaming.Entity.SeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<SeatEntity, Long> {

    List<SeatEntity> findAllByEvent_IdOrderBySeatNumber(Long eventId);

    long countByEvent_Id(Long eventId);

    // 현재 페이지의 공연만 묶어 집계한다. 공연마다 좌석을 따로 조회하지 않는다.
    @Query("""
            select s.event.id as eventId, count(s) as seatCount
            from SeatEntity s where s.event.id in :eventIds
            group by s.event.id
            """)
    List<EventSeatCount> countByEventIds(@Param("eventIds") List<Long> eventIds);

    interface EventSeatCount {
        Long getEventId();
        Long getSeatCount();
    }
}
