package com.azentio.wiregate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azentio.wiregate.api.ChargeBearer;
import com.azentio.wiregate.config.WireGateProperties;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FundsDecisionEvaluatorTest {
    private FundsDecisionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        WireGateProperties properties = new WireGateProperties();
        properties.setFees(Map.of(
                "DEBT", new BigDecimal("1500.00"),
                "SHAR", new BigDecimal("750.00"),
                "CRED", new BigDecimal("0.00")));
        evaluator = new FundsDecisionEvaluator(properties);
    }

    @Test
    void checksFundsReasonsInRequiredOrder() {
        assertReason("ACCOUNT_INVALID", evaluator.evaluate(null, "INR",
                money("100"), money("0"), () -> money("0")));
        assertReason("ACCOUNT_INVALID", evaluator.evaluate(
                account("INR", "1000", "1000", "BLOCKED"),
                "INR", money("100"), money("0"), () -> money("0")));
        assertReason("CURRENCY_MISMATCH", evaluator.evaluate(
                account("USD", "1", "1", "ACTIVE"), "INR", money("100"), money("0"), () -> money("0")));
        assertReason("INSUFFICIENT_FUNDS", evaluator.evaluate(
                account("INR", "100", "1000", "ACTIVE"), "INR", money("100"), money("1"), () -> money("0")));
        assertReason("DAILY_LIMIT_EXCEEDED", evaluator.evaluate(
                account("INR", "1000", "100", "ACTIVE"), "INR", money("50"), money("0"), () -> money("51")));
    }

    @Test
    void selectsConfiguredFeeAndRejectsWireThatOnlyFitsWithCred() {
        BigDecimal debtFee = evaluator.feeFor(ChargeBearer.DEBT);
        BigDecimal creditFee = evaluator.feeFor(ChargeBearer.CRED);
        assertThat(debtFee).isEqualByComparingTo("1500.00");
        assertThat(evaluator.feeFor(ChargeBearer.SHAR)).isEqualByComparingTo("750.00");
        assertThat(creditFee).isEqualByComparingTo("0.00");

        var account = account("INR", "100.00", "1000.00", "ACTIVE");
        assertReason("INSUFFICIENT_FUNDS",
                evaluator.evaluate(account, "INR", money("100"), debtFee, () -> money("0")));
        assertThat(evaluator.evaluate(account, "INR", money("100"), creditFee, () -> money("0")))
                .isEmpty();
    }

    @Test
    void rejectsNegativeOrMissingConfiguredFees() {
        WireGateProperties properties = new WireGateProperties();
        properties.setFees(Map.of("DEBT", new BigDecimal("-1")));
        FundsDecisionEvaluator invalid = new FundsDecisionEvaluator(properties);
        assertThatThrownBy(() -> invalid.feeFor(ChargeBearer.SHAR))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invalid.feeFor(ChargeBearer.DEBT))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertReason(String expected, Optional<com.azentio.wiregate.api.WireReason> reason) {
        assertThat(reason).isPresent().get().extracting("code").isEqualTo(expected);
    }

    private static FundsDecisionEvaluator.AccountSnapshot account(
            String currency, String balance, String limit, String status) {
        return new FundsDecisionEvaluator.AccountSnapshot(
                currency, money(balance), money(limit), status);
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
