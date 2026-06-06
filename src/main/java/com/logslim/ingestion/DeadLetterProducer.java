package com.logslim.ingestion;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Writes unprocessable records to a dead-letter Kafka topic so ingestion
 * can advance past poison pills without losing the original payload.
 *
 * When no DLQ topic is configured, falls back to WARN-level logging so the
 * payload is preserved in the log stream and ingestion still advances.
 */
class DeadLetterProducer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterProducer.class);

    private final String dlqTopic;
    private final KafkaProducer<String, String> producer;

    DeadLetterProducer(String bootstrapServers, String dlqTopic) {
        this.dlqTopic = dlqTopic.isBlank() ? null : dlqTopic;
        if (this.dlqTopic != null) {
            Properties props = new Properties();
            props.put("bootstrap.servers", bootstrapServers);
            props.put("key.serializer", StringSerializer.class.getName());
            props.put("value.serializer", StringSerializer.class.getName());
            // acks=1: don't block ingestion on DLQ write durability
            props.put("acks", "1");
            this.producer = new KafkaProducer<>(props);
            log.info("dead-letter producer initialised → topic '{}'", this.dlqTopic);
        } else {
            this.producer = null;
            log.info("dead-letter producer disabled (logslim.ingestion.dlq.topic not set) — poison pills logged only");
        }
    }

    void send(String value) {
        if (producer != null) {
            producer.send(new ProducerRecord<>(dlqTopic, value), (meta, ex) -> {
                if (ex != null) {
                    log.error("failed to write to DLQ topic '{}': {}", dlqTopic, ex.getMessage());
                }
            });
        } else {
            log.warn("[DLQ] unprocessable record dropped: {}", value);
        }
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.flush();
            producer.close();
        }
    }
}
