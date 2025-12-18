package com.ocean.sc.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DemoService 단위 테스트
 */
@SpringBootTest
class DemoServiceTest {

    @Autowired
    private DemoService demoService;

    @Test
    @DisplayName("processWithDelay - 정상 처리 테스트")
    void processWithDelay_Success() {
        // given
        String message = "Test Message";

        // when
        long startTime = System.currentTimeMillis();
        String result = demoService.processWithDelay(message);
        long duration = System.currentTimeMillis() - startTime;

        // then
        assertThat(result).contains("Processed 'Test Message'");
        assertThat(result).contains("Thread");
        assertThat(duration).isGreaterThanOrEqualTo(1000); // 최소 1초 대기
        assertThat(duration).isLessThan(1500); // 1.5초 이내 완료
    }

    @Test
    @DisplayName("processWithDelay - 스레드 정보 포함 확인")
    void processWithDelay_ContainsThreadInfo() {
        // given
        String message = "Thread Info Test";

        // when
        String result = demoService.processWithDelay(message);

        // then
        assertThat(result).contains("Thread");
        assertThat(result).contains("Processed 'Thread Info Test'");
    }

    @Test
    @DisplayName("processComplexLogic - 정상 처리 테스트")
    void processComplexLogic_Success() {
        // given
        String data = "test data";

        // when
        long startTime = System.currentTimeMillis();
        String result = demoService.processComplexLogic(data);
        long duration = System.currentTimeMillis() - startTime;

        // then
        assertThat(result).contains("Complex processing completed");
        assertThat(result).contains("TEST DATA"); // 대문자 변환 확인
        assertThat(result).contains("Thread");
        assertThat(duration).isGreaterThanOrEqualTo(1000); // 최소 1초 대기
    }

    @Test
    @DisplayName("processComplexLogic - 대문자 변환 확인")
    void processComplexLogic_UpperCaseConversion() {
        // given
        String data = "hello world";

        // when
        String result = demoService.processComplexLogic(data);

        // then
        assertThat(result).contains("HELLO WORLD");
    }

    @Test
    @DisplayName("여러 요청 동시 처리 테스트")
    void multipleRequestsProcessing() throws InterruptedException {
        // given
        int requestCount = 5;
        Thread[] threads = new Thread[requestCount];
        String[] results = new String[requestCount];

        // when
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                results[index] = demoService.processWithDelay("Request-" + index);
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }
        long duration = System.currentTimeMillis() - startTime;

        // then
        for (int i = 0; i < requestCount; i++) {
            assertThat(results[i]).contains("Request-" + i);
        }
        // 병렬 처리로 인해 총 시간은 개별 처리 시간보다 적어야 함
        assertThat(duration).isLessThan(requestCount * 1000L + 500);
    }
}
