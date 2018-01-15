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

import com.hazelcast.jet.impl.pipeline.SourceWithWatermarkImpl;
import com.hazelcast.jet.impl.pipeline.transform.Transform;
import com.hazelcast.jet.pipeline.SourceWithWatermark;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.pipeline.Source;
import com.hazelcast.jet.core.WatermarkPolicy;
import com.hazelcast.jet.function.DistributedSupplier;
import com.hazelcast.jet.function.DistributedToLongFunction;

import javax.annotation.Nonnull;

public class SourceImpl<T> implements Source<T>, Transform {
    private final String name;
    private final ProcessorMetaSupplier metaSupplier;

    public SourceImpl(String name, ProcessorMetaSupplier metaSupplier) {
        this.metaSupplier = metaSupplier;
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    public ProcessorMetaSupplier metaSupplier() {
        return metaSupplier;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public SourceWithWatermark<T> withWatermark(
            @Nonnull DistributedToLongFunction<? super T> timestampFn,
            @Nonnull WatermarkPolicy wmPolicy
    ) {
        return new SourceWithWatermarkImpl<>(this, timestampFn, wmPolicy);
    }
}