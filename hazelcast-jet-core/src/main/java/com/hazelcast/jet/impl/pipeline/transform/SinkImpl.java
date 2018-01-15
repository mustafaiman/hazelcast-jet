/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.pipeline.transform;

import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.function.DistributedSupplier;
import com.hazelcast.jet.impl.pipeline.transform.Transform;
import com.hazelcast.jet.pipeline.Sink;

public class SinkImpl<T> implements Sink<T>, Transform {
    private final String name;
    private final ProcessorMetaSupplier metaSupplier;

    public SinkImpl(String name, ProcessorMetaSupplier metaSupplier) {
        this.metaSupplier = metaSupplier;
        this.name = name;
    }

    public SinkImpl(String name, ProcessorSupplier supplier) {
        this(name, ProcessorMetaSupplier.of(supplier));
    }

    public SinkImpl(String name, DistributedSupplier<Processor> supplier) {
        this(name, ProcessorMetaSupplier.of(supplier));
    }

    public ProcessorMetaSupplier metaSupplier() {
        return metaSupplier;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}