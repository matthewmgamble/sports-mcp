package ca.hex42labs.sports.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/** Attaches a Prometheus registry to the global registry {@link SportsMetrics} records to. */
public final class MetricsSetup implements AutoCloseable {

    private final PrometheusMeterRegistry registry;
    private final JvmGcMetrics gc;

    private MetricsSetup(PrometheusMeterRegistry registry, JvmGcMetrics gc) {
        this.registry = registry;
        this.gc = gc;
    }

    public static MetricsSetup prometheus() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
        JvmGcMetrics gc = new JvmGcMetrics();
        gc.bindTo(registry);
        Metrics.addRegistry(registry);
        return new MetricsSetup(registry, gc);
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }

    /** Prometheus text exposition. */
    public String scrape() {
        return registry.scrape();
    }

    @Override
    public void close() {
        Metrics.removeRegistry(registry);
        gc.close();
        registry.close();
    }
}
