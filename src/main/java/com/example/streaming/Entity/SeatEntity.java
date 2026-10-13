package com.example.streaming.Entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private EventEntity event;

    @Column
    private Integer seatNumber;

    // [7] 낙관적 락용. UPDATE ... WHERE version=? 로 동시 수정 감지
    @Version
    private Long version;


    public SeatEntity(EventEntity event, Integer seatNumber) {
        this.event = event;
        this.seatNumber = seatNumber;
    }
}
