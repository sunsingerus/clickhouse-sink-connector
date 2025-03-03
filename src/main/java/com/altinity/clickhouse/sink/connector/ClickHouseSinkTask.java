package com.altinity.clickhouse.sink.connector;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.deduplicator.DeDuplicator;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchExecutor;
import com.altinity.clickhouse.sink.connector.executor.ClickHouseBatchRunnable;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * <p>Creates sink service instance, takes records loaded from those
 * Kafka partitions and ingests to
 * ClickHouse via Sink service
 */
public class ClickHouseSinkTask extends SinkTask {

    private String id = "-1";
    private static final Logger log = LoggerFactory.getLogger(ClickHouseSinkTask.class);

    public ClickHouseSinkTask() {

    }

    private ClickHouseBatchExecutor executor;
    private ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>> records;

    private DeDuplicator deduplicator;

    private ClickHouseSinkConnectorConfig config;

    @Override
    public void start(Map<String, String> config) {

        //ToDo: Check buffer.count.records and how its used.
        //final long count = Long.parseLong(config.get(ClickHouseSinkConnectorConfigVariables.BUFFER_COUNT));
        //log.info("start({}):{}", this.id, count);
        log.info("start({})", this.id);

        this.config = new ClickHouseSinkConnectorConfig(config);

        Map<String, String> topic2TableMap = null;
        try {
             topic2TableMap = Utils.parseTopicToTableMap(this.config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TOPICS_TABLES_MAP));
        } catch (Exception e) {
            log.error("Error parsing topic to table map" + e);
        }

        this.id = "task-" + this.config.getLong(ClickHouseSinkConnectorConfigVariables.TASK_ID);

        this.records = new ConcurrentHashMap<String, ConcurrentLinkedQueue<ClickHouseStruct>>();
        ClickHouseBatchRunnable runnable = new ClickHouseBatchRunnable(this.records, this.config, topic2TableMap);
        this.executor = new ClickHouseBatchExecutor(1);
        this.executor.scheduleAtFixedRate(runnable, 0, this.config.getLong(ClickHouseSinkConnectorConfigVariables.BUFFER_FLUSH_TIME), TimeUnit.SECONDS);

        this.deduplicator = new DeDuplicator(this.config);
    }

    @Override
    public void stop() {
        log.info("stop({})", this.id);
        if (this.executor != null) {
            this.executor.shutdown();
        }
    }

    @Override
    public void open(final Collection<TopicPartition> partitions) {
        log.info("open({}):{}", this.id, partitions.size());
    }

    @Override
    public void close(final Collection<TopicPartition> partitions) {
        log.info("close({}):{}", this.id, partitions.size());
    }


    @Override
    public void put(Collection<SinkRecord> records) {
        log.debug("CLICKHOUSE received records" + records.size());
        ClickHouseConverter converter = new ClickHouseConverter();

        for (SinkRecord record : records) {
            if (this.deduplicator.isNew(record)) {
                //if (true) {
                ClickHouseStruct c = converter.convert(record);
                if (c != null) {
                    this.appendToRecords(c.getTopic(), c);
                }
            } else {
                log.info("skip already seen record: " + record);
            }
        }
    }

    private void appendToRecords(String topicName, ClickHouseStruct chs) {
        ConcurrentLinkedQueue<ClickHouseStruct> structs = null;

        if(this.records.containsKey(topicName)) {
            structs = this.records.get(topicName);
        } else {
            structs = new ConcurrentLinkedQueue<ClickHouseStruct>();
        }
        structs.add(chs);
        this.records.put(topicName, structs);
    }

    /**
     * preCommit() is a something like a replacement for flush - takes the same parameters
     * Returns the offsets that Kafka Connect should commit.
     * Typical behavior is to call flush and return the same offsets that were passed as params,
     * which means Kafka Connect should commit all the offsets it passed to the connector via preCommit.
     * But if your preCommit returns an empty set of offsets, then Kafka Connect will record no offsets at all.
     * <p>
     * If the connector is going to handle all offsets in the external system and doesn't need Kafka Connect to record anything,
     * then you should override the preCommit method instead of flush, and return an empty set of offsets.
     *
     * @param currentOffsets Offset in Kafka.
     * @return Map of
     * @throws RetriableException
     */
    @Override
    public Map<TopicPartition, OffsetAndMetadata> preCommit(Map<TopicPartition, OffsetAndMetadata> currentOffsets)
            throws RetriableException {
        log.info("preCommit({}) {}", this.id, currentOffsets.size());

        Map<TopicPartition, OffsetAndMetadata> committedOffsets = new HashMap<>();
        try {
            currentOffsets.forEach(
                    (topicPartition, offsetAndMetadata) -> {
                        committedOffsets.put(topicPartition, new OffsetAndMetadata(offsetAndMetadata.offset()));
                    });
        } catch (Exception e) {
            log.error("preCommit({}):{}", this.id, e.getMessage());
            return new HashMap<>();
        }

        return committedOffsets;
    }

    @Override
    public String version() {
        return Version.VERSION;
    }
}
