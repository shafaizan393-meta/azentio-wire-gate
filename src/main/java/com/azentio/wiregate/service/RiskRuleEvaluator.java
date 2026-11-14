package com.azentio.wiregate.service;

import com.azentio.wiregate.api.WireReason;
import com.azentio.wiregate.api.WireRequest;
import com.azentio.wiregate.config.WireGateProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RiskRuleEvaluator {
    private final WireGateProperties properties;

    public RiskRuleEvaluator(WireGateProperties properties) {
        this.properties = properties;
    }

    public List<WireReason> evaluate(WireRequest request) {
        List<WireReason> reasons = new ArrayList<>();
        Set<String> highRisk = properties.getHighRiskCountries();
        if (highRisk.contains(request.creditor().country())
                || highRisk.contains(countryFromBic(request.creditorAgentBic()))
                || request.intermediaryAgentBic() != null
                && highRisk.contains(countryFromBic(request.intermediaryAgentBic()))) {
            reasons.add(new WireReason("HIGH_RISK_COUNTRY",
                    "The creditor country or an agent BIC country is configured as high risk."));
        }

        Set<String> restricted = properties.getRestrictedBics();
        if (restricted.contains(prefix(request.debtorAgentBic()))
                || restricted.contains(prefix(request.creditorAgentBic()))
                || request.intermediaryAgentBic() != null
                && restricted.contains(prefix(request.intermediaryAgentBic()))) {
            reasons.add(new WireReason("RESTRICTED_BIC",
                    "An agent BIC matches a configured restricted BIC."));
        }

        String corridor = corridor(request.debtorAgentBic(), request.creditor().country());
        Set<String> allowedCodes = properties.getCorridorPurposeCodes().get(corridor);
        if (allowedCodes != null && (request.purposeCode() == null
                || !allowedCodes.contains(request.purposeCode()))) {
            reasons.add(new WireReason("CORRIDOR_PURPOSE",
                    "The purpose code is missing or not allowed for corridor " + corridor + "."));
        }
        return List.copyOf(reasons);
    }

    public static String countryFromBic(String bic) {
        return bic.substring(4, 6).toUpperCase(Locale.ROOT);
    }

    public static String prefix(String bic) {
        return bic.substring(0, 8);
    }

    public static String corridor(String debtorAgentBic, String creditorCountry) {
        return countryFromBic(debtorAgentBic) + "-" + creditorCountry;
    }
}
