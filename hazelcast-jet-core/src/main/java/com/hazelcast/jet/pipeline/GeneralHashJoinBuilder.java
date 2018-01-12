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

import com.hazelcast.jet.datamodel.ItemsByTag;
import com.hazelcast.jet.datamodel.Tag;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.impl.pipeline.PipelineImpl;
import com.hazelcast.jet.impl.pipeline.transform.HashJoinTransform;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import static com.hazelcast.jet.datamodel.Tag.tag;
import static com.hazelcast.jet.datamodel.Tag.tag0;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;

/**
 * Offers a step-by-step fluent API to build a hash-join pipeline pipeline.
 * To obtain it, call {@link GeneralComputeStage#hashJoinBuilder()} on the primary
 * pipeline, whose data will be enriched from all other stages.
 * <p>
 * This object is mainly intended to build a hash-join of the primary pipeline
 * with three or more contributing stages. For one or two stages the direct
 * {@code pipeline.hashJoin(...)} calls should be preferred because they offer
 * more static type safety.
 *
 * @param <T0> the type of the stream-0 item
 */
public abstract class GeneralHashJoinBuilder<T0, OUT_STAGE extends GeneralComputeStage<Tuple2<T0, ItemsByTag>>> {
    private final GeneralComputeStage<T0> stage0;
    private final CreateOutStageFn<T0, OUT_STAGE> createOutStageFn;
    private final Map<Tag<?>, StageAndClause> clauses = new HashMap<>();

    GeneralHashJoinBuilder(GeneralComputeStage<T0> stage0, CreateOutStageFn<T0, OUT_STAGE> createOutStageFn) {
        this.stage0 = stage0;
        this.createOutStageFn = createOutStageFn;
    }

    /**
     * Adds another contributing pipeline pipeline to the hash-join operation.
     *
     * @param stage the contributing pipeline
     * @param joinClause specifies how to join the contributing pipeline
     * @param <K> the type of the join key
     * @param <T1_IN> the type of the contributing pipeline's data
     * @param <T1> the type of result after applying the projecting transformation
     *             to the contributing pipeline's data
     * @return the tag that refers to the contributing pipeline
     */
    public <K, T1_IN, T1> Tag<T1> add(ComputeStage<T1_IN> stage, JoinClause<K, T0, T1_IN, T1> joinClause) {
        Tag<T1> tag = tag(clauses.size());
        clauses.put(tag, new StageAndClause<>(stage, joinClause));
        return tag;
    }

    /**
     * Builds a new pipeline pipeline that performs the hash-join operation. The
     * pipeline is attached to all the contributing stages.
     *
     * @return the hash-join pipeline pipeline
     */
    @SuppressWarnings("unchecked")
    public OUT_STAGE build() {
        List<Entry<Tag<?>, StageAndClause>> orderedClauses = clauses.entrySet().stream()
                                                                    .sorted(comparing(Entry::getKey))
                                                                    .collect(toList());
        List<GeneralComputeStage> upstream =
                concat(
                        Stream.of(stage0),
                        orderedClauses.stream().map(e -> e.getValue().stage())
                ).collect(toList());
        // A probable javac bug forced us to extract this variable
        Stream<JoinClause<?, T0, ?, ?>> joinClauses = orderedClauses
                .stream()
                .skip(1)
                .map(e -> e.getValue().clause());
        HashJoinTransform<T0, Tuple2<T0, ItemsByTag>> hashJoinTransform = new HashJoinTransform<>(
                joinClauses.collect(toList()),
                orderedClauses.stream()
                              .map(Entry::getKey)
                              .collect(toList()));
        PipelineImpl pipeline = (PipelineImpl) clauses.get(tag0()).stage().getPipeline();
        OUT_STAGE attached = createOutStageFn.get(upstream, hashJoinTransform, pipeline);
        pipeline.connect(upstream, attached);
        return attached;
    }

    @FunctionalInterface
    public interface CreateOutStageFn<T0, OUT_STAGE> {
        OUT_STAGE get(
                List<GeneralComputeStage> upstream,
                HashJoinTransform<T0, Tuple2<T0, ItemsByTag>> hashJoinTransform,
                PipelineImpl pipeline);
    }

    private static class StageAndClause<K, E0, T1, T1_OUT> {
        private final GeneralComputeStage<T1> stage;
        private final JoinClause<K, E0, T1, T1_OUT> joinClause;

        @SuppressWarnings("unchecked")
        StageAndClause(GeneralComputeStage<T1> stage, JoinClause<K, E0, T1, T1_OUT> joinClause) {
            this.stage = stage;
            this.joinClause = joinClause;
        }

        GeneralComputeStage<T1> stage() {
            return stage;
        }

        JoinClause<K, E0, T1, T1_OUT> clause() {
            return joinClause;
        }
    }
}