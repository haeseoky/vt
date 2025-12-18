# Callable → Virtual Thread 동작 메커니즘

Controller에서 `Callable<T>`를 반환할 때 어떻게 Virtual Thread로 실행되는지 상세히 설명합니다.

## 목차

- [전체 흐름 개요](#전체-흐름-개요)
- [단계별 상세 설명](#단계별-상세-설명)
- [핵심 컴포넌트](#핵심-컴포넌트)
- [코드 레벨 분석](#코드-레벨-분석)
- [디버깅으로 확인하기](#디버깅으로-확인하기)

---

## 전체 흐름 개요

```
1. [Client] HTTP Request
   │
   ▼
2. [Tomcat Thread] DispatcherServlet 진입
   │
   ▼
3. [Tomcat Thread] Controller 메서드 호출
   │
   ├─► Controller returns Callable<String>
   │   (실제 로직은 아직 실행 안 됨)
   │
   ▼
4. [Spring MVC] Callable 반환 감지
   │
   ├─► RequestMappingHandlerAdapter
   │   └─► ServletInvocableHandlerMethod
   │       └─► CallableMethodReturnValueHandler 실행
   │
   ▼
5. [Spring MVC] WebAsyncManager 시작
   │
   ├─► startCallableProcessing(Callable, ...)
   │   └─► Tomcat Thread 해방 (return)
   │
   ▼
6. [Spring MVC] TaskExecutor 조회
   │
   ├─► VirtualThreadConfig에서 설정한 TaskExecutor 사용
   │   └─► ThreadPoolTaskExecutor (virtualThreads=true)
   │
   ▼
7. [TaskExecutor] Virtual Thread 생성
   │
   ├─► MdcTaskDecorator.decorate(Callable)
   │   ├─► MDC 정보 복사
   │   └─► Callable을 감싼 Runnable 반환
   │
   ▼
8. [Virtual Thread] Callable.call() 실행
   │
   ├─► Service 로직 수행
   │   └─► Thread.sleep(1000)  ← Virtual Thread만 Block
   │
   ▼
9. [Virtual Thread] 결과 반환
   │
   ├─► WebAsyncManager에게 결과 전달
   │
   ▼
10. [Tomcat Thread] 응답 렌더링
    │
    ├─► HTTP Response 생성
    │
    ▼
11. [Client] HTTP Response 수신
```

---

## 단계별 상세 설명

### 1단계: Controller에서 Callable 반환

**코드:**
```java
@GetMapping("/virtual")
public Callable<String> virtualThreadApi(@RequestParam String message) {
    log.info("Controller Thread (Tomcat): {}", Thread.currentThread());

    // Callable 객체를 생성하여 반환 (아직 실행 안 됨)
    return () -> {
        log.info("Worker Thread (Virtual): {}", Thread.currentThread());
        return demoService.processComplexLogic(message);
    };
}
```

**핵심 포인트:**
- Callable 객체만 생성, **실제 로직은 실행되지 않음**
- Lambda 표현식은 Callable 인터페이스를 구현한 익명 클래스
- Tomcat Thread는 여기서 메서드를 빠져나감

---

### 2단계: Spring MVC의 Callable 감지

**Spring MVC 내부 처리:**

```java
// RequestMappingHandlerAdapter.java (Spring Framework)
protected ModelAndView invokeHandlerMethod(...) {
    // Controller 메서드 실행
    Object returnValue = invocableMethod.invokeAndHandle(...);

    // 반환값이 Callable인지 확인
    if (returnValue instanceof Callable) {
        // WebAsyncManager를 통해 비동기 처리 시작
        WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
        asyncManager.startCallableProcessing((Callable<?>) returnValue);

        // Tomcat Thread 즉시 반환 (해방)
        return null;
    }
}
```

**핵심 포인트:**
- `RequestMappingHandlerAdapter`가 반환값 타입 체크
- Callable이면 `WebAsyncManager.startCallableProcessing()` 호출
- Tomcat Thread는 여기서 **즉시 반환되어 다른 요청 처리 가능**

---

### 3단계: WebAsyncManager의 비동기 처리 시작

**Spring MVC 내부:**

```java
// WebAsyncManager.java (Spring Framework)
public void startCallableProcessing(final Callable<?> callable, Object... processingContext) {
    // AsyncTaskExecutor 조회
    AsyncTaskExecutor executor = this.taskExecutor;  // ← VirtualThreadConfig에서 설정한 Executor

    if (executor == null) {
        executor = getDefaultTaskExecutor();  // 기본 Executor
    }

    // Callable을 WebAsyncTask로 감싸기
    WebAsyncTask<?> asyncTask = new WebAsyncTask<>(timeout, executorName, callable);

    // Executor에 작업 제출
    executor.submit(() -> {
        try {
            Object result = callable.call();  // ← 이 시점에 Callable 실행
            // 결과를 WebAsyncManager에 전달
            setConcurrentResult(result);
        } catch (Exception ex) {
            setConcurrentResult(ex);
        }
    });
}
```

**핵심 포인트:**
- `this.taskExecutor`는 `VirtualThreadConfig`에서 설정한 TaskExecutor
- `executor.submit()` 호출 시 새로운 스레드에서 Callable 실행

---

### 4단계: VirtualThreadConfig의 TaskExecutor 사용

**우리가 작성한 설정:**

```java
@Configuration
public class VirtualThreadConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // ★ 핵심: Virtual Thread 활성화
        executor.setVirtualThreads(true);

        // MDC 복사를 위한 Decorator
        executor.setTaskDecorator(new MdcTaskDecorator());

        executor.initialize();

        // ★ Spring MVC에 이 Executor 등록
        configurer.setTaskExecutor(executor);

        configurer.setDefaultTimeout(30000);
    }
}
```

**핵심 포인트:**
- `configurer.setTaskExecutor(executor)`가 핵심
- 이 설정이 `WebAsyncManager.taskExecutor`에 주입됨
- `setVirtualThreads(true)` 설정으로 Virtual Thread 사용

---

### 5단계: ThreadPoolTaskExecutor의 Virtual Thread 생성

**Spring Framework 내부 (ThreadPoolTaskExecutor.java):**

```java
// ThreadPoolTaskExecutor.java (Spring Framework 6.1+)
@Override
public void execute(Runnable task) {
    Executor executor = getThreadPoolExecutor();

    try {
        executor.execute(task);
    } catch (RejectedExecutionException ex) {
        throw new TaskRejectedException("Executor rejected task", ex);
    }
}

protected Executor getThreadPoolExecutor() {
    if (this.virtualThreads) {
        // ★ Virtual Thread Executor 사용
        return Executors.newVirtualThreadPerTaskExecutor();
    } else {
        // Platform Thread Pool 사용
        return super.getThreadPoolExecutor();
    }
}
```

**핵심 포인트:**
- `virtualThreads=true`면 `Executors.newVirtualThreadPerTaskExecutor()` 사용
- 이 Executor는 **요청마다 새로운 Virtual Thread 생성**
- Virtual Thread는 JVM이 관리하는 경량 스레드

---

### 6단계: MdcTaskDecorator의 MDC 복사

**우리가 작성한 Decorator:**

```java
public static class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 1. 현재 스레드(Tomcat Thread)의 MDC 정보 복사
        Map<String, String> contextMap = MDC.getCopyOfContextMap();

        // 2. 새로운 Runnable을 반환 (Original Runnable을 감쌈)
        return () -> {
            try {
                // 3. Virtual Thread에 MDC 정보 주입
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }

                // 4. 실제 작업 실행 (Callable.call())
                runnable.run();

            } finally {
                // 5. Virtual Thread 종료 시 MDC 정리
                MDC.clear();
            }
        };
    }
}
```

**실행 흐름:**

```
Tomcat Thread (MDC: {traceId=123, userId=user1})
   │
   ├─► MdcTaskDecorator.decorate()
   │   └─► contextMap = {traceId=123, userId=user1} 복사
   │
   ▼
Virtual Thread 생성
   │
   ├─► MDC.setContextMap(contextMap)
   │   └─► MDC: {traceId=123, userId=user1} 주입
   │
   ├─► runnable.run()
   │   └─► Callable.call()
   │       └─► Service 로직 실행
   │           └─► 로그 출력 시 traceId=123 포함
   │
   └─► MDC.clear()  (finally 블록)
```

---

### 7단계: Virtual Thread에서 Callable 실행

**실제 실행:**

```java
// Virtual Thread에서 실행
() -> {
    log.info("Worker Thread (Virtual): {}", Thread.currentThread());
    // 출력: Worker Thread (Virtual): VirtualThread[#456]/runnable@ForkJoinPool-1-worker-1

    // Service 로직 실행
    String result = demoService.processComplexLogic(message);

    return result;
}
```

**스레드 확인:**
```java
Thread currentThread = Thread.currentThread();
System.out.println("Thread Name: " + currentThread.getName());
System.out.println("Is Virtual: " + currentThread.isVirtual());
System.out.println("Thread ID: " + currentThread.threadId());

// 출력:
// Thread Name: VirtualThread[#456]
// Is Virtual: true
// Thread ID: 456
```

---

## 핵심 컴포넌트

### 1. WebMvcConfigurer 인터페이스

Spring MVC의 설정을 커스터마이징하는 표준 인터페이스

```java
public interface WebMvcConfigurer {
    default void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 비동기 처리 설정
    }
}
```

---

### 2. AsyncSupportConfigurer 클래스

비동기 처리 관련 설정을 담당

```java
public class AsyncSupportConfigurer {
    // TaskExecutor 설정
    public void setTaskExecutor(AsyncTaskExecutor taskExecutor);

    // 타임아웃 설정
    public void setDefaultTimeout(long timeout);

    // 인터셉터 등록
    public void registerCallableInterceptors(CallableProcessingInterceptor... interceptors);
}
```

---

### 3. WebAsyncManager 클래스

비동기 요청의 생명주기 관리

```java
public final class WebAsyncManager {
    // 등록된 TaskExecutor
    private AsyncTaskExecutor taskExecutor;

    // Callable 처리 시작
    public void startCallableProcessing(Callable<?> callable) {
        // TaskExecutor에 작업 제출
        this.taskExecutor.submit(() -> {
            Object result = callable.call();
            setConcurrentResult(result);
        });
    }

    // 비동기 결과 설정
    public void setConcurrentResult(Object result) {
        // 결과를 저장하고 요청 처리 재개
    }
}
```

---

### 4. ThreadPoolTaskExecutor 클래스

Spring의 TaskExecutor 구현체

```java
public class ThreadPoolTaskExecutor extends ExecutorConfigurationSupport {
    private boolean virtualThreads = false;

    public void setVirtualThreads(boolean virtualThreads) {
        this.virtualThreads = virtualThreads;
    }

    @Override
    protected Executor getThreadPoolExecutor() {
        if (this.virtualThreads) {
            // Virtual Thread Executor
            return Executors.newVirtualThreadPerTaskExecutor();
        } else {
            // Platform Thread Pool
            return new ThreadPoolExecutor(...);
        }
    }
}
```

---

## 코드 레벨 분석

### 비교: Platform Thread vs Virtual Thread

#### Platform Thread (동기 방식)

```java
@GetMapping("/platform")
public String platformApi(@RequestParam String message) {
    // ① Tomcat Thread가 직접 실행
    Thread current = Thread.currentThread();
    System.out.println("Thread: " + current);  // Thread[http-nio-8080-exec-1]

    // ② Service 로직 실행 (Tomcat Thread가 Block)
    String result = service.processWithDelay(message);  // 1초 대기

    // ③ Tomcat Thread가 결과 반환
    return result;
}
```

**흐름:**
```
Tomcat Thread [exec-1]
   │
   ├─► Controller.platformApi()
   │   └─► Service.processWithDelay()
   │       └─► Thread.sleep(1000)  ← Tomcat Thread Block
   │           └─► return result
   │
   └─► HTTP Response
```

---

#### Virtual Thread (비동기 방식)

```java
@GetMapping("/virtual")
public Callable<String> virtualApi(@RequestParam String message) {
    // ① Tomcat Thread가 Callable 생성만 하고 즉시 반환
    Thread current = Thread.currentThread();
    System.out.println("Thread: " + current);  // Thread[http-nio-8080-exec-1]

    // ② Callable 객체 반환 (실행 안 됨)
    return () -> {
        // ③ Virtual Thread에서 실행
        Thread worker = Thread.currentThread();
        System.out.println("Thread: " + worker);  // VirtualThread[#456]

        // ④ Service 로직 실행 (Virtual Thread만 Block)
        String result = service.processComplexLogic(message);  // 1초 대기

        // ⑤ Virtual Thread가 결과 반환
        return result;
    };
}
```

**흐름:**
```
Tomcat Thread [exec-1]
   │
   ├─► Controller.virtualApi()
   │   └─► return Callable  ← Tomcat Thread 해방
   │
   └─► (다른 요청 처리 가능)

Virtual Thread [#456]
   │
   ├─► Callable.call()
   │   └─► Service.processComplexLogic()
   │       └─► Thread.sleep(1000)  ← Virtual Thread만 Block
   │           └─► return result
   │
   └─► HTTP Response
```

---

## 디버깅으로 확인하기

### 1. 로깅으로 스레드 추적

**Controller에 로깅 추가:**

```java
@GetMapping("/virtual")
public Callable<String> virtualApi(@RequestParam String message) {
    long requestTime = System.currentTimeMillis();
    String tomcatThread = Thread.currentThread().getName();

    log.info("[{}ms] Tomcat Thread: {}", 0, tomcatThread);

    return () -> {
        long startTime = System.currentTimeMillis();
        String virtualThread = Thread.currentThread().getName();

        log.info("[{}ms] Virtual Thread Started: {}",
            startTime - requestTime, virtualThread);

        String result = service.processComplexLogic(message);

        long endTime = System.currentTimeMillis();
        log.info("[{}ms] Virtual Thread Finished: {}",
            endTime - requestTime, virtualThread);

        return result;
    };
}
```

**출력:**
```
[0ms] Tomcat Thread: http-nio-8080-exec-1
[2ms] Virtual Thread Started: VirtualThread[#456]/runnable@ForkJoinPool-1-worker-1
[1003ms] Virtual Thread Finished: VirtualThread[#456]/runnable@ForkJoinPool-1-worker-1
```

---

### 2. 브레이크포인트로 확인

**디버깅 포인트:**

1. **Controller 메서드**: Callable 객체 생성 시점
2. **Callable.call() 내부**: Virtual Thread 실행 시점
3. **MdcTaskDecorator.decorate()**: MDC 복사 시점
4. **WebAsyncManager.startCallableProcessing()**: 비동기 처리 시작 시점

**IntelliJ IDEA 디버깅:**

```
1. Controller 메서드에 브레이크포인트
   → Frames 탭에서 스레드 확인: http-nio-8080-exec-1

2. Callable 내부에 브레이크포인트
   → Frames 탭에서 스레드 확인: VirtualThread[#456]
```

---

### 3. JFR (Java Flight Recorder)로 분석

**Virtual Thread 이벤트 모니터링:**

```bash
# JFR 시작
jcmd <pid> JFR.start name=vt settings=profile

# 애플리케이션 실행

# JFR 덤프
jcmd <pid> JFR.dump name=vt filename=vt.jfr

# JFR 분석 (IntelliJ IDEA, JMC 등)
```

**확인 항목:**
- Virtual Thread 생성 수
- Carrier Thread 사용률
- Pinning 이벤트 발생 여부

---

## 왜 이렇게 설계되었는가?

### 1. Spring MVC의 비동기 처리 표준

Spring MVC는 다양한 비동기 반환 타입을 지원:

- `Callable<T>` - 단순 비동기 작업
- `DeferredResult<T>` - 외부 이벤트 대응
- `CompletableFuture<T>` - 비동기 체이닝
- `ResponseBodyEmitter` - 스트리밍
- `SseEmitter` - Server-Sent Events

**Callable의 장점:**
- Spring MVC 표준 패턴
- 코드가 간결하고 직관적
- 예외 처리가 자연스러움

---

### 2. VirtualThreads=true의 효과

```java
executor.setVirtualThreads(true);
```

**이 한 줄이 하는 일:**

```java
// 내부적으로 이렇게 변환됨
Executor virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

// 이는 다음과 동일:
Executor virtualExecutor = task -> {
    Thread.ofVirtual()
        .name("VirtualThread-", 0)
        .start(task);
};
```

**결과:**
- 각 Callable마다 새로운 Virtual Thread 생성
- Virtual Thread는 JVM이 자동으로 스케줄링
- I/O 대기 시 Carrier Thread 해방

---

### 3. MDC 복사가 필요한 이유

**문제:**
```java
MDC.put("traceId", "123");  // Tomcat Thread

return () -> {
    String traceId = MDC.get("traceId");  // Virtual Thread
    // traceId == null (다른 스레드이므로)
};
```

**해결: MdcTaskDecorator**
```java
// Tomcat Thread에서
Map<String, String> contextMap = MDC.getCopyOfContextMap();

// Virtual Thread에서
MDC.setContextMap(contextMap);
```

---

## 정리

### Callable → Virtual Thread 핵심 메커니즘

1. **Controller가 Callable 반환** → Tomcat Thread 해방
2. **Spring MVC가 Callable 감지** → WebAsyncManager 시작
3. **VirtualThreadConfig의 TaskExecutor 사용** → Virtual Thread 생성
4. **MdcTaskDecorator가 MDC 복사** → 로그 추적 유지
5. **Virtual Thread에서 Callable 실행** → 실제 로직 수행
6. **결과 반환** → HTTP Response

### 왜 이 방식이 효율적인가?

**Platform Thread 방식:**
```
동시 요청 200개 → Tomcat Thread 200개 사용 → 스레드 풀 고갈
```

**Virtual Thread 방식:**
```
동시 요청 200개 → Tomcat Thread 즉시 해방 → 수백만 개 처리 가능
```

### 핵심 코드 요약

**VirtualThreadConfig.java**
```java
configurer.setTaskExecutor(executor);  // ← 이 설정이 핵심
executor.setVirtualThreads(true);      // ← Virtual Thread 활성화
```

**Controller.java**
```java
return () -> { ... };  // ← Callable 반환으로 Virtual Thread 트리거
```

---

**References:**
- Spring Framework: WebAsyncManager
- Java 21+: Project Loom
- ThreadPoolTaskExecutor: Spring 6.1+ Virtual Thread Support
