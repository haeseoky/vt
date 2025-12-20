# DeferredResult 비동기 처리 메커니즘 상세 분석

## 목차
1. [개요](#개요)
2. [Spring MVC 비동기 처리 흐름](#spring-mvc-비동기-처리-흐름)
3. [핵심 컴포넌트 분석](#핵심-컴포넌트-분석)
4. [Tomcat Thread 해방 메커니즘](#tomcat-thread-해방-메커니즘)
5. [실제 소스 코드 분석](#실제-소스-코드-분석)

---

## 개요

**질문**: DeferredResult를 반환하면 왜 Tomcat Thread가 즉시 해방되는가?

**답변**: Servlet 3.0의 비동기 처리 API (`AsyncContext`)를 Spring이 활용하기 때문입니다.

### 핵심 메커니즘
1. Controller가 `DeferredResult`를 반환
2. Spring이 `request.startAsync()` 호출 (Servlet 3.0 API)
3. **Tomcat이 요청 처리 스레드를 즉시 해방**
4. 작업 스레드에서 `DeferredResult.setResult()` 호출
5. Servlet Container가 다시 요청을 디스패치하여 응답 처리

---

## Spring MVC 비동기 처리 흐름

### 전체 호출 흐름

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. HTTP 요청 도착                                                │
│    → Tomcat Thread Pool에서 Thread 할당                          │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 2. DispatcherServlet.doDispatch()                               │
│    - 요청 처리 시작                                              │
│    - HandlerMapping으로 Controller 찾기                         │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 3. RequestMappingHandlerAdapter.handle()                        │
│    - Controller 메서드 호출 준비                                │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 4. ServletInvocableHandlerMethod.invokeAndHandle()              │
│    - 실제 Controller 메서드 실행                                │
│    - 반환값: DeferredResult<String>                             │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 5. DeferredResultMethodReturnValueHandler.handleReturnValue()   │
│    - DeferredResult 감지 및 처리                                │
│    - WebAsyncManager에게 비동기 처리 위임                       │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 6. WebAsyncManager.startDeferredResultProcessing()              │
│    - DeferredResult 콜백 등록                                   │
│    - AsyncWebRequest.startAsync() 호출                          │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 7. StandardServletAsyncWebRequest.startAsync()                  │
│    - HttpServletRequest.startAsync() 호출 (Servlet 3.0 API)    │
│    - AsyncContext 생성                                          │
│    ✨ **여기서 Tomcat Thread 해방!**                            │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 8. DispatcherServlet.doDispatch() 종료                          │
│    - Tomcat Thread가 Thread Pool로 반환됨                       │
│    - 다른 요청 처리 가능                                         │
└─────────────────────────────────────────────────────────────────┘

        [Worker Thread에서 비동기 작업 실행]
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 9. DeferredResult.setResult(value)                              │
│    - Worker Thread (Virtual Thread)에서 호출                    │
│    - 등록된 콜백 실행                                            │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 10. AsyncContext.dispatch()                                     │
│     - Servlet Container가 요청을 다시 디스패치                  │
│     - 새로운 Tomcat Thread 할당 (다를 수 있음)                  │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ 11. DispatcherServlet.doDispatch() (2차 호출)                   │
│     - 비동기 처리 완료 감지                                      │
│     - 결과값으로 응답 렌더링                                     │
│     - 클라이언트에게 응답 전송                                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 핵심 컴포넌트 분석

### 1. DeferredResultMethodReturnValueHandler

**위치**: `org.springframework.web.servlet.mvc.method.annotation.DeferredResultMethodReturnValueHandler`

**역할**: Controller가 반환한 `DeferredResult`를 감지하고 비동기 처리를 시작

#### 핵심 메서드: `handleReturnValue()`

```java
public class DeferredResultMethodReturnValueHandler implements HandlerMethodReturnValueHandler {

    @Override
    public void handleReturnValue(@Nullable Object returnValue,
            MethodParameter returnType,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest) throws Exception {

        if (returnValue == null) {
            mavContainer.setRequestHandled(true);
            return;
        }

        DeferredResult<?> result = (DeferredResult<?>) returnValue;

        // ✨ 핵심: WebAsyncManager를 통해 비동기 처리 시작
        WebAsyncUtils.getAsyncManager(webRequest)
            .startDeferredResultProcessing(result, mavContainer);
    }
}
```

**핵심 동작**:
1. 반환값이 `DeferredResult` 타입인지 확인
2. `WebAsyncManager`를 가져옴
3. `startDeferredResultProcessing()` 호출하여 비동기 처리 시작

---

### 2. WebAsyncManager

**위치**: `org.springframework.web.context.request.async.WebAsyncManager`

**역할**: Spring의 비동기 웹 요청 처리를 관리하는 중앙 관리자

#### 핵심 메서드: `startDeferredResultProcessing()`

```java
public class WebAsyncManager {

    private AsyncWebRequest asyncWebRequest;

    public void startDeferredResultProcessing(
            final DeferredResult<?> deferredResult,
            Object... processingContext) throws Exception {

        Assert.notNull(deferredResult, "DeferredResult must not be null");
        Assert.state(this.asyncWebRequest != null, "AsyncWebRequest must not be null");

        Long timeout = deferredResult.getTimeoutValue();
        if (timeout != null) {
            this.asyncWebRequest.setTimeout(timeout);
        }

        // ✨ 핵심 1: DeferredResult에 콜백 등록
        deferredResult.setResultHandler(result -> {
            // 결과가 설정되면 이 콜백이 호출됨
            setConcurrentResultAndDispatch(result);
        });

        // ✨ 핵심 2: Servlet 3.0 비동기 처리 시작
        // 여기서 Tomcat Thread가 해방됨!
        this.asyncWebRequest.startAsync();

        // DeferredResult가 이미 설정되었는지 확인
        if (deferredResult.isSetOrExpired()) {
            return;
        }

        // 타임아웃 핸들러 등록
        deferredResult.setTimeoutHandler(() -> {
            handleTimeout();
        });
    }

    private void setConcurrentResultAndDispatch(Object result) {
        this.concurrentResult = result;
        // AsyncContext를 통해 다시 디스패치
        this.asyncWebRequest.dispatch();
    }
}
```

**핵심 동작**:
1. `DeferredResult`에 결과 핸들러 콜백 등록
2. **`asyncWebRequest.startAsync()` 호출** → 여기서 Tomcat Thread 해방!
3. 타임아웃 핸들러 설정
4. 결과가 설정되면 `dispatch()`로 다시 요청 처리

---

### 3. StandardServletAsyncWebRequest

**위치**: `org.springframework.web.context.request.async.StandardServletAsyncWebRequest`

**역할**: Servlet 3.0의 `AsyncContext` API를 Spring에서 사용할 수 있도록 래핑

#### 핵심 메서드: `startAsync()`

```java
public class StandardServletAsyncWebRequest extends ServletWebRequest
        implements AsyncWebRequest {

    private AsyncContext asyncContext;

    @Override
    public void startAsync() {
        Assert.state(getRequest().isAsyncSupported(),
            "Async support must be enabled on a servlet and for all filters involved " +
            "in async request processing.");

        // ✨ 핵심: Servlet 3.0 비동기 API 호출
        // 이 메서드가 호출되면 Tomcat이 현재 스레드를 해방함!
        if (this.asyncContext == null) {
            this.asyncContext = getRequest().startAsync(
                getRequest(), getResponse());
        }

        // 타임아웃 설정
        this.asyncContext.setTimeout(this.timeout);

        // 완료 리스너 등록
        this.asyncContext.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                asyncCompleted = true;
                asyncContext = null;
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                onTimeout();
            }

            // ... 기타 리스너 메서드
        });
    }

    @Override
    public void dispatch() {
        // 비동기 작업이 완료되면 다시 디스패치
        this.asyncContext.dispatch();
    }
}
```

**핵심 동작**:
1. **`HttpServletRequest.startAsync()` 호출** (Servlet 3.0 표준 API)
2. Servlet Container(Tomcat)가 현재 요청 처리 스레드를 해방
3. `AsyncContext` 생성 및 저장
4. 타임아웃 및 리스너 설정

---

## Tomcat Thread 해방 메커니즘

### Servlet 3.0 AsyncContext의 동작 원리

#### 1. 일반 동기 요청 처리

```
[Client] ──HTTP Request──> [Tomcat Thread Pool]
                                    ↓
                           [Thread-1 할당]
                                    ↓
                           [요청 처리 시작]
                                    ↓
                           [Controller 실행]
                                    ↓
                           [Service 로직 - 1초 대기]
                                    ↓ (Thread-1 블로킹 1초!)
                           [응답 생성]
                                    ↓
                           [응답 전송]
                                    ↓
[Client] <──HTTP Response── [Thread-1 반환]
```

**문제점**: Thread-1이 1초 동안 블로킹되어 다른 요청을 처리할 수 없음

---

#### 2. DeferredResult 비동기 요청 처리

```
[Client] ──HTTP Request──> [Tomcat Thread Pool]
                                    ↓
                           [Thread-1 할당]
                                    ↓
                           [요청 처리 시작]
                                    ↓
                           [Controller 실행]
                                    ↓
                           [DeferredResult 반환]
                                    ↓
                           [request.startAsync()] ✨
                                    ↓
                           [Thread-1 즉시 해방!] ←─┐
                                    ↓              │
                           [Thread Pool로 반환]   │
                                                   │
                                                   │
    ┌──────────────────────────────────────────────┘
    │
    │ [Virtual Thread에서 비동기 작업]
    │           ↓
    │ [Service 로직 - 1초 대기]
    │           ↓ (Virtual Thread만 블로킹)
    │ [DeferredResult.setResult()]
    │           ↓
    │ [AsyncContext.dispatch()]
    │           ↓
    └────> [Tomcat Thread Pool]
                   ↓
           [Thread-2 할당] (다른 스레드 사용 가능)
                   ↓
           [응답 렌더링]
                   ↓
           [응답 전송]
                   ↓
[Client] <──HTTP Response── [Thread-2 반환]
```

**장점**:
- Thread-1은 즉시 다른 요청 처리 가능
- Virtual Thread가 1초 동안 블로킹되지만 경량 스레드라 수천~수만 개 생성 가능
- 전체 처리량(Throughput) 대폭 증가

---

### HttpServletRequest.startAsync() 내부 동작

#### Tomcat의 구현 (org.apache.catalina.connector.Request)

```java
public class Request implements HttpServletRequest {

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        return startAsync(getRequest(), getResponse().getResponse());
    }

    @Override
    public AsyncContext startAsync(ServletRequest request, ServletResponse response)
            throws IllegalStateException {

        if (!isAsyncSupported()) {
            throw new IllegalStateException("Async not supported");
        }

        // ✨ 핵심: AsyncContext 생성
        AsyncContextImpl asyncContext = new AsyncContextImpl(this);

        // ✨ 핵심: 현재 요청을 비동기 모드로 전환
        // 이 시점에서 Tomcat이 현재 스레드를 해방하기로 결정!
        asyncContext.setStarted(getContext(), request, response);

        // CoyoteAdapter에게 비동기 시작 알림
        this.asyncContext = asyncContext;

        return asyncContext;
    }
}
```

#### Tomcat CoyoteAdapter의 처리

```java
public class CoyoteAdapter implements Adapter {

    @Override
    public void service(Request req, Response res) throws Exception {

        // ... 요청 처리

        // ✨ 핵심 체크: 비동기가 시작되었는가?
        if (request.isAsync()) {
            // 비동기 모드: 현재 스레드를 즉시 반환!
            // Response를 커밋하지 않고 스레드를 Thread Pool에 반환
            async = true;
        }

        if (!async) {
            // 동기 모드: 응답 완료 후 스레드 반환
            postParseRequest(req, request, res, response);
        }

        // ✨ 비동기 모드면 여기서 스레드 해방!
    }
}
```

---

## 실제 소스 코드 분석

### Spring Framework GitHub 소스 참조

#### 1. DeferredResultMethodReturnValueHandler

**파일**: `spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/DeferredResultMethodReturnValueHandler.java`

**GitHub**: https://github.com/spring-projects/spring-framework/blob/main/spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/DeferredResultMethodReturnValueHandler.java

```java
@Override
public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
        ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {

    if (returnValue == null) {
        mavContainer.setRequestHandled(true);
        return;
    }

    DeferredResult<?> result;
    if (returnValue instanceof DeferredResult<?> deferredResult) {
        result = deferredResult;
    }
    else if (returnValue instanceof ListenableFuture<?> listenableFuture) {
        result = adaptListenableFuture(listenableFuture);
    }
    else if (returnValue instanceof CompletionStage<?> completionStage) {
        result = adaptCompletionStage(completionStage);
    }
    else {
        // Should not happen...
        throw new IllegalStateException("Unexpected return value type: " + returnValue);
    }

    // ✨ 핵심 라인
    WebAsyncUtils.getAsyncManager(webRequest)
        .startDeferredResultProcessing(result, mavContainer);
}
```

---

#### 2. WebAsyncManager.startDeferredResultProcessing()

**파일**: `spring-web/src/main/java/org/springframework/web/context/request/async/WebAsyncManager.java`

**GitHub**: https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/web/context/request/async/WebAsyncManager.java

```java
public void startDeferredResultProcessing(
        final DeferredResult<?> deferredResult, Object... processingContext) throws Exception {

    Assert.notNull(deferredResult, "DeferredResult must not be null");
    Assert.state(this.asyncWebRequest != null, "AsyncWebRequest must not be null");

    Long timeout = deferredResult.getTimeoutValue();
    if (timeout != null) {
        this.asyncWebRequest.setTimeout(timeout);
    }

    // ✨ 핵심 1: 결과 핸들러 등록
    List<DeferredResultProcessingInterceptor> interceptors = new ArrayList<>();
    interceptors.add(deferredResult.getInterceptor());
    interceptors.addAll(this.deferredResultInterceptors.values());

    final DeferredResultInterceptorChain interceptorChain =
        new DeferredResultInterceptorChain(interceptors);

    deferredResult.setResultHandler(result -> {
        result = interceptorChain.triggerAfterCompletion(this.asyncWebRequest, deferredResult, result);
        setConcurrentResultAndDispatch(result);
    });

    // ✨ 핵심 2: 비동기 시작 (Tomcat Thread 해방!)
    interceptorChain.applyBeforeConcurrentHandling(this.asyncWebRequest, deferredResult);
    startAsyncProcessing(processingContext);

    // ... 타임아웃 핸들러 등록
}

private void startAsyncProcessing(Object[] processingContext) {
    synchronized (WebAsyncManager.this) {
        this.concurrentResult = RESULT_NONE;
    }

    // ✨ 핵심: AsyncWebRequest.startAsync() 호출
    this.asyncWebRequest.startAsync();

    // ... 기타 설정
}
```

---

#### 3. StandardServletAsyncWebRequest.startAsync()

**파일**: `spring-web/src/main/java/org/springframework/web/context/request/async/StandardServletAsyncWebRequest.java`

**GitHub**: https://github.com/spring-projects/spring-framework/blob/main/spring-web/src/main/java/org/springframework/web/context/request/async/StandardServletAsyncWebRequest.java

```java
@Override
public void startAsync() {
    Assert.state(getRequest().isAsyncSupported(),
        "Async support must be enabled on a servlet and for all filters involved " +
        "in async request processing. This is done in Java code using the Servlet API " +
        "or by adding \"<async-supported>true</async-supported>\" to servlet and " +
        "filter declarations in web.xml.");

    Assert.state(!isAsyncComplete(), "Async processing has already completed");

    // ✨ 핵심: Servlet 3.0 표준 API 호출
    if (this.asyncContext == null) {
        this.asyncContext = getRequest().startAsync(getRequest(), getResponse());
        this.asyncContext.addListener(new AsyncRequestListener());
        if (this.timeout != null) {
            this.asyncContext.setTimeout(this.timeout);
        }
    }
}

@Override
public void dispatch() {
    Assert.notNull(this.asyncContext, "Cannot dispatch without an AsyncContext");
    // ✨ 작업 완료 후 다시 디스패치
    this.asyncContext.dispatch();
}
```

---

## 정리

### DeferredResult가 Tomcat Thread를 해방시키는 원리

1. **Controller가 DeferredResult 반환**
   - Spring MVC가 이를 감지

2. **DeferredResultMethodReturnValueHandler 처리**
   - `WebAsyncManager.startDeferredResultProcessing()` 호출

3. **WebAsyncManager가 비동기 시작**
   - `asyncWebRequest.startAsync()` 호출

4. **StandardServletAsyncWebRequest가 Servlet API 호출**
   - `HttpServletRequest.startAsync()` 실행 (Servlet 3.0 표준)

5. **✨ Tomcat이 현재 스레드를 즉시 해방**
   - `AsyncContext` 생성
   - 요청을 비동기 모드로 전환
   - **현재 Tomcat Thread를 Thread Pool에 반환**
   - 다른 요청 처리 가능

6. **Worker Thread에서 작업 수행**
   - Virtual Thread에서 비즈니스 로직 실행
   - `DeferredResult.setResult()` 호출

7. **AsyncContext.dispatch() 호출**
   - Servlet Container가 요청을 다시 디스패치
   - 새로운 Tomcat Thread 할당 (기존과 다를 수 있음)

8. **응답 렌더링 및 전송**
   - DispatcherServlet이 결과를 처리
   - 클라이언트에게 응답 전송

---

### 핵심 요약

**Tomcat Thread 해방의 핵심**: `HttpServletRequest.startAsync()` (Servlet 3.0 API)

```java
// Spring Framework 코드 흐름
Controller (DeferredResult 반환)
    ↓
DeferredResultMethodReturnValueHandler
    ↓
WebAsyncManager.startDeferredResultProcessing()
    ↓
StandardServletAsyncWebRequest.startAsync()
    ↓
HttpServletRequest.startAsync()  // ✨ Servlet 3.0 표준 API
    ↓
Tomcat AsyncContext 생성
    ↓
✨ Tomcat Thread 즉시 해방! ✨
```

이것이 DeferredResult를 사용하면 Tomcat Thread가 즉시 해방되는 이유입니다.
