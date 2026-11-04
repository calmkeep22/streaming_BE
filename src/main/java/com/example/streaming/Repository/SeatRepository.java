package com.example.streaming.Repository;

import com.example.streaming.Entity.SeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatRepository extends JpaRepository<SeatEntity, Long> {

    List<SeatEntity> findAllByEvent_IdOrderBySeatNumber(Long eventId);
}
