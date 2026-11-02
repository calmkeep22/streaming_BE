package com.example.streaming.Dto;

// 브라우저에서 결제창 SDK를 초기화할 때 쓰는 값. 클라이언트 키는 공개돼도 되는 키다
public record PaymentClientConfigResponse(String clientKey, String customerKey) {
}
