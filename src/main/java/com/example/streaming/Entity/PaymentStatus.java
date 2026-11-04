package com.example.streaming.Entity;

public enum PaymentStatus {
    READY,
    // 결제사 승인 결과를 아직 반영하지 못한 상태. 오래 머물면 복구 작업이 결제사에 실제 상태를 조회한다
    PROCESSING,
    SUCCESS,
    FAILED,
    CANCELED,
    // 결제사 승인은 됐지만 예약으로 이어지지 못해 환불(결제사 취소)이 필요한 상태. 복구 작업이 재시도한다
    REFUND_PENDING,
    // 복구 재시도를 다 써서 사람이 확인해야 하는 상태
    NEEDS_REVIEW,
    // 예약 취소로 성공했던 결제의 환불(결제사 취소)을 요청한 상태. 결제사 취소가 끝나면 CANCELED, 실패하면 복구 작업이 재시도한다
    CANCEL_PENDING
}
