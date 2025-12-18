package com.ocean.sc.vt.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.async.WebAsyncTask;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VirtualThreadConfig 동작 검증 테스트
 */
@SpringBootTest
class VirtualThreadConfigTest {

    @Autowired
    private VirtualThreadConfig virtualThreadConfig;

    @Test
    @DisplayName("VirtualThreadConfig Bean 로딩 확인")
    void virtualThreadConfigBeanLoaded() {
        assertThat(virtualThreadConfig).isNotNull();
    }

    @Test
    @DisplayName("MdcTaskDecorator - MDC 복사 검증")
    void mdcTaskDecorator_CopiesMdcContext() throws Exception {
        // given
        VirtualThreadConfig.MdcTaskDecorator decorator = new VirtualThreadConfig.MdcTaskDecorator();
        String testKey = "testTraceId";
        String testValue = "trace-12345";

        AtomicReference<String> capturedValue = new AtomicReference<>();
        AtomicBoolean taskExecuted = new AtomicBoolean(false);

        // 메인 스레드에 MDC 설정
        MDC.put(testKey, testValue);

        Runnable originalTask = () -> {
            // 작업 스레드에서 MDC 값 읽기
            capturedValue.set(MDC.get(testKey));
            taskExecuted.set(true);
        };

        // when
        Runnable decoratedTask = decorator.decorate(originalTask);

        // MDC를 다른 값으로 변경 (원래 값이 복사되었는지 확인)
        MDC.put(testKey, "changed-value");

        // 별도 스레드에서 실행
        Thread thread = new Thread(decoratedTask);
        thread.start();
        thread.join();

        // then
        assertThat(taskExecuted.get()).isTrue();
        assertThat(capturedValue.get()).isEqualTo(testValue); // 원래 값이 복사되었는지 확인

        // cleanup
        MDC.clear();
    }

    @Test
    @DisplayName("MdcTaskDecorator - MDC가 null인 경우 처리")
    void mdcTaskDecorator_HandlesNullMdc() throws Exception {
        // given
        VirtualThreadConfig.MdcTaskDecorator decorator = new VirtualThreadConfig.MdcTaskDecorator();
        AtomicBoolean taskExecuted = new AtomicBoolean(false);

        // MDC 초기화
        MDC.clear();

        Runnable originalTask = () -> {
            taskExecuted.set(true);
        };

        // when
        Runnable decoratedTask = decorator.decorate(originalTask);
        Thread thread = new Thread(decoratedTask);
        thread.start();
        thread.join();

        // then
        assertThat(taskExecuted.get()).isTrue();
    }

    @Test
    @DisplayName("MdcTaskDecorator - MDC cleanup 검증")
    void mdcTaskDecorator_CleanupMdc() throws Exception {
        // given
        VirtualThreadConfig.MdcTaskDecorator decorator = new VirtualThreadConfig.MdcTaskDecorator();
        String testKey = "cleanupTest";
        String testValue = "cleanup-12345";

        MDC.put(testKey, testValue);

        AtomicReference<String> mdcAfterExecution = new AtomicReference<>();

        Runnable originalTask = () -> {
            // 작업이 끝난 후 MDC 상태 (finally 블록 전)
            // 실제로는 finally에서 clear되므로 이 값은 의미 없음
        };

        // when
        Runnable decoratedTask = decorator.decorate(originalTask);

        Thread thread = new Thread(() -> {
            decoratedTask.run();
            // finally 블록에서 MDC.clear()가 실행된 후
            mdcAfterExecution.set(MDC.get(testKey));
        });
        thread.start();
        thread.join();

        // then
        // 작업 스레드에서는 MDC가 clear되어야 함
        assertThat(mdcAfterExecution.get()).isNull();

        // cleanup
        MDC.clear();
    }

    @Test
    @DisplayName("Virtual Thread 실제 생성 확인")
    void virtualThreadActuallyCreated() throws Exception {
        // given
        AtomicBoolean isVirtualThread = new AtomicBoolean(false);
        AtomicReference<String> threadName = new AtomicReference<>();

        Callable<String> task = () -> {
            Thread currentThread = Thread.currentThread();
            isVirtualThread.set(currentThread.isVirtual());
            threadName.set(currentThread.toString());
            return "Task completed on " + currentThread.getName();
        };

        // when - Virtual Thread Executor로 실행
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setVirtualThreads(true);
        executor.initialize();

        String result = executor.submit(task).get();

        executor.shutdown();

        // then
        assertThat(isVirtualThread.get()).isTrue(); // Virtual Thread 확인
        assertThat(threadName.get()).contains("VirtualThread"); // 스레드 이름 확인
        assertThat(result).contains("Task completed");

        System.out.println("Virtual Thread Name: " + threadName.get());
        System.out.println("Result: " + result);
    }

    @Test
    @DisplayName("Platform Thread vs Virtual Thread 비교")
    void comparePlatformAndVirtualThread() throws Exception {
        AtomicBoolean isPlatformThreadVirtual = new AtomicBoolean(false);
        AtomicBoolean isVirtualThreadVirtual = new AtomicBoolean(false);

        Callable<String> task = () -> {
            Thread currentThread = Thread.currentThread();
            return currentThread.isVirtual() ? "Virtual" : "Platform";
        };

        // Platform Thread Executor
        ThreadPoolTaskExecutor platformExecutor = new ThreadPoolTaskExecutor();
        platformExecutor.setVirtualThreads(false); // Platform Thread
        platformExecutor.setCorePoolSize(1);
        platformExecutor.initialize();

        String platformResult = platformExecutor.submit(task).get();
        isPlatformThreadVirtual.set("Virtual".equals(platformResult));

        platformExecutor.shutdown();

        // Virtual Thread Executor
        ThreadPoolTaskExecutor virtualExecutor = new ThreadPoolTaskExecutor();
        virtualExecutor.setVirtualThreads(true); // Virtual Thread
        virtualExecutor.initialize();

        String virtualResult = virtualExecutor.submit(task).get();
        isVirtualThreadVirtual.set("Virtual".equals(virtualResult));

        virtualExecutor.shutdown();

        // then
        assertThat(isPlatformThreadVirtual.get()).isFalse(); // Platform Thread는 Virtual이 아님
        assertThat(isVirtualThreadVirtual.get()).isTrue(); // Virtual Thread는 Virtual임

        System.out.println("Platform Thread Result: " + platformResult);
        System.out.println("Virtual Thread Result: " + virtualResult);
    }

    @Test
    @DisplayName("다수의 Virtual Thread 동시 생성 테스트")
    void createMultipleVirtualThreads() throws Exception {
        // given
        int threadCount = 100;
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setVirtualThreads(true);
        executor.initialize();

        AtomicReference<Integer> virtualThreadCount = new AtomicReference<>(0);

        Callable<Boolean> task = () -> Thread.currentThread().isVirtual();

        // when
        long startTime = System.currentTimeMillis();

        var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(task));
        }

        // 모든 작업 완료 대기
        int count = 0;
        for (var future : futures) {
            if (future.get()) {
                count++;
            }
        }
        virtualThreadCount.set(count);

        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();

        // then
        assertThat(virtualThreadCount.get()).isEqualTo(threadCount); // 모든 스레드가 Virtual
        System.out.println("Created " + threadCount + " Virtual Threads in " + duration + "ms");
    }
}
