// 좌석 선점(HOLD) API 부하 테스트: POST /events/1/seats/{seatId}/holds
// 측정·집계 로직은 reserve-load.mjs 를 그대로 쓰고 호출 API만 바꾼다 (옵션/환경변수도 동일)
//
// 사용법:
//   node loadtest/hold-load.mjs hot     # 1000명이 좌석 1개를 동시에 선점 → 성공(201) 1건
//   node loadtest/hold-load.mjs spread  # 1000명이 좌석 100개를 나눠서 선점 → 성공(201) 100건

process.env.API = 'holds';
await import('./reserve-load.mjs');
