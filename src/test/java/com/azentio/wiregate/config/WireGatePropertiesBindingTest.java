package com.azentio.wiregate.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.azentio.wiregate.api.ChargeBearer;
import com.azentio.wiregate.api.WireRequest;
import com.azentio.wiregate.service.RiskRuleEvaluator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class WireGatePropertiesBindingTest {
    @Test
    void applicationPropertiesChangeHighRiskAndCorridorDecisionsWithoutCodeChanges() {
        WireGateProperties properties = Binder.get(new MapConfigurationPropertySource(Map.of(
                        "wire-gate.high-risk-countries[0]", "KP",
                        "wire-gate.corridor-purpose-codes.IN-AE[0]", "SALA")))
                .bind("wire-gate", Bindable.of(WireGateProperties.class))
                .orElseThrow();
        RiskRuleEvaluator evaluator = new RiskRuleEvaluator(properties);
        WireRequest wire = new WireRequest(
                "property-test",
                "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                OffsetDateTime.parse("2026-11-14T12:00:00Z"),
                new BigDecimal("100.00"),
                "INR",
                ChargeBearer.CRED,
                new WireRequest.Party("Synthetic Debtor", "100000000001"),
                "TESTINBBXXX",
                new WireRequest.Creditor("Synthetic Creditor", "200000000001", "IR"),
                "DEMOAEADXXX",
                null,
                "SALA",
                null);

        assertThat(evaluator.evaluate(wire)).isEmpty();

        properties.setHighRiskCountries(java.util.Set.of("IR"));

        assertThat(evaluator.evaluate(wire)).extracting("code")
                .containsExactly("HIGH_RISK_COUNTRY");
    }
}
