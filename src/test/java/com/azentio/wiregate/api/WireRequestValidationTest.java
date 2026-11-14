package com.azentio.wiregate.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WireRequestValidationTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        factory.close();
    }

    @Test
    void acceptsValidWireIncludingOptionalFieldsOmitted() {
        assertThat(validator.validate(validRequest())).isEmpty();
    }

    @Test
    void acceptsMaximumAmountAndRejectsMoreThanTwoFractionDigits() {
        WireRequest valid = validRequest();
        WireRequest maximum = new WireRequest(
                valid.msgId(), valid.uetr(), valid.createdAt(), new BigDecimal("99999999.99"),
                valid.currency(), valid.chargeBearer(), valid.debtor(), valid.debtorAgentBic(),
                valid.creditor(), valid.creditorAgentBic(), null, null, null);
        assertThat(validator.validate(maximum)).isEmpty();

        WireRequest tooManyFractionDigits = new WireRequest(
                valid.msgId(), valid.uetr(), valid.createdAt(), new BigDecimal("1.001"),
                valid.currency(), valid.chargeBearer(), valid.debtor(), valid.debtorAgentBic(),
                valid.creditor(), valid.creditorAgentBic(), null, null, null);
        assertThat(validator.validate(tooManyFractionDigits))
                .anySatisfy(error -> assertThat(error.getPropertyPath().toString()).isEqualTo("amount"));
    }

    @Test
    void reportsInvalidMoneyCurrencyBicAccountAndRemittanceFields() {
        WireRequest valid = validRequest();
        WireRequest invalid = new WireRequest(
                valid.msgId(), valid.uetr(), valid.createdAt(), new BigDecimal("100000000.00"),
                "inr", valid.chargeBearer(),
                new WireRequest.Party("Synthetic Debtor", "1234"), "INVALID",
                valid.creditor(), valid.creditorAgentBic(), null, null, "x".repeat(141));

        Set<String> fields = validator.validate(invalid).stream()
                .map(error -> error.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(fields).contains(
                "amount", "currency", "debtor.accountId", "debtorAgentBic", "remittanceInfo");
    }

    private static WireRequest validRequest() {
        return new WireRequest(
                "demo-001",
                "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                OffsetDateTime.parse("2026-11-14T12:00:00Z"),
                new BigDecimal("25000.00"),
                "INR",
                ChargeBearer.SHAR,
                new WireRequest.Party("Synthetic Debtor", "100000000001"),
                "TESTINBBXXX",
                new WireRequest.Creditor("Synthetic Creditor", "200000000001", "AE"),
                "DEMOAEADXXX",
                null,
                "SALA",
                null);
    }
}
