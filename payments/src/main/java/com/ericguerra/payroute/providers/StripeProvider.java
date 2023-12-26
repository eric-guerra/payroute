package com.ericguerra.payroute.providers;

import com.ericguerra.payroute.core.Payment;
import com.stripe.exception.*;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;

/** Card payments through Stripe (test mode keys in dev). */
public class StripeProvider implements PaymentProvider {
    private final String apiKey;

    public StripeProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String name() { return "stripe"; }

    @Override
    public Result charge(Payment p, String pmToken) throws Declined, Unavailable {
        try {
            PaymentIntent pi = PaymentIntent.create(PaymentIntentCreateParams.builder()
                            .setAmount(p.amountCents())
                            .setCurrency(p.currency().toLowerCase())
                            .setPaymentMethod(pmToken)
                            .setConfirm(true)
                            // Server-side confirm with no return_url: restrict to cards so no redirect-based method is chosen.
                            .addPaymentMethodType("card")
                            .putMetadata("payment_id", p.id())
                            .putMetadata("merchant_id", p.merchantId())
                            .build(),
                    // Our payment id as Stripe's idempotency key: a retry after a timeout returns the same PaymentIntent.
                    RequestOptions.builder().setApiKey(apiKey).setIdempotencyKey("pay_" + p.id()).build());
            return new Result(pi.getId(), "succeeded".equals(pi.getStatus()));
        } catch (CardException e) {
            throw new Declined(e.getDeclineCode() != null ? e.getDeclineCode() : e.getCode(), e.getMessage());
        } catch (RateLimitException | ApiConnectionException e) {
            throw new Unavailable("stripe unavailable", e);
        } catch (ApiException e) {
            if (e.getStatusCode() != null && e.getStatusCode() >= 500) throw new Unavailable("stripe " + e.getStatusCode(), e);
            throw new Declined("api_error", e.getMessage());
        } catch (StripeException e) {
            throw new Declined("invalid_request", e.getMessage());
        }
    }
}
