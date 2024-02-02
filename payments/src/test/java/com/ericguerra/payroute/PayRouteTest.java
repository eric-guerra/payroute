package com.ericguerra.payroute;

import com.ericguerra.payroute.ach.NachaFile;
import com.ericguerra.payroute.core.Orchestrator;
import com.ericguerra.payroute.core.Payment;
import com.ericguerra.payroute.core.Payment.Method;
import com.ericguerra.payroute.core.Payment.Status;
import com.ericguerra.payroute.providers.PaymentProvider;
import com.ericguerra.payroute.routing.ProviderRouter;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class PayRouteTest {
    final Clock clock = Clock.fixed(Instant.parse("2023-05-01T12:00:00Z"), ZoneOffset.UTC);
    final List<Orchestrator.Event> events = new ArrayList<>();

    static ProviderRouter.Provider prov(String name, long fixed, int bps) {
        return new ProviderRouter.Provider(name, Set.of(Method.CARD), Set.of("USD"), fixed, bps, 1_000_000);
    }

    /** Provider whose behaviour is scripted: "ok", "decline", "down". */
    static PaymentProvider scripted(String name, Deque<String> script, List<String> calls) {
        return new PaymentProvider() {
            public String name() { return name; }
            public Result charge(Payment p, String t) throws Declined, Unavailable {
                calls.add(name);
                String s = script.isEmpty() ? "ok" : script.poll();
                if (s.equals("decline")) throw new Declined("insufficient_funds", "declined");
                if (s.equals("down")) throw new Unavailable("timeout", null);
                return new Result(name + "_ref", true);
            }
        };
    }

    @Test
    void routesToCheapestEligibleProvider() {
        ProviderRouter r = new ProviderRouter(List.of(prov("a", 30, 290), prov("b", 0, 300), prov("c", 50, 250)), 20, 0.8, 5);
        // $100: a=320, b=300, c=300 → b and c tie on fee, name breaks the tie
        assertThat(r.candidates(Method.CARD, "USD", 10_000, Set.of())).extracting(ProviderRouter.Provider::name).containsExactly("b", "c", "a");
        assertThat(r.candidates(Method.ACH, "USD", 10_000, Set.of())).isEmpty();
        assertThat(r.candidates(Method.CARD, "USD", 10_000, Set.of("a"))).extracting(ProviderRouter.Provider::name).containsExactly("a");
    }

    @Test
    void unhealthyProviderIsDeprioritized() {
        ProviderRouter r = new ProviderRouter(List.of(prov("cheap", 0, 200), prov("pricey", 0, 300)), 10, 0.8, 5);
        for (int i = 0; i < 6; i++) r.record("cheap", i == 0);
        assertThat(r.healthy("cheap")).isFalse();
        assertThat(r.candidates(Method.CARD, "USD", 10_000, Set.of()).get(0).name()).isEqualTo("pricey");
        for (int i = 0; i < 10; i++) r.record("cheap", true); // window slides, it recovers
        assertThat(r.candidates(Method.CARD, "USD", 10_000, Set.of()).get(0).name()).isEqualTo("cheap");
    }

    @Test
    void failsOverOnOutageButNotOnDecline() {
        ProviderRouter r = new ProviderRouter(List.of(prov("a", 0, 200), prov("b", 0, 300)), 10, 0.8, 5);
        List<String> calls = new ArrayList<>();
        Deque<String> aScript = new ArrayDeque<>(List.of("down", "decline"));
        Orchestrator o = new Orchestrator(r, List.of(scripted("a", aScript, calls), scripted("b", new ArrayDeque<>(), calls)), clock, events::add);

        Payment p1 = o.pay("m1", "key-0001", 5000, "USD", Method.CARD, "tok", Set.of());
        assertThat(p1.status()).isEqualTo(Status.CAPTURED);
        assertThat(p1.provider()).isEqualTo("b");
        assertThat(calls).containsExactly("a", "b");

        calls.clear();
        Payment p2 = o.pay("m1", "key-0002", 5000, "USD", Method.CARD, "tok", Set.of());
        assertThat(p2.status()).isEqualTo(Status.FAILED);
        assertThat(calls).containsExactly("a");
        assertThat(events).extracting(Orchestrator.Event::type).containsExactly("payment.captured", "payment.declined");
    }

    @Test
    void idempotencyKeyReplaysAndRejectsMismatch() {
        ProviderRouter r = new ProviderRouter(List.of(prov("a", 0, 200)), 10, 0.8, 5);
        List<String> calls = new ArrayList<>();
        Orchestrator o = new Orchestrator(r, List.of(scripted("a", new ArrayDeque<>(), calls)), clock, events::add);
        Payment first = o.pay("m1", "same-key-1", 1200, "USD", Method.CARD, "tok", Set.of());
        Payment again = o.pay("m1", "same-key-1", 1200, "USD", Method.CARD, "tok", Set.of());
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(calls).hasSize(1);
        assertThatThrownBy(() -> o.pay("m1", "same-key-1", 9999, "USD", Method.CARD, "tok", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(o.pay("m2", "same-key-1", 1200, "USD", Method.CARD, "tok", Set.of()).id()).isNotEqualTo(first.id());
    }

    @Test
    void achLifecycleIncludesLateReturns() {
        ProviderRouter r = new ProviderRouter(List.of(new ProviderRouter.Provider("ach", Set.of(Method.ACH), Set.of("USD"), 25, 0, 10_000_000)), 10, 0.8, 5);
        PaymentProvider ach = new PaymentProvider() {
            public String name() { return "ach"; }
            public Result charge(Payment p, String t) { return new Result("ach_1", false); }
        };
        Orchestrator o = new Orchestrator(r, List.of(ach), clock, events::add);
        Payment p = o.pay("m1", "ach-key-01", 250_00, "USD", Method.ACH, "acct", Set.of());
        assertThat(p.status()).isEqualTo(Status.PENDING_SETTLEMENT);
        o.settle(p.id());
        o.achReturn(p.id(), "R01");
        assertThat(p.status()).isEqualTo(Status.RETURNED);
        assertThatThrownBy(() -> o.settle(p.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nachaFileHasValidStructure() {
        NachaFile f = new NachaFile("091000019", "1234567890", "WELLS FARGO", "RETAIL CO", "RETAIL CO", "1234567890", "09100001");
        String file = f.build(List.of(
                        new NachaFile.Entry("091000019", "12345678", true, 125_00, "CUST-1", "Jane Doe", "1"),
                        new NachaFile.Entry("021000021", "99887766", false, 99_99, "CUST-2", "Ana Pérez", "2")),
                "PPD", "INVOICES", LocalDate.of(2023, 5, 2), LocalDate.of(2023, 5, 1), LocalTime.of(18, 30), 'A');
        String[] lines = file.split("\n");
        assertThat(lines.length % 10).isZero();
        assertThat(lines).allMatch(l -> l.length() == 94);
        assertThat(lines[0]).startsWith("101 091000019");
        assertThat(lines[2]).startsWith("627091000019");
        assertThat(lines[3].substring(1, 3)).isEqualTo("37");
        assertThat(lines[3]).contains("ANA PREZ");
        String control = lines[4];
        assertThat(control).startsWith("8225000002");
        assertThat(control.substring(10, 20)).isEqualTo(String.format("%010d", 9100001 + 2100002));
        assertThat(control.substring(20, 32)).isEqualTo("000000022499");
        assertThat(NachaFile.validRouting("091000019")).isTrue();
        assertThat(NachaFile.validRouting("091000018")).isFalse();
        assertThatThrownBy(() -> f.build(List.of(new NachaFile.Entry("123456789", "1", true, 1, "x", "y", "1")), "PPD", "X",
                LocalDate.now(), LocalDate.now(), LocalTime.NOON, 'A')).hasMessageContaining("invalid routing");
    }
}
