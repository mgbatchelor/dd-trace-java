package datadog.trace.instrumentation.sparkjava;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.RouteHandlerDecorator.ROUTE_HANDLER_DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import spark.route.HttpMethod;
import spark.routematch.RouteMatch;

@AutoService(Instrumenter.class)
public class RoutesInstrumentation extends Instrumenter.Tracing {

  public RoutesInstrumentation() {
    super("sparkjava", "sparkjava-2.4");
  }

  @Override
  public boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("spark.route.Routes");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("find")
            .and(takesArgument(0, named("spark.route.HttpMethod")))
            .and(returns(named("spark.routematch.RouteMatch")))
            .and(isPublic()),
        RoutesInstrumentation.class.getName() + "$RoutesAdvice");
  }

  public static class RoutesAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void routeMatchEnricher(
        @Advice.Argument(0) final HttpMethod method, @Advice.Return final RouteMatch routeMatch) {

      final AgentSpan span = activeSpan();
      if (span != null && routeMatch != null) {
        ROUTE_HANDLER_DECORATOR.withRoute(span, method.name(), routeMatch.getMatchUri());
      }
    }
  }
}
