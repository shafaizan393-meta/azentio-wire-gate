package com.azentio.wiregate.kafka;

import com.azentio.wiregate.api.WireRequest;
import com.azentio.wiregate.api.WireResponse;
import com.azentio.wiregate.api.WireStatus;
import com.azentio.wiregate.service.WireDecisionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaWireListener {
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final WireDecisionService decisionService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaWireProperties properties;

    public KafkaWireListener(
            ObjectMapper objectMapper,
            Validator validator,
            WireDecisionService decisionService,
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaWireProperties properties) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.decisionService = decisionService;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @KafkaListener(topics = "${wire-gate.kafka.inbound-topic:wires.inbound}")
    public void onMessage(ConsumerRecord<String, String> record) {
        String payload = record.value();
        if (payload == null) {
            throw new NonRetryableWireException("Wire message body is empty.");
        }
        WireRequest request;
        try {
            request = objectMapper.readValue(payload, WireRequest.class);
        } catch (JsonProcessingException exception) {
            throw new NonRetryableWireException("Malformed wire JSON.", exception);
        }
        if (request == null) {
            throw new NonRetryableWireException("Wire message must contain a JSON object.");
        }
        Set<String> violations = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.toSet());
        if (!violations.isEmpty()) {
            throw new NonRetryableWireException(
                    "Wire validation failed: " + String.join("; ", violations));
        }
        if (record.key() == null || !record.key().equals(request.debtor().accountId())) {
            throw new NonRetryableWireException(
                    "Kafka record key must match the debtor accountId.");
        }

        WireResponse response = decisionService.decide(request);
        publish(outcomeTopic(response.status()), response.uetr().toString(), response);
    }

    private String outcomeTopic(WireStatus status) {
        return switch (status) {
            case APPROVED -> properties.getApprovedTopic();
            case HELD -> properties.getHeldTopic();
            case REJECTED -> properties.getRejectedTopic();
        };
    }

    private void publish(String topic, String key, WireResponse response) {
        try {
            kafkaTemplate.send(topic, key, objectMapper.writeValueAsString(response))
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KafkaPublishException("Interrupted while publishing wire outcome.", exception);
        } catch (Exception exception) {
            throw new KafkaPublishException("Could not publish wire outcome to " + topic + ".", exception);
        }
    }
}
