package com.azentio.wiregate.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azentio.wiregate.api.WireRequest;
import com.azentio.wiregate.api.WireResponse;
import com.azentio.wiregate.api.WireStatus;
import com.azentio.wiregate.service.WireDecisionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kafka-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "wire-gate.kafka.retry-backoff-ms=0"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {"wires.inbound", "wires.approved", "wires.held", "wires.rejected", "wires.inbound.DLQ"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class KafkaWireListenerTest {
    private static final UUID UETR = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WireDecisionService decisionService;

    @Test
    void validWirePublishesExactlyOneOutcomeKeyedByUetr() throws Exception {
        WireResponse response = response(WireStatus.APPROVED);
        when(decisionService.decide(any(WireRequest.class))).thenReturn(response);
        try (Consumer<String, String> consumer = consumer("approved-test")) {
            kafkaTemplate.send("wires.inbound", "100000000001", objectMapper.writeValueAsString(request()))
                    .get();

            ConsumerRecord<String, String> event =
                    KafkaTestUtils.getSingleRecord(consumer, "wires.approved", Duration.ofSeconds(10));
            assertThat(event.key()).isEqualTo(UETR.toString());
            assertThat(objectMapper.readValue(event.value(), WireResponse.class).status())
                    .isEqualTo(WireStatus.APPROVED);
            verify(decisionService, times(1)).decide(any(WireRequest.class));
        }
    }

    @Test
    void malformedJsonGoesDirectlyToDlqWithReasonAndDoesNotCallDecisionService() throws Exception {
        try (Consumer<String, String> consumer = consumer("malformed-test")) {
            kafkaTemplate.send("wires.inbound", "100000000001", "{not-json").get();

            ConsumerRecord<String, String> deadLetter =
                    KafkaTestUtils.getSingleRecord(consumer, "wires.inbound.DLQ", Duration.ofSeconds(10));
            Header reason = deadLetter.headers().lastHeader("error-reason");
            assertThat(reason).isNotNull();
            assertThat(new String(reason.value())).contains("Malformed wire JSON");
            assertThat(deadLetter.headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
            assertThat(deadLetter.headers().lastHeader("kafka_dlt-original-partition")).isNotNull();
            assertThat(deadLetter.headers().lastHeader("kafka_dlt-original-offset")).isNotNull();
            verify(decisionService, times(0)).decide(any(WireRequest.class));
        }
    }

    @Test
    void validationFailureGoesDirectlyToDlqWithoutCallingDecisionService() throws Exception {
        try (Consumer<String, String> consumer = consumer("validation-test")) {
            kafkaTemplate.send("wires.inbound", "100000000001", "{}").get();

            ConsumerRecord<String, String> deadLetter =
                    KafkaTestUtils.getSingleRecord(consumer, "wires.inbound.DLQ", Duration.ofSeconds(10));
            assertThat(new String(deadLetter.headers().lastHeader("error-reason").value()))
                    .contains("Wire validation failed");
            verify(decisionService, times(0)).decide(any(WireRequest.class));
        }
    }

    @Test
    void retriesTransientFailuresAndPublishesAfterSuccess() throws Exception {
        WireResponse response = response(WireStatus.HELD);
        when(decisionService.decide(any(WireRequest.class)))
                .thenThrow(new IllegalStateException("temporary database failure"))
                .thenThrow(new IllegalStateException("temporary database failure"))
                .thenReturn(response);
        try (Consumer<String, String> consumer = consumer("retry-success-test")) {
            kafkaTemplate.send("wires.inbound", "100000000001", objectMapper.writeValueAsString(request()))
                    .get();

            ConsumerRecord<String, String> event =
                    KafkaTestUtils.getSingleRecord(consumer, "wires.held", Duration.ofSeconds(10));
            assertThat(event.key()).isEqualTo(UETR.toString());
            verify(decisionService, times(3)).decide(any(WireRequest.class));
        }
    }

    @Test
    void sendsPersistentTransientFailureToDlqAfterThreeRetries() throws Exception {
        when(decisionService.decide(any(WireRequest.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        try (Consumer<String, String> consumer = consumer("retry-failure-test")) {
            kafkaTemplate.send("wires.inbound", "100000000001", objectMapper.writeValueAsString(request()))
                    .get();

            ConsumerRecord<String, String> deadLetter =
                    KafkaTestUtils.getSingleRecord(consumer, "wires.inbound.DLQ", Duration.ofSeconds(10));
            assertThat(new String(deadLetter.headers().lastHeader("error-reason").value()))
                    .contains("database unavailable");
            verify(decisionService, times(4)).decide(any(WireRequest.class));
        }
    }

    private Consumer<String, String> consumer(String groupId) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps(groupId, "false", broker);
        properties.put("key.deserializer",
                org.apache.kafka.common.serialization.StringDeserializer.class);
        properties.put("value.deserializer",
                org.apache.kafka.common.serialization.StringDeserializer.class);
        properties.put("auto.offset.reset", "latest");
        Consumer<String, String> consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of("wires.approved", "wires.held", "wires.rejected", "wires.inbound.DLQ"));
        consumer.poll(Duration.ofSeconds(1));
        return consumer;
    }

    private static WireRequest request() {
        return new WireRequest(
                "message-1", UETR.toString(), OffsetDateTime.parse("2026-11-14T12:00:00Z"),
                new BigDecimal("100.00"), "INR", com.azentio.wiregate.api.ChargeBearer.CRED,
                new WireRequest.Party("Synthetic Debtor", "100000000001"), "TESTINBBXXX",
                new WireRequest.Creditor("Synthetic Creditor", "200000000001", "AE"),
                "DEMOAEADXXX", null, "SALA", null);
    }

    private static WireResponse response(WireStatus status) {
        return new WireResponse(
                UETR, status, List.of(), new BigDecimal("100.00"), "INR", BigDecimal.ZERO,
                new BigDecimal("100.00"), "****0001", "****0001",
                "TESTINBBXXX", "DEMOAEADXXX", null, "IN-AE", null);
    }
}
