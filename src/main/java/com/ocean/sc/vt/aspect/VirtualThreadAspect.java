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
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * @VirtualThread 어노테이션이 붙은 메서드를 Virtual Thread에서 실행하는 Aspect
 *
 * <p>동작 방식:</p>
 * <ol>
 *     <li>현재 스레드(Tomcat Thread)의 MDC 정보 복사</li>
 *     <li>DeferredResult 생성 및 즉시 반환 (Tomcat Thread 해방)</li>
 *     <li>Virtual Thread Executor에 작업 제출</li>
 *     <li>작업 완료 시 DeferredResult.setResult() 호출</li>
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
     * DeferredResult를 반환하여 Tomcat Thread를 즉시 해방
     *
     * @param joinPoint 메서드 실행 지점
     * @param virtualThread 어노테이션 정보
     * @return DeferredResult (비동기 처리 결과)
     */
    @Around("@annotation(virtualThread)")
    public Object executeInVirtualThread(
            ProceedingJoinPoint joinPoint,
            VirtualThread virtualThread) {

        // 현재 스레드 정보 (Tomcat Thread)
        Thread currentThread = Thread.currentThread();
        String methodName = joinPoint.getSignature().toShortString();

        log.info("[VirtualThread Aspect] Method: {}, Tomcat Thread: {}, IsVirtual: {}",
                methodName, currentThread.getName(), currentThread.isVirtual());
        log.info("[VirtualThread Aspect] 🚀 DeferredResult 생성 - Tomcat Thread 즉시 해방");

        // MDC 정보 복사 (로그 추적을 위해)
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        // DeferredResult 생성 (타임아웃 설정)
        DeferredResult<Object> deferredResult = new DeferredResult<>(virtualThread.timeout());

        // 타임아웃 핸들러
        deferredResult.onTimeout(() -> {
            log.error("[VirtualThread Aspect] ⏱️ Method: {} timed out after {}ms",
                    methodName, virtualThread.timeout());
            deferredResult.setErrorResult(
                    new RuntimeException(String.format("Virtual Thread execution timed out after %dms",
                            virtualThread.timeout())));
        });

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
                Object result = joinPoint.proceed();

                // 성공 결과 설정
                log.info("[VirtualThread Aspect] ✅ Method: {} completed successfully", methodName);
                deferredResult.setResult(result);

                return result;

            } catch (Throwable e) {
                // 예외 발생 시 에러 결과 설정
                log.error("[VirtualThread Aspect] ❌ Method: {} failed with exception",
                        methodName, e);
                deferredResult.setErrorResult(e);
                throw new RuntimeException(e);

            } finally {
                // MDC 정리
                MDC.clear();
            }
        };

        // Virtual Thread Executor에 작업 제출 (비동기)
        virtualThreadExecutor.submit(task);

        // DeferredResult 즉시 반환 → Tomcat Thread 해방
        log.info("[VirtualThread Aspect] 🎯 DeferredResult 반환 완료 - Tomcat Thread: {}",
                currentThread.getName());

        return deferredResult;
    }
}
