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

package com.hazelcast.jet.impl.pipeline;

import com.hazelcast.jet.aggregate.AggregateOperation;
import com.hazelcast.jet.datamodel.Tag;
import com.hazelcast.jet.function.DistributedFunction;
import com.hazelcast.jet.impl.pipeline.AggBuilder.CreateOutStageFn;
import com.hazelcast.jet.impl.pipeline.transform.CoGroupTransform;
import com.hazelcast.jet.impl.pipeline.transform.Transform;
import com.hazelcast.jet.pipeline.GeneralStage;
import com.hazelcast.jet.pipeline.GroupAggregateBuilder;
import com.hazelcast.jet.pipeline.StageWithGrouping;
import com.hazelcast.jet.pipeline.StageWithGroupingAndWindow;
import com.hazelcast.jet.pipeline.StreamStageWithGrouping;
import com.hazelcast.jet.pipeline.WindowDefinition;
import com.hazelcast.jet.pipeline.WindowGroupAggregateBuilder;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

import static com.hazelcast.jet.datamodel.Tag.tag;
import static com.hazelcast.jet.impl.pipeline.ComputeStageImplBase.ADAPT_TO_JET_EVENT;
import static com.hazelcast.jet.impl.pipeline.ComputeStageImplBase.DONT_ADAPT;
import static com.hazelcast.jet.impl.pipeline.ComputeStageImplBase.ensureJetEvents;
import static java.util.stream.Collectors.toList;

/**
 * Support class for {@link GroupAggregateBuilder}
 * and {@link WindowGroupAggregateBuilder}. The
 * motivation is to have the ability to specify different output
 * types ({@code Entry<K, R>} vs. {@code TimestampedEntry<K, R>}).
 *
 * @param <K> type of the grouping key
 */
public class GrAggBuilder<K> {
    private final PipelineImpl pipelineImpl;
    private final WindowDefinition wDef;
    private final List<ComputeStageImplBase> upstreamStages = new ArrayList<>();
    private final List<DistributedFunction<?, ? extends K>> keyFns = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public GrAggBuilder(StageWithGrouping<?, K> stage0) {
        ComputeStageImplBase computeStage = ((StageWithGroupingBase) stage0).computeStage;
        pipelineImpl = (PipelineImpl) computeStage.getPipeline();
        wDef = null;
        upstreamStages.add(computeStage);
        keyFns.add(stage0.keyFn());
    }

    @SuppressWarnings("unchecked")
    public GrAggBuilder(StageWithGroupingAndWindow<?, K> stage) {
        ComputeStageImplBase computeStage = ((StageWithGroupingBase) stage).computeStage;
        ensureJetEvents(computeStage, "This pipeline stage");
        pipelineImpl = (PipelineImpl) computeStage.getPipeline();
        wDef = stage.windowDefinition();
        upstreamStages.add(computeStage);
        keyFns.add(stage.keyFn());
    }

    @SuppressWarnings("unchecked")
    public <E> Tag<E> add(StreamStageWithGrouping<E, K> stage) {
        ComputeStageImplBase computeStage = ((StageWithGroupingBase) stage).computeStage;
        ensureJetEvents(computeStage, "This pipeline stage");
        upstreamStages.add(computeStage);
        keyFns.add(stage.keyFn());
        return (Tag<E>) tag(upstreamStages.size() - 1);
    }

    @SuppressWarnings("unchecked")
    public <E> Tag<E> add(StageWithGrouping<E, K> stage) {
        upstreamStages.add(((StageWithGroupingBase) stage).computeStage);
        keyFns.add(stage.keyFn());
        return (Tag<E>) tag(upstreamStages.size() - 1);
    }

    @SuppressWarnings("unchecked")
    public <A, R, OUT, OUT_STAGE extends GeneralStage<OUT>> OUT_STAGE build(
            @Nonnull AggregateOperation<A, R> aggrOp,
            @Nonnull CreateOutStageFn<OUT, OUT_STAGE> createOutStageFn
    ) {
        AggregateOperation adaptedAggrOp = wDef != null
                ? aggrOp
                : ADAPT_TO_JET_EVENT.adaptAggregateOperation(aggrOp);
        List<Transform> upstreamTransforms = upstreamStages.stream().map(s -> s.transform).collect(toList());
        CoGroupTransform<K, A, R> transform = new CoGroupTransform<>(upstreamTransforms, keyFns, adaptedAggrOp, wDef);
        pipelineImpl.connect(upstreamTransforms, transform);
        return createOutStageFn.get(transform, DONT_ADAPT, pipelineImpl);
    }
}
