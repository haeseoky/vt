package com.ocean.sc.vt.aspect;

import com.ocean.sc.vt.annotation.VirtualThread;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @VirtualThread 어노테이션이 붙은 메서드를 Virtual Thread에서 실행하는 Aspect
 *
 * <p>동작 방식:</p>
 * <ol>
 *     <li>현재 스레드(Tomcat Thread)의 MDC 정보 복사</li>
 *     <li>메서드 실행을 Callable로 감싸기</li>
 *     <li>Virtual Thread Executor에 작업 제출</li>
 *     <li>타임아웃 내에 결과 반환</li>
 * </ol>
 */
@Aspect
@Component
public class VirtualThreadAspect {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadAspect.class);

    private final AsyncTaskExecutor virtualThreadExecutor;

    public VirtualThreadAspect(AsyncTaskExecutor virtualThreadExecutor) {
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    /**
     * @VirtualThread 어노테이션이 붙은 메서드를 Virtual Thread에서 실행
     *
     * @param joinPoint 메서드 실행 지점
     * @param virtualThread 어노테이션 정보
     * @return 메서드 실행 결과
     * @throws Throwable 실행 중 발생한 예외
     */
    @Around("@annotation(virtualThread)")
    public Object executeInVirtualThread(
            ProceedingJoinPoint joinPoint,
            VirtualThread virtualThread) throws Throwable {

        // 현재 스레드 정보 (Tomcat Thread)
        Thread currentThread = Thread.currentThread();
        String methodName = joinPoint.getSignature().toShortString();

        log.info("[VirtualThread Aspect] Method: {}, Tomcat Thread: {}, IsVirtual: {}",
                methodName, currentThread.getName(), currentThread.isVirtual());

        // MDC 정보 복사 (로그 추적을 위해)
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        // Callable로 감싸기
        Callable<Object> task = () -> {
            try {
                // Virtual Thread에 MDC 정보 주입
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }

                Thread workerThread = Thread.currentThread();
                log.info("[VirtualThread Aspect] Method: {}, Worker Thread: {}, IsVirtual: {}",
                        methodName, workerThread.getName(), workerThread.isVirtual());

                // 실제 메서드 실행
                return joinPoint.proceed();

            } catch (Throwable e) {
                throw new RuntimeException(e);
            } finally {
                // MDC 정리
                MDC.clear();
            }
        };

        // Virtual Thread Executor에 작업 제출
        Future<Object> future = virtualThreadExecutor.submit(task);

        try {
            // 타임아웃 내에 결과 반환
            long timeout = virtualThread.timeout();
            Object result = future.get(timeout, TimeUnit.MILLISECONDS);

            log.info("[VirtualThread Aspect] Method: {} completed successfully", methodName);
            return result;

        } catch (TimeoutException e) {
            // 타임아웃 발생
            future.cancel(true);
            log.error("[VirtualThread Aspect] Method: {} timed out after {}ms",
                    methodName, virtualThread.timeout());
            throw new RuntimeException(
                    String.format("Virtual Thread execution timed out after %dms", virtualThread.timeout()), e);

        } catch (Exception e) {
            // 기타 예외
            log.error("[VirtualThread Aspect] Method: {} failed with exception",
                    methodName, e);
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
