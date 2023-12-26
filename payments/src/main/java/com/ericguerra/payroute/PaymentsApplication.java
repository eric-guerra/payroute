package com.ericguerra.payroute;

import com.ericguerra.payroute.core.Orchestrator;
import com.ericguerra.payroute.core.Payment;
import com.ericguerra.payroute.providers.PaymentProvider;
import com.ericguerra.payroute.providers.StripeProvider;
import com.ericguerra.payroute.routing.ProviderRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SpringBootApplication
@RestController
@RequestMapping("/v1/payments")
public class PaymentsApplication {
    public record PayReq(long amountCents, String currency, Payment.Method method, String paymentMethodToken) {}

    private final Orchestrator orchestrator;

    public PaymentsApplication(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }

    @Bean
    static Orchestrator orchestrator(KafkaTemplate<String, Object> kafka, @Value("${stripe.api-key}") String stripeKey) {
        ProviderRouter router = new ProviderRouter(List.of(
                new ProviderRouter.Provider("stripe", Set.of(Payment.Method.CARD), Set.of("USD", "CAD", "EUR"), 30, 290, 99_999_99L),
                new ProviderRouter.Provider("bank-ach", Set.of(Payment.Method.ACH), Set.of("USD"), 25, 0, 1_000_000_00L)),
                50, 0.8, 20);
        List<PaymentProvider> impls = List.of(new StripeProvider(stripeKey),
                new PaymentProvider() { // ACH entries are batched into the nightly NACHA file
                    public String name() { return "bank-ach"; }
                    public Result charge(Payment p, String token) { return new Result("ach_" + UUID.randomUUID(), false); }
                });
        // Key = merchant id: all events for a merchant stay ordered on one partition.
        return new Orchestrator(router, impls, Clock.systemUTC(), e -> kafka.send("payments.events", e.merchantId(), e));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> pay(@RequestHeader("X-Merchant-Id") String merchant, @RequestHeader("Idempotency-Key") String key, @RequestBody PayReq r) {
        Payment p = orchestrator.pay(merchant, key, r.amountCents(), r.currency(), r.method(), r.paymentMethodToken(), Set.of());
        return Map.of("id", p.id(), "status", p.status(), "provider", String.valueOf(p.provider()));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Payment p = orchestrator.get(id);
        return Map.of("id", p.id(), "status", p.status(), "history", p.history());
    }
}
