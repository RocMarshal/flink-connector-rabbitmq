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

package org.apache.flink.connector.rabbitmq.source.enumerator;

import org.apache.flink.core.io.SimpleVersionedSerializer;

/**
 * The EnumStateSerializer does nothing particular because the EnumState does not contain data.
 *
 * @see RabbitMQSourceEnumState
 */
public class RabbitMQSourceEnumStateSerializer
        implements SimpleVersionedSerializer<RabbitMQSourceEnumState> {

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public byte[] serialize(RabbitMQSourceEnumState rabbitMQSourceEnumState) {
        if (getVersion() == 1) {
            return new byte[0];
        }
        throw new RuntimeException("Version " + getVersion() + " is not supported");
    }

    @Override
    public RabbitMQSourceEnumState deserialize(int i, byte[] bytes) {
        if (getVersion() == 1) {
            return new RabbitMQSourceEnumState();
        }
        throw new RuntimeException("Version " + getVersion() + " is not supported");
    }
}