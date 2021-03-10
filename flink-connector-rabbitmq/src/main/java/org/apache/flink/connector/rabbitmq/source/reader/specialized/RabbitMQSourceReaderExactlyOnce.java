/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.rabbitmq.source.reader.specialized;

import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.connector.rabbitmq.source.common.RabbitMQSourceMessageWrapper;
import org.apache.flink.connector.rabbitmq.source.reader.RabbitMQSourceReaderBase;
import org.apache.flink.connector.rabbitmq.source.split.RabbitMQSourceSplit;
import org.apache.flink.util.Preconditions;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * The RabbitMQSourceReaderExactlyOnce provides exactly-once guarantee. The deliveryTag from the
 * received messages are used to acknowledge the messages once it is assured that they are safely
 * consumed by the output. In addition, correlation ids are used to deduplicate messages. Messages
 * polled by the output are stored so they can be later acknowledged. During a checkpoint the
 * messages that were polled since the last checkpoint are associated with the id of the current
 * checkpoint. Once the checkpoint is completed, the messages for the checkpoint are acknowledged in
 * a transaction to assure that RabbitMQ successfully receives the acknowledgements.
 *
 * <p>In order for the exactly-once source reader to work, checkpointing needs to be enabled and the
 * message from RabbitMQ need to have a correlation id.
 *
 * @param <T> The output type of the source.
 * @see RabbitMQSourceReaderBase
 */
public class RabbitMQSourceReaderExactlyOnce<T> extends RabbitMQSourceReaderBase<T> {
    // Message that were polled by the output since the last checkpoint was created.
    // These messages are currently forward but not yet acknowledged to RabbitMQ.
    // It needs to be ensured they are persisted before they can be acknowledged and thus be delete
    // in RabbitMQ.
    private final List<RabbitMQSourceMessageWrapper<T>>
            polledAndUnacknowledgedMessagesSinceLastCheckpoint;

    // All message in polledAndUnacknowledgedMessagesSinceLastCheckpoint will move to hear when
    // a new checkpoint is created and therefore the messages can be mapped to it. This mapping is
    // necessary to ensure we acknowledge only message which belong to a completed checkpoint.
    private final BlockingQueue<Tuple2<Long, List<RabbitMQSourceMessageWrapper<T>>>>
            polledAndUnacknowledgedMessagesPerCheckpoint;

    // Set of correlation ids that have been seen and are not acknowledged yet.
    // The message publisher (who pushes the messages to RabbitMQ) is obligated to set the
    // correlation id per message and ensure their uniqueness.
    private final ConcurrentHashMap.KeySetView<String, Boolean> correlationIds;

    public RabbitMQSourceReaderExactlyOnce(
            SourceReaderContext sourceReaderContext,
            DeserializationSchema<T> deliveryDeserializer) {
        super(sourceReaderContext, deliveryDeserializer);
        this.polledAndUnacknowledgedMessagesSinceLastCheckpoint =
                Collections.synchronizedList(new ArrayList<>());
        this.polledAndUnacknowledgedMessagesPerCheckpoint = new LinkedBlockingQueue<>();
        this.correlationIds = ConcurrentHashMap.newKeySet();
    }

    @Override
    protected boolean isAutoAck() {
        return false;
    }

    @Override
    protected void handleMessagePolled(RabbitMQSourceMessageWrapper<T> message) {
        System.out.println("handleMessagePolled  " + message.getMessage());
        this.polledAndUnacknowledgedMessagesSinceLastCheckpoint.add(message);
    }

    @Override
    protected void handleMessageReceivedCallback(String consumerTag, Delivery delivery)
            throws IOException {
        AMQP.BasicProperties properties = delivery.getProperties();
        String correlationId = properties.getCorrelationId();
        Preconditions.checkNotNull(
                correlationId,
                "RabbitMQ source was instantiated "
                        + "with consistencyMode set EXACTLY_ONCE yet we couldn't extract the correlation id from it !");

        Envelope envelope = delivery.getEnvelope();
        long deliveryTag = envelope.getDeliveryTag();

        if (correlationIds.add(correlationId)) {
            // Handle the message only if the correlation id hasn't been seen before.
            // The message will follow the normal process and be acknowledge when it got polled.
            super.handleMessageReceivedCallback(consumerTag, delivery);
        } else {
            // Otherwise, store the new delivery-tag for later acknowledgments. The correlation id
            // was seen before and therefore this is a duplicate received from RabbitMQ.
            // Instead of letting the message to be polled, the message will directly be marked
            // to be acknowledged in the next wave of acknowledgments under their new deliveryTag.
            polledAndUnacknowledgedMessagesSinceLastCheckpoint.add(
                    new RabbitMQSourceMessageWrapper<>(deliveryTag, correlationId));
        }
    }

    @Override
    public List<RabbitMQSourceSplit> snapshotState(long checkpointId) {
        Tuple2<Long, List<RabbitMQSourceMessageWrapper<T>>> tuple =
                new Tuple2<>(
                        checkpointId,
                        new ArrayList<>(polledAndUnacknowledgedMessagesSinceLastCheckpoint));
        polledAndUnacknowledgedMessagesPerCheckpoint.add(tuple);
        polledAndUnacknowledgedMessagesSinceLastCheckpoint.clear();

        if (getSplit() != null) {
            getSplit().setCorrelationIds(correlationIds);
        }
        return super.snapshotState(checkpointId);
    }

    @Override
    public void addSplits(List<RabbitMQSourceSplit> list) {
        super.addSplits(list);
        correlationIds.addAll(getSplit().getCorrelationIds());
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws IOException {
        Iterator<Tuple2<Long, List<RabbitMQSourceMessageWrapper<T>>>> checkpointIterator =
                polledAndUnacknowledgedMessagesPerCheckpoint.iterator();
        while (checkpointIterator.hasNext()) {
            final Tuple2<Long, List<RabbitMQSourceMessageWrapper<T>>> nextCheckpoint =
                    checkpointIterator.next();
            long nextCheckpointId = nextCheckpoint.f0;
            if (nextCheckpointId <= checkpointId) {
                acknowledgeMessages(nextCheckpoint.f1);
                checkpointIterator.remove();
            }
        }
    }

    @Override
    protected void setupChannel() throws IOException {
        super.setupChannel();
        // enable channel transactional mode
        getRmqChannel().txSelect();
    }

    private void acknowledgeMessages(List<RabbitMQSourceMessageWrapper<T>> messages)
            throws IOException {
        List<String> correlationIds =
                messages.stream()
                        .map(RabbitMQSourceMessageWrapper::getCorrelationId)
                        .collect(Collectors.toList());
        this.correlationIds.removeAll(correlationIds);
        try {
            List<Long> deliveryTags =
                    messages.stream()
                            .map(RabbitMQSourceMessageWrapper::getDeliveryTag)
                            .collect(Collectors.toList());
            acknowledgeMessageIds(deliveryTags);
            getRmqChannel().txCommit();
            LOG.info("Successfully acknowledged " + deliveryTags.size() + " messages.");
        } catch (IOException e) {
            LOG.error(
                    "Error during acknowledgement of "
                            + correlationIds.size()
                            + " messages. CorrelationIds will be rolled back. Error: "
                            + e.getMessage());
            this.correlationIds.addAll(correlationIds);
            throw e;
        }
    }
}