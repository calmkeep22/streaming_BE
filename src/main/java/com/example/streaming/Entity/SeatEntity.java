package com.example.streaming.Entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatEntity {
    // 등급 도입 전 생성 요청과 기존 좌석에 적용하는 가격.
    public static final long LEGACY_STANDARD_PRICE = 50_000L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private EventEntity event;

    @Column
    private Integer seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatGrade grade;

    @Column(nullable = false)
    private Long price;

    // [7] 낙관적 락용. UPDATE ... WHERE version=? 로 동시 수정 감지
    @Version
    private Long version;


    public SeatEntity(EventEntity event, Integer seatNumber) {
        this(event, seatNumber, SeatGrade.STANDARD, LEGACY_STANDARD_PRICE);
    }

    public SeatEntity(EventEntity event, Integer seatNumber, SeatGrade grade, Long price) {
        if (grade == null) {
            throw new IllegalArgumentException("좌석 등급은 필수입니다.");
        }
        grade.validatePrice(price);
        this.event = event;
        this.seatNumber = seatNumber;
        this.grade = grade;
        this.price = price;
    }
}
