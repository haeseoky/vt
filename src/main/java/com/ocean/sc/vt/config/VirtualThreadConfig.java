package com.ocean.sc.vt.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

/**
 * Virtual Thread 설정 클래스
 * - Callable을 반환하는 Controller 메서드에 대해 Virtual Thread 적용
 * - MDC(Mapped Diagnostic Context) 복사를 통한 로그 추적 지원
 */
@Configuration
public class VirtualThreadConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Virtual Thread 활성화 (Java 21+)
        executor.setVirtualThreads(true);

        // MDC 복사를 위한 TaskDecorator 설정
        executor.setTaskDecorator(new MdcTaskDecorator());

        executor.initialize();

        // Callable 리턴 시 사용할 TaskExecutor 설정
        configurer.setTaskExecutor(executor);

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
