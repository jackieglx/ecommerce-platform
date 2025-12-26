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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
        if (!isVoid && anno.onProcessing() == ProcessingAction.ACK) {
            throw new IllegalStateException("@Idempotent onProcessing=ACK requires void method, method=" + methodName);
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
        String payload = null;
        if (StringUtils.hasText(anno.payload())) {
            payload = keyResolver.resolve(method, pjp.getTarget(), pjp.getArgs(), anno.payload());
        }

        AcquireOutcome acquire = store.acquire(key, token, processingTtl, payload == null ? "" : Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)));
        AcquireResult result = acquire.result();
        if (result == AcquireResult.DONE) {
            String pointerRaw = acquire.pointer()
                    .or(() -> store.getDonePointer(key))
                    .orElse(null);
            ParsedPointer parsed = parsePointer(pointerRaw);
            String expectedPayload = payload == null ? "" : Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            String actualPayload = parsed.payloadB64() == null ? "" : parsed.payloadB64();
            if (!expectedPayload.equals(actualPayload)) {
                throw new IdempotencyPayloadMismatchException("Idempotent key payload mismatch key=" + key);
            }
            if (anno.onDone() == DoneAction.THROW) {
                throw new IdempotencyCompletedException("Idempotent key already done key=" + key);
            }
            if (anno.onDone() == DoneAction.RETURN_POINTER) {
                if (parsed.pointer == null) {
                    throw new IdempotencyPointerMissingException("Idempotent key done but no pointer stored key=" + key);
                }
                return parsed.pointer;
            }
            log.debug("Idempotent skip DONE key={}", key);
            return null;
        }
        if (result == AcquireResult.PROCESSING) {
            if (acquire.pointer().isPresent() && payload != null) {
                String storedPayload = acquire.pointer().get();
                if (!storedPayload.equals(Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)))) {
                    throw new IdempotencyPayloadMismatchException("Idempotent key payload mismatch (processing) key=" + key);
                }
            }
            if (anno.onProcessing() == ProcessingAction.ACK) {
                log.debug("Idempotent processing acknowledged key={}", key);
                return null;
            }
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
                String payloadB64 = payload == null ? "" : Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
                String toStore = pointer == null ? "" : pointer;
                boolean marked = store.markDone(key, token, doneTtl, toStore, payloadB64);
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

    private ParsedPointer parsePointer(String raw) {
        if (raw == null) {
            return new ParsedPointer("", null);
        }
        int idx = raw.indexOf('\n');
        if (idx >= 0) {
            String payload = raw.substring(0, idx);
            String pointer = raw.substring(idx + 1);
            return new ParsedPointer(payload, pointer);
        }
        return new ParsedPointer("", raw);
    }

    private record ParsedPointer(String payloadB64, String pointer) {}
}

