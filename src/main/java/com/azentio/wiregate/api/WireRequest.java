package com.azentio.wiregate.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WireRequest(
        @NotBlank @Size(max = 100) String msgId,
        @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String uetr,
        @NotNull OffsetDateTime createdAt,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2)
        BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotNull ChargeBearer chargeBearer,
        @NotNull @Valid Party debtor,
        @NotBlank @Pattern(regexp = "^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$")
        String debtorAgentBic,
        @NotNull @Valid Creditor creditor,
        @NotBlank @Pattern(regexp = "^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$")
        String creditorAgentBic,
        @Pattern(regexp = "^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$")
        String intermediaryAgentBic,
        String purposeCode,
        @Size(max = 140) String remittanceInfo) {

    public record Party(
            @NotBlank @Size(max = 140) String name,
            @NotBlank @Pattern(regexp = "^[0-9]{12}$") String accountId) {
    }

    public record Creditor(
            @NotBlank @Size(max = 140) String name,
            @NotBlank @Pattern(regexp = "^[0-9]{12}$") String accountId,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String country) {
    }
}
