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

package com.hazelcast.jet.impl.pipeline.transform;

import com.hazelcast.config.EventJournalConfig;
import com.hazelcast.core.IMap;
import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.config.JetConfig;
import com.hazelcast.jet.core.JetTestSupport;
import com.hazelcast.jet.pipeline.BatchStage;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sinks;
import com.hazelcast.jet.pipeline.Sources;
import com.hazelcast.test.HazelcastParametersRunnerFactory;
import com.hazelcast.test.annotation.ParallelTest;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.Parameterized.UseParametersRunnerFactory;

import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.aggregate.AggregateOperations.toSet;
import static com.hazelcast.jet.core.TestUtil.set;
import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
@UseParametersRunnerFactory(HazelcastParametersRunnerFactory.class)
@Category(ParallelTest.class)
public class GroupTransform_IntegrationTest extends JetTestSupport {

    @Parameter
    public boolean singleStage;

    private JetInstance instance;

    @Parameters(name = "singleStage={0}")
    public static Object[] parameters() {
        return new Object[]{true, false};
    }

    @Before
    public void before() {
        JetConfig config = new JetConfig();
        config.getHazelcastConfig().addEventJournalConfig(
                new EventJournalConfig().setMapName("source").setEnabled(true));
        instance = createJetMember(config);
    }

    @Test
    public void test() {
        IMap<Long, String> map = instance.getMap("source");
        map.put(0L, "foo");
        map.put(1L, "bar");
        map.put(2L, "baz");

        Pipeline p = Pipeline.create();
        BatchStage<Entry<Character, Set<Entry<Long, String>>>> stage =
                p.drawFrom(Sources.<Long, String>map("source"))
                 .groupingKey(entry -> entry.getValue().charAt(0))
                 .aggregate(toSet());
        if (singleStage) {
            stage = stage.setOptimizeMemory();
        }
        stage.drainTo(Sinks.list("sink"));

        instance.newJob(p).join();

        assertEquals(
                set(
                        entry('f', set(entry(0L, "foo"))),
                        entry('b', set(entry(1L, "bar"), entry(2L, "baz")))),
                new HashSet<>(instance.getHazelcastInstance().getList("sink")));
    }
}