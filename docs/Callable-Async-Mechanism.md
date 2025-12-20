# Callable 비동기 처리 메커니즘 상세 분석

## 목차
1. [개요](#개요)
2. [Callable vs DeferredResult 비교](#callable-vs-deferredresult-비교)
3. [Spring MVC Callable 처리 흐름](#spring-mvc-callable-처리-흐름)
4. [핵심 컴포넌트 분석](#핵심-컴포넌트-분석)
5. [VirtualThreadConfig와의 통합](#virtualthreadconfig와의-통합)
6. [실제 소스 코드 분석](#실제-소스-코드-분석)
7. [완전한 실행 흐름](#완전한-실행-흐름)

---

## 개요

**질문**: Callable을 Controller 메서드의 반환 타입으로 사용하면 어떻게 비동기 처리가 되는가?

**답변**: Spring MVC가 Callable을 감지하고, 설정된 TaskExecutor(VirtualThreadExecutor)에서 Callable을 실행한 후, Servlet 3.0의 AsyncContext를 통해 비동기 응답을 처리합니다.

### Callable 방식의 특징

1. **Spring이 자동으로 실행**: 개발자는 Callable만 반환하면, Spring이 알아서 실행
2. **설정된 Executor 사용**: `AsyncTaskExecutor` Bean을 자동으로 사용
3. **간단한 코드**: DeferredResult보다 코드가 단순함
4. **Pull 모델**: Spring이 Callable에서 결과를 "당겨옴"

---

## Callable vs DeferredResult 비교

### 1. 코드 스타일 비교

#### Callable 방식
```java
@GetMapping("/callable")
public Callable<String> callableApi() {
    log.info("Controller Thread: {}", Thread.currentThread());

    // Callable 반환 → Spring이 알아서 실행
    return () -> {
        log.info("Worker Thread: {}", Thread.currentThread());
        Thread.sleep(1000);
        return "Result";
    };
}
```

#### DeferredResult 방식
```java
@GetMapping("/deferred")
public DeferredResult<String> deferredApi() {
    log.info("Controller Thread: {}", Thread.currentThread());

    DeferredResult<String> result = new DeferredResult<>();

    // 개발자가 직접 Executor에 제출하고 결과 설정
    executor.submit(() -> {
        log.info("Worker Thread: {}", Thread.currentThread());
        Thread.sleep(1000);
        result.setResult("Result");
    });

    return result;
}
```

### 2. 제어 흐름 비교

| 특성 | Callable | DeferredResult |
|------|----------|----------------|
| **실행 주체** | Spring이 자동 실행 | 개발자가 직접 제어 |
| **Executor 선택** | Spring이 자동 선택 | 개발자가 명시적 선택 가능 |
| **결과 설정** | return으로 자동 | setResult() 수동 호출 |
| **타임아웃 설정** | WebAsyncTask 필요 | 생성자에서 설정 |
| **제어 수준** | 낮음 (단순) | 높음 (유연) |
| **적합한 경우** | 단순 비동기 작업 | 복잡한 비동기 흐름, 이벤트 기반 |

### 3. 실행 모델

#### Callable: Pull 모델
```
Controller → Callable 반환 → Spring이 실행 → 결과 획득
```

#### DeferredResult: Push 모델
```
Controller → DeferredResult 반환 → 개발자가 실행 → 결과 푸시
```

---

## Spring MVC Callable 처리 흐름

### 전체 호출 흐름

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. HTTP 요청 도착                                                │
│    → Tomcat Thread Pool에서 Thread-1 할당                       │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 2. DispatcherServlet.doDispatch()                               │
│    - 요청 처리 시작                                              │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 3. RequestMappingHandlerAdapter.handle()                        │
│    - Controller 메서드 호출                                      │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 4. Controller 메서드 실행                                        │
│    - Callable<String> 객체 생성 및 반환                         │
│    - ⚠️ 주의: Callable은 아직 실행되지 않음! (람다 정의만)      │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 5. CallableMethodReturnValueHandler.handleReturnValue()         │
│    - Callable을 WebAsyncTask로 래핑                             │
│    - WebAsyncManager에게 전달                                   │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 6. WebAsyncManager.startCallableProcessing()                    │
│    - AsyncTaskExecutor 조회 (VirtualThreadExecutor)             │
│    - AsyncWebRequest.startAsync() 호출                          │
│    ✨ Tomcat Thread-1 해방!                                     │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 7. TaskExecutor.submit(Callable)                                │
│    - VirtualThreadExecutor가 Virtual Thread 생성                │
│    - ✨ 이 시점에 Callable.call() 실행 시작!                     │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 8. Virtual Thread에서 Callable.call() 실행                      │
│    - 비즈니스 로직 수행 (1초 대기)                               │
│    - 결과값 반환                                                 │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 9. Future.get() / FutureCallback 실행                           │
│    - Callable 결과 획득                                          │
│    - WebAsyncManager.setConcurrentResultAndDispatch()           │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 10. AsyncContext.dispatch()                                     │
│     - 새로운 Tomcat Thread-2 할당                               │
│     - DispatcherServlet으로 재진입                              │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 11. 응답 렌더링 및 전송                                          │
│     - Callable 결과를 HTTP 응답으로 변환                         │
│     - 클라이언트에게 전송                                        │
└─────────────────────────────────────────────────────────────────┘
```

---

## 핵심 컴포넌트 분석

### 1. CallableMethodReturnValueHandler

**위치**: `org.springframework.web.servlet.mvc.method.annotation.CallableMethodReturnValueHandler`

**역할**: Controller가 반환한 `Callable`을 감지하고 비동기 처리를 시작

#### 핵심 메서드: `handleReturnValue()`

```java
public class CallableMethodReturnValueHandler implements AsyncHandlerMethodReturnValueHandler {

    @Override
    public boolean isAsyncReturnValue(@Nullable Object returnValue, MethodParameter returnType) {
        return (returnValue != null && returnValue instanceof Callable);
    }

    @Override
    public void handleReturnValue(@Nullable Object returnValue,
            MethodParameter returnType,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest) throws Exception {

        if (returnValue == null) {
            mavContainer.setRequestHandled(true);
            return;
        }

        Callable<?> callable = (Callable<?>) returnValue;

        // ✨ 핵심: Callable을 WebAsyncTask로 래핑
        WebAsyncTask<?> webAsyncTask = new WebAsyncTask<>(callable);

        // ✨ WebAsyncManager를 통해 Callable 처리 시작
        WebAsyncUtils.getAsyncManager(webRequest)
            .startCallableProcessing(webAsyncTask, mavContainer);
    }
}
```

**핵심 동작**:
1. 반환값이 `Callable` 타입인지 확인
2. `Callable`을 `WebAsyncTask`로 래핑 (타임아웃, Executor 설정 가능)
3. `WebAsyncManager.startCallableProcessing()` 호출

---

### 2. WebAsyncTask

**위치**: `org.springframework.web.context.request.async.WebAsyncTask`

**역할**: Callable을 래핑하여 타임아웃, Executor, 콜백 등의 추가 설정 제공

```java
public class WebAsyncTask<V> {

    private final Callable<V> callable;

    @Nullable
    private Long timeout;

    @Nullable
    private AsyncTaskExecutor executor;

    @Nullable
    private String executorName;

    private final Map<Callable<?>, Object> completionCallbacks = new HashMap<>();

    // 생성자
    public WebAsyncTask(Callable<V> callable) {
        this.callable = callable;
    }

    public WebAsyncTask(long timeout, Callable<V> callable) {
        this(callable);
        this.timeout = timeout;
    }

    public WebAsyncTask(long timeout, String executorName, Callable<V> callable) {
        this(timeout, callable);
        this.executorName = executorName;
    }

    public WebAsyncTask(long timeout, AsyncTaskExecutor executor, Callable<V> callable) {
        this(timeout, callable);
        this.executor = executor;
    }

    // Callable 실행
    public Callable<V> getCallable() {
        return this.callable;
    }

    // Executor 설정
    @Nullable
    public AsyncTaskExecutor getExecutor() {
        if (this.executor != null) {
            return this.executor;
        }
        else if (this.executorName != null) {
            // executorName으로 Bean 조회
            return null; // BeanFactory에서 조회 필요
        }
        return null;
    }

    // 타임아웃 설정
    @Nullable
    public Long getTimeout() {
        return this.timeout;
    }
}
```

**핵심 기능**:
- Callable 래핑
- 타임아웃 설정
- 커스텀 Executor 지정 가능
- 완료/타임아웃/에러 콜백 등록

---

### 3. WebAsyncManager.startCallableProcessing()

**위치**: `org.springframework.web.context.request.async.WebAsyncManager`

**역할**: Callable 비동기 처리의 핵심 로직 담당

```java
public class WebAsyncManager {

    private AsyncWebRequest asyncWebRequest;

    @Nullable
    private AsyncTaskExecutor taskExecutor;

    public void startCallableProcessing(final WebAsyncTask<?> webAsyncTask, Object... processingContext)
            throws Exception {

        Assert.notNull(webAsyncTask, "WebAsyncTask must not be null");
        Assert.state(this.asyncWebRequest != null, "AsyncWebRequest must not be null");

        // ✨ 핵심 1: Executor 선택
        AsyncTaskExecutor executor = webAsyncTask.getExecutor();
        if (executor == null) {
            // WebAsyncTask에 Executor가 없으면 기본 Executor 사용
            executor = this.taskExecutor;
        }
        if (executor == null) {
            throw new IllegalStateException(
                "No TaskExecutor configured. " +
                "Either set an executor on the WebAsyncTask or configure a default executor.");
        }

        // ✨ 핵심 2: 타임아웃 설정
        Long timeout = webAsyncTask.getTimeout();
        if (timeout != null) {
            this.asyncWebRequest.setTimeout(timeout);
        }

        // ✨ 핵심 3: Callable 실행 준비
        List<CallableProcessingInterceptor> interceptors = new ArrayList<>();
        interceptors.add(webAsyncTask.getInterceptor());
        interceptors.addAll(this.callableInterceptors.values());

        final Callable<?> callable = webAsyncTask.getCallable();

        // ✨ 핵심 4: Callable을 AsyncTaskExecutor에 제출
        Callable<Object> interceptorChain =
            new CallableInterceptorChain(interceptors, callable);

        // ✨ 핵심 5: 비동기 시작 (Tomcat Thread 해방!)
        this.asyncWebRequest.startAsync();

        // ✨ 핵심 6: Executor에서 Callable 실행
        Future<?> future = executor.submit(() -> {
            Object result = null;
            try {
                // 인터셉터 체인을 통해 Callable 실행
                result = interceptorChain.call();
            }
            catch (Throwable ex) {
                result = ex;
            }

            // 결과를 WebAsyncManager에 설정하고 다시 디스패치
            setConcurrentResultAndDispatch(result);

            return result;
        });

        // 타임아웃 핸들러 등록
        this.asyncWebRequest.onTimeout(() -> {
            future.cancel(true);
            Object result = interceptorChain.triggerAfterTimeout();
            setConcurrentResultAndDispatch(result);
        });

        // 에러 핸들러 등록
        this.asyncWebRequest.onError(() -> {
            Object result = interceptorChain.triggerAfterError();
            setConcurrentResultAndDispatch(result);
        });
    }

    private void setConcurrentResultAndDispatch(Object result) {
        synchronized (WebAsyncManager.this) {
            this.concurrentResult = result;
        }

        // ✨ AsyncContext를 통해 다시 디스패치
        this.asyncWebRequest.dispatch();
    }
}
```

**핵심 동작**:
1. **Executor 선택**: WebAsyncTask의 Executor 또는 기본 Executor 사용
2. **타임아웃 설정**: WebAsyncTask의 타임아웃 적용
3. **비동기 시작**: `asyncWebRequest.startAsync()` 호출 → **Tomcat Thread 해방!**
4. **Callable 실행**: Executor에 Callable 제출 및 실행
5. **결과 처리**: Callable 완료 후 `dispatch()`로 응답 처리

---

## VirtualThreadConfig와의 통합

### 1. VirtualThreadConfig 설정

**파일**: `com.ocean.sc.vt.config.VirtualThreadConfig`

```java
@Configuration
@EnableAsync
public class VirtualThreadConfig implements WebMvcConfigurer {

    /**
     * Virtual Thread 기반 TaskExecutor
     */
    @Bean(name = "applicationTaskExecutor")
    public AsyncTaskExecutor applicationTaskExecutor() {
        TaskExecutorAdapter adapter = new TaskExecutorAdapter(
            Executors.newVirtualThreadPerTaskExecutor()
        );
        adapter.setTaskDecorator(new MdcTaskDecorator());
        return adapter;
    }

    /**
     * Spring MVC의 비동기 요청 처리 설정
     * Callable 반환 시 사용할 Executor 지정
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(30000); // 30초
        configurer.setTaskExecutor(applicationTaskExecutor()); // ✨ 핵심!
    }
}
```

**핵심**:
- `configureAsyncSupport()`에서 설정한 Executor가 Callable 실행에 사용됨
- 별도 설정이 없으면 Spring의 기본 `SimpleAsyncTaskExecutor` 사용 (Platform Thread)

---

### 2. Callable 실행 흐름

```
┌────────────────────────────────────────────────┐
│ Controller에서 Callable 반환                    │
│ return () -> { ... };                          │
└────────────────────────────────────────────────┘
                    ↓
┌────────────────────────────────────────────────┐
│ CallableMethodReturnValueHandler                │
│ - Callable을 WebAsyncTask로 래핑               │
└────────────────────────────────────────────────┘
                    ↓
┌────────────────────────────────────────────────┐
│ WebAsyncManager.startCallableProcessing()      │
│                                                 │
│ 1. Executor 선택:                              │
│    - WebAsyncTask.getExecutor() 확인           │
│    - 없으면 configureAsyncSupport()의 설정 사용│
│    - VirtualThreadExecutor 획득 ✨             │
│                                                 │
│ 2. asyncWebRequest.startAsync()                │
│    - Tomcat Thread 해방! ✨                     │
│                                                 │
│ 3. executor.submit(callable)                   │
│    - VirtualThreadExecutor에 Callable 제출     │
└────────────────────────────────────────────────┘
                    ↓
┌────────────────────────────────────────────────┐
│ VirtualThreadExecutor                           │
│ - Virtual Thread 생성                          │
│ - Callable.call() 실행                         │
└────────────────────────────────────────────────┘
                    ↓
┌────────────────────────────────────────────────┐
│ Virtual Thread에서 비즈니스 로직 실행          │
│ - Service 호출                                 │
│ - 1초 대기 (Virtual Thread만 블로킹)           │
│ - 결과 반환                                    │
└────────────────────────────────────────────────┘
                    ↓
┌────────────────────────────────────────────────┐
│ WebAsyncManager.setConcurrentResultAndDispatch()│
│ - 결과 저장                                     │
│ - AsyncContext.dispatch() 호출                 │
└────────────────────────────────────────────────┘
                    ↓
┌────────────────────────────────────────────────┐
│ DispatcherServlet (2차 호출)                   │
│ - 새로운 Tomcat Thread 할당                    │
│ - 결과로 응답 렌더링                           │
│ - 클라이언트에게 전송                          │
└────────────────────────────────────────────────┘
```

---

## 실제 소스 코드 분석

### Spring Framework GitHub 소스 참조

#### 1. CallableMethodReturnValueHandler

**파일**: `spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/CallableMethodReturnValueHandler.java`

**GitHub**: https://github.com/spring-projects/spring-framework/blob/main/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/CallableMethodReturnValueHandler.java

```java
public class CallableMethodReturnValueHandler implements AsyncHandlerMethodReturnValueHandler {

    @Override
    public boolean isAsyncReturnValue(@Nullable Object returnValue, MethodParameter returnType) {
        return (returnValue != null && returnValue instanceof Callable);
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return Callable.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
            ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {

        if (returnValue == null) {
            mavContainer.setRequestHandled(true);
            return;
        }

        Callable<?> callable = (Callable<?>) returnValue;

        // ✨ Callable을 WebAsyncTask로 래핑
        WebAsyncTask<?> webAsyncTask = new WebAsyncTask<>(callable);

        // ✨ WebAsyncManager를 통해 비동기 처리 시작
        WebAsyncUtils.getAsyncManager(webRequest)
            .startCallableProcessing(webAsyncTask, mavContainer);
    }
}
```

---

#### 2. WebAsyncManager.startCallableProcessing()

**파일**: `spring-web/src/main/java/org/springframework/web/context/request/async/WebAsyncManager.java`

**GitHub**: https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/web/context/request/async/WebAsyncManager.java

```java
public void startCallableProcessing(final WebAsyncTask<?> webAsyncTask, Object... processingContext)
        throws Exception {

    Assert.notNull(webAsyncTask, "WebAsyncTask must not be null");
    Assert.state(this.asyncWebRequest != null, "AsyncWebRequest must not be null");

    // ✨ 핵심 1: Executor 결정
    AsyncTaskExecutor executor = webAsyncTask.getExecutor();
    if (executor == null) {
        // WebAsyncTask에 명시적 Executor가 없으면
        // WebMvcConfigurer.configureAsyncSupport()에서 설정한 기본 Executor 사용
        executor = this.taskExecutor;
    }
    if (executor == null) {
        throw new IllegalStateException(
            "No TaskExecutor for Callable. " +
            "Consider setting a TaskExecutor on the WebAsyncTask or " +
            "configuring a default TaskExecutor as an application bean.");
    }

    // ✨ 핵심 2: 타임아웃 설정
    Long timeout = webAsyncTask.getTimeout();
    if (timeout != null) {
        this.asyncWebRequest.setTimeout(timeout);
    }

    // ✨ 핵심 3: 인터셉터 체인 구성
    List<CallableProcessingInterceptor> interceptors = new ArrayList<>();
    interceptors.add(webAsyncTask.getInterceptor());
    interceptors.addAll(this.callableInterceptors.values());

    final CallableInterceptorChain interceptorChain =
        new CallableInterceptorChain(interceptors);

    // ✨ 핵심 4: 비동기 시작 (Tomcat Thread 해방!)
    this.asyncWebRequest.startAsync();

    // ✨ 핵심 5: Callable을 Executor에 제출
    final Callable<?> callable = webAsyncTask.getCallable();

    Future<?> future = executor.submit(() -> {
        Object result = null;
        try {
            // 인터셉터 전처리
            interceptorChain.applyBeforeConcurrentHandling(this.asyncWebRequest, callable);

            // ✨ 핵심: Callable.call() 실행!
            result = callable.call();

            // 인터셉터 후처리
            result = interceptorChain.applyPostProcess(this.asyncWebRequest, callable, result);
        }
        catch (Throwable ex) {
            result = interceptorChain.applyPostProcess(this.asyncWebRequest, callable, ex);
        }

        // ✨ 결과 설정 및 재디스패치
        setConcurrentResultAndDispatch(result);

        return result;
    });

    // 타임아웃 핸들러
    this.asyncWebRequest.onTimeout(() -> {
        logger.debug("Async request timeout");
        Object result = interceptorChain.triggerAfterTimeout(this.asyncWebRequest, callable);
        if (result != CallableProcessingInterceptor.RESULT_NONE) {
            setConcurrentResultAndDispatch(result);
        }
    });

    // 에러 핸들러
    this.asyncWebRequest.onError(() -> {
        logger.debug("Async request error");
        Object result = interceptorChain.triggerAfterError(this.asyncWebRequest, callable,
            this.asyncWebRequest.getNativeRequest(HttpServletRequest.class).getAttribute("jakarta.servlet.error.exception"));
        setConcurrentResultAndDispatch(result);
    });

    // 완료 핸들러
    this.asyncWebRequest.onCompletion(() -> {
        interceptorChain.triggerAfterCompletion(this.asyncWebRequest, callable);
    });
}

private void setConcurrentResultAndDispatch(Object result) {
    synchronized (WebAsyncManager.this) {
        this.concurrentResult = result;
        this.concurrentResultContext = processingContext;
    }

    // ✨ AsyncContext를 통해 다시 디스패치
    if (this.asyncWebRequest.isAsyncComplete()) {
        return;
    }

    logger.trace("Dispatching concurrent result");
    this.asyncWebRequest.dispatch();
}
```

---

#### 3. WebAsyncTask

**파일**: `spring-web/src/main/java/org/springframework/web/context/request/async/WebAsyncTask.java`

**GitHub**: https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/web/context/request/async/WebAsyncTask.java

```java
public class WebAsyncTask<V> {

    private final Callable<V> callable;

    @Nullable
    private Long timeout;

    @Nullable
    private AsyncTaskExecutor executor;

    @Nullable
    private String executorName;

    private CallableProcessingInterceptor interceptor = new CallableProcessingInterceptor() {};

    /**
     * 기본 생성자: Callable만 지정
     */
    public WebAsyncTask(Callable<V> callable) {
        Assert.notNull(callable, "Callable must not be null");
        this.callable = callable;
    }

    /**
     * 타임아웃과 함께 생성
     */
    public WebAsyncTask(long timeout, Callable<V> callable) {
        this(callable);
        this.timeout = timeout;
    }

    /**
     * 타임아웃과 Executor 이름 지정
     */
    public WebAsyncTask(long timeout, String executorName, Callable<V> callable) {
        this(timeout, callable);
        this.executorName = executorName;
    }

    /**
     * 타임아웃과 Executor 인스턴스 지정
     */
    public WebAsyncTask(@Nullable Long timeout, @Nullable AsyncTaskExecutor executor, Callable<V> callable) {
        this(callable);
        this.timeout = timeout;
        this.executor = executor;
    }

    /**
     * Callable 반환
     */
    public Callable<V> getCallable() {
        return this.callable;
    }

    /**
     * Executor 반환
     * WebAsyncTask 생성 시 지정한 Executor 또는 null
     */
    @Nullable
    public AsyncTaskExecutor getExecutor() {
        return this.executor;
    }

    /**
     * 타임아웃 반환
     */
    @Nullable
    public Long getTimeout() {
        return this.timeout;
    }

    /**
     * 인터셉터 설정
     */
    public void setInterceptor(CallableProcessingInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    public CallableProcessingInterceptor getInterceptor() {
        return this.interceptor;
    }
}
```

---

## 완전한 실행 흐름

### 시간순 상세 흐름

#### Phase 1: 요청 수신 및 Controller 실행 (Tomcat Thread-1)

```java
// 1. DispatcherServlet.doDispatch()
protected void doDispatch(HttpServletRequest request, HttpServletResponse response) {
    // Handler 찾기
    HandlerExecutionChain mappedHandler = getHandler(request);

    // HandlerAdapter 가져오기
    HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler());

    // ✨ Handler(Controller) 실행
    ModelAndView mv = ha.handle(request, response, mappedHandler.getHandler());

    // 비동기 처리가 시작되었는지 확인
    if (asyncManager.isConcurrentHandlingStarted()) {
        // ✨ 비동기면 여기서 메서드 종료 → Tomcat Thread 해방!
        return;
    }

    // 동기 처리라면 View 렌더링 계속 진행...
}
```

```java
// 2. Controller 메서드 실행
@GetMapping("/api/demo/virtual")
public Callable<String> virtualThreadApi(@RequestParam String message) {
    log.info("Controller Thread: {}", Thread.currentThread()); // Tomcat Thread

    // ✨ Callable 객체 생성 (아직 실행 안됨!)
    return () -> {
        log.info("Worker Thread: {}", Thread.currentThread()); // Virtual Thread
        String result = demoService.processComplexLogic(message);
        return String.format("Virtual Thread Result: %s", result);
    };
}
```

---

#### Phase 2: Callable 감지 및 비동기 시작 (Tomcat Thread-1)

```java
// 3. CallableMethodReturnValueHandler.handleReturnValue()
public void handleReturnValue(Object returnValue, ...) {
    Callable<?> callable = (Callable<?>) returnValue;

    // ✨ WebAsyncTask로 래핑
    WebAsyncTask<?> webAsyncTask = new WebAsyncTask<>(callable);

    // ✨ 비동기 처리 시작
    WebAsyncUtils.getAsyncManager(webRequest)
        .startCallableProcessing(webAsyncTask, mavContainer);
}
```

```java
// 4. WebAsyncManager.startCallableProcessing()
public void startCallableProcessing(WebAsyncTask<?> webAsyncTask, ...) {
    // ✨ Executor 선택 (VirtualThreadExecutor)
    AsyncTaskExecutor executor = webAsyncTask.getExecutor();
    if (executor == null) {
        executor = this.taskExecutor; // configureAsyncSupport()의 설정
    }

    // ✨ 비동기 시작 - Tomcat Thread 해방!
    this.asyncWebRequest.startAsync();

    // ✨ Callable을 Executor에 제출
    Future<?> future = executor.submit(() -> {
        Object result = callable.call(); // ← 여기서 실제 실행!
        setConcurrentResultAndDispatch(result);
        return result;
    });
}
```

```java
// 5. StandardServletAsyncWebRequest.startAsync()
public void startAsync() {
    // ✨ Servlet 3.0 API 호출
    this.asyncContext = getRequest().startAsync(getRequest(), getResponse());

    // ✨ 이 시점에서 Tomcat Thread-1 해방!
}
```

---

#### Phase 3: Callable 실행 (Virtual Thread)

```java
// 6. VirtualThreadExecutor에서 실행
executor.submit(() -> {
    // ✨ 새로운 Virtual Thread 생성
    Thread workerThread = Thread.currentThread();
    log.info("Worker Thread: {}", workerThread); // VirtualThread-0

    // ✨ Callable.call() 실행
    Object result = callable.call();

    // 결과 처리
    setConcurrentResultAndDispatch(result);

    return result;
});
```

```java
// 7. Callable.call() 내부 (Virtual Thread)
() -> {
    log.info("Worker Thread: {}", Thread.currentThread());

    // ✨ 비즈니스 로직 실행 (1초 대기)
    // Virtual Thread만 블로킹, Tomcat Thread는 이미 해방됨!
    String result = demoService.processComplexLogic(message);

    return String.format("Virtual Thread Result: %s", result);
}
```

---

#### Phase 4: 결과 처리 및 재디스패치 (Virtual Thread → Tomcat Thread-2)

```java
// 8. WebAsyncManager.setConcurrentResultAndDispatch()
private void setConcurrentResultAndDispatch(Object result) {
    // ✨ 결과 저장
    synchronized (WebAsyncManager.this) {
        this.concurrentResult = result;
    }

    // ✨ AsyncContext를 통해 다시 디스패치
    this.asyncWebRequest.dispatch();
}
```

```java
// 9. AsyncContext.dispatch()
public void dispatch() {
    // ✨ Servlet Container에게 재디스패치 요청
    // 새로운 Tomcat Thread-2가 할당됨
    this.asyncContext.dispatch();
}
```

---

#### Phase 5: 응답 렌더링 (Tomcat Thread-2)

```java
// 10. DispatcherServlet.doDispatch() (2차 호출)
protected void doDispatch(HttpServletRequest request, HttpServletResponse response) {
    // ✨ 비동기 처리 완료 확인
    WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);

    if (asyncManager.hasConcurrentResult()) {
        // ✨ Callable 결과 가져오기
        Object result = asyncManager.getConcurrentResult();

        // ✨ 결과를 ModelAndView로 변환
        ModelAndView mv = getModelAndView(result);

        // ✨ View 렌더링
        render(mv, request, response);

        // ✨ 응답 전송
        return;
    }

    // ... 일반 처리 흐름
}
```

---

## 핵심 정리

### Callable 비동기 처리의 핵심

#### 1. **Spring이 자동으로 실행**
```java
// Controller는 Callable만 반환
return () -> { ... };

// Spring이 알아서:
// 1. Callable 감지
// 2. Executor에 제출
// 3. 결과 획득
// 4. 응답 처리
```

#### 2. **Tomcat Thread 해방 시점**
```
Controller 실행 (Thread-1)
    ↓
Callable 반환
    ↓
WebAsyncManager.startCallableProcessing()
    ↓
asyncWebRequest.startAsync() ✨ 여기서 Thread-1 해방!
    ↓
executor.submit(callable)
    ↓
Virtual Thread에서 Callable.call() 실행
```

#### 3. **VirtualThreadExecutor 통합**
```java
@Override
public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
    // ✨ 이 설정이 Callable 실행에 사용됨!
    configurer.setTaskExecutor(applicationTaskExecutor());
}
```

### Callable vs DeferredResult 다시 비교

| 관점 | Callable | DeferredResult |
|------|----------|----------------|
| **코드 작성** | `return () -> {...}` | `DeferredResult dr = new DeferredResult()` |
| **실행 주체** | Spring | 개발자 |
| **Executor 지정** | configureAsyncSupport() | 개발자가 직접 선택 |
| **결과 설정** | `return value` | `dr.setResult(value)` |
| **Tomcat Thread 해방** | startCallableProcessing() 시점 | startDeferredResultProcessing() 시점 |
| **실제 작업 시작** | executor.submit() 시점 | 개발자가 제출한 시점 |
| **유연성** | 낮음 (단순) | 높음 (복잡한 시나리오) |

---

## 결론

**Callable 비동기 처리 메커니즘**:

1. **Controller가 Callable 반환**
2. **CallableMethodReturnValueHandler가 감지**
3. **WebAsyncManager.startCallableProcessing() 호출**
   - `asyncWebRequest.startAsync()` → **Tomcat Thread 해방!**
   - `executor.submit(callable)` → Virtual Thread에서 실행
4. **Callable.call() 실행** (Virtual Thread)
5. **결과 반환 후 AsyncContext.dispatch()**
6. **새로운 Tomcat Thread에서 응답 렌더링**

**핵심**: Servlet 3.0의 `AsyncContext`를 활용하여 Tomcat Thread를 즉시 해방하고, 설정된 VirtualThreadExecutor에서 Callable을 실행하여 비동기 처리를 완성합니다.
