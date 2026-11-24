package com.example.streaming.Repository;


import com.example.streaming.Entity.EventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface EventRepository extends JpaRepository<EventEntity, Long> {
    @Query("""
            select e from EventEntity e
            where (:titlePattern is null or lower(e.title) like :titlePattern escape '!')
              and (:fromTime is null or e.startTime >= :fromTime)
              and (:untilTime is null or e.startTime < :untilTime)
            """)
    Page<EventEntity> search(@Param("titlePattern") String titlePattern,
                            @Param("fromTime") LocalDateTime fromTime,
                            @Param("untilTime") LocalDateTime untilTime,
                            Pageable pageable);
}
