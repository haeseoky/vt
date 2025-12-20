package com.ocean.sc.vt.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * VirtualThreadDemoController 통합 테스트
 */
@SpringBootTest
class VirtualThreadDemoControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("Platform Thread API - 정상 응답 테스트")
    void platformThreadApi_Success() throws Exception {
        // when & then
        mockMvc.perform(get("/api/demo/platform")
                        .param("message", "HelloPlatform"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Platform Thread Result")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HelloPlatform")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Thread")));
    }

    @Test
    @DisplayName("Platform Thread API - 기본값 테스트")
    void platformThreadApi_DefaultValue() throws Exception {
        // when & then
        mockMvc.perform(get("/api/demo/platform"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Platform")));
    }

    @Test
    @DisplayName("Virtual Thread API - 정상 응답 테스트 (Async)")
    void virtualThreadApi_Success() throws Exception {
        // when
        MvcResult mvcResult = mockMvc.perform(get("/api/demo/virtual")
                        .param("message", "HelloVirtual"))
                .andDo(print())
                .andExpect(request().asyncStarted())
                .andReturn();

        // then - async 결과 확인
        mockMvc.perform(asyncDispatch(mvcResult))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Virtual Thread Result")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HELLOVIRTUAL"))) // 대문자 변환 확인
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Thread")));
    }

    @Test
    @DisplayName("Virtual Thread API - 기본값 테스트")
    void virtualThreadApi_DefaultValue() throws Exception {
        // when
        MvcResult mvcResult = mockMvc.perform(get("/api/demo/virtual"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // then
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("VIRTUAL")));
    }

    @Test
    @DisplayName("Virtual Thread AOP API - 정상 응답 테스트 (DeferredResult)")
    void virtualThreadAopApi_Success() throws Exception {
        // when - DeferredResult를 사용하므로 비동기 처리
        MvcResult mvcResult = mockMvc.perform(get("/api/demo/virtual-aop")
                        .param("message", "HelloAOP"))
                .andDo(print())
                .andExpect(request().asyncStarted())
                .andReturn();

        // then - async 결과 확인
        mockMvc.perform(asyncDispatch(mvcResult))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Virtual Thread (AOP) Result")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HELLOAOP"))) // 대문자 변환 확인
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Thread")));
    }

    @Test
    @DisplayName("Virtual Thread AOP API - 기본값 테스트 (DeferredResult)")
    void virtualThreadAopApi_DefaultValue() throws Exception {
        // when - DeferredResult를 사용하므로 비동기 처리
        MvcResult mvcResult = mockMvc.perform(get("/api/demo/virtual-aop"))
                .andDo(print())
                .andExpect(request().asyncStarted())
                .andReturn();

        // then - async 결과 확인
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("VIRTUALAOP")));
    }

    @Test
    @DisplayName("AOP 방식 vs Callable 방식 - 응답 시간 비교 (둘 다 비동기)")
    void compareAopVsCallable() throws Exception {
        // Callable 방식 측정
        long callableStart = System.currentTimeMillis();
        MvcResult callableResult = mockMvc.perform(get("/api/demo/virtual")
                        .param("message", "CompareTest"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(callableResult))
                .andExpect(status().isOk());
        long callableDuration = System.currentTimeMillis() - callableStart;

        // AOP 방식 측정 (이제 DeferredResult를 사용하여 비동기)
        long aopStart = System.currentTimeMillis();
        MvcResult aopResult = mockMvc.perform(get("/api/demo/virtual-aop")
                        .param("message", "CompareTest"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(aopResult))
                .andExpect(status().isOk());
        long aopDuration = System.currentTimeMillis() - aopStart;

        // then - 둘 다 최소 1초 이상 소요
        assertThat(callableDuration).isGreaterThanOrEqualTo(1000);
        assertThat(aopDuration).isGreaterThanOrEqualTo(1000);

        System.out.println("Callable Duration: " + callableDuration + "ms");
        System.out.println("AOP Duration (DeferredResult): " + aopDuration + "ms");
        System.out.println("💡 이제 AOP 방식도 DeferredResult를 사용하여 진정한 비동기 처리!");
    }

    @Test
    @DisplayName("AOP 방식 vs Callable 방식 - 병렬 호출 응답 시간 비교")
    void compareAopVsCallable_ConcurrentRequests() throws Exception {
        int requestCount = 5;

        // Callable 방식 병렬 호출
        Thread[] callableThreads = new Thread[requestCount];
        long[] callableDurations = new long[requestCount];

        long callableTotalStart = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            callableThreads[i] = new Thread(() -> {
                try {
                    long start = System.currentTimeMillis();
                    MvcResult mvcResult = mockMvc.perform(get("/api/demo/virtual")
                                    .param("message", "CallableTest-" + index))
                            .andExpect(request().asyncStarted())
                            .andReturn();
                    mockMvc.perform(asyncDispatch(mvcResult))
                            .andExpect(status().isOk());
                    callableDurations[index] = System.currentTimeMillis() - start;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            callableThreads[i].start();
        }

        for (Thread thread : callableThreads) {
            thread.join();
        }

        long callableTotalDuration = System.currentTimeMillis() - callableTotalStart;

        // AOP 방식 병렬 호출 (이제 DeferredResult 사용)
        Thread[] aopThreads = new Thread[requestCount];
        long[] aopDurations = new long[requestCount];

        long aopTotalStart = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            aopThreads[i] = new Thread(() -> {
                try {
                    long start = System.currentTimeMillis();
                    MvcResult aopResult = mockMvc.perform(get("/api/demo/virtual-aop")
                                    .param("message", "AopTest-" + index))
                            .andExpect(request().asyncStarted())
                            .andReturn();
                    mockMvc.perform(asyncDispatch(aopResult))
                            .andExpect(status().isOk());
                    aopDurations[index] = System.currentTimeMillis() - start;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            aopThreads[i].start();
        }

        for (Thread thread : aopThreads) {
            thread.join();
        }

        long aopTotalDuration = System.currentTimeMillis() - aopTotalStart;

        // 결과 출력
        System.out.println("\n===== AOP vs Callable 병렬 호출 비교 (요청 수: " + requestCount + ") =====");
        System.out.println("\n[Callable 방식]");
        System.out.println("Total Duration: " + callableTotalDuration + "ms");
        for (int i = 0; i < requestCount; i++) {
            System.out.println("  Request " + i + ": " + callableDurations[i] + "ms");
        }
        long callableAvg = calculateAverage(callableDurations);
        System.out.println("  Average: " + callableAvg + "ms");

        System.out.println("\n[AOP 방식]");
        System.out.println("Total Duration: " + aopTotalDuration + "ms");
        for (int i = 0; i < requestCount; i++) {
            System.out.println("  Request " + i + ": " + aopDurations[i] + "ms");
        }
        long aopAvg = calculateAverage(aopDurations);
        System.out.println("  Average: " + aopAvg + "ms");

        System.out.println("\n[비교 결과]");
        System.out.println("Callable 평균: " + callableAvg + "ms");
        System.out.println("AOP 평균: " + aopAvg + "ms");
        System.out.println("차이: " + Math.abs(callableAvg - aopAvg) + "ms");

        // 검증 - 각 요청은 최소 1초 이상 소요
        for (long duration : callableDurations) {
            assertThat(duration).isGreaterThanOrEqualTo(1000);
        }
        for (long duration : aopDurations) {
            assertThat(duration).isGreaterThanOrEqualTo(1000);
        }
    }

    /**
     * 배열의 평균 계산
     */
    private long calculateAverage(long[] values) {
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    @Test
    @DisplayName("AOP 방식 vs Callable 방식 - Phaser를 이용한 완전 동시 호출 비교")
    void compareAopVsCallable_PhaserConcurrentRequests() throws Exception {
        int requestCount = 5;

        // Callable 방식 병렬 호출 with Phaser
        System.out.println("\n===== Callable 방식 - Phaser 동시 호출 시작 =====");
        Phaser callablePhaser = new Phaser(requestCount + 1); // +1 for main thread
        Thread[] callableThreads = new Thread[requestCount];
        AtomicLongArray callableDurations = new AtomicLongArray(requestCount);

        long callableTotalStart = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            callableThreads[i] = new Thread(() -> {
                try {
                    // Phase 0: 모든 스레드가 준비될 때까지 대기
                    callablePhaser.arriveAndAwaitAdvance();

                    // 모든 스레드가 동시에 실행 시작
                    long start = System.currentTimeMillis();
                    System.out.println("[Callable-" + index + "] 요청 시작 at " +
                        (start - callableTotalStart) + "ms");

                    MvcResult mvcResult = mockMvc.perform(get("/api/demo/virtual")
                                    .param("message", "CallablePhaser-" + index))
                            .andExpect(request().asyncStarted())
                            .andReturn();
                    mockMvc.perform(asyncDispatch(mvcResult))
                            .andExpect(status().isOk());

                    long duration = System.currentTimeMillis() - start;
                    callableDurations.set(index, duration);
                    System.out.println("[Callable-" + index + "] 완료 - " + duration + "ms");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            callableThreads[i].start();
        }

        // Main thread도 Phaser에 참여하여 모든 스레드를 동시에 시작
        callablePhaser.arriveAndAwaitAdvance();

        // 모든 스레드가 완료될 때까지 대기
        for (Thread thread : callableThreads) {
            thread.join();
        }

        long callableTotalDuration = System.currentTimeMillis() - callableTotalStart;

        // AOP 방식 병렬 호출 with Phaser (이제 DeferredResult 사용)
        System.out.println("\n===== AOP 방식 - Phaser 동시 호출 시작 (DeferredResult) =====");
        Phaser aopPhaser = new Phaser(requestCount + 1); // +1 for main thread
        Thread[] aopThreads = new Thread[requestCount];
        AtomicLongArray aopDurations = new AtomicLongArray(requestCount);

        long aopTotalStart = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            aopThreads[i] = new Thread(() -> {
                try {
                    // Phase 0: 모든 스레드가 준비될 때까지 대기
                    aopPhaser.arriveAndAwaitAdvance();

                    // 모든 스레드가 동시에 실행 시작
                    long start = System.currentTimeMillis();
                    System.out.println("[AOP-" + index + "] 요청 시작 at " +
                        (start - aopTotalStart) + "ms");

                    MvcResult aopResult = mockMvc.perform(get("/api/demo/virtual-aop")
                                    .param("message", "AopPhaser-" + index))
                            .andExpect(request().asyncStarted())
                            .andReturn();
                    mockMvc.perform(asyncDispatch(aopResult))
                            .andExpect(status().isOk());

                    long duration = System.currentTimeMillis() - start;
                    aopDurations.set(index, duration);
                    System.out.println("[AOP-" + index + "] 완료 - " + duration + "ms");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            aopThreads[i].start();
        }

        // Main thread도 Phaser에 참여하여 모든 스레드를 동시에 시작
        aopPhaser.arriveAndAwaitAdvance();

        // 모든 스레드가 완료될 때까지 대기
        for (Thread thread : aopThreads) {
            thread.join();
        }

        long aopTotalDuration = System.currentTimeMillis() - aopTotalStart;

        // 결과 출력 및 분석
        System.out.println("\n===== Phaser 기반 동시 호출 비교 결과 (요청 수: " + requestCount + ") =====");

        System.out.println("\n[Callable 방식]");
        System.out.println("Total Duration: " + callableTotalDuration + "ms");
        long callableSum = 0;
        for (int i = 0; i < requestCount; i++) {
            long duration = callableDurations.get(i);
            callableSum += duration;
            System.out.println("  Request " + i + ": " + duration + "ms");
        }
        long callableAvg = callableSum / requestCount;
        System.out.println("  Average: " + callableAvg + "ms");

        System.out.println("\n[AOP 방식]");
        System.out.println("Total Duration: " + aopTotalDuration + "ms");
        long aopSum = 0;
        for (int i = 0; i < requestCount; i++) {
            long duration = aopDurations.get(i);
            aopSum += duration;
            System.out.println("  Request " + i + ": " + duration + "ms");
        }
        long aopAvg = aopSum / requestCount;
        System.out.println("  Average: " + aopAvg + "ms");

        System.out.println("\n[비교 결과]");
        System.out.println("Callable 평균: " + callableAvg + "ms");
        System.out.println("AOP 평균: " + aopAvg + "ms");
        System.out.println("차이: " + Math.abs(callableAvg - aopAvg) + "ms");
        System.out.println("\n💡 Phaser를 사용하여 모든 요청이 완전히 동시에 시작되었습니다.");

        // 검증 - 각 요청은 최소 1초 이상 소요
        for (int i = 0; i < requestCount; i++) {
            assertThat(callableDurations.get(i)).isGreaterThanOrEqualTo(1000);
            assertThat(aopDurations.get(i)).isGreaterThanOrEqualTo(1000);
        }
    }

    @Test
    @DisplayName("Thread Info API - 스레드 정보 조회")
    void threadInfoApi_Success() throws Exception {
        // when & then
        String response = mockMvc.perform(get("/api/demo/thread-info"))
                .andDo(print())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 응답에 스레드 정보가 포함되어 있는지 확인
        assertThat(response).contains("Thread Name:");
        assertThat(response).contains("Thread ID:");
        assertThat(response).contains("Is Virtual:");
    }

    @Test
    @DisplayName("Platform Load Test API - 응답 형식 확인")
    void platformLoadTestApi_Success() throws Exception {
        // when & then
        mockMvc.perform(get("/api/demo/platform-load")
                        .param("id", "100"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Platform [100]")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("completed in")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ms")));
    }

    @Test
    @DisplayName("Virtual Load Test API - 응답 형식 확인 (Async)")
    void virtualLoadTestApi_Success() throws Exception {
        // when
        MvcResult mvcResult = mockMvc.perform(get("/api/demo/virtual-load")
                        .param("id", "200"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // then
        String response = mockMvc.perform(asyncDispatch(mvcResult))
                .andDo(print())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).contains("Virtual [200]");
        assertThat(response).contains("completed in");
        assertThat(response).contains("ms");
        assertThat(response).contains("REQUEST-200"); // 대문자 변환 확인
    }

    @Test
    @DisplayName("Platform vs Virtual - 응답 시간 비교 (단일 요청)")
    void compareResponseTime_SingleRequest() throws Exception {
        // Platform Thread 측정
        long platformStart = System.currentTimeMillis();
        mockMvc.perform(get("/api/demo/platform")
                        .param("message", "PerformanceTest"))
                .andExpect(status().isOk());
        long platformDuration = System.currentTimeMillis() - platformStart;

        // Virtual Thread 측정
        long virtualStart = System.currentTimeMillis();
        MvcResult mvcResult = mockMvc.perform(get("/api/demo/virtual")
                        .param("message", "PerformanceTest"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());
        long virtualDuration = System.currentTimeMillis() - virtualStart;

        // then - 둘 다 최소 1초 이상 소요
        assertThat(platformDuration).isGreaterThanOrEqualTo(1000);
        assertThat(virtualDuration).isGreaterThanOrEqualTo(1000);

        System.out.println("Platform Thread Duration: " + platformDuration + "ms");
        System.out.println("Virtual Thread Duration: " + virtualDuration + "ms");
    }

    @Test
    @DisplayName("동시 요청 처리 - Platform Thread")
    void concurrentRequests_PlatformThread() throws Exception {
        int requestCount = 3;
        Thread[] threads = new Thread[requestCount];
        long[] durations = new long[requestCount];

        long totalStart = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    long start = System.currentTimeMillis();
                    mockMvc.perform(get("/api/demo/platform-load")
                                    .param("id", String.valueOf(index)))
                            .andExpect(status().isOk());
                    durations[index] = System.currentTimeMillis() - start;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        long totalDuration = System.currentTimeMillis() - totalStart;

        System.out.println("Platform Thread - Total Duration: " + totalDuration + "ms");
        for (int i = 0; i < requestCount; i++) {
            System.out.println("  Request " + i + ": " + durations[i] + "ms");
        }

        // 각 요청은 최소 1초 이상 소요
        for (long duration : durations) {
            assertThat(duration).isGreaterThanOrEqualTo(1000);
        }
    }

    @Test
    @DisplayName("동시 요청 처리 - Virtual Thread")
    void concurrentRequests_VirtualThread() throws Exception {
        int requestCount = 3;
        Thread[] threads = new Thread[requestCount];
        long[] durations = new long[requestCount];

        long totalStart = System.currentTimeMillis();

        for (int i = 0; i < requestCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    long start = System.currentTimeMillis();
                    MvcResult mvcResult = mockMvc.perform(get("/api/demo/virtual-load")
                                    .param("id", String.valueOf(index)))
                            .andExpect(request().asyncStarted())
                            .andReturn();
                    mockMvc.perform(asyncDispatch(mvcResult))
                            .andExpect(status().isOk());
                    durations[index] = System.currentTimeMillis() - start;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        long totalDuration = System.currentTimeMillis() - totalStart;

        System.out.println("Virtual Thread - Total Duration: " + totalDuration + "ms");
        for (int i = 0; i < requestCount; i++) {
            System.out.println("  Request " + i + ": " + durations[i] + "ms");
        }

        // 각 요청은 최소 1초 이상 소요
        for (long duration : durations) {
            assertThat(duration).isGreaterThanOrEqualTo(1000);
        }
    }
}
