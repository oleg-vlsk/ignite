/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed.dht.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.collections.CollectionUtils;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.processors.cache.CacheGroupContext;
import org.apache.ignite.internal.processors.cache.GridCacheSharedContext;
import org.apache.ignite.internal.processors.resource.DependencyResolver;
import org.apache.ignite.internal.util.lang.RunnableX;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.CallbackExecutorLogListener;
import org.apache.ignite.testframework.ListeningTestLogger;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static org.apache.ignite.cache.CacheRebalanceMode.NONE;
import static org.apache.ignite.testframework.GridTestUtils.waitForCondition;

/** Test class for verifying eviction result log messages. */
public class LogEvictionResultsTest extends GridCommonAbstractTest {
    /** Number of keys to load into a partition. */
    private static final int KEY_CNT = 10;

    /** Number of partitions to move to the MOVING state. */
    private static final int PART_CNT = 3;

    /** Pattern for extracting partition ids from log messages. */
    private static final Pattern CNT_PATTERN = Pattern.compile("p=(?<count>\\d+)");

    /** Listening test logger. */
    private final ListeningTestLogger testLog = new ListeningTestLogger(log);

    /** Latch for locking partition clearing. */
    private final CountDownLatch lock = new CountDownLatch(1);

    /** Latch for unlocking partition clearing. */
    private final CountDownLatch unlock = new CountDownLatch(1);

    /** Function to obtain a dependency resolver for launching a grid, allowing delayed partition clearing. */
    private final Function<List<Integer>, DependencyResolver> depResolverFunc = (evictedParts) ->
        new DependencyResolver() {
            @Override public <T> T resolve(T instance) {
                if (instance instanceof GridDhtPartitionTopologyImpl) {
                    GridDhtPartitionTopologyImpl top = (GridDhtPartitionTopologyImpl)instance;

                    top.partitionFactory(new GridDhtPartitionTopologyImpl.PartitionFactory() {
                        @Override public GridDhtLocalPartition create(GridCacheSharedContext ctx,
                            CacheGroupContext grp, int id, boolean recovery) {
                            return evictedParts.contains(id) ?
                                new GridDhtLocalPartitionSyncEviction(ctx, grp, id, recovery, 2, lock, unlock) :
                                new GridDhtLocalPartition(ctx, grp, id, recovery);
                        }
                    });
                }

                return instance;
            }
        };

    /** Flag indicating whether the cluster is configured as persistent. */
    private boolean isPersistentCluster;

    /** Flag indicating whether rebalance is disabled. */
    private boolean isRebalanceDisabled;

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        cleanPersistenceDir();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        cleanPersistenceDir();

        super.afterTest();
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        CacheConfiguration<?, ?> cacheCfg = new CacheConfiguration<>(DEFAULT_CACHE_NAME)
            .setBackups(2)
            .setAffinity(new RendezvousAffinityFunction(false, 32));

        if (isRebalanceDisabled)
            cacheCfg.setRebalanceMode(NONE);

        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName)
            .setRebalanceThreadPoolSize(PART_CNT)
            .setGridLogger(testLog)
            .setConsistentId(igniteInstanceName)
            .setCacheConfiguration(cacheCfg);

        if (isPersistentCluster) {
            DataStorageConfiguration dsCfg = new DataStorageConfiguration()
                .setWalSegmentSize(1024 * 1024);

            dsCfg.getDefaultDataRegionConfiguration()
                .setPersistenceEnabled(true)
                .setMaxSize(50 * 1024 * 1024);

            cfg.setDataStorageConfiguration(dsCfg);
        }

        return cfg;
    }

    /** */
    private void startTestGrids() throws Exception {
        startGrids(2);
        awaitPartitionMapExchange();

        startGrid(2);
        awaitPartitionMapExchange();

        startGrid(3);
        awaitPartitionMapExchange();
    }

    /** Verifies log messages for partition clearing completion during rebalancing. */
    @Test
    public void testClearingDuringRebalance() throws Exception {
        List<String> rebalPrepMsgs = new ArrayList<>();
        List<String> rebalEvictMsgs = new ArrayList<>();

        CallbackExecutorLogListener rebalPrepLsnr = new CallbackExecutorLogListener(
            "Prepared rebalancing.*", rebalPrepMsgs::add);

        CallbackExecutorLogListener rebalEvictLsnr = new CallbackExecutorLogListener(
            "Following partitions have been successfully evicted in preparation for rebalancing.*",
            rebalEvictMsgs::add);

        testLog.registerAllListeners(rebalPrepLsnr, rebalEvictLsnr);

        startTestGrids();

        List<Integer> prepared = extractPartsCount(rebalPrepMsgs);
        List<Integer> evicted = extractPartsCount(rebalEvictMsgs);

        assertTrue(CollectionUtils.isEqualCollection(prepared, evicted));
    }

    /** Verifies log messages for eviction completion triggered by topology changes. */
    @Test
    public void testCheckEviction() throws Exception {
        String prepStr = "Partition has been scheduled for eviction \\((all affinity nodes are owners|this node " +
            "is oldest non-affinity node)\\).*";

        String evictStr = "Eviction completed successfully \\[grp=default, reason='partitions no longer belong to affinity'.*";

        checkLogMessages(prepStr, evictStr, CNT_PATTERN, this::startTestGrids);
    }

    /** Verifies log messages for eviction completion when rebalancing is disabled. */
    @Test
    public void testRebalanceDisabled() throws Exception {
        String prepStr = "Evicting partition with rebalancing disabled \\(it does not belong to affinity\\).*";

        String evictStr = "Eviction completed successfully \\[grp=default, reason='rebalancing is disabled " +
            "\\(partitions do not belong to affinity\\)'.*";

        Pattern pat = Pattern.compile("id=(?<count>\\d+)");

        checkLogMessages(prepStr, evictStr, pat, () -> {
            isRebalanceDisabled = true;

            startTestGrids();
        });
    }

    /** Verifies log messages for eviction of partitions in MOVING state. */
    @Test
    public void testEvictMovingPartitions() throws Exception {
        String prepStr = "Evicting MOVING partition \\(it does not belong to affinity\\).*";

        String evictStr = "Eviction completed successfully \\[grp=default, " +
            "reason='MOVING partitions do not belong to affinity'.*";

        checkLogMessages(prepStr, evictStr, CNT_PATTERN, () -> {
            isPersistentCluster = true;

            startGrids(3);

            grid(0).cluster().state(ClusterState.ACTIVE);

            final List<Integer> evictedParts = evictingPartitionsAfterJoin(grid(2),
                grid(2).cache(DEFAULT_CACHE_NAME), PART_CNT);

            evictedParts.forEach(p ->
                loadDataToPartition(p, getTestIgniteInstanceName(0), DEFAULT_CACHE_NAME, KEY_CNT, 0));

            forceCheckpoint();

            stopGrid(2);

            evictedParts.forEach(p -> partitionKeys(grid(0).cache(DEFAULT_CACHE_NAME), p, KEY_CNT, 0)
                .forEach(k -> grid(0).cache(DEFAULT_CACHE_NAME).remove(k)));

            startGrid(2, depResolverFunc.apply(evictedParts));

            assertTrue(U.await(lock, GridDhtLocalPartitionSyncEviction.TIMEOUT, TimeUnit.MILLISECONDS));

            startGrid(3);

            resetBaselineTopology();

            awaitPartitionMapExchange();

            unlock.countDown();

            awaitPartitionMapExchange();
        });
    }

    /**
     * Verifies log messages produced during partition preparation and eviction.
     *
     * @param prepMsg Log message indicating partition preparation for eviction.
     * @param evictMsg Log message indicating completion of partition eviction.
     * @param pat Pattern used to extract partition information from log messages.
     * @param task Action that triggers the expected log output.
     */
    private void checkLogMessages(String prepMsg, String evictMsg, Pattern pat, RunnableX task) throws Exception {
        setLoggerDebugLevel();

        List<String> partsPreparedMsgs = new ArrayList<>();
        List<String> partsEvictedMsgs = new ArrayList<>();

        CallbackExecutorLogListener prepLsnr = new CallbackExecutorLogListener(prepMsg, partsPreparedMsgs::add);
        CallbackExecutorLogListener evictLsnr = new CallbackExecutorLogListener(evictMsg, partsEvictedMsgs::add);

        testLog.registerAllListeners(prepLsnr, evictLsnr);

        task.run();

        Pattern evictPat = Pattern.compile("evictedParts=\\[([^\\]]+)\\]");

        assertTrue(waitForCondition(() -> {
            List<Integer> prepared = extractParts(partsPreparedMsgs, pat);
            List<Integer> evicted = extractParts(partsEvictedMsgs, evictPat);

            return CollectionUtils.isEqualCollection(prepared, evicted);
        }, 10_000));
    }

    /** @param logMsgs List of log messages. */
    private List<Integer> extractPartsCount(List<String> logMsgs) {
        List<Integer> res = new ArrayList<>();

        for (String msg : logMsgs) {
            Matcher matcher = Pattern.compile("partitionsCount=(?<count>\\d+)").matcher(msg);

            if (matcher.find())
                res.add(Integer.parseInt(matcher.group("count")));
        }

        return res;
    }

    /**
     * @param logMsgs List of log messages.
     * @param pattern Pattern used to extract partitions from debug log messages.
     */
    private List<Integer> extractParts(List<String> logMsgs, Pattern pattern) {
        List<Integer> res = new ArrayList<>();

        for (String msg : logMsgs) {
            Matcher matcher = pattern.matcher(msg);

            if (matcher.find()) {
                String[] parts = matcher.group(1).split(",");

                for (String part : parts)
                    res.add(Integer.parseInt(part.trim()));
            }
        }

        return res;
    }
}
