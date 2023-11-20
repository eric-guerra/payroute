package com.ericguerra.payroute.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A payment and its lifecycle. ACH settles days later and can be returned after "success", hence RETURNED. */
public final class Payment {
    public enum Method { CARD, ACH }

    public enum Status {
        CREATED, AUTHORIZED, CAPTURED, PENDING_SETTLEMENT, SETTLED, FAILED, REFUNDED, RETURNED;

        private static final Map<Status, Set<Status>> NEXT = Map.of(
                CREATED, EnumSet.of(AUTHORIZED, PENDING_SETTLEMENT, FAILED),
                AUTHORIZED, EnumSet.of(CAPTURED, FAILED),
                CAPTURED, EnumSet.of(SETTLED, REFUNDED),
                PENDING_SETTLEMENT, EnumSet.of(SETTLED, RETURNED, FAILED),
                SETTLED, EnumSet.of(REFUNDED, RETURNED),
                FAILED, EnumSet.noneOf(Status.class),
                REFUNDED, EnumSet.noneOf(Status.class),
                RETURNED, EnumSet.noneOf(Status.class));

        public boolean canMoveTo(Status s) { return NEXT.get(this).contains(s); }
    }

    public record Transition(Status from, Status to, String reason, String provider, Instant at) {}

    private final String id;
    private final String merchantId;
    private final String idempotencyKey;
    private final long amountCents;
    private final String currency;
    private final Method method;
    private Status status = Status.CREATED;
    private String provider;
    private String providerRef;
    private final List<Transition> history = new ArrayList<>();

    public Payment(String id, String merchantId, String idempotencyKey, long amountCents, String currency, Method method) {
        if (amountCents <= 0) throw new IllegalArgumentException("amount must be positive");
        this.id = id;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.amountCents = amountCents;
        this.currency = currency;
        this.method = method;
    }

    public synchronized void move(Status to, String reason, String provider, Instant at) {
        if (!status.canMoveTo(to)) throw new IllegalStateException(id + ": " + status + " → " + to + " not allowed");
        history.add(new Transition(status, to, reason, provider, at));
        status = to;
    }

    public String id() { return id; }
    public String merchantId() { return merchantId; }
    public String idempotencyKey() { return idempotencyKey; }
    public long amountCents() { return amountCents; }
    public String currency() { return currency; }
    public Method method() { return method; }
    public Status status() { return status; }
    public String provider() { return provider; }
    public String providerRef() { return providerRef; }
    public void attach(String provider, String ref) { this.provider = provider; this.providerRef = ref; }
    public List<Transition> history() { return List.copyOf(history); }
}
