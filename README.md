# Virtual Thread Demo

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Spring Boot 4.0과 Java 25를 활용한 Virtual Thread 데모 프로젝트입니다. Platform Thread와 Virtual Thread의 성능 차이를 비교하고, Callable 패턴을 통한 특정 API만 Virtual Thread를 적용하는 방법을 보여줍니다.

## 목차

- [주요 특징](#주요-특징)
- [프로젝트 구조](#프로젝트-구조)
- [빠른 시작](#빠른-시작)
- [API 엔드포인트](#api-엔드포인트)
- [핵심 개념](#핵심-개념)
- [테스트](#테스트)
- [성능 비교](#성능-비교)
- [문서](#문서)
- [기여](#기여)

## 주요 특징

### ✨ 핵심 기능

- **선택적 Virtual Thread 적용**: Tomcat 전체가 아닌 특정 API만 Virtual Thread 사용
- **Callable 패턴**: Spring MVC의 표준 비동기 패턴 활용
- **MDC 복사**: 로그 컨텍스트(TraceId, UserId) 자동 복사 및 관리
- **성능 비교**: Platform Thread vs Virtual Thread 실시간 비교
- **완전한 테스트**: 27개 테스트 케이스 (단위/통합/성능)

### 🎯 기술 스택

- **Java 25**: Virtual Thread 네이티브 지원
- **Spring Boot 4.0**: 최신 Spring 프레임워크
- **Gradle 9.2**: 빌드 자동화
- **JUnit 5**: 테스트 프레임워크
- **AssertJ**: 테스트 검증 라이브러리

## 프로젝트 구조

```
vt/
├── src/
│   ├── main/
│   │   └── java/com/ocean/sc/vt/
│   │       ├── VtApplication.java                    # 메인 애플리케이션
│   │       ├── config/
│   │       │   └── VirtualThreadConfig.java          # Virtual Thread 설정
│   │       ├── controller/
│   │       │   └── VirtualThreadDemoController.java  # API 컨트롤러
│   │       └── service/
│   │           └── DemoService.java                  # 비즈니스 로직
│   └── test/
│       └── java/com/ocean/sc/vt/
│           ├── config/
│           │   └── VirtualThreadConfigTest.java      # Config 테스트
│           ├── controller/
│           │   └── VirtualThreadDemoControllerTest.java
│           ├── service/
│           │   └── DemoServiceTest.java              # Service 테스트
│           └── integration/
│               └── VirtualThreadIntegrationTest.java # 통합 테스트
├── VIRTUAL_THREAD_GUIDE.md                          # 사용 가이드
├── TEST_RESULTS.md                                  # 테스트 결과
└── API_DOCUMENTATION.md                             # API 문서
```

## 빠른 시작

### 사전 요구사항

- Java 25 이상
- Gradle 9.x 이상

### 설치 및 실행

```bash
# 저장소 클론
git clone https://github.com/haeseoky/vt.git
cd vt

# 빌드
./gradlew build

# 애플리케이션 실행
./gradlew bootRun
```

애플리케이션이 `http://localhost:8080`에서 실행됩니다.

### 빠른 테스트

```bash
# Platform Thread API
curl "http://localhost:8080/api/demo/platform?message=Hello"

# Virtual Thread API
curl "http://localhost:8080/api/demo/virtual?message=Hello"

# 스레드 정보 확인
curl "http://localhost:8080/api/demo/thread-info"
```

## API 엔드포인트

### 기본 API

| 엔드포인트 | 메서드 | 설명 | 스레드 타입 |
|-----------|--------|------|------------|
| `/api/demo/platform` | GET | Platform Thread 사용 | Tomcat Thread |
| `/api/demo/virtual` | GET | Virtual Thread 사용 | Virtual Thread |
| `/api/demo/thread-info` | GET | 현재 스레드 정보 | Tomcat Thread |

### 성능 테스트 API

| 엔드포인트 | 메서드 | 설명 |
|-----------|--------|------|
| `/api/demo/platform-load` | GET | Platform Thread 부하 테스트 |
| `/api/demo/virtual-load` | GET | Virtual Thread 부하 테스트 |

### 요청 예시

**Platform Thread API**
```bash
curl "http://localhost:8080/api/demo/platform?message=HelloPlatform"
```

**Response:**
```
Platform Thread Result: Processed 'HelloPlatform' on Thread[http-nio-8080-exec-1]
```

**Virtual Thread API**
```bash
curl "http://localhost:8080/api/demo/virtual?message=HelloVirtual"
```

**Response:**
```
Virtual Thread Result: Complex processing completed: HELLOVIRTUAL (Thread: VirtualThread[#456])
```

### 동시 요청 테스트

**Platform Thread 부하 테스트** (10개 동시 요청)
```bash
for i in {1..10}; do
  curl "http://localhost:8080/api/demo/platform-load?id=$i" &
done
wait
```

**Virtual Thread 부하 테스트** (10개 동시 요청)
```bash
for i in {1..10}; do
  curl "http://localhost:8080/api/demo/virtual-load?id=$i" &
done
wait
```

## 핵심 개념

### Virtual Thread란?

Java 21에서 도입된 Project Loom의 핵심 기능으로, 수백만 개의 경량 스레드를 생성할 수 있습니다.

**전통적인 Platform Thread의 문제:**
- OS 스레드와 1:1 매핑 → 생성 비용 높음
- 스레드 풀 크기 제한 (일반적으로 200~500개)
- I/O 대기 시 스레드 낭비

**Virtual Thread의 장점:**
- 수백만 개 생성 가능 (메모리만 충분하면)
- I/O 대기 시 다른 작업 처리 가능
- 코드는 동기 방식으로 작성 (가독성 향상)

### 특정 API만 Virtual Thread 적용하는 방법

#### 1. VirtualThreadConfig 설정

```java
@Configuration
public class VirtualThreadConfig implements WebMvcConfigurer {
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setVirtualThreads(true);  // Virtual Thread 활성화
        executor.setTaskDecorator(new MdcTaskDecorator());
        configurer.setTaskExecutor(executor);
    }
}
```

#### 2. Controller에서 Callable 반환

```java
// [일반 API] Platform Thread 사용
@GetMapping("/platform")
public String platformApi(@RequestParam String message) {
    return service.process(message);
}

// [Virtual Thread API] Callable 반환
@GetMapping("/virtual")
public Callable<String> virtualApi(@RequestParam String message) {
    return () -> service.process(message);
}
```

**동작 원리:**
1. 요청 진입: Tomcat Thread가 메서드 호출
2. Callable 반환: Tomcat Thread 즉시 해방
3. Virtual Thread 생성: 새로운 Virtual Thread가 작업 처리
4. 응답 전송: 작업 완료 시 결과 반환

### MDC(Mapped Diagnostic Context) 복사

로그 추적을 위한 컨텍스트 정보(TraceId, UserId 등)를 자동으로 복사합니다.

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

## 테스트

### 전체 테스트 실행

```bash
./gradlew test
```

### 테스트 통계

- **총 테스트 수**: 27개
- **성공**: 27개 ✅
- **실패**: 0개
- **성공률**: 100%

### 테스트 분류

**단위 테스트 (12개)**
- DemoServiceTest: 5개
- VirtualThreadConfigTest: 7개

**통합 테스트 (15개)**
- VirtualThreadDemoControllerTest: 10개
- VirtualThreadIntegrationTest: 5개

### 주요 테스트 케이스

```bash
# Service 테스트
./gradlew test --tests "com.ocean.sc.vt.service.DemoServiceTest"

# Config 테스트
./gradlew test --tests "com.ocean.sc.vt.config.VirtualThreadConfigTest"

# Controller 테스트
./gradlew test --tests "com.ocean.sc.vt.controller.VirtualThreadDemoControllerTest"

# 통합 테스트
./gradlew test --tests "com.ocean.sc.vt.integration.VirtualThreadIntegrationTest"
```

## 성능 비교

### 시나리오: 20개 동시 요청 (각 1초 소요)

**Platform Thread**
- 처리 시간: ~20초
- 이유: Tomcat 스레드 풀 제약으로 순차 처리

**Virtual Thread**
- 처리 시간: ~1초
- 이유: 모든 요청이 동시에 Virtual Thread에서 처리

### 성능 향상

```
동시 요청 수가 많을수록 Virtual Thread의 성능 우위가 명확해집니다.

요청 10개: 약 10배 성능 향상
요청 100개: 약 100배 성능 향상
```

## 문서

- **[VIRTUAL_THREAD_GUIDE.md](VIRTUAL_THREAD_GUIDE.md)**: 상세 사용 가이드
- **[TEST_RESULTS.md](TEST_RESULTS.md)**: 테스트 결과 및 통계
- **[API_DOCUMENTATION.md](API_DOCUMENTATION.md)**: API 명세서

## 주의사항

### Virtual Thread 사용 권장

✅ **사용 권장**
- I/O 대기가 많은 작업 (DB 조회, 외부 API 호출)
- 동시 요청이 많은 API
- 긴 시간이 걸리는 작업

❌ **사용 주의**
- CPU 집약적 작업 (복잡한 계산)
- synchronized 블록이 많은 레거시 코드 (Pinning 문제)

### Pinning 문제

Virtual Thread가 `synchronized` 블록 실행 시 Platform Thread에 고정되는 현상:

```java
// 문제: synchronized 사용
synchronized (lock) {
    Thread.sleep(1000);  // Virtual Thread가 고정됨
}

// 해결: ReentrantLock 사용
lock.lock();
try {
    Thread.sleep(1000);  // Virtual Thread가 자유롭게 이동
} finally {
    lock.unlock();
}
```

## 라이선스

이 프로젝트는 MIT 라이선스 하에 배포됩니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

## 기여

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 문의

프로젝트 관련 문의사항은 [Issues](https://github.com/haeseoky/vt/issues)에 등록해주세요.

## 참고 자료

- [Java Virtual Threads (JEP 444)](https://openjdk.org/jeps/444)
- [Spring Boot Virtual Threads](https://spring.io/blog/2022/10/11/embracing-virtual-threads)
- [Project Loom](https://wiki.openjdk.org/display/loom)
- [Callable Pattern in Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)

---

**Made with ❤️ using Spring Boot 4.0 and Java 25**
