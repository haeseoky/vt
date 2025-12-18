package com.ocean.sc.vt.controller;

import com.ocean.sc.vt.service.DemoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Callable;

/**
 * Virtual Thread 데모 컨트롤러
 * - Platform Thread vs Virtual Thread 비교
 */
@RestController
@RequestMapping("/api/demo")
public class VirtualThreadDemoController {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadDemoController.class);

    private final DemoService demoService;

    public VirtualThreadDemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    /**
     * [일반 API] Platform Thread 사용
     * - Tomcat의 일반 스레드(Platform Thread)가 직접 처리
     * - 1초 동안 스레드가 Block됨
     *
     * 테스트: curl "http://localhost:8080/api/demo/platform?message=Hello"
     */
    @GetMapping("/platform")
    public String platformThreadApi(@RequestParam(defaultValue = "Platform") String message) {
        log.info("===== [Platform Thread API] 요청 시작 =====");
        log.info("Controller Thread: {}", Thread.currentThread());

        // Service에서 1초 대기 (이 동안 Tomcat 스레드가 Block됨)
        String result = demoService.processWithDelay(message);

        log.info("===== [Platform Thread API] 요청 완료 =====");
        return String.format("Platform Thread Result: %s", result);
    }

    /**
     * [Virtual Thread API] Virtual Thread 사용
     * - Tomcat 스레드는 즉시 반환되고, Virtual Thread가 작업 처리
     * - Callable 반환 시 VirtualThreadConfig가 자동 적용
     * - 1초 동안 대기해도 Tomcat 스레드는 Block되지 않음
     *
     * 테스트: curl "http://localhost:8080/api/demo/virtual?message=Hello"
     */
    @GetMapping("/virtual")
    public Callable<String> virtualThreadApi(@RequestParam(defaultValue = "Virtual") String message) {
        log.info("===== [Virtual Thread API] 요청 시작 =====");
        log.info("Controller Thread (Tomcat): {}", Thread.currentThread());

        // Callable을 반환하면 VirtualThreadConfig에 설정된 Virtual Thread Executor가 실행
        return () -> {
            // --- 여기부터 Virtual Thread 영역 ---
            log.info("Worker Thread (Virtual): {}", Thread.currentThread());

            // Service에서 1초 대기 (Virtual Thread가 Block됨, Tomcat 스레드는 해방됨)
            String result = demoService.processComplexLogic(message);

            log.info("===== [Virtual Thread API] 요청 완료 =====");
            return String.format("Virtual Thread Result: %s", result);
        };
    }

    /**
     * [비교용 API] 스레드 정보 확인
     * - 현재 실행 중인 스레드 정보 반환
     *
     * 테스트: curl "http://localhost:8080/api/demo/thread-info"
     */
    @GetMapping("/thread-info")
    public String getThreadInfo() {
        Thread currentThread = Thread.currentThread();
        return String.format(
            "Thread Name: %s, Thread ID: %d, Is Virtual: %s, Thread Group: %s",
            currentThread.getName(),
            currentThread.threadId(),
            currentThread.isVirtual(),
            currentThread.getThreadGroup() != null ? currentThread.getThreadGroup().getName() : "N/A"
        );
    }

    /**
     * [성능 테스트용 API] Platform Thread - 여러 요청 동시 처리
     *
     * 테스트:
     * for i in {1..10}; do curl "http://localhost:8080/api/demo/platform-load?id=$i" & done
     */
    @GetMapping("/platform-load")
    public String platformLoadTest(@RequestParam(defaultValue = "1") String id) {
        long startTime = System.currentTimeMillis();
        log.info("[Platform Load Test] 요청 {} 시작", id);

        String result = demoService.processWithDelay("Request-" + id);

        long duration = System.currentTimeMillis() - startTime;
        log.info("[Platform Load Test] 요청 {} 완료 ({}ms)", id, duration);

        return String.format("Platform [%s] completed in %dms - %s", id, duration, result);
    }

    /**
     * [성능 테스트용 API] Virtual Thread - 여러 요청 동시 처리
     *
     * 테스트:
     * for i in {1..10}; do curl "http://localhost:8080/api/demo/virtual-load?id=$i" & done
     */
    @GetMapping("/virtual-load")
    public Callable<String> virtualLoadTest(@RequestParam(defaultValue = "1") String id) {
        log.info("[Virtual Load Test] 요청 {} 시작 (Tomcat Thread)", id);

        return () -> {
            long startTime = System.currentTimeMillis();
            log.info("[Virtual Load Test] 요청 {} 처리 시작 (Virtual Thread)", id);

            String result = demoService.processComplexLogic("Request-" + id);

            long duration = System.currentTimeMillis() - startTime;
            log.info("[Virtual Load Test] 요청 {} 완료 ({}ms)", id, duration);

            return String.format("Virtual [%s] completed in %dms - %s", id, duration, result);
        };
    }
}
