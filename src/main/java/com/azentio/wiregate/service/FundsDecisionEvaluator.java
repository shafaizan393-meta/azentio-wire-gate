package com.azentio.wiregate.service;

import com.azentio.wiregate.api.ChargeBearer;
import com.azentio.wiregate.api.WireReason;
import com.azentio.wiregate.config.WireGateProperties;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class FundsDecisionEvaluator {
    private final WireGateProperties properties;

    public FundsDecisionEvaluator(WireGateProperties properties) {
        this.properties = properties;
    }

    public BigDecimal feeFor(ChargeBearer chargeBearer) {
        BigDecimal fee = properties.getFees().get(chargeBearer.name());
        if (fee == null || fee.signum() < 0) {
            throw new IllegalStateException("A non-negative fee must be configured for " + chargeBearer);
        }
        return fee;
    }

    public Optional<WireReason> evaluate(
            AccountSnapshot account,
            String wireCurrency,
            BigDecimal amount,
            BigDecimal fee,
            Supplier<BigDecimal> alreadyEarmarkedToday) {
        if (account == null || !"ACTIVE".equals(account.status())) {
            return Optional.of(new WireReason("ACCOUNT_INVALID", "Debtor account is missing or not ACTIVE."));
        }
        if (!account.currency().equals(wireCurrency)) {
            return Optional.of(new WireReason(
                    "CURRENCY_MISMATCH", "Wire currency differs from the debtor account currency."));
        }
        if (account.availableBalance().compareTo(amount.add(fee)) < 0) {
            return Optional.of(new WireReason(
                    "INSUFFICIENT_FUNDS", "Available balance does not cover amount plus fee."));
        }
        if (alreadyEarmarkedToday.get().add(amount).compareTo(account.dailyOutwardLimit()) > 0) {
            return Optional.of(new WireReason(
                    "DAILY_LIMIT_EXCEEDED", "Wire amount exceeds the remaining daily outward limit."));
        }
        return Optional.empty();
    }

    public record AccountSnapshot(
            String currency, BigDecimal availableBalance, BigDecimal dailyOutwardLimit, String status) {
    }
}
