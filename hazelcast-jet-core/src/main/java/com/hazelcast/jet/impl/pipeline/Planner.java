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

import com.hazelcast.jet.ComputeStage;
import com.hazelcast.jet.JoinClause;
import com.hazelcast.jet.SessionWindowDef;
import com.hazelcast.jet.SlidingWindowDef;
import com.hazelcast.jet.Stage;
import com.hazelcast.jet.Transform;
import com.hazelcast.jet.WindowDefinition;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Edge;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.SlidingWindowPolicy;
import com.hazelcast.jet.core.TimestampKind;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.WatermarkEmissionPolicy;
import com.hazelcast.jet.datamodel.TimestampedEntry;
import com.hazelcast.jet.function.DistributedFunction;
import com.hazelcast.jet.function.DistributedSupplier;
import com.hazelcast.jet.impl.pipeline.transform.CoGroupTransform;
import com.hazelcast.jet.impl.pipeline.transform.FilterTransform;
import com.hazelcast.jet.impl.pipeline.transform.FlatMapTransform;
import com.hazelcast.jet.impl.pipeline.transform.GroupTransform;
import com.hazelcast.jet.impl.pipeline.transform.HashJoinTransform;
import com.hazelcast.jet.impl.pipeline.transform.MapTransform;
import com.hazelcast.jet.impl.pipeline.transform.PeekTransform;
import com.hazelcast.jet.impl.pipeline.transform.ProcessorTransform;
import com.hazelcast.jet.impl.processor.HashJoinCollectP;
import com.hazelcast.jet.impl.processor.HashJoinP;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.jet.core.Edge.from;
import static com.hazelcast.jet.core.Partitioner.HASH_CODE;
import static com.hazelcast.jet.core.processor.DiagnosticProcessors.peekOutputP;
import static com.hazelcast.jet.core.processor.Processors.accumulateByFrameP;
import static com.hazelcast.jet.core.processor.Processors.accumulateByKeyP;
import static com.hazelcast.jet.core.processor.Processors.aggregateToSessionWindowP;
import static com.hazelcast.jet.core.processor.Processors.coAccumulateByKeyP;
import static com.hazelcast.jet.core.processor.Processors.combineByKeyP;
import static com.hazelcast.jet.core.processor.Processors.combineToSlidingWindowP;
import static com.hazelcast.jet.core.processor.Processors.filterP;
import static com.hazelcast.jet.core.processor.Processors.flatMapP;
import static com.hazelcast.jet.core.processor.Processors.insertWatermarksP;
import static com.hazelcast.jet.core.processor.Processors.mapP;
import static com.hazelcast.jet.function.DistributedFunction.identity;
import static com.hazelcast.jet.function.DistributedFunctions.entryKey;
import static com.hazelcast.jet.impl.TopologicalSorter.topologicalSort;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

@SuppressWarnings("unchecked")
class Planner {

    private final PipelineImpl pipeline;
    private final DAG dag = new DAG();
    private final Map<Stage, PlannerVertex> stage2vertex = new HashMap<>();

    private final Set<String> vertexNames = new HashSet<>();

    Planner(PipelineImpl pipeline) {
        this.pipeline = pipeline;
    }

    DAG createDag() {
        Map<Stage, List<Stage>> adjacencyMap = pipeline.adjacencyMap();
        validateNoLeakage(adjacencyMap);
        Iterable<AbstractStage> sorted = (Iterable<AbstractStage>) (Iterable<? extends Stage>)
                topologicalSort(adjacencyMap, Object::toString);
        for (AbstractStage stage : sorted) {
            Transform transform = stage.transform;
            if (transform instanceof SourceImpl) {
                handleSource(stage, (SourceImpl) transform);
            } else if (transform instanceof SourceWithWatermarkImpl) {
                handleSourceWithWatermark(stage, (SourceWithWatermarkImpl) transform);
            } else if (transform instanceof ProcessorTransform) {
                handleProcessorStage(stage, (ProcessorTransform) transform);
            } else if (transform instanceof FilterTransform) {
                handleFilter(stage, (FilterTransform) transform);
            } else if (transform instanceof MapTransform) {
                handleMap(stage, (MapTransform) transform);
            } else if (transform instanceof FlatMapTransform) {
                handleFlatMap(stage, (FlatMapTransform) transform);
            } else if (transform instanceof GroupTransform) {
                handleGroup(stage, (GroupTransform) transform);
            } else if (transform instanceof CoGroupTransform) {
                handleCoGroup(stage, (CoGroupTransform) transform);
            } else if (transform instanceof HashJoinTransform) {
                handleHashJoin(stage, (HashJoinTransform) transform);
            } else if (transform instanceof PeekTransform) {
                handlePeek(stage, (PeekTransform) transform);
            } else if (transform instanceof SinkImpl) {
                handleSink(stage, (SinkImpl) transform);
            } else {
                throw new IllegalArgumentException("Unknown transform " + transform);
            }
        }
        return dag;
    }

    private static void validateNoLeakage(Map<Stage, List<Stage>> adjacencyMap) {
        List<ComputeStage> leakages = adjacencyMap
                .entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Entry::getKey)
                .filter(stage -> stage instanceof ComputeStage)
                .map(stage -> (ComputeStage) stage)
                .collect(toList());
        if (!leakages.isEmpty()) {
            throw new IllegalArgumentException("These ComputeStages have nothing attached to them: " + leakages);
        }
    }

    private void handleSource(AbstractStage stage, SourceImpl source) {
        addVertex(stage, vertexName(source.name(), ""), source.metaSupplier());
    }

    private void handleSourceWithWatermark(AbstractStage stage, SourceWithWatermarkImpl wmSource) {
        SourceImpl source = wmSource.source();
        Vertex srcVertex = dag.newVertex(vertexName(source.name(), ""), source.metaSupplier());
        PlannerVertex watermarkPv = addVertex(stage, vertexName(source.name(), "-wm"), insertWatermarksP(
                wmSource.timestampFn(),
                wmSource.createWatermarkPolicyFn(),
                WatermarkEmissionPolicy.suppressDuplicates()
        ));
        dag.edge(between(srcVertex, watermarkPv.v));
    }

    private void handleProcessorStage(AbstractStage stage, ProcessorTransform procTransform) {
        PlannerVertex pv = addVertex(stage, vertexName(procTransform.name(), ""), procTransform.procSupplier);
        addEdges(stage, pv.v);
    }

    private void handleMap(AbstractStage stage, MapTransform map) {
        PlannerVertex pv = addVertex(stage, vertexName(map.name(), ""), mapP(map.mapFn));
        addEdges(stage, pv.v);
    }

    private void handleFilter(AbstractStage stage, FilterTransform filter) {
        PlannerVertex pv = addVertex(stage, vertexName(filter.name(), ""), filterP(filter.filterFn));
        addEdges(stage, pv.v);
    }

    private void handleFlatMap(AbstractStage stage, FlatMapTransform flatMap) {
        PlannerVertex pv = addVertex(stage, vertexName(flatMap.name(), ""), flatMapP(flatMap.flatMapFn()));
        addEdges(stage, pv.v);
    }

    //                       --------
    //                      | source |
    //                       --------
    //                           |
    //                      partitioned
    //                           v
    //                  -------------------
    //                 | accumulatebyKeyP  |
    //                  -------------------
    //                           |
    //                      distributed
    //                      partitioned
    //                           v
    //                   ----------------
    //                  | combineByKeyP  |
    //                   ----------------
    private void handleGroup(AbstractStage stage, GroupTransform<Object, Object, Object, Object, Object> xform) {
        if (xform.windowDefinition() != null) {
            handleWindowedGroup(stage, xform);
            return;
        }
        String namePrefix = vertexName(xform.name(), "-stage");
        Vertex v1 = dag.newVertex(namePrefix + '1', accumulateByKeyP(
                xform.keyFn(), xform.aggregateOperation().withFinishFn(identity())));
        PlannerVertex pv2 = addVertex(stage, namePrefix + '2', combineByKeyP(xform.aggregateOperation()));
        addEdges(stage, v1, e -> e.partitioned(xform.keyFn(), HASH_CODE));
        dag.edge(between(v1, pv2.v).distributed().partitioned(entryKey()));
    }

    private void handleWindowedGroup(
            AbstractStage stage, GroupTransform<Object, Object, Object, Object, Object> xform
    ) {
        WindowDefinition wDef = requireNonNull(xform.windowDefinition());
        switch (wDef.kind()) {
            case TUMBLING:
            case SLIDING:
                handleSlidingWindow(stage, xform, wDef.downcast());
                return;
            case SESSION:
                handleSessionWindow(stage, xform, wDef.downcast());
                return;
        }
        throw new IllegalArgumentException("Unknown window definition " + wDef.kind());
    }

    //                       --------
    //                      | source |
    //                       --------
    //                           |
    //                      partitioned
    //                           v
    //                 --------------------
    //                | accumulatebyFrameP |
    //                 --------------------
    //                           |
    //                      distributed
    //                      partitioned
    //                           v
    //                   -----------------
    //                  | combineByFrameP |
    //                   -----------------
    private void handleSlidingWindow(
            AbstractStage stage,
            GroupTransform<Object, Object, Object, Object, Object> xform,
            SlidingWindowDef wDef
    ) {
        String namePrefix = vertexName("sliding-window", "-stage");
        SlidingWindowPolicy winPolicy = wDef.toSlidingWindowPolicy();
        Vertex v1 = dag.newVertex(namePrefix + '1', accumulateByFrameP(
                xform.keyFn(),
                (TimestampedEntry<Object, Object> e) -> e.getTimestamp(),
                TimestampKind.EVENT,
                winPolicy,
                xform.aggregateOperation().withFinishFn(identity())));
        PlannerVertex pv2 = addVertex(stage, namePrefix + '2',
                combineToSlidingWindowP(winPolicy, xform.aggregateOperation()));
        addEdges(stage, v1, e -> e.partitioned(xform.keyFn(), HASH_CODE));
        dag.edge(between(v1, pv2.v).distributed().partitioned(entryKey()));
    }

    private void handleSessionWindow(
            AbstractStage stage,
            GroupTransform<Object, Object, Object, Object, Object> xform,
            SessionWindowDef wDef
    ) {
        PlannerVertex pv = addVertex(stage, vertexName("session-window", ""), aggregateToSessionWindowP(
                wDef.sessionTimeout(),
                (TimestampedEntry<Object, Object> e) -> e.getTimestamp(),
                xform.keyFn(),
                xform.aggregateOperation()
        ));
        addEdges(stage, pv.v, e -> e.partitioned(xform.keyFn()));
    }

    //           ----------       ----------         ----------
    //          | source-1 |     | source-2 |  ...  | source-n |
    //           ----------       ----------         ----------
    //               |                 |                  |
    //          partitioned       partitioned        partitioned
    //               \------------v    v    v------------/
    //                       --------------------
    //                      | coAccumulateByKeyP |
    //                       --------------------
    //                                 |
    //                            distributed
    //                            partitioned
    //                                 v
    //                          ---------------
    //                         | combineByKeyP |
    //                          ---------------
    private void handleCoGroup(AbstractStage stage, CoGroupTransform<Object, Object, Object, Object> xform) {
        if (xform.windowDefinition() != null) {
            handleWindowedCoGroup(stage, xform);
            return;
        }
        List<DistributedFunction<?, ?>> groupKeyFns = xform.groupKeyFns();
        String namePrefix = vertexName(xform.name(), "-stage");
        Vertex v1 = dag.newVertex(namePrefix + '1',
                coAccumulateByKeyP(groupKeyFns, xform.aggregateOperation().withFinishFn(identity())));
        PlannerVertex pv2 = addVertex(stage, namePrefix + '2',
                combineByKeyP(xform.aggregateOperation()));
        addEdges(stage, v1, (e, ord) -> e.partitioned(groupKeyFns.get(ord), HASH_CODE));
        dag.edge(between(v1, pv2.v).distributed().partitioned(entryKey()));
    }

    private void handleWindowedCoGroup(AbstractStage stage, CoGroupTransform<Object, Object, Object, Object> xform) {
        WindowDefinition wDef = requireNonNull(xform.windowDefinition());
        switch (wDef.kind()) {
            case TUMBLING:
            case SLIDING:
                handleSlidingCoWindow(stage, xform, wDef.downcast());
                return;
            case SESSION:
                handleSessionCoWindow(stage, xform, wDef.downcast());
                return;
        }
        throw new IllegalArgumentException("Unknown window definition " + wDef.kind());
    }

    private void handleSlidingCoWindow(
            AbstractStage stage,
            CoGroupTransform<Object, Object, Object, Object> xform,
            SlidingWindowDef wDef
    ) {
        throw new UnsupportedOperationException("Windowed co-grouping not yet implemented");
    }

    private void handleSessionCoWindow(
            AbstractStage stage,
            CoGroupTransform<Object, Object, Object, Object> xform,
            SessionWindowDef downcast
    ) {
        throw new UnsupportedOperationException("Windowed co-grouping not yet implemented");
    }

    //         ---------           ----------           ----------
    //        | primary |         | joined-1 |         | joined-2 |
    //         ---------           ----------           ----------
    //             |                   |                     |
    //             |              distributed          distributed
    //             |               broadcast            broadcast
    //             |                   v                     v
    //             |             -------------         -------------
    //             |            | collector-1 |       | collector-2 |
    //             |             -------------         -------------
    //             |                   |                     |
    //             |                 local                 local
    //        distributed          broadcast             broadcast
    //        partitioned         prioritized           prioritized
    //         ordinal 0           ordinal 1             ordinal 2
    //             \                   |                     |
    //              ----------------\  |   /----------------/
    //                              v  v  v
    //                              --------
    //                             | joiner |
    //                              --------
    private void handleHashJoin(AbstractStage stage, HashJoinTransform<?, ?> hashJoin) {
        String namePrefix = vertexName(hashJoin.name(), "");
        PlannerVertex primary = stage2vertex.get(stage.upstream.get(0));
        List<Function<Object, Object>> keyFns = (List<Function<Object, Object>>) (List)
                hashJoin.clauses().stream()
                        .map(JoinClause::leftKeyFn)
                        .collect(toList());
        Vertex joiner = addVertex(stage, namePrefix + "joiner",
                () -> new HashJoinP<>(keyFns, hashJoin.tags())).v;
        dag.edge(from(primary.v, primary.availableOrdinal++).to(joiner, 0));

        String collectorName = namePrefix + "collector-";
        int collectorOrdinal = 1;
        for (Stage fromStage : tailList(stage.upstream)) {
            PlannerVertex fromPv = stage2vertex.get(fromStage);
            JoinClause<?, ?, ?, ?> clause = hashJoin.clauses().get(collectorOrdinal - 1);
            DistributedFunction<Object, Object> getKeyFn =
                    (DistributedFunction<Object, Object>) clause.rightKeyFn();
            DistributedFunction<Object, Object> projectFn =
                    (DistributedFunction<Object, Object>) clause.rightProjectFn();
            Vertex collector = dag.newVertex(collectorName + collectorOrdinal,
                    () -> new HashJoinCollectP(getKeyFn, projectFn));
            collector.localParallelism(1);
            dag.edge(from(fromPv.v, fromPv.availableOrdinal++)
                    .to(collector, 0)
                    .distributed().broadcast());
            dag.edge(from(collector, 0)
                    .to(joiner, collectorOrdinal)
                    .broadcast().priority(-1));
            collectorOrdinal++;
        }
    }

    private void handlePeek(AbstractStage stage, PeekTransform peekTransform) {
        PlannerVertex peekedPv = stage2vertex.get(stage.upstream.get(0));
        // Peeking transform doesn't add a vertex, so point to the upstream pipeline's
        // vertex:
        stage2vertex.put(stage, peekedPv);
        peekedPv.v.updateMetaSupplier(sup ->
                peekOutputP(peekTransform.toStringFn(), peekTransform.shouldLogFn(), sup));
    }

    private void handleSink(AbstractStage stage, SinkImpl sink) {
        PlannerVertex pv = addVertex(stage, vertexName(sink.name(), ""), sink.metaSupplier());
        addEdges(stage, pv.v);
    }

    private PlannerVertex addVertex(Stage stage, String name, DistributedSupplier<Processor> procSupplier) {
        return addVertex(stage, name, ProcessorMetaSupplier.of(procSupplier));
    }

    private PlannerVertex addVertex(Stage stage, String name, ProcessorMetaSupplier metaSupplier) {
        PlannerVertex pv = new PlannerVertex(dag.newVertex(name, metaSupplier));
        stage2vertex.put(stage, pv);
        return pv;
    }

    private void addEdges(AbstractStage stage, Vertex toVertex, BiConsumer<Edge, Integer> configureEdgeFn) {
        int destOrdinal = 0;
        for (Stage fromStage : stage.upstream) {
            PlannerVertex fromPv = stage2vertex.get(fromStage);
            Edge edge = from(fromPv.v, fromPv.availableOrdinal++).to(toVertex, destOrdinal);
            dag.edge(edge);
            configureEdgeFn.accept(edge, destOrdinal);
            destOrdinal++;
        }
    }

    private void addEdges(AbstractStage stage, Vertex toVertex, Consumer<Edge> configureEdgeFn) {
        addEdges(stage, toVertex, (e, ord) -> configureEdgeFn.accept(e));
    }

    private void addEdges(AbstractStage stage, Vertex toVertex) {
        addEdges(stage, toVertex, e -> { });
    }

    private String vertexName(@Nonnull String name, @Nonnull String suffix) {
        for (int index = 1; ; index++) {
            String candidate = name
                    + (index == 1 ? "" : "-" + index)
                    + suffix;
            if (vertexNames.add(candidate)) {
                return candidate;
            }
        }
    }

    private static <E> List<E> tailList(List<E> list) {
        return list.subList(1, list.size());
    }

    private static class PlannerVertex {
        Vertex v;

        int availableOrdinal;

        PlannerVertex(Vertex v) {
            this.v = v;
        }

        @Override
        public String toString() {
            return v.toString();
        }
    }
}
