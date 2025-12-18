package com.ocean.sc.vt.aspect;

import com.ocean.sc.vt.annotation.VirtualThread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * VirtualThreadAspect 테스트
 * - @VirtualThread 어노테이션 기반 AOP 동작 검증
 */
@SpringBootTest
class VirtualThreadAspectTest {

    @Autowired
    private TestService testService;

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @Test
    @DisplayName("@VirtualThread 메서드는 Virtual Thread에서 실행되어야 함")
    void shouldExecuteInVirtualThread() throws Exception {
        // when
        String result = testService.virtualThreadMethod("test");

        // then
        assertThat(result).contains("VirtualThread-");
        assertThat(result).contains("isVirtual=true");
    }

    @Test
    @DisplayName("@VirtualThread 없는 메서드는 Platform Thread에서 실행되어야 함")
    void shouldExecuteInPlatformThread() throws Exception {
        // when
        String result = testService.normalMethod("test");

        // then
        assertThat(result).doesNotContain("VirtualThread-");
        assertThat(result).contains("isVirtual=false");
    }

    @Test
    @DisplayName("MDC 컨텍스트가 Virtual Thread로 복사되어야 함")
    void shouldCopyMdcContext() throws Exception {
        // given
        String traceId = "trace-123";
        String userId = "user-456";
        MDC.put("traceId", traceId);
        MDC.put("userId", userId);

        // when
        String result = testService.mdcTestMethod();

        // then
        assertThat(result).contains("traceId=" + traceId);
        assertThat(result).contains("userId=" + userId);
    }

    @Test
    @DisplayName("@VirtualThread 메서드 실행 후 MDC가 정리되어야 함")
    void shouldCleanupMdcAfterExecution() throws Exception {
        // given
        MDC.put("testKey", "testValue");

        // when
        testService.virtualThreadMethod("test");

        // then
        // Virtual Thread의 MDC는 정리되어야 하지만, 호출 스레드의 MDC는 유지
        assertThat(MDC.get("testKey")).isEqualTo("testValue");
    }

    @Test
    @DisplayName("타임아웃 시간 내에 실행이 완료되어야 함")
    void shouldCompleteWithinTimeout() throws Exception {
        // when
        long startTime = System.currentTimeMillis();
        String result = testService.fastMethod();
        long duration = System.currentTimeMillis() - startTime;

        // then
        assertThat(result).isNotNull();
        assertThat(duration).isLessThan(5000); // 5초 이내 완료
    }

    @Test
    @DisplayName("타임아웃 시간을 초과하면 예외가 발생해야 함")
    void shouldThrowTimeoutException() {
        // when & then
        assertThatThrownBy(() -> testService.slowMethod())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("메서드 실행 중 예외가 발생하면 원본 예외가 전달되어야 함")
    void shouldPropagateOriginalException() {
        // when & then
        assertThatThrownBy(() -> testService.exceptionMethod())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Test exception");
    }

    @Test
    @DisplayName("동시에 여러 @VirtualThread 메서드를 실행할 수 있어야 함")
    void shouldHandleConcurrentExecution() throws Exception {
        // given
        int concurrentRequests = 10;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        CompletableFuture<String>[] futures = new CompletableFuture[concurrentRequests];

        // when
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < concurrentRequests; i++) {
            int finalI = i;
            futures[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    String result = testService.concurrentMethod("Request-" + finalI);
                    latch.countDown();
                    return result;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        // then
        latch.await(5, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        // 모든 요청이 성공해야 함
        for (CompletableFuture<String> future : futures) {
            assertThat(future.get()).contains("Request-");
        }

        // Virtual Thread 사용 시 동시 실행으로 시간이 단축되어야 함
        // (10개 요청 * 100ms = 1000ms이지만, 동시 실행으로 더 짧아야 함)
        assertThat(duration).isLessThan(2000);
    }

    @Test
    @DisplayName("커스텀 타임아웃 설정이 적용되어야 함")
    void shouldRespectCustomTimeout() {
        // when & then
        assertThatThrownBy(() -> testService.customTimeoutMethod())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("500ms");
    }

    /**
     * 테스트용 서비스 클래스
     */
    @Component
    static class TestService {

        @VirtualThread
        public String virtualThreadMethod(String input) throws InterruptedException {
            Thread currentThread = Thread.currentThread();
            Thread.sleep(100);
            return String.format("Thread: %s, isVirtual=%s, input=%s",
                    currentThread.getName(), currentThread.isVirtual(), input);
        }

        public String normalMethod(String input) throws InterruptedException {
            Thread currentThread = Thread.currentThread();
            Thread.sleep(100);
            return String.format("Thread: %s, isVirtual=%s, input=%s",
                    currentThread.getName(), currentThread.isVirtual(), input);
        }

        @VirtualThread
        public String mdcTestMethod() {
            String traceId = MDC.get("traceId");
            String userId = MDC.get("userId");
            return String.format("traceId=%s, userId=%s", traceId, userId);
        }

        @VirtualThread
        public String fastMethod() throws InterruptedException {
            Thread.sleep(100);
            return "fast";
        }

        @VirtualThread(timeout = 1000)
        public String slowMethod() throws InterruptedException {
            Thread.sleep(2000); // 2초 대기 (타임아웃 1초)
            return "slow";
        }

        @VirtualThread
        public String exceptionMethod() {
            throw new IllegalStateException("Test exception");
        }

        @VirtualThread
        public String concurrentMethod(String id) throws InterruptedException {
            Thread.sleep(100);
            return String.format("Processed: %s on %s", id, Thread.currentThread().getName());
        }

        @VirtualThread(timeout = 500)
        public String customTimeoutMethod() throws InterruptedException {
            Thread.sleep(1000); // 1초 대기 (타임아웃 500ms)
            return "timeout";
        }
    }

    /**
     * 테스트 설정
     */
    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public TestService testService() {
            return new TestService();
        }
    }
}
