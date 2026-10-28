package com.example.streaming.Repository;

import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationRepository
        extends JpaRepository<ReservationEntity, Long> {

    List<ReservationEntity> findAllByUser_Id(Long userId);

    boolean existsBySeat_IdAndStatus(Long seatId, ReservationStatus status);
}
