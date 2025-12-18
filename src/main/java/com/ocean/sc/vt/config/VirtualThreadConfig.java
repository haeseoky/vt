package com.ocean.sc.vt.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

/**
 * Virtual Thread 설정 클래스
 * - 두 가지 방식 지원:
 *   1. Callable 반환 방식 (WebMvcConfigurer)
 *   2. @VirtualThread 어노테이션 방식 (AOP)
 * - MDC(Mapped Diagnostic Context) 복사를 통한 로그 추적 지원
 */
@Configuration
public class VirtualThreadConfig implements WebMvcConfigurer {

    /**
     * Virtual Thread Executor Bean 생성
     * - @VirtualThread 어노테이션에서 사용
     * - Callable 방식에서도 재사용
     */
    @Bean
    public AsyncTaskExecutor virtualThreadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Virtual Thread 활성화 (Java 21+)
        executor.setVirtualThreads(true);

        // MDC 복사를 위한 TaskDecorator 설정
        executor.setTaskDecorator(new MdcTaskDecorator());

        // Bean 이름 설정
        executor.setThreadNamePrefix("VirtualThread-");

        executor.initialize();

        return executor;
    }

    /**
     * WebMvc 비동기 지원 설정
     * - Callable 반환 방식에서 사용
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Callable 리턴 시 사용할 TaskExecutor 설정
        configurer.setTaskExecutor(virtualThreadExecutor());

        // 타임아웃 설정 (30초)
        configurer.setDefaultTimeout(30000);
    }

    /**
     * MDC(Mapped Diagnostic Context) 복사 데코레이터
     * - Tomcat 스레드의 MDC 정보를 Virtual Thread로 복사
     * - TraceId, UserId 등 로그 컨텍스트 유지
     */
    public static class MdcTaskDecorator implements TaskDecorator {

        @Override
        public Runnable decorate(Runnable runnable) {
            // 1. 현재 스레드(Tomcat)의 MDC 정보 복사
            Map<String, String> contextMap = MDC.getCopyOfContextMap();

            return () -> {
                try {
                    // 2. Virtual Thread에 MDC 정보 주입
                    if (contextMap != null) {
                        MDC.setContextMap(contextMap);
                    }

                    // 3. 실제 로직 실행
                    runnable.run();
                } finally {
                    // 4. Virtual Thread 반납 시 정리 (ThreadLocal 누수 방지)
                    MDC.clear();
                }
            };
        }
    }
}
