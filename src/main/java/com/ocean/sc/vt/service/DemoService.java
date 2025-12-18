package com.ocean.sc.vt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 데모 서비스 - 1초 대기 후 응답 처리
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    /**
     * 1초 대기 후 응답 반환
     * @param message 메시지
     * @return 처리 결과
     */
    public String processWithDelay(String message) {
        String currentThread = Thread.currentThread().toString();
        log.info("Service 처리 시작 - Thread: {}, Message: {}", currentThread, message);

        try {
            // 1초 대기 (I/O 작업 시뮬레이션)
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted", e);
            throw new RuntimeException("Processing interrupted", e);
        }

        String result = String.format("Processed '%s' on %s", message, currentThread);
        log.info("Service 처리 완료 - Result: {}", result);

        return result;
    }

    /**
     * 무거운 작업 시뮬레이션 (복잡한 비즈니스 로직)
     * @param data 입력 데이터
     * @return 처리 결과
     */
    public String processComplexLogic(String data) {
        String currentThread = Thread.currentThread().toString();
        log.info("복잡한 로직 처리 시작 - Thread: {}, Data: {}", currentThread, data);

        try {
            // 무거운 작업 시뮬레이션 (1초)
            Thread.sleep(1000);

            // 추가 처리 로직 (예: DB 조회, 외부 API 호출 등)
            String processedData = data.toUpperCase();

            log.info("복잡한 로직 처리 완료 - Processed Data: {}", processedData);

            return String.format("Complex processing completed: %s (Thread: %s)",
                processedData, currentThread);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted during complex logic", e);
            throw new RuntimeException("Complex processing interrupted", e);
        }
    }
}
