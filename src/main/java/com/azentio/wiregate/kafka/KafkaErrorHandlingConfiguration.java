package com.azentio.wiregate.kafka;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlingConfiguration {
    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaWireProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLQ", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setHeadersFunction((record, exception) -> {
            Throwable reasonCause = exception;
            while (reasonCause != null && !(reasonCause instanceof NonRetryableWireException)
                    && reasonCause.getCause() != null) {
                reasonCause = reasonCause.getCause();
            }
            String reason = reasonCause == null || reasonCause.getMessage() == null
                    ? exception.getClass().getSimpleName() : reasonCause.getMessage();
            return new RecordHeaders().add("error-reason", reason.getBytes(StandardCharsets.UTF_8));
        });

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(properties.getRetryBackoffMs(), properties.getRetryCount()));
        errorHandler.addNotRetryableExceptions(NonRetryableWireException.class);
        return errorHandler;
    }
}
