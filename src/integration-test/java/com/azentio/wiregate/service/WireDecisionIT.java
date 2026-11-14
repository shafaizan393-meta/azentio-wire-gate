package com.azentio.wiregate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azentio.wiregate.api.ChargeBearer;
import com.azentio.wiregate.api.WireConflictException;
import com.azentio.wiregate.api.WirePageResponse;
import com.azentio.wiregate.api.WireRequest;
import com.azentio.wiregate.api.WireResponse;
import com.azentio.wiregate.api.WireStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "wire-gate.fees.DEBT=1500.00",
        "wire-gate.fees.SHAR=750.00",
        "wire-gate.fees.CRED=0.00"
})
@ExtendWith(OutputCaptureExtension.class)
class WireDecisionIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WireDecisionService decisionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearPaymentsAndResetPrimaryAccount() {
        jdbc.update("DELETE FROM alert");
        jdbc.update("DELETE FROM funds_earmark");
        jdbc.update("DELETE FROM wire_payment");
        jdbc.update("""
                UPDATE accounts SET available_balance = 5000000.00,
                    daily_outward_limit = 2000000.00, status = 'ACTIVE'
                WHERE account_id = '100000000001'
                """);
    }

    @Test
    void repeatedUetrReturnsOriginalResponseAndOnlyCreatesOneEarmark() {
        WireRequest wire = wire(UUID.randomUUID(), "100000000001", "1000.00", "SALA",
                "2026-11-14T12:00:00Z");

        WireResponse first = decisionService.decide(wire);
        WireResponse second = decisionService.decide(wire);

        assertThat(first.status()).isEqualTo(WireStatus.APPROVED);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wire_payment", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM funds_earmark", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT available_balance FROM accounts WHERE account_id = '100000000001'
                """, BigDecimal.class)).isEqualByComparingTo("4999000.00");

        WireRequest changed = new WireRequest(
                wire.msgId(), wire.uetr(), wire.createdAt(), new BigDecimal("1001.00"),
                wire.currency(), wire.chargeBearer(), wire.debtor(), wire.debtorAgentBic(),
                wire.creditor(), wire.creditorAgentBic(), wire.intermediaryAgentBic(),
                wire.purposeCode(), wire.remittanceInfo());
        assertThatThrownBy(() -> decisionService.decide(changed))
                .isInstanceOf(WireConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM funds_earmark", Long.class)).isEqualTo(1);
    }

    @Test
    void responsesAndDecisionLogsContainMaskedButNotFullAccountNumber(CapturedOutput output)
            throws Exception {
        String accountId = "100000000001";
        WireResponse response = decisionService.decide(
                wire(UUID.randomUUID(), accountId, "100.00", "SALA", "2026-11-14T12:00:00Z"));
        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("****0001").doesNotContain(accountId);
        assertThat(output.getOut()).contains("****0001").doesNotContain(accountId);
    }

    @Test
    void dailyLimitUsesUtcBusinessDateAcrossOffsetRepresentations() {
        jdbc.update("""
                UPDATE accounts SET available_balance = 1000.00, daily_outward_limit = 150.00
                WHERE account_id = '100000000001'
                """);
        WireResponse first = decisionService.decide(
                wire(UUID.randomUUID(), "100000000001", "100.00", "SALA", "2026-11-14T23:00:00Z"));
        WireResponse second = decisionService.decide(
                wire(UUID.randomUUID(), "100000000001", "100.00", "SALA", "2026-11-15T01:00:00+02:00"));

        assertThat(first.status()).isEqualTo(WireStatus.APPROVED);
        assertThat(second.status()).isEqualTo(WireStatus.REJECTED);
        assertThat(second.reasons()).extracting("code").containsExactly("DAILY_LIMIT_EXCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM funds_earmark", Long.class)).isEqualTo(1);
    }

    @Test
    void debtFeeCanRejectWhileCredChargeBearerFitsTheSameBalance() {
        jdbc.update("""
                UPDATE accounts SET available_balance = 100.00, daily_outward_limit = 1000.00
                WHERE account_id = '100000000001'
                """);
        WireRequest debtWire = wire(UUID.randomUUID(), "100000000001", "100.00", "SALA",
                "2026-11-14T12:00:00Z");
        debtWire = withChargeBearer(debtWire, ChargeBearer.DEBT);
        WireRequest creditWire = withChargeBearer(
                wire(UUID.randomUUID(), "100000000001", "100.00", "SALA", "2026-11-14T12:00:00Z"),
                ChargeBearer.CRED);

        WireResponse debtResponse = decisionService.decide(debtWire);
        WireResponse creditResponse = decisionService.decide(creditWire);

        assertThat(debtResponse.status()).isEqualTo(WireStatus.REJECTED);
        assertThat(debtResponse.reasons()).extracting("code").containsExactly("INSUFFICIENT_FUNDS");
        assertThat(debtResponse.fee()).isEqualByComparingTo("1500.00");
        assertThat(creditResponse.status()).isEqualTo(WireStatus.APPROVED);
        assertThat(creditResponse.fee()).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM funds_earmark", Long.class)).isEqualTo(1);
    }

    @Test
    void keysetPaginationReturns45HeldWiresNewestFirstWithoutDuplicates() {
        jdbc.update("""
                UPDATE accounts SET available_balance = 1000000000.00,
                    daily_outward_limit = 1000000000.00
                WHERE account_id = '100000000001'
                """);
        OffsetDateTime start = OffsetDateTime.parse("2026-11-14T12:00:00Z");
        List<UUID> expectedNewestFirst = new ArrayList<>();
        for (int index = 0; index < 45; index++) {
            UUID uetr = UUID.randomUUID();
            decisionService.decide(wire(uetr, "100000000001", "100.00", null,
                    start.plusSeconds(index).toString()));
            expectedNewestFirst.add(0, uetr);
        }

        List<WireResponse> all = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            WirePageResponse page = decisionService.list(WireStatus.HELD, 20, cursor);
            pageSizes.add(page.items().size());
            all.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(20, 20, 5);
        assertThat(all).hasSize(45).extracting(WireResponse::status)
                .containsOnly(WireStatus.HELD);
        assertThat(all.stream().map(WireResponse::uetr).collect(Collectors.toSet())).hasSize(45);
        assertThat(all).extracting(WireResponse::uetr).containsExactlyElementsOf(expectedNewestFirst);
    }

    @Test
    void concurrentWiresCannotOverspendAccountBalance() throws Exception {
        jdbc.update("""
                UPDATE accounts SET available_balance = 100000.00,
                    daily_outward_limit = 1000000.00
                WHERE account_id = '100000000001'
                """);
        var executor = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<WireResponse>> futures = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                WireRequest wire = wire(UUID.randomUUID(), "100000000001", "10000.00", "SALA",
                        "2026-11-14T12:00:00Z");
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test did not start.");
                    }
                    return decisionService.decide(wire);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<WireResponse> responses = new ArrayList<>();
            for (Future<WireResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(responses.stream().filter(response -> response.status() == WireStatus.APPROVED))
                    .hasSize(10);
            assertThat(responses.stream().filter(response -> response.status() == WireStatus.REJECTED))
                    .hasSize(10).allSatisfy(response ->
                            assertThat(response.reasons()).extracting("code")
                                    .containsExactly("INSUFFICIENT_FUNDS"));
            assertThat(jdbc.queryForObject("""
                    SELECT available_balance FROM accounts WHERE account_id = '100000000001'
                    """, BigDecimal.class)).isEqualByComparingTo("0.00");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM funds_earmark", Long.class)).isEqualTo(10);
        } finally {
            executor.shutdownNow();
        }
    }

    private static WireRequest wire(
            UUID uetr, String accountId, String amount, String purpose, String createdAt) {
        return new WireRequest(
                "integration-" + uetr,
                uetr.toString(),
                OffsetDateTime.parse(createdAt),
                new BigDecimal(amount),
                "INR",
                ChargeBearer.CRED,
                new WireRequest.Party("Synthetic Debtor", accountId),
                "TESTINBBXXX",
                new WireRequest.Creditor("Synthetic Creditor", "200000000001", "AE"),
                "DEMOAEADXXX",
                null,
                purpose,
                null);
    }

    private static WireRequest withChargeBearer(WireRequest wire, ChargeBearer chargeBearer) {
        return new WireRequest(
                wire.msgId(), wire.uetr(), wire.createdAt(), wire.amount(), wire.currency(),
                chargeBearer, wire.debtor(), wire.debtorAgentBic(), wire.creditor(),
                wire.creditorAgentBic(), wire.intermediaryAgentBic(), wire.purposeCode(),
                wire.remittanceInfo());
    }
}
