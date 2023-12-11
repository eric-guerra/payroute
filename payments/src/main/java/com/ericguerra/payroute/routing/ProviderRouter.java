package com.ericguerra.payroute.routing;

import com.ericguerra.payroute.core.Payment;

import java.util.*;

/**
 * Picks the provider for a payment. Each provider advertises what it supports and its fee; the
 * router keeps a rolling success rate per provider and skips unhealthy ones, so an acquirer outage
 * shifts traffic within a minute instead of failing every checkout.
 */
public final class ProviderRouter {

    public record Provider(String name, Set<Payment.Method> methods, Set<String> currencies,
                           long fixedFeeCents, int feeBasisPoints, long maxAmountCents) {
        long fee(long amount) { return fixedFeeCents + Math.round(amount * feeBasisPoints / 10_000.0); }
    }

    /** Ring buffer of recent outcomes per provider. */
    static final class Health {
        private final boolean[] ok;
        private int n, idx;
        Health(int window) { ok = new boolean[window]; }
        synchronized void record(boolean success) { ok[idx] = success; idx = (idx + 1) % ok.length; n = Math.min(n + 1, ok.length); }
        synchronized double successRate() {
            if (n == 0) return 1.0;
            int s = 0;
            for (int i = 0; i < n; i++) if (ok[i]) s++;
            return (double) s / n;
        }
        synchronized int samples() { return n; }
    }

    private final List<Provider> providers;
    private final Map<String, Health> health = new HashMap<>();
    private final double minSuccessRate;
    private final int minSamples;

    public ProviderRouter(List<Provider> providers, int window, double minSuccessRate, int minSamples) {
        this.providers = List.copyOf(providers);
        this.minSuccessRate = minSuccessRate;
        this.minSamples = minSamples;
        for (Provider p : providers) health.put(p.name(), new Health(window));
    }

    public void record(String provider, boolean success) {
        Health h = health.get(provider);
        if (h != null) h.record(success);
    }

    public boolean healthy(String provider) {
        Health h = health.get(provider);
        return h.samples() < minSamples || h.successRate() >= minSuccessRate;
    }

    /**
     * Ordered candidates: eligible providers, healthy ones first, cheapest first within each group.
     * The orchestrator tries them in order (only for failures that are safe to retry elsewhere).
     */
    public List<Provider> candidates(Payment.Method method, String currency, long amountCents, Set<String> merchantAllowed) {
        List<Provider> eligible = new ArrayList<>();
        for (Provider p : providers) {
            if (p.methods().contains(method) && p.currencies().contains(currency) && amountCents <= p.maxAmountCents()
                    && (merchantAllowed.isEmpty() || merchantAllowed.contains(p.name()))) eligible.add(p);
        }
        eligible.sort(Comparator.<Provider, Boolean>comparing(p -> !healthy(p.name()))
                .thenComparingLong(p -> p.fee(amountCents))
                .thenComparing(Provider::name));
        return eligible;
    }
}
