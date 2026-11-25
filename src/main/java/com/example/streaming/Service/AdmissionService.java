package com.example.streaming.Service;

import com.example.streaming.Dto.AdmissionResponse;
import com.example.streaming.Entity.AdmissionType;
import com.example.streaming.Entity.PaymentEntity;
import com.example.streaming.Entity.PaymentStatus;
import com.example.streaming.Entity.ReservationEntity;
import com.example.streaming.Entity.ReservationStatus;
import com.example.streaming.Entity.SeatGrade;
import com.example.streaming.Exception.AdmissionDeniedException;
import com.example.streaming.Repository.PaymentRepository;
import com.example.streaming.Repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdmissionService {
    private final ReservationRepository reservations;
    private final PaymentRepository payments;

    public AdmissionService(ReservationRepository reservations, PaymentRepository payments) {
        this.reservations = reservations;
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public AdmissionResponse getAdmission(Long eventId, Long seatId, Long userId) {
        ReservationEntity reservation = reservations.findBySeat_IdAndUser_IdAndStatus(
                seatId, userId, ReservationStatus.RESERVED).orElseThrow(AdmissionDeniedException::new);
        if (!reservation.getSeat().getEvent().getId().equals(eventId)) {
            throw new AdmissionDeniedException();
        }
        SeatGrade grade;
        if (reservation.getAdmissionType() == AdmissionType.FREE) {
            grade = SeatGrade.FREE;
        } else {
            // 선점/주문 생성만으로는 입장할 수 없다. 예약과 사용자/좌석까지 일치하는 성공 결제만 인정한다.
            PaymentEntity payment = payments.findByReservation_IdAndUser_IdAndSeat_IdAndStatus(
                    reservation.getId(), userId, seatId, PaymentStatus.SUCCESS)
                    .orElseThrow(AdmissionDeniedException::new);
            if (payment.getAmount() <= 0 || payment.getSeatGrade() == SeatGrade.FREE) {
                throw new AdmissionDeniedException();
            }
            grade = payment.getSeatGrade();
        }
        return new AdmissionResponse(reservation.getId(), eventId, seatId, grade);
    }
}
