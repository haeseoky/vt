# Spring MVC 비동기 처리 실제 소스 코드 분석

Spring Framework에서 Callable을 Virtual Thread로 실행하는 실제 코드를 분석합니다.

## 목차

- [전체 처리 흐름](#전체-처리-흐름)
- [1단계: CallableMethodReturnValueHandler](#1단계-callablemethodreturnvaluehandler)
- [2단계: WebAsyncManager](#2단계-webasyncmanager)
- [3단계: TaskExecutor 실행](#3단계-taskexecutor-실행)
- [코드 흐름 추적](#코드-흐름-추적)

---

## 전체 처리 흐름

```
Controller 메서드 실행
   │
   ▼
CallableMethodReturnValueHandler.handleReturnValue()
   │
   ├─► Callable 감지
   │
   ▼
WebAsyncManager.startCallableProcessing()
   │
   ├─► TaskExecutor 조회 (VirtualThreadConfig에서 설정한 것)
   ├─► WebAsyncTask로 래핑
   │
   ▼
TaskExecutor.submit(() -> callable.call())
   │
   └─► Virtual Thread 생성 및 실행
```

---

## 1단계: CallableMethodReturnValueHandler

**위치**: `spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/CallableMethodReturnValueHandler.java`

### 전체 코드

```java
package org.springframework.web.servlet.mvc.method.annotation;

import java.util.concurrent.Callable;

import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Callable 타입의 반환값을 처리하는 핸들러
 */
public class CallableMethodReturnValueHandler implements HandlerMethodReturnValueHandler {

    /**
     * 반환 타입이 Callable인지 확인
     */
    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return Callable.class.isAssignableFrom(returnType.getParameterType());
    }

    /**
     * Callable 반환값 처리
     *
     * @param returnValue Controller가 반환한 Callable 객체
     * @param returnType 메서드 파라미터 정보
     * @param mavContainer ModelAndView 컨테이너
     * @param webRequest 네이티브 웹 요청
     */
    @Override
    public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
            ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {

        // null 체크
        if (returnValue == null) {
            mavContainer.setRequestHandled(true);
            return;
        }

        // Callable로 캐스팅
        Callable<?> callable = (Callable<?>) returnValue;

        // ★ 핵심: WebAsyncManager를 통해 비동기 처리 시작
        WebAsyncUtils.getAsyncManager(webRequest)
            .startCallableProcessing(callable, mavContainer);
    }
}
```

### 핵심 포인트

1. **supportsReturnType()**: 반환 타입이 `Callable`인지 체크
2. **handleReturnValue()**: Callable을 WebAsyncManager에 전달
3. **WebAsyncUtils.getAsyncManager()**: 현재 요청의 WebAsyncManager 조회

---

## 2단계: WebAsyncManager

**위치**: `spring-web/src/main/java/org/springframework/web/context/request/async/WebAsyncManager.java`

### startCallableProcessing 메서드 (간단한 버전)

```java
/**
 * Callable 처리 시작 (간단한 버전)
 *
 * @param callable 실행할 Callable
 * @param processingContext 처리 컨텍스트 (ModelAndView 등)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public void startCallableProcessing(Callable<?> callable, Object... processingContext)
        throws Exception {

    Assert.notNull(callable, "Callable must not be null");

    // WebAsyncTask로 래핑하여 상세 버전 호출
    startCallableProcessing(new WebAsyncTask(callable), processingContext);
}
```

### startCallableProcessing 메서드 (상세한 버전)

```java
/**
 * WebAsyncTask를 통한 Callable 처리 시작
 *
 * @param webAsyncTask Callable과 타임아웃 등을 포함한 Task
 * @param processingContext 처리 컨텍스트
 */
public void startCallableProcessing(final WebAsyncTask<?> webAsyncTask, Object... processingContext)
        throws Exception {

    Assert.notNull(webAsyncTask, "WebAsyncTask must not be null");
    Assert.state(this.asyncWebRequest != null, "AsyncWebRequest must not be null");

    // ─────────────────────────────────────────────────────────
    // 1. 상태 검증 (NOT_STARTED → ASYNC_PROCESSING)
    // ─────────────────────────────────────────────────────────
    if (!this.state.compareAndSet(State.NOT_STARTED, State.ASYNC_PROCESSING)) {
        throw new IllegalStateException(
            "Unexpected call to startCallableProcessing: [" + this.state.get() + "]");
    }

    // ─────────────────────────────────────────────────────────
    // 2. 타임아웃 설정
    // ─────────────────────────────────────────────────────────
    Long timeout = webAsyncTask.getTimeout();
    if (timeout != null) {
        this.asyncWebRequest.setTimeout(timeout);
    }

    // ─────────────────────────────────────────────────────────
    // 3. TaskExecutor 설정
    // ─────────────────────────────────────────────────────────
    AsyncTaskExecutor executor = webAsyncTask.getExecutor();
    if (executor != null) {
        // ★ WebAsyncTask에 Executor가 설정되어 있으면 사용
        this.taskExecutor = executor;
    } else {
        // ★ 없으면 기본 Executor 사용 (VirtualThreadConfig에서 설정한 것)
        if (this.taskExecutor == null) {
            this.taskExecutor = getDefaultTaskExecutor();
        }
    }

    // ─────────────────────────────────────────────────────────
    // 4. 인터셉터 체인 구성
    // ─────────────────────────────────────────────────────────
    List<CallableProcessingInterceptor> interceptors = new ArrayList<>();

    // WebAsyncTask의 인터셉터 추가
    if (webAsyncTask.getInterceptor() != null) {
        interceptors.add(webAsyncTask.getInterceptor());
    }

    // 등록된 인터셉터들 추가
    interceptors.addAll(this.callableInterceptors.values());

    // 타임아웃 인터셉터 추가
    interceptors.add(timeoutCallableInterceptor);

    final CallableInterceptorChain interceptorChain =
        new CallableInterceptorChain(interceptors);

    // ─────────────────────────────────────────────────────────
    // 5. 비동기 처리 시작
    // ─────────────────────────────────────────────────────────
    this.asyncWebRequest.addTimeoutHandler(() -> {
        if (logger.isDebugEnabled()) {
            logger.debug("Async request timeout for " + formatRequestUri());
        }
        Object result = interceptorChain.triggerAfterTimeout(this.asyncWebRequest, callable);
        if (result != CallableProcessingInterceptor.RESULT_NONE) {
            setConcurrentResultAndDispatch(result);
        }
    });

    this.asyncWebRequest.addErrorHandler(ex -> {
        if (logger.isDebugEnabled()) {
            logger.debug("Async request error for " + formatRequestUri() + ": " + ex);
        }
        Object result = interceptorChain.triggerAfterError(this.asyncWebRequest, callable, ex);
        result = (result != CallableProcessingInterceptor.RESULT_NONE ? result : ex);
        setConcurrentResultAndDispatch(result);
    });

    this.asyncWebRequest.addCompletionHandler(() ->
        interceptorChain.triggerAfterCompletion(this.asyncWebRequest, callable));

    // ─────────────────────────────────────────────────────────
    // 6. ★★★ TaskExecutor를 통한 Callable 실행 (핵심!) ★★★
    // ─────────────────────────────────────────────────────────
    startAsyncProcessing(processingContext);

    try {
        // ★ TaskExecutor에 작업 제출
        Future<?> future = this.taskExecutor.submit(() -> {
            Object result = null;
            try {
                // Pre-process 인터셉터 실행
                interceptorChain.applyPreProcess(this.asyncWebRequest, callable);

                // ★★★ Callable.call() 실행 (실제 로직) ★★★
                result = callable.call();
            }
            catch (Throwable ex) {
                result = ex;
            }
            finally {
                // Post-process 인터셉터 실행
                result = interceptorChain.applyPostProcess(
                    this.asyncWebRequest, callable, result);
            }

            // ★ 결과를 저장하고 요청 디스패치
            setConcurrentResultAndDispatch(result);
        });

        interceptorChain.setTaskFuture(future);
    }
    catch (Throwable ex) {
        // 예외 발생 시 처리
        Object result = interceptorChain.applyPostProcess(
            this.asyncWebRequest, callable, ex);
        setConcurrentResultAndDispatch(result);
    }
}
```

### 핵심 포인트

1. **this.taskExecutor**: VirtualThreadConfig에서 설정한 TaskExecutor
2. **this.taskExecutor.submit()**: 별도 스레드에서 Callable 실행
3. **callable.call()**: 실제 비즈니스 로직 실행
4. **setConcurrentResultAndDispatch()**: 결과 저장 및 요청 재디스패치

---

## 3단계: TaskExecutor 실행

### VirtualThreadConfig에서 설정한 TaskExecutor

```java
@Configuration
public class VirtualThreadConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // ★ Virtual Thread 활성화
        executor.setVirtualThreads(true);

        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();

        // ★ Spring MVC에 이 Executor 등록
        // → WebAsyncManager.taskExecutor에 주입됨
        configurer.setTaskExecutor(executor);

        configurer.setDefaultTimeout(30000);
    }
}
```

### ThreadPoolTaskExecutor의 submit 메서드

**위치**: `spring-context/src/main/java/org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor.java`

```java
@Override
public void execute(Runnable task) {
    Executor executor = getThreadPoolExecutor();

    try {
        // ★ TaskDecorator로 감싸기 (MDC 복사)
        if (this.taskDecorator != null) {
            task = this.taskDecorator.decorate(task);
        }

        // ★ 실제 실행
        executor.execute(task);
    }
    catch (RejectedExecutionException ex) {
        throw new TaskRejectedException("Executor rejected task", ex);
    }
}

protected Executor getThreadPoolExecutor() {
    if (this.virtualThreads) {
        // ★★★ Virtual Thread Executor 반환 ★★★
        return Executors.newVirtualThreadPerTaskExecutor();
    } else {
        // Platform Thread Pool 반환
        return super.getThreadPoolExecutor();
    }
}
```

### Executors.newVirtualThreadPerTaskExecutor()

**Java 21 표준 API**

```java
// java.util.concurrent.Executors
public static ExecutorService newVirtualThreadPerTaskExecutor() {
    return new ThreadPerTaskExecutor(
        Thread.ofVirtual()
            .name("VirtualThread-", 0)
            .factory()
    );
}

// ThreadPerTaskExecutor 내부 구현
static class ThreadPerTaskExecutor implements ExecutorService {
    private final ThreadFactory factory;

    ThreadPerTaskExecutor(ThreadFactory factory) {
        this.factory = factory;
    }

    @Override
    public void execute(Runnable task) {
        // ★ 요청마다 새로운 Virtual Thread 생성 및 시작
        Thread thread = factory.newThread(task);
        thread.start();
    }
}
```

---

## 코드 흐름 추적

### 실제 실행 흐름

```java
// ① Controller 메서드 실행
@GetMapping("/virtual")
public Callable<String> virtualApi(@RequestParam String message) {
    return () -> service.processComplexLogic(message);
}

// ② Spring MVC 내부 처리
// → DispatcherServlet
// → RequestMappingHandlerAdapter
// → ServletInvocableHandlerMethod.invokeAndHandle()

// ③ CallableMethodReturnValueHandler.handleReturnValue()
public void handleReturnValue(Object returnValue, ...) {
    Callable<?> callable = (Callable<?>) returnValue;
    WebAsyncUtils.getAsyncManager(webRequest)
        .startCallableProcessing(callable, mavContainer);
}

// ④ WebAsyncManager.startCallableProcessing()
public void startCallableProcessing(Callable<?> callable, ...) {
    // TaskExecutor 조회 (VirtualThreadConfig에서 설정한 것)
    AsyncTaskExecutor executor = this.taskExecutor;

    // TaskExecutor에 작업 제출
    executor.submit(() -> {
        Object result = callable.call();  // ← Virtual Thread에서 실행
        setConcurrentResultAndDispatch(result);
    });
}

// ⑤ ThreadPoolTaskExecutor.submit()
public void execute(Runnable task) {
    // TaskDecorator로 감싸기 (MDC 복사)
    task = this.taskDecorator.decorate(task);

    // Virtual Thread Executor 실행
    Executor executor = getThreadPoolExecutor();  // Executors.newVirtualThreadPerTaskExecutor()
    executor.execute(task);
}

// ⑥ Virtual Thread 생성 및 실행
// → Thread.ofVirtual().start(task)
// → Virtual Thread에서 callable.call() 실행
// → Service.processComplexLogic(message) 실행
```

### 스레드 전환 추적

```
[Tomcat Thread: http-nio-8080-exec-1]
   │
   ├─► Controller.virtualApi() 실행
   │   └─► return Callable 객체
   │
   ├─► CallableMethodReturnValueHandler.handleReturnValue()
   │   └─► WebAsyncManager.startCallableProcessing()
   │       └─► ★ Tomcat Thread는 여기서 반환됨
   │
   └─► (Tomcat Thread 해방, 다른 요청 처리 가능)

[Virtual Thread: VirtualThread[#456]]
   │
   ├─► taskExecutor.submit() 호출
   │   └─► MdcTaskDecorator.decorate()
   │       └─► MDC 복사
   │
   ├─► Thread.ofVirtual().start()
   │   └─► callable.call() 실행
   │       └─► Service.processComplexLogic()
   │           └─► Thread.sleep(1000)  ← Virtual Thread만 Block
   │
   └─► setConcurrentResultAndDispatch(result)
       └─► HTTP Response 생성
```

---

## 핵심 정리

### 1. Callable 감지

```java
// CallableMethodReturnValueHandler.java
public boolean supportsReturnType(MethodParameter returnType) {
    return Callable.class.isAssignableFrom(returnType.getParameterType());
}
```

**역할**: 반환 타입이 Callable인지 체크

---

### 2. 비동기 처리 시작

```java
// CallableMethodReturnValueHandler.java
WebAsyncUtils.getAsyncManager(webRequest)
    .startCallableProcessing(callable, mavContainer);
```

**역할**: WebAsyncManager에 Callable 전달

---

### 3. TaskExecutor 실행

```java
// WebAsyncManager.java
Future<?> future = this.taskExecutor.submit(() -> {
    Object result = callable.call();  // ← 여기서 실행
    setConcurrentResultAndDispatch(result);
});
```

**역할**: VirtualThreadConfig에서 설정한 TaskExecutor로 Callable 실행

---

### 4. Virtual Thread 생성

```java
// ThreadPoolTaskExecutor.java
protected Executor getThreadPoolExecutor() {
    if (this.virtualThreads) {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

**역할**: Virtual Thread Executor 반환

---

### 5. 실제 실행

```java
// Executors.newVirtualThreadPerTaskExecutor() 내부
public void execute(Runnable task) {
    Thread thread = factory.newThread(task);  // Virtual Thread 생성
    thread.start();  // Virtual Thread 시작
}
```

**역할**: Virtual Thread 생성 및 시작

---

## 설정 연결 고리

```
VirtualThreadConfig.configureAsyncSupport()
   │
   └─► configurer.setTaskExecutor(executor)
       │
       └─► AsyncSupportConfigurer 내부에서 저장
           │
           └─► RequestMappingHandlerAdapter 초기화 시
               │
               └─► WebAsyncManager.taskExecutor에 주입
                   │
                   └─► WebAsyncManager.startCallableProcessing()에서 사용
                       │
                       └─► this.taskExecutor.submit(...)
```

---

## 전체 코드 경로

```
1. @GetMapping("/virtual") → Callable 반환

2. DispatcherServlet.doDispatch()

3. RequestMappingHandlerAdapter.invokeHandlerMethod()

4. ServletInvocableHandlerMethod.invokeAndHandle()

5. HandlerMethodReturnValueHandlerComposite.handleReturnValue()

6. CallableMethodReturnValueHandler.handleReturnValue()
   ↓
   WebAsyncUtils.getAsyncManager(webRequest).startCallableProcessing(callable)

7. WebAsyncManager.startCallableProcessing()
   ↓
   this.taskExecutor.submit(() -> callable.call())

8. ThreadPoolTaskExecutor.submit()
   ↓
   getThreadPoolExecutor().execute(task)

9. Executors.newVirtualThreadPerTaskExecutor().execute()
   ↓
   Thread.ofVirtual().start(task)

10. [Virtual Thread] callable.call() 실행
```

---

## 참고 자료

- **Spring Framework GitHub**: https://github.com/spring-projects/spring-framework
- **CallableMethodReturnValueHandler**: `spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/CallableMethodReturnValueHandler.java`
- **WebAsyncManager**: `spring-web/src/main/java/org/springframework/web/context/request/async/WebAsyncManager.java`
- **ThreadPoolTaskExecutor**: `spring-context/src/main/java/org/springframework/scheduling/concurrent/ThreadPoolTaskExecutor.java`
- **Java Executors**: `java.util.concurrent.Executors`

---

**Last Updated**: 2025-12-19
