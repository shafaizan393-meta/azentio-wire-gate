package com.azentio.wiregate.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wire-gate")
public class WireGateProperties {
    private Map<String, BigDecimal> fees = new HashMap<>();
    private Set<String> highRiskCountries = new HashSet<>();
    private Set<String> restrictedBics = new HashSet<>();
    private Map<String, Set<String>> corridorPurposeCodes = new HashMap<>();

    public Map<String, BigDecimal> getFees() {
        return fees;
    }

    public void setFees(Map<String, BigDecimal> fees) {
        this.fees = fees;
    }

    public Set<String> getHighRiskCountries() {
        return highRiskCountries;
    }

    public void setHighRiskCountries(Set<String> highRiskCountries) {
        this.highRiskCountries = highRiskCountries;
    }

    public Set<String> getRestrictedBics() {
        return restrictedBics;
    }

    public void setRestrictedBics(Set<String> restrictedBics) {
        this.restrictedBics = restrictedBics;
    }

    public Map<String, Set<String>> getCorridorPurposeCodes() {
        return corridorPurposeCodes;
    }

    public void setCorridorPurposeCodes(Map<String, Set<String>> corridorPurposeCodes) {
        this.corridorPurposeCodes = corridorPurposeCodes;
    }
}
