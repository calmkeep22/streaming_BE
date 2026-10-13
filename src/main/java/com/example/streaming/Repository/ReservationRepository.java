package com.example.streaming.Repository;

import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository
        extends JpaRepository<ReservationEntity, Long> {

    boolean existsBySeat_IdAndStatus(Long seatId, ReservationStatus status);
}
