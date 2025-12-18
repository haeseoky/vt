package com.ocean.sc.vt.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Virtual Thread를 사용하여 메서드를 실행하는 어노테이션
 *
 * <p>이 어노테이션이 붙은 Controller 메서드는 Virtual Thread에서 실행됩니다.
 * Tomcat Thread는 즉시 해방되어 다른 요청을 처리할 수 있습니다.</p>
 *
 * <p>사용 예시:</p>
 * <pre>
 * {@code
 * @GetMapping("/api")
 * @VirtualThread
 * public String myApi() {
 *     // 이 코드는 Virtual Thread에서 실행됨
 *     return service.heavyOperation();
 * }
 * }
 * </pre>
 *
 * @see com.ocean.sc.vt.aspect.VirtualThreadAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface VirtualThread {

    /**
     * 타임아웃 시간 (밀리초)
     * <p>기본값: 30초 (30000ms)</p>
     *
     * @return 타임아웃 시간 (밀리초)
     */
    long timeout() default 30000;

    /**
     * 비동기 처리 설명 (선택적)
     * <p>로깅 및 문서화 목적</p>
     *
     * @return 설명
     */
    String description() default "";
}
