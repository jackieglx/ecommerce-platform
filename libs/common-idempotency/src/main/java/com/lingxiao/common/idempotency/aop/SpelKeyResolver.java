package com.lingxiao.common.idempotency.aop;

import com.lingxiao.common.idempotency.IdempotencyKeyResolveException;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Map;

public class SpelKeyResolver {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentReferenceHashMap<>();
    private final ParameterNameDiscoverer paramDiscoverer = new DefaultParameterNameDiscoverer();

    public String resolve(Method method, Object target, Object[] args, String expr) {
        return resolve(method, target, args, expr, null);
    }

    public String resolve(Method method, Object target, Object[] args, String expr, Object result) {
        if (!StringUtils.hasText(expr)) {
            throw new IdempotencyKeyResolveException("Id expression is empty");
        }
        try {
            String cacheKey = method.toGenericString() + "#" + expr;
            Expression expression = cache.computeIfAbsent(cacheKey, k -> parser.parseExpression(expr));
            StandardEvaluationContext context = new MethodBasedEvaluationContext(target, method, args, paramDiscoverer);
            for (int i = 0; i < args.length; i++) {
                context.setVariable("a" + i, args[i]);
                context.setVariable("p" + i, args[i]);
            }
            context.setVariable("result", result);
            Object val = expression.getValue(context);
            if (val == null) {
                throw new IdempotencyKeyResolveException("Resolved id is null for expr: " + expr);
            }
            return val.toString();
        } catch (Exception e) {
            throw new IdempotencyKeyResolveException("Failed to resolve id expression: " + expr, e);
        }
    }
}

