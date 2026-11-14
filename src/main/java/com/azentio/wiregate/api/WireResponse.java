package com.azentio.wiregate.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record WireResponse(
        UUID uetr,
        WireStatus status,
        List<WireReason> reasons,
        BigDecimal amount,
        String currency,
        BigDecimal fee,
        BigDecimal earmarkedAmount,
        String debtorAccountMasked,
        String creditorAccountMasked,
        String debtorAgentBic,
        String creditorAgentBic,
        String intermediaryAgentBic,
        String corridor,
        Long alertId) {
}
