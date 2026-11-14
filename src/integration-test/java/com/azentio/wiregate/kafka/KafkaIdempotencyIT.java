package com.azentio.wiregate.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.azentio.wiregate.api.ChargeBearer;
import com.azentio.wiregate.api.WireRequest;
import com.azentio.wiregate.api.WireResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@EmbeddedKafka(
        partitions = 1,
        topics = {"wires.inbound", "wires.approved", "wires.held", "wires.rejected", "wires.inbound.DLQ"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@SpringBootTest(properties = "spring.kafka.listener.concurrency=1")
class KafkaIdempotencyIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearPaymentsAndResetAccount() {
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
    void redeliveredKafkaWireProducesSameOutcomeButOnlyOneDatabaseDecision() throws Exception {
        UUID uetr = UUID.randomUUID();
        WireRequest wire = new WireRequest(
                "kafka-idempotency-" + uetr,
                uetr.toString(),
                OffsetDateTime.parse("2026-11-14T12:00:00Z"),
                new BigDecimal("1000.00"),
                "INR",
                ChargeBearer.CRED,
                new WireRequest.Party("Synthetic Debtor", "100000000001"),
                "TESTINBBXXX",
                new WireRequest.Creditor("Synthetic Creditor", "200000000001", "AE"),
                "DEMOAEADXXX",
                null,
                "SALA",
                null);
        String payload = objectMapper.writeValueAsString(wire);

        try (Consumer<String, String> consumer = outcomeConsumer("kafka-idempotency-" + uetr)) {
            kafkaTemplate.send("wires.inbound", wire.debtor().accountId(), payload).get();
            kafkaTemplate.send("wires.inbound", wire.debtor().accountId(), payload).get();
            List<ConsumerRecord<String, String>> outcomes = collectOutcomes(consumer, 2);

            assertThat(outcomes).hasSize(2);
            assertThat(outcomes).extracting(ConsumerRecord::topic)
                    .containsOnly("wires.approved");
            assertThat(outcomes).extracting(ConsumerRecord::key).containsOnly(uetr.toString());
            WireResponse first = objectMapper.readValue(outcomes.get(0).value(), WireResponse.class);
            WireResponse second = objectMapper.readValue(outcomes.get(1).value(), WireResponse.class);
            assertThat(second).isEqualTo(first);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wire_payment", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM funds_earmark", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT available_balance FROM accounts WHERE account_id = '100000000001'
                    """, BigDecimal.class)).isEqualByComparingTo("4999000.00");
        }
    }

    @Test
    void malformedKafkaWireIsDeadLetteredWithoutCreatingDatabaseRows() throws Exception {
        try (Consumer<String, String> consumer = outcomeConsumer("kafka-malformed-test")) {
            kafkaTemplate.send("wires.inbound", "100000000001", "{malformed json").get();

            ConsumerRecord<String, String> deadLetter = KafkaTestUtils.getSingleRecord(
                    consumer, "wires.inbound.DLQ", Duration.ofSeconds(10));

            assertThat(new String(deadLetter.headers().lastHeader("error-reason").value()))
                    .contains("Malformed wire JSON");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wire_payment", Long.class)).isEqualTo(0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM funds_earmark", Long.class)).isEqualTo(0);
        }
    }

    private Consumer<String, String> outcomeConsumer(String groupId) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(groupId, "false", broker);
        properties.put("key.deserializer", StringDeserializer.class);
        properties.put("value.deserializer", StringDeserializer.class);
        properties.put("auto.offset.reset", "latest");
        Consumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(
                "wires.approved", "wires.held", "wires.rejected", "wires.inbound.DLQ"));
        consumer.poll(Duration.ofSeconds(1));
        return consumer;
    }

    private static List<ConsumerRecord<String, String>> collectOutcomes(
            Consumer<String, String> consumer, int expectedCount) {
        List<ConsumerRecord<String, String>> outcomes = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (outcomes.size() < expectedCount && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).forEach(outcomes::add);
        }
        return outcomes;
    }
}
