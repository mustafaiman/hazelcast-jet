/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet;

import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.datamodel.Tuple3;
import com.hazelcast.jet.function.DistributedFunction;
import com.hazelcast.jet.function.DistributedPredicate;
import com.hazelcast.jet.function.DistributedSupplier;

import javax.annotation.Nonnull;

/**
 * Javadoc pending.
 */
public interface ComputeStageWM<T> extends ComputeStage<T> {

    @Nonnull @Override
    <R> ComputeStageWM<R> map(@Nonnull DistributedFunction<? super T, ? extends R> mapFn);

    @Nonnull @Override
    ComputeStageWM<T> filter(@Nonnull DistributedPredicate<T> filterFn);

    @Nonnull @Override
    <R> ComputeStageWM<R> flatMap(@Nonnull DistributedFunction<? super T, ? extends Traverser<? extends R>> flatMapFn);

    @Nonnull @Override
    <K, T1_IN, T1> ComputeStageWM<Tuple2<T, T1>> hashJoin(
            @Nonnull ComputeStage<T1_IN> stage1,
            @Nonnull JoinClause<K, ? super T, ? super T1_IN, ? extends T1> joinClause1);

    @Nonnull @Override
    <K1, T1_IN, T1, K2, T2_IN, T2> ComputeStageWM<Tuple3<T, T1, T2>> hashJoin(
            @Nonnull ComputeStage<T1_IN> stage1,
            @Nonnull JoinClause<K1, ? super T, ? super T1_IN, ? extends T1> joinClause1,
            @Nonnull ComputeStage<T2_IN> stage2,
            @Nonnull JoinClause<K2, ? super T, ? super T2_IN, ? extends T2> joinClause2);

    @Nonnull @Override
    default HashJoinBuilder<T> hashJoinBuilder() {
        return null;
    }

    @Nonnull @Override
    <K> StageWithGroupingWM<T, K> groupingKey(@Nonnull DistributedFunction<? super T, ? extends K> keyFn);

    StageWithWindow<T> window(WindowDefinition wDef);

    @Nonnull @Override
    ComputeStageWM<T> peek(
            @Nonnull DistributedPredicate<? super T> shouldLogFn,
            @Nonnull DistributedFunction<? super T, ? extends CharSequence> toStringFn);

    @Override
    default ComputeStageWM<T> peek(@Nonnull DistributedFunction<? super T, ? extends CharSequence> toStringFn) {
        return (ComputeStageWM<T>) ComputeStage.super.peek(toStringFn);
    }

    @Nonnull @Override
    SinkStage drainTo(@Nonnull Sink<? super T> sink);

    @Nonnull @Override
    <R> ComputeStageWM<R> customTransform(
            @Nonnull String stageName,
            @Nonnull DistributedSupplier<Processor> procSupplier);
}
