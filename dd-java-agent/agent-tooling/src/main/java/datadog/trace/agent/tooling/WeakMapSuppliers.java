package datadog.trace.agent.tooling;

import com.blogspot.mydailyjava.weaklockfree.WeakConcurrentMap;
import datadog.trace.api.Function;
import datadog.trace.bootstrap.WeakMap;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.AgentTaskScheduler.Task;
import java.util.concurrent.TimeUnit;

class WeakMapSuppliers {
  // Comparison with using WeakConcurrentMap vs Guava's implementation:
  // Cleaning:
  // * `WeakConcurrentMap`: centralized but we have to maintain out own code and thread for it
  // * `Guava`: inline on application's thread, with constant max delay
  // Jar Size:
  // * `WeakConcurrentMap`: small
  // * `Guava`: large, but we may use other features, like immutable collections - and we already
  //          ship Guava as part of distribution now, so using Guava for this doesn’t increase size.
  // Must go on bootstrap classpath:
  // * `WeakConcurrentMap`: version conflict is unlikely, so we can directly inject for now
  // * `Guava`: need to implement shadow copy (might eventually be necessary for other dependencies)
  // Used by other javaagents for similar purposes:
  // * `WeakConcurrentMap`: anecdotally used by other agents
  // * `Guava`: specifically agent use is unknown at the moment, but Guava is a well known library
  //            backed by big company with many-many users

  /**
   * Provides instances of {@link WeakConcurrentMap} and retains weak reference to them to allow a
   * single thread to clean void weak references out for all instances. Cleaning is done every
   * second.
   */
  static class WeakConcurrent implements WeakMap.Implementation {

    static final long CLEAN_FREQUENCY_SECONDS = 1;

    @Override
    public <K, V> WeakMap<K, V> get() {
      final WeakConcurrentMap<K, V> map = new WeakConcurrentMap<>(false, true);
      AgentTaskScheduler.INSTANCE.weakScheduleAtFixedRate(
          MapCleaningTask.INSTANCE,
          map,
          CLEAN_FREQUENCY_SECONDS,
          CLEAN_FREQUENCY_SECONDS,
          TimeUnit.SECONDS);
      return new Adapter<>(map);
    }

    // Important to use explicit class to avoid implicit hard references to target
    private static class MapCleaningTask implements Task<WeakConcurrentMap<?, ?>> {

      static final MapCleaningTask INSTANCE = new MapCleaningTask();

      @Override
      public void run(final WeakConcurrentMap<?, ?> target) {
        target.expungeStaleEntries();
      }
    }

    private static class Adapter<K, V> implements WeakMap<K, V> {

      private final WeakConcurrentMap<K, V> map;

      private Adapter(final WeakConcurrentMap<K, V> map) {
        this.map = map;
      }

      @Override
      public int size() {
        return map.approximateSize();
      }

      @Override
      public boolean containsKey(final K key) {
        return map.containsKey(key);
      }

      @Override
      public V get(final K key) {
        return map.get(key);
      }

      @Override
      public void put(final K key, final V value) {
        if (null != value) {
          map.put(key, value);
        } else {
          map.remove(key); // WeakConcurrentMap doesn't accept null values
        }
      }

      @Override
      public void putIfAbsent(final K key, final V value) {
        map.putIfAbsent(key, value);
      }

      @Override
      public V computeIfAbsent(final K key, final Function<? super K, ? extends V> supplier) {
        V value = map.get(key);
        if (null == value) {
          synchronized (this) {
            value = map.get(key);
            if (null == value) {
              value = supplier.apply(key);
              map.put(key, value);
            }
          }
        }
        return value;
      }
    }
  }
}
