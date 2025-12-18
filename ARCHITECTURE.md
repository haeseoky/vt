# Architecture Documentation

Virtual Thread Demo 프로젝트의 아키텍처 및 설계 문서입니다.

## 목차

- [시스템 아키텍처](#시스템-아키텍처)
- [계층 구조](#계층-구조)
- [핵심 컴포넌트](#핵심-컴포넌트)
- [데이터 흐름](#데이터-흐름)
- [스레드 모델](#스레드-모델)
- [설계 결정사항](#설계-결정사항)

---

## 시스템 아키텍처

### 전체 구조도

```
┌─────────────────────────────────────────────────────────┐
│                        Client                           │
└────────────────────┬────────────────────────────────────┘
                     │ HTTP Request
                     ▼
┌─────────────────────────────────────────────────────────┐
│                    Tomcat Server                        │
│  ┌───────────────────────────────────────────────────┐  │
│  │              Platform Thread Pool                 │  │
│  │  (http-nio-8080-exec-1, exec-2, ...)           │  │
│  └───────────┬───────────────────────┬───────────────┘  │
└──────────────┼───────────────────────┼──────────────────┘
               │                       │
               │                       │ Callable 반환
               │                       ▼
               │            ┌─────────────────────────┐
               │            │  VirtualThreadConfig    │
               │            │  (TaskExecutor)         │
               │            └──────────┬──────────────┘
               │                       │
               │                       ▼
               │            ┌─────────────────────────┐
               │            │   Virtual Thread Pool   │
               │            │  (수백만 개 생성 가능) │
               │            └──────────┬──────────────┘
               │                       │
               ▼                       ▼
┌──────────────────────────────────────────────────────────┐
│                    Controller Layer                      │
│  ┌────────────────────────────────────────────────────┐  │
│  │      VirtualThreadDemoController                   │  │
│  │  - platformThreadApi() → 직접 처리                │  │
│  │  - virtualThreadApi() → Callable 반환             │  │
│  └────────────────────┬───────────────────────────────┘  │
└───────────────────────┼──────────────────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────────────────┐
│                    Service Layer                         │
│  ┌────────────────────────────────────────────────────┐  │
│  │              DemoService                           │  │
│  │  - processWithDelay(String)                       │  │
│  │  - processComplexLogic(String)                    │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

---

## 계층 구조

### Layer Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  Presentation Layer                     │
│  - VirtualThreadDemoController                         │
│  - HTTP 요청/응답 처리                                  │
│  - Callable 반환 (Virtual Thread 트리거)              │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│                   Business Layer                        │
│  - DemoService                                         │
│  - 비즈니스 로직 처리                                   │
│  - Thread.sleep() 시뮬레이션                          │
└─────────────────────────────────────────────────────────┘
```

### 설정 계층

```
┌─────────────────────────────────────────────────────────┐
│                 Configuration Layer                     │
│  - VirtualThreadConfig                                 │
│  - WebMvcConfigurer 구현                               │
│  - Virtual Thread Executor 설정                       │
│  - MDC TaskDecorator 설정                             │
└─────────────────────────────────────────────────────────┘
```

---

## 핵심 컴포넌트

### 1. VirtualThreadConfig

**책임**
- Virtual Thread Executor 생성 및 구성
- Callable 처리 시 Virtual Thread 할당
- MDC 컨텍스트 복사 및 정리

**주요 메서드**
```java
@Override
public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
    // Virtual Thread Executor 설정
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setVirtualThreads(true);
    executor.setTaskDecorator(new MdcTaskDecorator());
    executor.initialize();

    configurer.setTaskExecutor(executor);
    configurer.setDefaultTimeout(30000);
}
```

**설계 결정**
- `WebMvcConfigurer` 인터페이스 구현으로 Spring MVC 표준 준수
- `TaskDecorator` 패턴으로 MDC 복사 로직 캡슐화
- 타임아웃 30초 설정 (조정 가능)

---

### 2. MdcTaskDecorator

**책임**
- Tomcat Thread의 MDC 정보를 Virtual Thread로 복사
- Virtual Thread 종료 시 MDC 정리 (메모리 누수 방지)

**구현**
```java
public static class MdcTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();

        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
```

**설계 패턴**
- **Decorator Pattern**: Runnable을 감싸서 부가 기능(MDC 복사) 추가
- **Template Method**: try-finally로 일관된 리소스 관리

---

### 3. VirtualThreadDemoController

**책임**
- HTTP 요청 수신 및 응답 반환
- Platform Thread vs Virtual Thread 선택
- 요청 파라미터 검증 및 변환

**API 구조**
```java
// Platform Thread API
@GetMapping("/platform")
public String platformThreadApi(@RequestParam String message) {
    // 동기 처리: Tomcat Thread가 Block됨
    return service.processWithDelay(message);
}

// Virtual Thread API
@GetMapping("/virtual")
public Callable<String> virtualThreadApi(@RequestParam String message) {
    // 비동기 처리: Callable 반환 시 Tomcat Thread 해방
    return () -> service.processComplexLogic(message);
}
```

**설계 결정**
- 동일한 기능을 두 가지 방식으로 제공 (성능 비교 목적)
- `Callable<T>` 반환으로 Spring MVC의 표준 비동기 패턴 활용
- `@RequestParam` 기본값 설정으로 간편한 테스트

---

### 4. DemoService

**책임**
- 비즈니스 로직 처리
- I/O 작업 시뮬레이션 (Thread.sleep)
- 스레드 정보 로깅

**주요 메서드**
```java
public String processWithDelay(String message) {
    // 1초 대기 (I/O 시뮬레이션)
    Thread.sleep(1000);
    return "Processed: " + message;
}

public String processComplexLogic(String data) {
    // 복잡한 로직 + 대문자 변환
    Thread.sleep(1000);
    return data.toUpperCase();
}
```

**설계 결정**
- `Thread.sleep()`으로 I/O 대기 시뮬레이션 (실제로는 DB 조회, API 호출 등)
- 로깅으로 스레드 정보 추적 가능
- 단순한 로직으로 Virtual Thread의 효과 명확히 표현

---

## 데이터 흐름

### Platform Thread 흐름

```
1. Client Request
   │
   ▼
2. Tomcat Thread (http-nio-8080-exec-1)
   │
   ├─► Controller.platformThreadApi()
   │   └─► Service.processWithDelay()
   │       └─► Thread.sleep(1000)  ← Tomcat Thread Block
   │           └─► return result
   │
   ▼
3. HTTP Response
```

**특징**
- Tomcat Thread가 1초 동안 Block됨
- 동시 요청 수 = Tomcat Thread Pool 크기에 제약

---

### Virtual Thread 흐름

```
1. Client Request
   │
   ▼
2. Tomcat Thread (http-nio-8080-exec-1)
   │
   ├─► Controller.virtualThreadApi()
   │   └─► return Callable<String>  ← Tomcat Thread 즉시 해방
   │
   ▼
3. Spring MVC Async Processing
   │
   ├─► VirtualThreadConfig.getTaskExecutor()
   │   └─► Virtual Thread 생성
   │
   ▼
4. Virtual Thread (VirtualThread[#456])
   │
   ├─► MdcTaskDecorator.decorate()
   │   ├─► MDC 복사
   │   └─► Callable.call()
   │       └─► Service.processComplexLogic()
   │           └─► Thread.sleep(1000)  ← Virtual Thread만 Block
   │               └─► return result
   │
   ▼
5. HTTP Response
```

**특징**
- Tomcat Thread는 즉시 해방되어 다른 요청 처리 가능
- Virtual Thread가 Block되어도 OS Thread는 다른 작업 수행
- 수백만 개의 Virtual Thread 생성 가능

---

## 스레드 모델

### Platform Thread Model

```
┌────────────────────────────────────────┐
│       Platform Thread Pool             │
│  (Tomcat Thread Pool - 약 200개)      │
├────────────────────────────────────────┤
│  Thread 1  →  [────── 1초 ──────]     │
│  Thread 2  →  [────── 1초 ──────]     │
│  Thread 3  →  [────── 1초 ──────]     │
│  ...                                   │
│  Thread 200 → [────── 1초 ──────]     │
├────────────────────────────────────────┤
│  Thread 201 → (대기...)               │
│  Thread 202 → (대기...)               │
└────────────────────────────────────────┘

동시 처리: 최대 200개 (스레드 풀 크기)
```

---

### Virtual Thread Model

```
┌────────────────────────────────────────┐
│     Virtual Thread Pool                │
│  (수백만 개 생성 가능)                 │
├────────────────────────────────────────┤
│  VThread 1    →  [──── 1초 ────]      │
│  VThread 2    →  [──── 1초 ────]      │
│  VThread 3    →  [──── 1초 ────]      │
│  ...                                   │
│  VThread 1000 →  [──── 1초 ────]      │
│  ...                                   │
│  VThread 1,000,000 → [──── 1초 ────]  │
└────────────────────────────────────────┘
         ▲
         │ Carrier Thread (Platform Thread)
         │ 실제 작업 수행
┌────────┴───────────────────────────────┐
│  Carrier Thread Pool                   │
│  (CPU 코어 수만큼, 보통 8-16개)        │
├────────────────────────────────────────┤
│  Carrier 1 → VThread 1, 5, 12, ...    │
│  Carrier 2 → VThread 2, 8, 20, ...    │
│  ...                                   │
└────────────────────────────────────────┘

동시 처리: 수백만 개 (메모리 허용 범위 내)
```

**핵심 개념**
- **Virtual Thread**: 경량 스레드, JVM이 관리
- **Carrier Thread**: 실제 작업 수행하는 Platform Thread
- **스케줄링**: Virtual Thread가 I/O 대기 시 Carrier Thread 해방

---

## 설계 결정사항

### 1. Tomcat 전체가 아닌 특정 API만 Virtual Thread 적용

**이유**
- 레거시 코드와의 호환성 보장
- Pinning 문제 회피 (synchronized 블록이 있는 코드)
- 단계적 마이그레이션 가능

**방법**
- `Callable<T>` 반환으로 명시적 선택
- `VirtualThreadConfig`로 중앙 집중식 관리

---

### 2. Callable vs CompletableFuture

**선택**: Callable<T>

**이유**
- Spring MVC 표준 지원 (`@Controller`에서 직접 반환 가능)
- CompletableFuture보다 코드 간결
- 예외 처리가 자연스러움

**비교**
```java
// Callable (선택)
@GetMapping("/api")
public Callable<String> api() {
    return () -> service.process();
}

// CompletableFuture (선택 안 함)
@GetMapping("/api")
public CompletableFuture<String> api() {
    return CompletableFuture.supplyAsync(() -> service.process(), executor);
}
```

---

### 3. MDC 복사 전략

**선택**: TaskDecorator 패턴

**이유**
- Spring의 표준 확장 포인트 활용
- 재사용 가능한 컴포넌트
- 테스트 용이

**대안**
- Filter에서 수동 복사 (중복 코드 발생)
- Aspect로 처리 (복잡도 증가)

---

### 4. 타임아웃 설정

**선택**: 30초

**이유**
- 대부분의 API 응답 시간 < 5초
- 충분한 여유 시간 제공
- 무한 대기 방지

**조정 방법**
```java
// VirtualThreadConfig.java
configurer.setDefaultTimeout(30000); // 밀리초 단위
```

---

### 5. 로깅 전략

**선택**: SLF4J + MDC

**이유**
- 표준 Java 로깅 인터페이스
- 스레드 정보 자동 추적
- 분산 트레이싱 가능 (TraceId)

**로그 포맷**
```
[날짜시간] [로그레벨] [스레드명] [클래스명] - 메시지
```

---

## 성능 특성

### Platform Thread 특성

| 항목 | 값 |
|------|-----|
| 스레드 생성 시간 | ~1ms |
| 메모리 사용량 | ~1MB/thread |
| 최대 동시 스레드 | ~수천 개 |
| Context Switch 비용 | 높음 |

---

### Virtual Thread 특성

| 항목 | 값 |
|------|-----|
| 스레드 생성 시간 | ~1μs (1000배 빠름) |
| 메모리 사용량 | ~1KB/thread (1000배 적음) |
| 최대 동시 스레드 | 수백만 개 |
| Context Switch 비용 | 매우 낮음 |

---

## 확장성

### 수평 확장 (Horizontal Scaling)

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│  Instance 1 │  │  Instance 2 │  │  Instance 3 │
│  (8 cores)  │  │  (8 cores)  │  │  (8 cores)  │
└─────────────┘  └─────────────┘  └─────────────┘
       │                 │                 │
       └─────────────────┴─────────────────┘
                         │
                 ┌───────┴────────┐
                 │ Load Balancer  │
                 └────────────────┘
```

Virtual Thread는 인스턴스당 처리량을 크게 향상시킵니다.

---

### 수직 확장 (Vertical Scaling)

```
더 많은 CPU 코어 = 더 많은 Carrier Thread = 더 나은 Virtual Thread 성능
```

---

## 모니터링 포인트

### 1. 스레드 메트릭

- Virtual Thread 생성 수
- Carrier Thread 사용률
- Pinning 발생 빈도

### 2. 성능 메트릭

- API 응답 시간
- 동시 요청 처리 수
- 처리량 (req/sec)

### 3. 리소스 메트릭

- CPU 사용률
- 메모리 사용량
- GC 빈도 및 시간

---

## 참고 자료

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [Spring Boot Virtual Threads Support](https://spring.io/blog/2022/10/11/embracing-virtual-threads)
- [Project Loom Documentation](https://wiki.openjdk.org/display/loom)

---

**Last Updated**: 2025-12-18
