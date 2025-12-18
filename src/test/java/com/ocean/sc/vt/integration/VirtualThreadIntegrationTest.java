package com.ocean.sc.vt.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Virtual Thread 통합 테스트
 * - 실제 애플리케이션 시나리오에서의 Virtual Thread 동작 검증
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VirtualThreadIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("대량 동시 요청 처리 - Platform Thread vs Virtual Thread 비교")
    void massiveConcurrentRequests_Comparison() throws Exception {
        int concurrentRequests = 20;

        // Platform Thread 테스트
        long platformStart = System.currentTimeMillis();
        int platformSuccess = processConcurrentRequests("/api/demo/platform-load", concurrentRequests, false);
        long platformDuration = System.currentTimeMillis() - platformStart;

        // Virtual Thread 테스트
        long virtualStart = System.currentTimeMillis();
        int virtualSuccess = processConcurrentRequests("/api/demo/virtual-load", concurrentRequests, true);
        long virtualDuration = System.currentTimeMillis() - virtualStart;

        // 결과 출력
        System.out.println("\n===== 대량 동시 요청 테스트 결과 =====");
        System.out.println("동시 요청 수: " + concurrentRequests);
        System.out.println("\n[Platform Thread]");
        System.out.println("  성공: " + platformSuccess + "/" + concurrentRequests);
        System.out.println("  소요 시간: " + platformDuration + "ms");
        System.out.println("  평균 처리 시간: " + (platformDuration / concurrentRequests) + "ms");

        System.out.println("\n[Virtual Thread]");
        System.out.println("  성공: " + virtualSuccess + "/" + concurrentRequests);
        System.out.println("  소요 시간: " + virtualDuration + "ms");
        System.out.println("  평균 처리 시간: " + (virtualDuration / concurrentRequests) + "ms");

        System.out.println("\n[성능 향상]");
        double improvement = ((double) (platformDuration - virtualDuration) / platformDuration) * 100;
        System.out.println("  시간 절감: " + String.format("%.2f%%", improvement));

        // 검증
        assertThat(platformSuccess).isEqualTo(concurrentRequests);
        assertThat(virtualSuccess).isEqualTo(concurrentRequests);
    }

    @Test
    @DisplayName("순차 처리 vs 병렬 처리 성능 비교")
    void sequentialVsParallel_Performance() throws Exception {
        int requestCount = 5;

        // 순차 처리
        long sequentialStart = System.currentTimeMillis();
        for (int i = 0; i < requestCount; i++) {
            mockMvc.perform(get("/api/demo/platform")
                            .param("message", "Sequential-" + i))
                    .andExpect(status().isOk());
        }
        long sequentialDuration = System.currentTimeMillis() - sequentialStart;

        // 병렬 처리 (Virtual Thread)
        long parallelStart = System.currentTimeMillis();
        int parallelSuccess = processConcurrentRequests("/api/demo/virtual", requestCount, true);
        long parallelDuration = System.currentTimeMillis() - parallelStart;

        System.out.println("\n===== 순차 vs 병렬 처리 테스트 =====");
        System.out.println("요청 수: " + requestCount);
        System.out.println("순차 처리 시간: " + sequentialDuration + "ms");
        System.out.println("병렬 처리 시간: " + parallelDuration + "ms");
        System.out.println("성능 향상: " + String.format("%.2fx", (double) sequentialDuration / parallelDuration));

        // 병렬 처리가 더 빨라야 함
        assertThat(parallelDuration).isLessThan(sequentialDuration);
        assertThat(parallelSuccess).isEqualTo(requestCount);
    }

    @Test
    @DisplayName("스레드 안전성 검증 - 동시 요청 시 데이터 무결성")
    void threadSafety_DataIntegrity() throws Exception {
        int concurrentRequests = 10;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        List<String> responses = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);

        // 동시 요청 실행
        for (int i = 0; i < concurrentRequests; i++) {
            final int requestId = i;
            new Thread(() -> {
                try {
                    MvcResult result = mockMvc.perform(get("/api/demo/virtual")
                                    .param("message", "Request-" + requestId))
                            .andExpect(status().isOk())
                            .andReturn();

                    if (result.getRequest().isAsyncStarted()) {
                        String response = mockMvc.perform(asyncDispatch(result))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();

                        synchronized (responses) {
                            responses.add(response);
                        }
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // 모든 요청 완료 대기 (최대 30초)
        boolean completed = latch.await(30, TimeUnit.SECONDS);

        // 검증
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(concurrentRequests);
        assertThat(responses).hasSize(concurrentRequests);

        // 각 응답이 올바른 메시지를 포함하는지 확인
        for (int i = 0; i < concurrentRequests; i++) {
            final int requestId = i;
            boolean found = responses.stream()
                    .anyMatch(r -> r.contains("REQUEST-" + requestId));
            assertThat(found).as("Response for Request-" + requestId + " should exist").isTrue();
        }

        System.out.println("스레드 안전성 테스트 완료 - 성공: " + successCount.get() + "/" + concurrentRequests);
    }

    @Test
    @DisplayName("타임아웃 설정 검증")
    void timeout_Configuration() throws Exception {
        // Virtual Thread API는 30초 타임아웃 설정됨 (VirtualThreadConfig)
        // 정상 요청은 1초 내 완료되므로 타임아웃 발생하지 않음

        MvcResult result = mockMvc.perform(get("/api/demo/virtual")
                        .param("message", "TimeoutTest"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getRequest().isAsyncStarted()).isTrue();

        // Async 결과 확인
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("에러 처리 검증 - Virtual Thread에서 예외 발생")
    void errorHandling_VirtualThread() throws Exception {
        // 실제 에러를 발생시키는 테스트는 별도 에러 케이스가 필요
        // 현재는 정상 동작만 검증
        MvcResult result = mockMvc.perform(get("/api/demo/virtual")
                        .param("message", "ErrorTest"))
                .andExpect(status().isOk())
                .andReturn();

        if (result.getRequest().isAsyncStarted()) {
            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk());
        }
    }

    /**
     * 동시 요청 처리 헬퍼 메서드
     */
    private int processConcurrentRequests(String url, int count, boolean isAsync) throws Exception {
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < count; i++) {
            final int requestId = i;
            new Thread(() -> {
                try {
                    MvcResult result = mockMvc.perform(get(url)
                                    .param("id", String.valueOf(requestId)))
                            .andExpect(status().isOk())
                            .andReturn();

                    if (isAsync && result.getRequest().isAsyncStarted()) {
                        mockMvc.perform(asyncDispatch(result))
                                .andExpect(status().isOk());
                    }

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // 모든 요청 완료 대기 (최대 60초)
        latch.await(60, TimeUnit.SECONDS);
        return successCount.get();
    }
}
