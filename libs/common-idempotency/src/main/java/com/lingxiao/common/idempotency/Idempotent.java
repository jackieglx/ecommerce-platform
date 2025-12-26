package com.lingxiao.common.idempotency;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
    String eventType();
    String id();
    String result() default "";
    String payload() default "";
    ProcessingAction onProcessing() default ProcessingAction.RETRY;
    DoneAction onDone() default DoneAction.ACK;
    String processingTtl() default "PT30S";
    String doneTtl() default "PT2H";
    String keyPrefix() default "idem:v1";
}

