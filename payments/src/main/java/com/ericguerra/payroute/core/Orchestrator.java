package com.ericguerra.payroute.core;

import com.ericguerra.payroute.providers.PaymentProvider;
import com.ericguerra.payroute.routing.ProviderRouter;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Creates a payment, routes it, and fails over across providers only when that's safe:
 * an Unavailable (no charge created) moves to the next candidate; a Declined stops immediately,
 * because retrying a declined card elsewhere is both pointless and a card-network rules problem.
 */
public class Orchestrator {
    public record Event(String type, String paymentId, String merchantId, String status, String provider, long amountCents, String detail) {}

    private final ProviderRouter router;
    private final Map<String, PaymentProvider> providers;
    private final Clock clock;
    private final Consumer<Event> events; // Kafka in prod
    private final Map<String, Payment> byIdempotency = new ConcurrentHashMap<>();
    private final Map<String, Payment> byId = new ConcurrentHashMap<>();

    public Orchestrator(ProviderRouter router, Collection<PaymentProvider> providers, Clock clock, Consumer<Event> events) {
        this.router = router;
        this.providers = new HashMap<>();
        for (PaymentProvider p : providers) this.providers.put(p.name(), p);
        this.clock = clock;
        this.events = events;
    }

    public Payment pay(String merchantId, String idempotencyKey, long amountCents, String currency, Payment.Method method,
                       String token, Set<String> merchantAllowedProviders) {
        String key = merchantId + ":" + idempotencyKey;
        Payment fresh = new Payment(UUID.randomUUID().toString(), merchantId, idempotencyKey, amountCents, currency, method);
        Payment existing = byIdempotency.putIfAbsent(key, fresh);
        if (existing != null) {
            if (existing.amountCents() != amountCents || !existing.currency().equals(currency))
                throw new IllegalArgumentException("Idempotency-Key reused with different parameters");
            return existing; // replay: same result, no second charge
        }
        Payment p = fresh;
        byId.put(p.id(), p);

        List<ProviderRouter.Provider> candidates = router.candidates(method, currency, amountCents, merchantAllowedProviders);
        if (candidates.isEmpty()) {
            p.move(Payment.Status.FAILED, "no provider supports " + method + "/" + currency, null, clock.instant());
            emit("payment.failed", p, "no_route");
            return p;
        }
        String lastError = null;
        for (ProviderRouter.Provider c : candidates) {
            PaymentProvider impl = providers.get(c.name());
            if (impl == null) continue;
            try {
                PaymentProvider.Result r = impl.charge(p, token);
                router.record(c.name(), true);
                p.attach(c.name(), r.reference());
                if (r.settledImmediately()) {
                    p.move(Payment.Status.AUTHORIZED, "authorized", c.name(), clock.instant());
                    p.move(Payment.Status.CAPTURED, "captured", c.name(), clock.instant());
                    emit("payment.captured", p, r.reference());
                } else {
                    p.move(Payment.Status.PENDING_SETTLEMENT, "submitted", c.name(), clock.instant());
                    emit("payment.pending", p, r.reference());
                }
                return p;
            } catch (PaymentProvider.Declined d) {
                router.record(c.name(), true); // a decline is the provider working correctly
                p.move(Payment.Status.FAILED, "declined: " + d.code, c.name(), clock.instant());
                emit("payment.declined", p, d.code);
                return p;
            } catch (PaymentProvider.Unavailable u) {
                router.record(c.name(), false);
                lastError = c.name() + ": " + u.getMessage();
            }
        }
        p.move(Payment.Status.FAILED, "all providers unavailable (" + lastError + ")", null, clock.instant());
        emit("payment.failed", p, "providers_unavailable");
        return p;
    }

    /** ACH return (R01 insufficient funds, R02 account closed, ...) arrives days after "success". */
    public Payment achReturn(String paymentId, String returnCode) {
        Payment p = get(paymentId);
        p.move(Payment.Status.RETURNED, "ACH return " + returnCode, p.provider(), clock.instant());
        emit("payment.returned", p, returnCode);
        return p;
    }

    public Payment settle(String paymentId) {
        Payment p = get(paymentId);
        p.move(Payment.Status.SETTLED, "settled", p.provider(), clock.instant());
        emit("payment.settled", p, null);
        return p;
    }

    public Payment get(String id) {
        Payment p = byId.get(id);
        if (p == null) throw new NoSuchElementException("payment " + id);
        return p;
    }

    private void emit(String type, Payment p, String detail) {
        events.accept(new Event(type, p.id(), p.merchantId(), p.status().name(), p.provider(), p.amountCents(), detail));
    }
}
