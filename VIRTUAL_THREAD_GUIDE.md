# Virtual Thread Demo Guide

## 프로젝트 개요

Spring Boot 4.0 + Java 25 환경에서 Virtual Thread를 활용한 API 성능 비교 데모

## 주요 구성 요소

### 1. VirtualThreadConfig (config/VirtualThreadConfig.java)
- `WebMvcConfigurer` 구현
- Callable 반환 시 Virtual Thread Executor 자동 적용
- MDC(로그 컨텍스트) 복사 지원 via `MdcTaskDecorator`
- Tomcat 전체가 아닌 **특정 API만** Virtual Thread 적용

### 2. DemoService (service/DemoService.java)
- 1초 대기 후 응답 처리 (I/O 작업 시뮬레이션)
- 스레드 정보 로깅

### 3. VirtualThreadDemoController (controller/VirtualThreadDemoController.java)
- **Platform Thread API**: 일반 스레드 사용 (Tomcat 스레드 Block)
- **Virtual Thread API**: Callable 반환 → Virtual Thread 사용 (Tomcat 스레드 해방)

## 실행 방법

### 1. 애플리케이션 시작
```bash
./gradlew bootRun
```

### 2. API 테스트

#### 기본 테스트

**[Platform Thread API]** - 일반 스레드 사용
```bash
curl "http://localhost:8080/api/demo/platform?message=HelloPlatform"
```

**[Virtual Thread API]** - Virtual Thread 사용
```bash
curl "http://localhost:8080/api/demo/virtual?message=HelloVirtual"
```

**[Thread Info API]** - 현재 스레드 정보 확인
```bash
curl "http://localhost:8080/api/demo/thread-info"
```

#### 성능 비교 테스트 (동시 요청)

**[Platform Thread 부하 테스트]** - 10개 동시 요청
```bash
for i in {1..10}; do
  curl "http://localhost:8080/api/demo/platform-load?id=$i" &
done
wait
```

**[Virtual Thread 부하 테스트]** - 10개 동시 요청
```bash
for i in {1..10}; do
  curl "http://localhost:8080/api/demo/virtual-load?id=$i" &
done
wait
```

## 핵심 차이점

### Platform Thread (일반 API)
```java
@GetMapping("/platform")
public String platformThreadApi(@RequestParam String message) {
    // Tomcat 스레드가 직접 처리
    return service.processWithDelay(message);
    // 1초 동안 Tomcat 스레드가 Block됨
}
```
- **동작**: Tomcat 스레드가 1초 동안 대기 (Block)
- **문제**: 동시 요청 시 Tomcat 스레드 풀 고갈 가능

### Virtual Thread (Callable 반환)
```java
@GetMapping("/virtual")
public Callable<String> virtualThreadApi(@RequestParam String message) {
    // Tomcat 스레드는 즉시 반환
    return () -> {
        // Virtual Thread가 처리
        return service.processComplexLogic(message);
        // Tomcat 스레드는 다른 요청 처리 가능
    };
}
```
- **동작**: Tomcat 스레드는 즉시 반환, Virtual Thread가 작업 처리
- **장점**: 동시 요청 처리 능력 대폭 향상

## 로그 확인 포인트

애플리케이션 로그에서 다음을 확인할 수 있습니다:

### Platform Thread
```
Controller Thread: Thread[#123,http-nio-8080-exec-1,5,main]
Service 처리 시작 - Thread: Thread[#123,http-nio-8080-exec-1,5,main]
```
→ Controller와 Service가 **같은 Tomcat 스레드** 사용

### Virtual Thread
```
Controller Thread (Tomcat): Thread[#123,http-nio-8080-exec-1,5,main]
Worker Thread (Virtual): VirtualThread[#456]/runnable@ForkJoinPool-1-worker-1
```
→ Controller는 Tomcat 스레드, 실제 작업은 **Virtual Thread** 처리

## 성능 차이 확인

### 시나리오: 10개 동시 요청

**Platform Thread**
- Tomcat 기본 스레드 풀: 약 200개
- 각 요청당 1초씩 Block
- 스레드 부족 시 요청 대기 발생

**Virtual Thread**
- Virtual Thread: 수백만 개 생성 가능
- Tomcat 스레드는 즉시 해방
- 모든 요청이 거의 동시에 처리

## 적용 전략

### 언제 Virtual Thread를 사용해야 하는가?

✅ **사용 권장**
- I/O 대기가 많은 작업 (DB 조회, 외부 API 호출, 파일 읽기 등)
- 동시 요청이 많은 API
- 긴 시간이 걸리는 작업

❌ **사용 주의**
- CPU 집약적 작업 (복잡한 계산, 이미지 처리 등)
- synchronized 블록이 많은 레거시 라이브러리 사용 시 (Pinning 문제)

### Config 설정의 의미

```java
@Configuration
public class VirtualThreadConfig implements WebMvcConfigurer {
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setVirtualThreads(true);  // ← Virtual Thread 활성화
        executor.setTaskDecorator(new MdcTaskDecorator());  // ← MDC 복사
        configurer.setTaskExecutor(executor);  // ← Callable용 Executor 설정
    }
}
```

- **Tomcat 전체 적용 X**: application.yml 설정 없음
- **특정 API만 적용 O**: Callable 반환하는 Controller 메서드만 자동 적용
- **안전한 부분 적용**: 레거시 코드 영향 없음

## 추가 개선 사항

### 예외 처리
- 기존 `@ExceptionHandler` / `@RestControllerAdvice` 정상 동작
- Virtual Thread에서 발생한 예외도 동일하게 처리됨

### 로그 추적 (MDC)
- `MdcTaskDecorator`를 통해 TraceId, UserId 등 로그 컨텍스트 유지
- Tomcat 스레드 → Virtual Thread로 MDC 정보 자동 복사
- Virtual Thread 종료 시 MDC 자동 정리 (메모리 누수 방지)

## 참고 자료

- Java Virtual Threads (Project Loom): https://openjdk.org/jeps/444
- Spring Boot Virtual Threads: https://spring.io/blog/2022/10/11/embracing-virtual-threads
- Callable Pattern: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html
