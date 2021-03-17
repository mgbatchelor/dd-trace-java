package datadog.trace.instrumentation.classloading;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static datadog.trace.bootstrap.BootstrapLoadedPackages.forceLoadWithBootstrapClassLoaderIfNecessary;
import static datadog.trace.bootstrap.BootstrapLoadedPackages.mayForceLoadWithBootstrapClassLoader;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/*
 * Some class loaders do not delegate to their parent, so classes in those class loaders
 * will not be able to see classes in the bootstrap class loader.
 *
 * In particular, instrumentation on classes in those class loaders will not be able to see
 * the shaded OpenTelemetry API classes in the bootstrap class loader.
 *
 * This instrumentation forces all class loaders to delegate to the bootstrap class loader
 * for the classes that we have put in the bootstrap class loader.
 */
@AutoService(Instrumenter.class)
public final class ClassloadingInstrumentation extends Instrumenter.Tracing {
  public ClassloadingInstrumentation() {
    super("classloading");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    // just an optimization to exclude common class loaders that are known to delegate to the
    // bootstrap loader (or happen to _be_ the bootstrap loader)
    return namedNoneOf(
            "java.lang.ClassLoader",
            "com.ibm.oti.vm.BootstrapClassLoader",
            "datadog.trace.bootstrap.DatadogClassLoader",
            "datadog.trace.bootstrap.DatadogClassLoader$DelegateClassLoader")
        .and(extendsClass(named("java.lang.ClassLoader")));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("loadClass"))
            .and(
                takesArguments(1)
                    .and(takesArgument(0, named("java.lang.String")))
                    .or(
                        takesArguments(2)
                            .and(takesArgument(0, named("java.lang.String")))
                            .and(takesArgument(1, named("boolean")))))
            .and(isPublic().or(isProtected()))
            .and(not(isStatic())),
        ClassloadingInstrumentation.class.getName() + "$LoadClassAdvice");
  }

  public static class LoadClassAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static Class<?> onEnter(@Advice.Argument(0) final String name) {
      // optimisation to avoid incrementing the call depth on every load
      // this call also ensures that the BootstrapLoadedPackages class
      // must have already been loaded whenever the call depth is > 0
      if (!mayForceLoadWithBootstrapClassLoader(name)) {
        return null;
      }

      // need to use call depth here to prevent re-entry from call to Class.forName() below
      // because on some JVMs (e.g. IBM's, though IBM bootstrap loader is explicitly excluded above)
      // Class.forName() ends up calling loadClass() on the bootstrap loader which would then come
      // back to this instrumentation over and over, causing a StackOverflowError
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(ClassLoader.class);
      if (callDepth > 0) {
        return null;
      }
      try {
        return forceLoadWithBootstrapClassLoaderIfNecessary(name);
      } finally {
        // need to reset it right away, not waiting until onExit()
        // otherwise it will prevent this instrumentation from being applied when loadClass()
        // ends up calling a ClassFileTransformer which ends up calling loadClass() further down the
        // stack on one of our bootstrap packages (since the call depth check would then suppress
        // the nested loadClass instrumentation)
        CallDepthThreadLocalMap.reset(ClassLoader.class);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Return(readOnly = false) Class<?> result,
        @Advice.Enter final Class<?> resultFromBootstrapLoader) {
      if (resultFromBootstrapLoader != null) {
        result = resultFromBootstrapLoader;
      }
    }
  }
}
