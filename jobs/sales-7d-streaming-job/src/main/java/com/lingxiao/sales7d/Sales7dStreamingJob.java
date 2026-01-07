package com.lingxiao.sales7d;

import com.lingxiao.sales7d.client.SearchInternalClient;
import com.lingxiao.sales7d.model.Sales7dState;
import com.lingxiao.sales7d.model.Sales7dUpdate;
import com.lingxiao.sales7d.util.TimeUtil;
import org.apache.spark.api.java.function.FlatMapGroupsWithStateFunction;
import org.apache.spark.api.java.function.MapFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.api.java.function.VoidFunction2;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.KeyValueGroupedDataset;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.GroupState;
import org.apache.spark.sql.streaming.GroupStateTimeout;
import org.apache.spark.sql.streaming.OutputMode;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.explode;
import static org.apache.spark.sql.functions.from_json;

public class Sales7dStreamingJob implements Serializable {

    private static final Logger log = LoggerFactory.getLogger(Sales7dStreamingJob.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: Sales7dStreamingJob <kafka-bootstrap> <kafka-topic> <checkpoint-location> <search-service-url>");
            System.exit(1);
        }

        String kafkaBootstrap = args[0];
        String kafkaTopic = args[1];
        String checkpointLocation = args[2];
        String searchServiceUrl = args[3];

        new Sales7dStreamingJob().run(kafkaBootstrap, kafkaTopic, checkpointLocation, searchServiceUrl);
    }

    public void run(String kafkaBootstrap,
                    String kafkaTopic,
                    String checkpointLocation,
                    String searchServiceUrl) throws Exception {

        SparkSession spark = SparkSession.builder()
                .appName("Sales7dStreamingJob")
                .config("spark.sql.streaming.checkpointLocation", checkpointLocation)
                .getOrCreate();

        spark.sparkContext().setLogLevel("WARN");

        Dataset<org.apache.spark.sql.Row> kafkaSource = spark
                .readStream()
                .format("kafka")
                .option("kafka.bootstrap.servers", kafkaBootstrap)
                .option("subscribe", kafkaTopic)
                .option("startingOffsets", "latest")
                .load();

        StructType orderPaidEventSchema = DataTypes.createStructType(new StructField[]{
                DataTypes.createStructField("eventId", DataTypes.StringType, false),
                DataTypes.createStructField("orderId", DataTypes.StringType, false),
                DataTypes.createStructField("paidAt", DataTypes.StringType, false),
                DataTypes.createStructField("items", DataTypes.createArrayType(
                        DataTypes.createStructType(new StructField[]{
                                DataTypes.createStructField("skuId", DataTypes.StringType, false),
                                DataTypes.createStructField("qty", DataTypes.LongType, false)
                        }), false
                ), false)
        });

        Dataset<org.apache.spark.sql.Row> events = kafkaSource
                .selectExpr("CAST(value AS STRING) as json")
                .select(from_json(col("json"), orderPaidEventSchema).as("event"))
                .select("event.*")
                .filter(col("eventId").isNotNull());

        Dataset<org.apache.spark.sql.Row> items = events
                .select(
                        col("orderId"),
                        col("paidAt"),
                        explode(col("items")).as("item")
                )
                .select(
                        col("orderId"),
                        col("paidAt"),
                        col("item.skuId").as("skuId"),
                        col("item.qty").as("qty")
                );

        Dataset<OrderItemRow> itemRows = items.as(Encoders.bean(OrderItemRow.class));

        KeyValueGroupedDataset<String, OrderItemRow> grouped = itemRows.groupByKey(
                (MapFunction<OrderItemRow, String>) OrderItemRow::getSkuId,
                Encoders.STRING()
        );

        Dataset<Sales7dUpdate> updates = grouped.flatMapGroupsWithState(
                new Sales7dStateFunction(),
                OutputMode.Append(),
                Encoders.kryo(Sales7dState.class),
                Encoders.kryo(Sales7dUpdate.class),
                GroupStateTimeout.ProcessingTimeTimeout()
        );

        Dataset<Sales7dUpdate> updatesForSink = updates.coalesce(8);

        StreamingQuery query = updatesForSink.writeStream()
                .foreachBatch(new VoidFunction2<Dataset<Sales7dUpdate>, Long>() {
                    @Override
                    public void call(Dataset<Sales7dUpdate> batch, Long batchId) throws Exception {
                        final long batchTimeHour = System.currentTimeMillis() / 3600000;

                        batch.toJavaRDD().foreachPartition(new VoidFunction<Iterator<Sales7dUpdate>>() {
                            @Override
                            public void call(Iterator<Sales7dUpdate> partition) throws Exception {
                                final int BATCH_SIZE = 500;
                                List<Sales7dUpdate> buf = new ArrayList<>(BATCH_SIZE);

                                SearchInternalClient client = new SearchInternalClient(searchServiceUrl);
                                try {
                                    int totalUpdated = 0;

                                    while (partition.hasNext()) {
                                        buf.add(partition.next());
                                        if (buf.size() >= BATCH_SIZE) {
                                            totalUpdated += sendChunk(client, buf);
                                            buf.clear();
                                        }
                                    }
                                    if (!buf.isEmpty()) {
                                        totalUpdated += sendChunk(client, buf);
                                        buf.clear();
                                    }

                                    log.info("Batch {} partition done: totalUpdated={} hour={}", batchId, totalUpdated, batchTimeHour);

                                } finally {
                                    client.close();
                                }
                            }

                            private int sendChunk(SearchInternalClient client, List<Sales7dUpdate> buf) {
                                try {
                                    return client.bulkUpdateSales7d(buf);
                                } catch (Exception e) {
                                    throw new RuntimeException("search-service bulk update failed", e);
                                }
                            }
                        });
                    }
                })
                .outputMode("append")
                .start();

        query.awaitTermination();
    }

    public static class OrderItemRow implements Serializable {
        private String orderId;
        private String paidAt;
        private String skuId;
        private long qty;

        public OrderItemRow() {}

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getPaidAt() { return paidAt; }
        public void setPaidAt(String paidAt) { this.paidAt = paidAt; }

        public String getSkuId() { return skuId; }
        public void setSkuId(String skuId) { this.skuId = skuId; }

        public long getQty() { return qty; }
        public void setQty(long qty) { this.qty = qty; }
    }

    /**
     * Per-SKU 7d aggregation with 168 hourly ring buckets + slotHour to detect reuse.
     *
     * Tradeoff aligned with你的“取舍1”：
     * - 不做“无事件也刷新 ES”
     * - 仅在有事件触发该 key 的 call() 时，正确清理过期桶 + 产生必要的更新
     * - 使用长 timeout（8 days）只为了清理 state，避免 checkpoint 无限增长
     */
    private static class Sales7dStateFunction
            implements FlatMapGroupsWithStateFunction<String, OrderItemRow, Sales7dState, Sales7dUpdate> {

        private static final Logger log = LoggerFactory.getLogger(Sales7dStateFunction.class);
        private static final int BUCKETS = 168;

        @Override
        public Iterator<Sales7dUpdate> call(String skuId, Iterator<OrderItemRow> items, GroupState<Sales7dState> state) {

            // A) timeout 先处理：只负责清理 state（不负责“整点回调刷新 ES”）
            if (state.hasTimedOut()) {
                state.remove();
                return Collections.emptyIterator();
            }

            Sales7dState currentState = state.exists() ? state.get() : new Sales7dState();

            long currentHour = TimeUtil.currentHour();

            long[] buckets = currentState.getBucketVal();
            long[] slotHours = currentState.getSlotHour();

            long sum7d = currentState.getSum7d();
            boolean dirty = currentState.isDirty();
            long lastEmitHour = currentState.getLastEmitHour();
            long lastAdvancedHour = currentState.getLastAdvancedHour();

            // 拉平 iterator（一个 micro-batch 内同 key 的 items）
            List<OrderItemRow> itemsList = new ArrayList<>();
            while (items.hasNext()) itemsList.add(items.next());

            // 初始化 lastAdvancedHour（只在第一次出现该 sku 时）
            if (lastAdvancedHour == 0) {
                long minPaidAtHour = Long.MAX_VALUE;
                for (OrderItemRow item : itemsList) {
                    long paidAtHour = TimeUtil.parsePaidAtHour(item.getPaidAt());
                    if (paidAtHour != Long.MIN_VALUE) {
                        minPaidAtHour = Math.min(minPaidAtHour, paidAtHour);
                    } else {
                        log.warn("Failed to parse paidAt for init skuId={} orderId={}", skuId, item.getOrderId());
                    }
                }
                lastAdvancedHour = (minPaidAtHour != Long.MAX_VALUE) ? minPaidAtHour : currentHour;
            }

            // 窗口定义：保留最近 168 个小时（含 currentHour）
            // [windowStart, currentHour] = 168 hours
            long windowStart = currentHour - (BUCKETS - 1);

            // B) 只清理“跨过的旧小时”（O(Δhours)），并用 slotHour 校验避免误删
            // 需要清理的小时区间：(-inf, windowStart-1]
            // 因为 lastAdvancedHour 记录“我上次推进到哪里”，所以只清理 (lastAdvancedHour, windowStart-1]
            if (lastAdvancedHour < currentHour) {
                long startExpire = lastAdvancedHour + 1;
                long endExpire = windowStart - 1;

                if (endExpire >= startExpire) {
                    for (long h = startExpire; h <= endExpire; h++) {
                        int idx = (int) Math.floorMod(h, BUCKETS);

                        // 只有当这个槽确实代表小时 h 时才清理（防止桶复用/乱序导致误删）
                        if (slotHours[idx] == h) {
                            long old = buckets[idx];
                            if (old != 0) {
                                sum7d -= old;
                                dirty = true;
                            }
                            buckets[idx] = 0;
                            slotHours[idx] = 0;
                        }
                    }
                }

                // 推进水位线到当前小时（语义：窗口至少被推进到 currentHour）
                lastAdvancedHour = currentHour;
            }

            // 处理新事件
            for (OrderItemRow item : itemsList) {
                long paidAtHour = TimeUtil.parsePaidAtHour(item.getPaidAt());
                if (paidAtHour == Long.MIN_VALUE) {
                    log.warn("Failed to parse paidAt for item skuId={} orderId={}", skuId, item.getOrderId());
                    continue;
                }

                // 太旧/未来事件直接跳过（按“处理时间窗口”口径）
                if (paidAtHour < windowStart) continue;
                if (paidAtHour > currentHour) continue;

                int idx = (int) Math.floorMod(paidAtHour, BUCKETS);

                // 桶复用检测：如果 idx 当前存的不是 paidAtHour 对应的小时，则先把旧贡献从 sum7d 移除
                if (slotHours[idx] != 0 && slotHours[idx] != paidAtHour) {
                    long old = buckets[idx];
                    if (old != 0) {
                        sum7d -= old;
                        dirty = true;
                    }
                    buckets[idx] = 0;
                } else if (slotHours[idx] == 0 && buckets[idx] != 0) {
                    // 防御式：理论上 slotHour=0 时 bucket 应为 0；若不一致，先修正 sum
                    sum7d -= buckets[idx];
                    buckets[idx] = 0;
                    dirty = true;
                }

                // 写入本小时桶
                buckets[idx] += item.getQty();
                slotHours[idx] = paidAtHour;
                sum7d += item.getQty();
                dirty = true;
            }

            // 输出：只要 dirty 且跨过小时边界，就发一次（避免同一小时重复写 ES）
            List<Sales7dUpdate> outputs = new ArrayList<>(1);
            if (dirty && currentHour > lastEmitHour) {
                outputs.add(new Sales7dUpdate(skuId, sum7d, currentHour, Instant.now()));
                dirty = false;
                lastEmitHour = currentHour;
            }

            state.update(new Sales7dState(buckets, slotHours, sum7d, dirty, lastEmitHour, lastAdvancedHour));

            // 仅用于清理不活跃 key（与你的 tradeoff 一致：不做“无事件刷新 ES”）
            state.setTimeoutDuration("8 days");

            return outputs.iterator();
        }
    }
}
