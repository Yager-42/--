package cn.nexus.infrastructure.mq.reliable.aop;

import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

@Component
public class ReliableMqExpressionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public ReliableMqExpressionEvaluator() {
    }

    public String requiredString(ProceedingJoinPoint joinPoint, String expression, String name) {
        Object value = evaluate(joinPoint, expression);
        if (value == null) {
            throw new IllegalArgumentException(name + " is null");
        }
        String text = value.toString();
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return text;
    }

    public String optionalString(ProceedingJoinPoint joinPoint, String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        Object value = evaluate(joinPoint, expression);
        return value == null ? "" : value.toString();
    }

    public Object requiredObject(ProceedingJoinPoint joinPoint, String expression, String name) {
        Object value = evaluate(joinPoint, expression);
        if (value == null) {
            throw new IllegalArgumentException(name + " is null");
        }
        return value;
    }

    public Object evaluate(ProceedingJoinPoint joinPoint, String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression is blank");
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(), method, joinPoint.getArgs(), parameterNameDiscoverer);
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length && i < args.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        return parser.parseExpression(expression).getValue(context);
    }
}
