package com.example.streaming.Repository;

import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReservationRepository
        extends JpaRepository<ReservationEntity, Long> {

    List<ReservationEntity> findAllByUser_Id(Long userId);

    boolean existsBySeat_IdAndStatus(Long seatId, ReservationStatus status);

    // 이벤트 좌석 중 해당 상태의 예약이 있는 좌석 id (좌석 목록 조회에서 좌석마다 쿼리하지 않으려고 한 번에 읽는다)
    @Query("select r.seat.id from ReservationEntity r where r.seat.event.id = :eventId and r.status = :status")
    List<Long> findSeatIdsByEventIdAndStatus(@Param("eventId") Long eventId, @Param("status") ReservationStatus status);
}
