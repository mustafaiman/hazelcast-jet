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

package com.hazelcast.jet.pipeline;

import com.hazelcast.jet.aggregate.AggregateOperation1;
import com.hazelcast.jet.aggregate.AggregateOperation2;
import com.hazelcast.jet.aggregate.AggregateOperation3;
import com.hazelcast.jet.datamodel.TimestampedEntry;
import com.hazelcast.jet.function.DistributedFunction;

import javax.annotation.Nonnull;

/**
 * Javadoc pending.
 */
public interface StageWithWindow<T> {

    @Nonnull
    WindowDefinition windowDefinition();

    @Nonnull
    <K> StageWithGroupingAndWindow<T, K> groupingKey(
            @Nonnull DistributedFunction<? super T, ? extends K> keyFn
    );

    @Nonnull
    <A, R> StreamStage<TimestampedEntry<Void, R>> aggregate(
            @Nonnull AggregateOperation1<? super T, A, ? extends R> aggrOp
    );

    @Nonnull
    <T1, A, R> StreamStage<TimestampedEntry<Void, R>> aggregate2(
            @Nonnull StreamStage<T1> stage1,
            @Nonnull AggregateOperation2<? super T, ? super T1, A, ? extends R> aggrOp);

    @Nonnull
    <T1, T2, A, R> StreamStage<TimestampedEntry<Void, R>> aggregate3(
            @Nonnull StreamStage<T1> stage1,
            @Nonnull StreamStage<T2> stage2,
            @Nonnull AggregateOperation3<? super T, ? super T1, ? super T2, A, ? extends R> aggrOp);

    @Nonnull
    WindowAggregateBuilder<T> aggregateBuilder();
}
