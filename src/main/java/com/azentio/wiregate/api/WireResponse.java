package com.azentio.wiregate.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record WireResponse(
        UUID uetr,
        WireStatus status,
        List<WireReason> reasons,
        BigDecimal fee,
        BigDecimal earmarkedAmount,
        String debtorAccountMasked,
        String creditorAccountMasked,
        String corridor,
        Long alertId) {
}
