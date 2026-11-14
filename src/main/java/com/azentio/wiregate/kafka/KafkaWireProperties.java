package com.azentio.wiregate.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wire-gate.kafka")
public class KafkaWireProperties {
    private String inboundTopic = "wires.inbound";
    private String approvedTopic = "wires.approved";
    private String heldTopic = "wires.held";
    private String rejectedTopic = "wires.rejected";
    private long retryBackoffMs = 250;
    private long retryCount = 3;

    public String getInboundTopic() {
        return inboundTopic;
    }

    public void setInboundTopic(String inboundTopic) {
        this.inboundTopic = inboundTopic;
    }

    public String getApprovedTopic() {
        return approvedTopic;
    }

    public void setApprovedTopic(String approvedTopic) {
        this.approvedTopic = approvedTopic;
    }

    public String getHeldTopic() {
        return heldTopic;
    }

    public void setHeldTopic(String heldTopic) {
        this.heldTopic = heldTopic;
    }

    public String getRejectedTopic() {
        return rejectedTopic;
    }

    public void setRejectedTopic(String rejectedTopic) {
        this.rejectedTopic = rejectedTopic;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public long getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(long retryCount) {
        this.retryCount = retryCount;
    }
}
