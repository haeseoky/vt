# API Documentation

Virtual Thread Demo 프로젝트의 API 명세서입니다.

## Base URL

```
http://localhost:8080
```

## 목차

- [기본 API](#기본-api)
- [성능 테스트 API](#성능-테스트-api)
- [스레드 정보 API](#스레드-정보-api)
- [에러 응답](#에러-응답)
- [cURL 예제](#curl-예제)

---

## 기본 API

### 1. Platform Thread API

Platform Thread(Tomcat 스레드)를 사용하여 요청을 처리합니다.

**Endpoint**
```
GET /api/demo/platform
```

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| message | String | No | "Platform" | 처리할 메시지 |

**Response**

```json
{
  "type": "text/plain",
  "body": "Platform Thread Result: Processed 'HelloPlatform' on Thread[http-nio-8080-exec-1,5,main]"
}
```

**특징**
- Tomcat 스레드가 직접 처리
- 1초 동안 스레드가 Block됨
- 동시 요청 시 스레드 풀 제약 존재

**cURL 예제**
```bash
curl -X GET "http://localhost:8080/api/demo/platform?message=HelloPlatform"
```

---

### 2. Virtual Thread API

Virtual Thread를 사용하여 요청을 비동기로 처리합니다.

**Endpoint**
```
GET /api/demo/virtual
```

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| message | String | No | "Virtual" | 처리할 메시지 |

**Response**

```json
{
  "type": "text/plain",
  "body": "Virtual Thread Result: Complex processing completed: HELLOVIRTUAL (Thread: VirtualThread[#456]/runnable@ForkJoinPool-1-worker-1)"
}
```

**특징**
- Callable 반환으로 비동기 처리
- Tomcat 스레드 즉시 해방
- Virtual Thread가 작업 처리
- 메시지를 대문자로 변환

**cURL 예제**
```bash
curl -X GET "http://localhost:8080/api/demo/virtual?message=HelloVirtual"
```

---

### 3. Thread Info API

현재 요청을 처리하는 스레드 정보를 반환합니다.

**Endpoint**
```
GET /api/demo/thread-info
```

**Query Parameters**
없음

**Response**

```json
{
  "type": "text/plain",
  "body": "Thread Name: http-nio-8080-exec-1, Thread ID: 123, Is Virtual: false, Thread Group: main"
}
```

**Response Fields**

| 필드 | 타입 | 설명 |
|------|------|------|
| Thread Name | String | 스레드 이름 |
| Thread ID | Long | 스레드 고유 ID |
| Is Virtual | Boolean | Virtual Thread 여부 |
| Thread Group | String | 스레드 그룹명 |

**cURL 예제**
```bash
curl -X GET "http://localhost:8080/api/demo/thread-info"
```

---

## 성능 테스트 API

### 4. Platform Thread Load Test

Platform Thread로 부하 테스트를 수행합니다.

**Endpoint**
```
GET /api/demo/platform-load
```

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| id | String | No | "1" | 요청 식별자 |

**Response**

```json
{
  "type": "text/plain",
  "body": "Platform [10] completed in 1005ms - Processed 'Request-10' on Thread[http-nio-8080-exec-5]"
}
```

**Response Format**
```
Platform [<id>] completed in <duration>ms - <result>
```

**특징**
- 소요 시간 측정 포함
- 동시 요청 테스트에 적합

**cURL 예제**
```bash
# 단일 요청
curl -X GET "http://localhost:8080/api/demo/platform-load?id=1"

# 10개 동시 요청
for i in {1..10}; do
  curl -X GET "http://localhost:8080/api/demo/platform-load?id=$i" &
done
wait
```

---

### 5. Virtual Thread Load Test

Virtual Thread로 부하 테스트를 수행합니다.

**Endpoint**
```
GET /api/demo/virtual-load
```

**Query Parameters**

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---------|------|------|--------|------|
| id | String | No | "1" | 요청 식별자 |

**Response**

```json
{
  "type": "text/plain",
  "body": "Virtual [10] completed in 1003ms - Complex processing completed: REQUEST-10 (Thread: VirtualThread[#456])"
}
```

**Response Format**
```
Virtual [<id>] completed in <duration>ms - <result>
```

**특징**
- 비동기 처리 (Callable 반환)
- 소요 시간 측정 포함
- Platform Thread 대비 성능 비교 가능

**cURL 예제**
```bash
# 단일 요청
curl -X GET "http://localhost:8080/api/demo/virtual-load?id=1"

# 10개 동시 요청
for i in {1..10}; do
  curl -X GET "http://localhost:8080/api/demo/virtual-load?id=$i" &
done
wait
```

---

## 에러 응답

### 400 Bad Request

잘못된 요청 파라미터

**Example**
```json
{
  "timestamp": "2025-12-18T14:30:00.000+00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid parameter value",
  "path": "/api/demo/platform"
}
```

### 500 Internal Server Error

서버 내부 오류

**Example**
```json
{
  "timestamp": "2025-12-18T14:30:00.000+00:00",
  "status": 500,
  "error": "Internal Server Error",
  "message": "An unexpected error occurred",
  "path": "/api/demo/virtual"
}
```

### 503 Service Unavailable

타임아웃 또는 서비스 불가

**Example**
```json
{
  "timestamp": "2025-12-18T14:30:00.000+00:00",
  "status": 503,
  "error": "Service Unavailable",
  "message": "Request timeout",
  "path": "/api/demo/virtual"
}
```

---

## cURL 예제

### 기본 요청

```bash
# Platform Thread API
curl -X GET "http://localhost:8080/api/demo/platform?message=Test"

# Virtual Thread API
curl -X GET "http://localhost:8080/api/demo/virtual?message=Test"

# Thread Info
curl -X GET "http://localhost:8080/api/demo/thread-info"
```

### 성능 비교 테스트

**시나리오: 10개 동시 요청**

```bash
# Platform Thread (순차 처리 경향)
echo "Testing Platform Thread..."
time for i in {1..10}; do
  curl -s "http://localhost:8080/api/demo/platform-load?id=$i" &
done
wait

# Virtual Thread (병렬 처리)
echo "Testing Virtual Thread..."
time for i in {1..10}; do
  curl -s "http://localhost:8080/api/demo/virtual-load?id=$i" &
done
wait
```

### 응답 시간 측정

```bash
# Platform Thread 응답 시간
time curl -w "\nTotal Time: %{time_total}s\n" \
  "http://localhost:8080/api/demo/platform?message=Performance"

# Virtual Thread 응답 시간
time curl -w "\nTotal Time: %{time_total}s\n" \
  "http://localhost:8080/api/demo/virtual?message=Performance"
```

### JSON 형식 출력

```bash
# jq를 사용한 포매팅
curl -s "http://localhost:8080/api/demo/thread-info" | jq '.'
```

---

## 성능 벤치마크

### 테스트 환경

- **OS**: macOS / Linux
- **Java**: 25
- **Spring Boot**: 4.0.0
- **CPU**: 8 cores
- **RAM**: 16GB

### 벤치마크 결과

#### 단일 요청 (1초 대기)

| API | 응답 시간 | 스레드 타입 |
|-----|-----------|-------------|
| Platform | ~1005ms | Platform Thread |
| Virtual | ~1003ms | Virtual Thread |

*단일 요청에서는 차이 미미*

#### 동시 요청 (10개, 각 1초 대기)

| API | 총 소요 시간 | 평균 응답 시간 | 스레드 타입 |
|-----|--------------|----------------|-------------|
| Platform | ~10초 | ~1000ms | Platform Thread |
| Virtual | ~1초 | ~1000ms | Virtual Thread |

*동시 요청이 많을수록 Virtual Thread의 우위 명확*

#### 대량 요청 (100개, 각 1초 대기)

| API | 총 소요 시간 | 처리량 |
|-----|--------------|--------|
| Platform | ~100초 | 1 req/s |
| Virtual | ~1-2초 | 50-100 req/s |

**성능 향상: 약 50-100배**

---

## HTTP 헤더

### Request Headers

```
Accept: text/plain, application/json
User-Agent: curl/7.88.0
```

### Response Headers

```
Content-Type: text/plain;charset=UTF-8
Transfer-Encoding: chunked
Date: Wed, 18 Dec 2025 14:30:00 GMT
```

---

## 비동기 처리 상세

### Callable 반환 흐름

```
1. [Client] → HTTP Request → [Tomcat Thread]
2. [Tomcat Thread] → Controller 호출 → Callable 객체 반환
3. [Tomcat Thread] 즉시 해방 (다른 요청 처리 가능)
4. [Virtual Thread] → Callable.call() 실행
5. [Virtual Thread] → Service 로직 처리 (1초 대기)
6. [Virtual Thread] → 결과 반환
7. [Client] ← HTTP Response
```

### MDC 컨텍스트 복사

```
1. [Tomcat Thread] MDC 정보 저장 (TraceId, UserId 등)
2. [MdcTaskDecorator] MDC 정보 복사
3. [Virtual Thread] MDC 정보 주입
4. [Virtual Thread] 로직 실행 (로그에 TraceId 포함)
5. [Virtual Thread] MDC 정리 (finally 블록)
```

---

## 타임아웃 설정

### 기본 타임아웃

```java
// VirtualThreadConfig.java
configurer.setDefaultTimeout(30000); // 30초
```

### 타임아웃 발생 시

```
503 Service Unavailable
"Request timeout after 30000ms"
```

---

## 로그 예시

### Platform Thread 로그

```
2025-12-18 23:47:04.744 INFO  [http-nio-8080-exec-1] - ===== [Platform Thread API] 요청 시작 =====
2025-12-18 23:47:04.744 INFO  [http-nio-8080-exec-1] - Controller Thread: Thread[http-nio-8080-exec-1]
2025-12-18 23:47:04.744 INFO  [http-nio-8080-exec-1] - Service 처리 시작 - Thread: Thread[http-nio-8080-exec-1]
2025-12-18 23:47:05.750 INFO  [http-nio-8080-exec-1] - Service 처리 완료
2025-12-18 23:47:05.751 INFO  [http-nio-8080-exec-1] - ===== [Platform Thread API] 요청 완료 =====
```

### Virtual Thread 로그

```
2025-12-18 23:47:05.760 INFO  [http-nio-8080-exec-2] - ===== [Virtual Thread API] 요청 시작 =====
2025-12-18 23:47:05.760 INFO  [http-nio-8080-exec-2] - Controller Thread (Tomcat): Thread[http-nio-8080-exec-2]
2025-12-18 23:47:05.761 INFO  [VirtualThread-456] - Worker Thread (Virtual): VirtualThread[#456]
2025-12-18 23:47:05.761 INFO  [VirtualThread-456] - 복잡한 로직 처리 시작
2025-12-18 23:47:06.765 INFO  [VirtualThread-456] - 복잡한 로직 처리 완료
2025-12-18 23:47:06.766 INFO  [VirtualThread-456] - ===== [Virtual Thread API] 요청 완료 =====
```

---

## 참고사항

### Virtual Thread 사용 권장

✅ **권장 사항**
- I/O 대기가 많은 작업
- 외부 API 호출
- 데이터베이스 조회
- 파일 읽기/쓰기
- 동시 요청이 많은 API

❌ **비권장 사항**
- CPU 집약적 계산
- synchronized 블록이 많은 코드
- 짧은 처리 시간 (<10ms)

### Pinning 주의사항

Virtual Thread가 Platform Thread에 고정되는 현상:

```java
// 문제: synchronized 사용
synchronized (lock) {
    Thread.sleep(1000);  // Pinning 발생
}

// 해결: ReentrantLock 사용
lock.lock();
try {
    Thread.sleep(1000);  // Pinning 없음
} finally {
    lock.unlock();
}
```

---

## 추가 리소스

- [README.md](README.md) - 프로젝트 개요
- [VIRTUAL_THREAD_GUIDE.md](VIRTUAL_THREAD_GUIDE.md) - 상세 가이드
- [TEST_RESULTS.md](TEST_RESULTS.md) - 테스트 결과
- [GitHub Repository](https://github.com/haeseoky/vt)
