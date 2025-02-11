package datadog.trace.instrumentation.micronaut;

import static datadog.trace.instrumentation.micronaut.MicronautDecorator.DECORATE;
import static datadog.trace.instrumentation.micronaut.MicronautDecorator.SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.micronaut.http.server.netty.NettyHttpRequest;
import net.bytebuddy.asm.Advice;

public class WriteDefaultErrorResponseAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beginRequest(
      @Advice.Argument(1) final NettyHttpRequest nettyHttpRequest,
      @Advice.Argument(2) final Throwable cause) {
    AgentSpan span = nettyHttpRequest.getAttribute(SPAN_ATTRIBUTE, AgentSpan.class).orElse(null);
    if (null == span) {
      return;
    }
    DECORATE.onError(span, cause);
  }
}
