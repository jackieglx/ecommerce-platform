package com.lingxiao.common.idempotency.aop;

import com.lingxiao.common.idempotency.*;
import com.lingxiao.common.idempotency.store.AcquireResult;
import com.lingxiao.common.idempotency.store.AcquireOutcome;
import com.lingxiao.common.idempotency.store.IdempotencyStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;

@Aspect
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    private final IdempotencyStore store;
    private final SpelKeyResolver keyResolver;
    private final DurationParser durationParser;
    private final IdempotencyNamespaceProvider namespaceProvider;

    public IdempotentAspect(IdempotencyStore store,
                            SpelKeyResolver keyResolver,
                            DurationParser durationParser,
                            IdempotencyNamespaceProvider namespaceProvider) {
        this.store = store;
        this.keyResolver = keyResolver;
        this.durationParser = durationParser;
        this.namespaceProvider = namespaceProvider;
    }

    @Around("@annotation(com.lingxiao.common.idempotency.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Idempotent anno = method.getAnnotation(Idempotent.class);
        boolean isVoid = method.getReturnType().equals(Void.TYPE);
        String methodName = method.getDeclaringClass().getName() + "#" + method.getName();
        if (!isVoid && anno.onDone() != DoneAction.RETURN_POINTER) {
            throw new IllegalStateException("@Idempotent non-void methods require onDone=RETURN_POINTER, method=" + methodName);
        }
        if (isVoid && anno.onDone() == DoneAction.RETURN_POINTER) {
            throw new IllegalStateException("@Idempotent RETURN_POINTER requires non-void method, method=" + methodName);
        }
        if (anno.onDone() == DoneAction.RETURN_POINTER && !StringUtils.hasText(anno.result())) {
            throw new IllegalStateException("@Idempotent onDone=RETURN_POINTER requires non-empty result SpEL, method=" + methodName);
        }
        String id = keyResolver.resolve(method, pjp.getTarget(), pjp.getArgs(), anno.id());
        String namespace = namespaceProvider.namespace();
        String key = anno.keyPrefix() + ":" + namespace + ":" + anno.eventType() + ":" + id;
        String token = UUID.randomUUID().toString();
        Duration processingTtl = durationParser.parse(anno.processingTtl());
        Duration doneTtl = durationParser.parse(anno.doneTtl());

        AcquireOutcome acquire = store.acquire(key, token, processingTtl);
        AcquireResult result = acquire.result();
        if (result == AcquireResult.DONE) {
            if (anno.onDone() == DoneAction.THROW) {
                throw new IdempotencyCompletedException("Idempotent key already done key=" + key);
            }
            if (anno.onDone() == DoneAction.RETURN_POINTER) {
                return acquire.pointer()
                        .or(() -> store.getDonePointer(key))
                        .orElseThrow(() ->
                                new IdempotencyPointerMissingException("Idempotent key done but no pointer stored key=" + key));
            }
            log.debug("Idempotent skip DONE key={}", key);
            return null;
        }
        if (result == AcquireResult.PROCESSING) {
            throw new IdempotencyInProgressException("Idempotent key processing key=" + key);
        }

        try {
            Object ret = pjp.proceed();
            try {
                String pointer = null;
                if (StringUtils.hasText(anno.result())) {
                    pointer = keyResolver.resolve(method, pjp.getTarget(), pjp.getArgs(), anno.result(), ret);
                }
                if (anno.onDone() == DoneAction.RETURN_POINTER && !StringUtils.hasText(pointer)) {
                    throw new IdempotencyMarkDoneFailedException("RETURN_POINTER requires non-empty pointer, key=" + key);
                }
                boolean marked = store.markDone(key, token, doneTtl, pointer);
                if (!marked) {
                    log.warn("Idempotent markDone failed, key={} token={}", key, token);
                    throw new IdempotencyMarkDoneFailedException("Idempotent markDone failed, key=" + key);
                }
            } catch (Exception e) {
                log.warn("Idempotent markDone error, key={} token={}", key, token, e);
                throw new IdempotencyMarkDoneFailedException("Idempotent markDone error, key=" + key, e);
            }
            return ret;
        } catch (IdempotencyMarkDoneFailedException ex) {
            // markDone failure: keep PROCESSING to avoid double-processing; let retry after TTL
            throw ex;
        } catch (Throwable ex) {
            store.release(key, token);
            throw ex;
        }
    }
}

