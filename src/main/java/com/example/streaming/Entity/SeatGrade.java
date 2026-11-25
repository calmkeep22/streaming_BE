package com.example.streaming.Entity;

public enum SeatGrade {
    FREE, STANDARD, PREMIUM, VIP;

    public void validatePrice(Long price) {
        if (price == null || price < 0 || (this == FREE ? price != 0 : price == 0)) {
            throw new IllegalArgumentException("무료 좌석은 0원, 유료 좌석은 0원보다 큰 가격이어야 합니다.");
        }
    }
}
