package com.example.streaming.Repository;

import com.example.streaming.Entity.PaymentEntity;
import com.example.streaming.Entity.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    @EntityGraph(attributePaths = {"user", "seat", "reservation"})
    Optional<PaymentEntity> findByOrderId(String orderId);

    boolean existsByOrderId(String orderId);

    /**
     * 복구 대상 결제 id.
     * - PROCESSING: 요청 스레드가 아직 처리 중일 수 있으므로, staleBefore보다 오래된 것만 본다 (유예 시간)
     * - REFUND_PENDING: 환불이 필요하다는 게 확실하므로 바로 본다
     * 둘 다 재시도 대기 중(nextRecoveryAt이 미래)이면 건너뛴다.
     * 엔티티 대신 id만 읽어서, 결제사 호출 동안 영속성 컨텍스트나 트랜잭션을 붙잡지 않게 한다.
     */
    @Query("""
            select p.id from PaymentEntity p
            where ((p.status = :processing and p.requestedAt < :staleBefore) or p.status = :refundPending)
              and (p.nextRecoveryAt is null or p.nextRecoveryAt <= :now)
            order by p.id
            """)
    List<Long> findRecoveryTargetIds(
            @Param("processing") PaymentStatus processing,
            @Param("refundPending") PaymentStatus refundPending,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );
}
