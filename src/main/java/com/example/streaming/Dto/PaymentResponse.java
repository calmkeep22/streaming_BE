package com.example.streaming.Dto;

import com.example.streaming.Entity.PaymentEntity;
import com.example.streaming.Entity.PaymentStatus;

public record PaymentResponse(
        Long paymentId,
        String orderId,
        Long reservationId,
        Long seatId,
        Long amount,
        String currency,
        PaymentStatus status
) {
    public static PaymentResponse from(PaymentEntity payment) {
        Long reservationId = payment.getReservation() == null
                ? null
                : payment.getReservation().getId();

        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                reservationId,
                payment.getSeat().getId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus()
        );
    }
}
