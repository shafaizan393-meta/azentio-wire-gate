import json
import os
import time
import uuid
from collections import Counter
from datetime import datetime, timezone

from kafka import KafkaConsumer, KafkaProducer


def main() -> None:
    count = int(os.getenv("LOAD_MESSAGE_COUNT", "10000"))
    malformed_count = max(1, count // 100)
    bootstrap_servers = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    inbound_topic = os.getenv("WIRE_INBOUND_TOPIC", "wires.inbound")
    outcome_topics = [
        "wires.approved",
        "wires.held",
        "wires.rejected",
        f"{inbound_topic}.DLQ",
    ]
    group_id = f"wire-gate-load-{uuid.uuid4()}"
    consumer = KafkaConsumer(
        *outcome_topics,
        bootstrap_servers=bootstrap_servers,
        group_id=group_id,
        auto_offset_reset="latest",
        enable_auto_commit=False,
        consumer_timeout_ms=1000,
    )
    producer = KafkaProducer(
        bootstrap_servers=bootstrap_servers,
        key_serializer=lambda key: key.encode("utf-8"),
        value_serializer=lambda value: (
            value.encode("utf-8") if isinstance(value, str) else json.dumps(value).encode("utf-8")
        ),
        acks="all",
        retries=5,
    )

    try:
        deadline = time.monotonic() + 30
        while not consumer.assignment() and time.monotonic() < deadline:
            consumer.poll(timeout_ms=250)
        if not consumer.assignment():
            raise TimeoutError("Kafka outcome topics could not be assigned to the load consumer.")

        started = time.monotonic()
        created_at = datetime.now(timezone.utc).isoformat()
        pending = []
        for index in range(count):
            account_number = 1 + index % 200
            account_id = f"{200000000000 + account_number:012d}"
            if index < malformed_count:
                future = producer.send(inbound_topic, key=account_id, value="{malformed json")
                pending.append((None, future))
                continue
            uetr = str(uuid.uuid4())
            wire = {
                "msgId": f"load-{index:05d}",
                "uetr": uetr,
                "createdAt": created_at,
                "amount": "1000.00",
                "currency": "INR",
                "chargeBearer": "CRED",
                "debtor": {"name": "Synthetic Load Debtor", "accountId": account_id},
                "debtorAgentBic": "TESTINBBXXX",
                "creditor": {
                    "name": "Synthetic Load Creditor",
                    "accountId": f"{300000000000 + account_number:012d}",
                    "country": "AE",
                },
                "creditorAgentBic": "DEMOAEADXXX",
                "purposeCode": "SALA",
            }
            future = producer.send(inbound_topic, key=account_id, value=wire)
            pending.append((uetr, future))

        producer.flush(timeout=60)
        source_offset_by_uetr = {}
        expected_source_offsets = set()
        for uetr, future in pending:
            metadata = future.get(timeout=10)
            source_offset = (metadata.partition, metadata.offset)
            expected_source_offsets.add(source_offset)
            if uetr is not None:
                source_offset_by_uetr[uetr] = source_offset

        counts: Counter[str] = Counter()
        completed_source_offsets = set()
        deadline = time.monotonic() + int(os.getenv("LOAD_TIMEOUT_SECONDS", "600"))
        while len(completed_source_offsets) < count and time.monotonic() < deadline:
            for record in consumer.poll(timeout_ms=1000, max_records=1000).values():
                for message in record:
                    if message.topic == f"{inbound_topic}.DLQ":
                        original_partition = message.headers and dict(message.headers).get(
                            "kafka_dlt-original-partition"
                        )
                        original_offset = message.headers and dict(message.headers).get(
                            "kafka_dlt-original-offset"
                        )
                        if original_partition is None or original_offset is None:
                            continue
                        source_offset = (
                            int.from_bytes(original_partition, byteorder="big", signed=True),
                            int.from_bytes(original_offset, byteorder="big", signed=True),
                        )
                        if source_offset in expected_source_offsets and source_offset not in completed_source_offsets:
                            completed_source_offsets.add(source_offset)
                            counts[message.topic] += 1
                    elif message.key is not None:
                        uetr = message.key.decode("utf-8")
                        source_offset = source_offset_by_uetr.get(uetr)
                        if source_offset is not None and source_offset not in completed_source_offsets:
                            completed_source_offsets.add(source_offset)
                            counts[message.topic] += 1

        elapsed = time.monotonic() - started
        completed = len(completed_source_offsets)
        if completed < count:
            raise TimeoutError(f"Received {completed} of {count} expected outcome/DLQ records.")
        print(f"messages={completed} elapsed_seconds={elapsed:.2f} throughput_per_second={completed / elapsed:.2f}")
        for topic in outcome_topics:
            print(f"{topic}={counts[topic]}")
    finally:
        producer.close()
        consumer.close()


if __name__ == "__main__":
    main()
