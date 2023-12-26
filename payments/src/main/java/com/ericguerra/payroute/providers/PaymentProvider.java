package com.ericguerra.payroute.providers;

import com.ericguerra.payroute.core.Payment;

public interface PaymentProvider {
    String name();

    /**
     * @return the provider's reference. Throws {@link Declined} for a definitive no (do not try another
     * provider: the card is the problem) or {@link Unavailable} for a provider-side failure (safe to fail over,
     * because no charge was created).
     */
    Result charge(Payment p, String paymentMethodToken) throws Declined, Unavailable;

    record Result(String reference, boolean settledImmediately) {}

    class Declined extends Exception {
        public final String code;
        public Declined(String code, String message) { super(message); this.code = code; }
    }

    class Unavailable extends Exception {
        public Unavailable(String message, Throwable cause) { super(message, cause); }
    }
}
