package com.azentio.wiregate.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.azentio.wiregate.api.ChargeBearer;
import com.azentio.wiregate.api.WireRequest;
import com.azentio.wiregate.config.WireGateProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskRuleEvaluatorTest {
    private WireGateProperties properties;
    private RiskRuleEvaluator evaluator;

    @BeforeEach
    void setUp() {
        properties = new WireGateProperties();
        properties.setHighRiskCountries(Set.of("IR", "KP", "MM"));
        properties.setRestrictedBics(Set.of("TESTGB2L"));
        properties.setCorridorPurposeCodes(Map.of(
                "IN-AE", Set.of("SALA", "SUPP", "GDDS", "INTC"),
                "IN-GB", Set.of("SALA", "SUPP", "GDDS", "INTC")));
        evaluator = new RiskRuleEvaluator(properties);
    }

    @Test
    void appliesAllMatchingRulesIncludingCountryCodeFromAgentBic() {
        var reasons = evaluator.evaluate(wire("GB", "DEMOINBBXXX", "TESTIR2LXXX", null));
        assertThat(reasons).extracting("code")
                .containsExactly("HIGH_RISK_COUNTRY", "CORRIDOR_PURPOSE");
    }

    @Test
    void matchesRestrictedBicByEightCharacterPrefixAndAllowsConfiguredPurpose() {
        var reasons = evaluator.evaluate(wire("GB", "DEMOINBBXXX", "TESTGB2LXXX", "SALA"));
        assertThat(reasons).extracting("code").containsExactly("RESTRICTED_BIC");
    }

    @Test
    void doesNotFireForLowRiskUnrestrictedAllowedCorridor() {
        var reasons = evaluator.evaluate(wire("AE", "DEMOINBBXXX", "DEMOAEADXXX", "SUPP"));
        assertThat(reasons).isEmpty();
    }

    @Test
    void doesNotRequirePurposeCodeForUnconfiguredCorridor() {
        var reasons = evaluator.evaluate(wire("FR", "DEMOINBBXXX", "DEMOFRPPXXX", null));
        assertThat(reasons).isEmpty();
    }

    @Test
    void changingConfiguredHighRiskCountriesChangesTheDecision() {
        var request = wire("IR", "DEMOINBBXXX", "DEMOAEADXXX", "SALA");
        assertThat(evaluator.evaluate(request)).extracting("code").containsExactly("HIGH_RISK_COUNTRY");

        properties.setHighRiskCountries(Set.of("KP", "MM"));

        assertThat(evaluator.evaluate(request)).isEmpty();
    }

    @Test
    void reportsMultipleIndependentRules() {
        properties.setRestrictedBics(Set.of("DEMOINBB"));
        var reasons = evaluator.evaluate(wire("IR", "DEMOINBBXXX", "DEMOIRBBXXX", "SALA"));
        assertThat(reasons).extracting("code")
                .containsExactly("HIGH_RISK_COUNTRY", "RESTRICTED_BIC");
    }

    private WireRequest wire(String country, String debtorBic, String creditorBic, String purpose) {
        return new WireRequest(
                "msg-1", "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                OffsetDateTime.parse("2026-11-14T12:00:00Z"),
                new BigDecimal("100.00"), "INR", ChargeBearer.CRED,
                new WireRequest.Party("Synthetic Debtor", "100000000001"), debtorBic,
                new WireRequest.Creditor("Synthetic Creditor", "200000000001", country),
                creditorBic, null, purpose, null);
    }
}
