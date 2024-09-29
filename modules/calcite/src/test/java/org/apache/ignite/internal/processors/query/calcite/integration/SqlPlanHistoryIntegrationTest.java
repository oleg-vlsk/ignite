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

package org.apache.ignite.internal.processors.query.calcite.integration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.Query;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.cache.query.SqlQuery;
import org.apache.ignite.cache.query.TextQuery;
import org.apache.ignite.cache.query.annotations.QuerySqlField;
import org.apache.ignite.cache.query.annotations.QuerySqlFunction;
import org.apache.ignite.calcite.CalciteQueryEngineConfiguration;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.SqlConfiguration;
import org.apache.ignite.indexing.IndexingQueryEngineConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.query.IgniteSQLException;
import org.apache.ignite.internal.processors.query.QueryEngineConfigurationEx;
import org.apache.ignite.internal.processors.query.running.SqlPlan;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assume.assumeFalse;

/** Tests for SQL plan history (Calcite engine). */
@RunWith(Parameterized.class)
public class SqlPlanHistoryIntegrationTest extends GridCommonAbstractTest {
    /** SQL plan history size. */
    private static final int PLAN_HISTORY_SIZE = 10;

    /** SQL plan history size excess. */
    private static final int PLAN_HISTORY_EXCESS = 2;

    /** Simple SQL query. */
    private static final String SQL = "SELECT * FROM A.String";

    /** Failed SQL query. */
    private static final String SQL_FAILED = "select * from A.String where A.fail()=1";

    /** Cross-cache SQL query. */
    private static final String SQL_CROSS_CACHE = "SELECT * FROM B.String";

    /** Failed cross-cache SQL query. */
    private static final String SQL_CROSS_CACHE_FAILED = "select * from B.String where B.fail()=1";

    /** SQL query with reduce phase. */
    private static final String SQL_WITH_REDUCE_PHASE = "select o.name n1, p.name n2 from \"pers\".Person p, " +
        "\"org\".Organization o where p.orgId=o._key and o._key=101" +
        " union select o.name n1, p.name n2 from \"pers\".Person p, \"org\".Organization o" +
        " where p.orgId=o._key and o._key=102";

    /** Set of simple DML commands. */
    private final List<String> dmlCmds = Arrays.asList(
        "insert into A.String (_key, _val) values(101, '101')",
        "update A.String set _val='111' where _key=101",
        "delete from A.String where _key=101"
    );

    /** Set of DML commands with joins. */
    private final List<String> dmlCmdsWithJoins = Arrays.asList(
        "insert into A.String (_key, _val) select o._key, p.name " +
            "from \"pers\".Person p, \"org\".Organization o where p.orgId=o._key",
        "update A.String set _val = 'updated' where _key in " +
            "(select o._key from \"pers\".Person p, \"org\".Organization o where p.orgId=o._key)",
        "delete from A.String where _key in (select orgId from \"pers\".Person)"
    );

    /** Successful SqlFieldsQuery. */
    private final SqlFieldsQuery sqlFieldsQry = new SqlFieldsQuery(SQL);

    /** Failed SqlFieldsQuery. */
    private final SqlFieldsQuery sqlFieldsQryFailed = new SqlFieldsQuery(SQL_FAILED);

    /** Successful cross-cache SqlFieldsQuery. */
    private final SqlFieldsQuery sqlFieldsQryCrossCache = new SqlFieldsQuery(SQL_CROSS_CACHE);

    /** Failed cross-cache SqlFieldsQuery. */
    private final SqlFieldsQuery sqlFieldsQryCrossCacheFailed = new SqlFieldsQuery(SQL_CROSS_CACHE_FAILED);

    /** Successful SqlFieldsQuery with reduce phase. */
    private final SqlFieldsQuery sqlFieldsQryWithReducePhase = new SqlFieldsQuery(SQL_WITH_REDUCE_PHASE)
        .setDistributedJoins(true);

    /** Failed SqlFieldsQueries with reduce phase. */
    private final SqlFieldsQuery[] sqlFieldsQryWithReducePhaseFailed = F.asArray(
        new SqlFieldsQuery(SQL_WITH_REDUCE_PHASE.replace("o._key=101", "fail()")),
        new SqlFieldsQuery(SQL_WITH_REDUCE_PHASE.replace("o._key=102", "fail()"))
    );

    /** Successful SqlQuery. */
    private final SqlQuery sqlQry = new SqlQuery<>("String", "from String");

    /** Failed SqlQuery. */
    private final SqlQuery sqlQryFailed = new SqlQuery<>("String", "from String where fail()=1");

    /** ScanQuery. */
    private final ScanQuery<Integer, String> scanQry = new ScanQuery<>();

    /** TextQuery. */
    private final TextQuery<Integer, String> textQry = new TextQuery<>("String", "2");

    /** Flag for queries with map-reduce phases. */
    private boolean isReducePhase;

    /** SQL engine. */
    @Parameterized.Parameter
    public String sqlEngine;

    /** Client mode flag. */
    @Parameterized.Parameter(1)
    public boolean isClient;

    /** Local query flag. */
    @Parameterized.Parameter(2)
    public boolean loc;

    /** Fully-fetched query flag. */
    @Parameterized.Parameter(3)
    public boolean isFullyFetched;

    /** */
    @Parameterized.Parameters(name = "sqlEngine={0}, isClient={1} loc={2}, fullyFetched={3}")
    public static Collection<Object[]> params() {
        return Arrays.asList(new Object[][] {
            {CalciteQueryEngineConfiguration.ENGINE_NAME, true, true, true},
            {CalciteQueryEngineConfiguration.ENGINE_NAME, true, true, false},
            {CalciteQueryEngineConfiguration.ENGINE_NAME, true, false, true},
            {CalciteQueryEngineConfiguration.ENGINE_NAME, true, false, false},
            {CalciteQueryEngineConfiguration.ENGINE_NAME, false, true, true},
            {CalciteQueryEngineConfiguration.ENGINE_NAME, false, true, false},
            {CalciteQueryEngineConfiguration.ENGINE_NAME, false, false, true},
            {CalciteQueryEngineConfiguration.ENGINE_NAME, false, false, false},
            {IndexingQueryEngineConfiguration.ENGINE_NAME, true, true, true},
            {IndexingQueryEngineConfiguration.ENGINE_NAME, true, true, false},
            {IndexingQueryEngineConfiguration.ENGINE_NAME, true, false, true},
            {IndexingQueryEngineConfiguration.ENGINE_NAME, true, false, false},
            {IndexingQueryEngineConfiguration.ENGINE_NAME, false, true, true},
            {IndexingQueryEngineConfiguration.ENGINE_NAME, false, true, false},
            {IndexingQueryEngineConfiguration.ENGINE_NAME, false, false, true},
            {IndexingQueryEngineConfiguration.ENGINE_NAME, false, false, false}
        });
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        QueryEngineConfigurationEx engCfg = configureSqlEngine();

        cfg.setSqlConfiguration(new SqlConfiguration()
            .setSqlPlanHistorySize(PLAN_HISTORY_SIZE)
            .setQueryEnginesConfiguration(engCfg)
        );

        return cfg.setCacheConfiguration(
            configureCache("A", Integer.class, String.class),
            configureCache("B", Integer.class, String.class),
            configureCache("pers", Integer.class, Person.class),
            configureCache("org", Integer.class, Organization.class)
        );
    }

    /** */
    protected QueryEngineConfigurationEx configureSqlEngine() {
        if (sqlEngine.equals(CalciteQueryEngineConfiguration.ENGINE_NAME))
            return new CalciteQueryEngineConfiguration();
        else
            return new IndexingQueryEngineConfiguration();
    }

    /**
     * @param name Name.
     * @param idxTypes Index types.
     * @return Cache configuration.
     */
    @SuppressWarnings("unchecked")
    private CacheConfiguration configureCache(String name, Class<?>... idxTypes) {
        return new CacheConfiguration()
            .setName(name)
            .setIndexedTypes(idxTypes)
            .setSqlFunctionClasses(Functions.class);
    }

    /**
     * @return Ignite node where queries are executed.
     */
    protected IgniteEx queryNode() {
        IgniteEx node = isClient ? grid(2) : grid(0);

        if (isClient)
            assertTrue(node.context().clientNode());
        else
            assertFalse(node.context().clientNode());

        return node;
    }

    /**
     * @return Ignite map node.
     */
    protected IgniteEx mapNode() {
        IgniteEx node = grid(1);

        assertFalse(node.context().clientNode());

        return node;
    }

    /**
     * Starts Ignite instance.
     *
     * @throws Exception In case of failure.
     */
    protected void startTestGrid() throws Exception {
        startGrids(2);

        if (isClient)
            startClientGrid(2);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        startTestGrid();

        IgniteCache<Integer, String> cacheA = queryNode().cache("A");
        IgniteCache<Integer, String> cacheB = queryNode().cache("B");

        for (int i = 0; i < 100; i++) {
            cacheA.put(i, String.valueOf(i));
            cacheB.put(i, String.valueOf(i));
        }

        IgniteCache<Integer, Person> cachePers = queryNode().cache("pers");
        IgniteCache<Integer, Organization> cacheOrg = queryNode().cache("org");

        cacheOrg.put(101, new Organization("o1"));
        cacheOrg.put(102, new Organization("o2"));
        cachePers.put(103, new Person(101, "p1"));
        cachePers.put(104, new Person(102, "p2"));
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
    }

    /**
     * @param sqlEngine Sql engine.
     */
    protected void setSqlEngine(String sqlEngine) {
        this.sqlEngine = sqlEngine;
    }

    /**
     * @param isClient Client more flag.
     */
    protected void setClientMode(boolean isClient) {
        this.isClient = isClient;
    }

    /**
     * Clears current SQL plan history.
     */
    public void resetPlanHistory() {
        for (Ignite ignite : G.allGrids())
            ((IgniteEx)ignite).context().query().runningQueryManager().resetPlanHistoryMetrics();
    }

    /** Checks successful JDBC queries. */
    @Test
    public void testJdbcQuery() throws Exception {
        for (int i = 0; i < 2; i++) {
            jdbcQuery(SQL);

            checkSqlPlanHistory(1);
        }
    }

    /** Checks failed JDBC queries. */
    @Test
    public void testJdbcQueryFailed() throws Exception {
        try {
            jdbcQuery(SQL_FAILED);
        }
        catch (Exception e) {
            if (e instanceof AssumptionViolatedException)
                throw e;
        }

        checkSqlPlanHistory(1);
    }

    /** Checks successful SqlFieldsQuery. */
    @Test
    public void testSqlFieldsQuery() {
        runSuccessfulQuery(sqlFieldsQry);
    }

    /** Checks failed SqlFieldsQuery. */
    @Test
    public void testSqlFieldsQueryFailed() {
        runFailedQuery(sqlFieldsQryFailed);
    }

    /** Checks successful cross-cache SqlFieldsQuery. */
    @Test
    public void testSqlFieldsCrossCacheQuery() {
        runSuccessfulQuery(sqlFieldsQryCrossCache);
    }

    /** Checks failed cross-cache SqlFieldsQuery. */
    @Test
    public void testSqlFieldsCrossCacheQueryFailed() {
        runFailedQuery(sqlFieldsQryCrossCacheFailed);
    }

    /** Checks successful SqlFieldsQuery with reduce phase. */
    @Test
    public void testSqlFieldsQueryWithReducePhase() {
        assumeFalse("Only distributed queries have the map and reduce phases", loc);

        isReducePhase = true;

        try {
            cacheQuery(sqlFieldsQryWithReducePhase, "pers");

            checkSqlPlanHistory((!isClient && sqlEngine == IndexingQueryEngineConfiguration.ENGINE_NAME) ? 3 : 1);
        }
        finally {
            isReducePhase = false;
        }
    }

    /** Checks failed SqlFieldsQuery with reduce phase. */
    @Test
    public void testSqlFieldsQueryWithReducePhaseFailed() {
        assumeFalse("Only distributed queries have the map and reduce phases", loc);

        isReducePhase = true;

        try {
            for (int i = 0; i < sqlFieldsQryWithReducePhaseFailed.length; i++) {
                try {
                    cacheQuery(sqlFieldsQryWithReducePhaseFailed[i], "pers");
                }
                catch (Exception ignore) {
                    //No-Op
                }

                checkSqlPlanHistory((!isClient && sqlEngine == IndexingQueryEngineConfiguration.ENGINE_NAME) ? i + 1 : 0);

                resetPlanHistory();
            }
        }
        finally {
            isReducePhase = false;
        }
    }

    /** Checks successful SqlQuery. */
    @Test
    public void testSqlQuery() {
        runSuccessfulQuery(sqlQry);
    }

    /** Checks failed SqlQuery. */
    @Test
    public void testSqlQueryFailed() {
        runFailedQuery(sqlQryFailed);
    }

    /** Checks ScanQuery. */
    @Test
    public void testScanQuery() {
        runQueryWithoutPlan(scanQry);
    }

    /** Checks TextQuery. */
    @Test
    public void testTextQuery() {
        runQueryWithoutPlan(textQry);
    }

    /** Checks DML commands executed via JDBC. */
    @Test
    public void testJdbcDml() throws SQLException {
        runDml(new IgniteBiTuple<>(DmlQueryType.JDBC, dmlCmds), true);
    }

    /** Checks DML commands with joins executed via JDBC. */
    @Test
    public void testJdbcDmlWithJoins() throws SQLException {
        runDml(new IgniteBiTuple<>(DmlQueryType.JDBC, dmlCmdsWithJoins), false);
    }

    /** Checks DML commands executed via SqlFieldsQuery. */
    @Test
    public void testSqlFieldsDml() throws SQLException {
        runDml(new IgniteBiTuple<>(DmlQueryType.SQL_FIELDS, dmlCmds), true);
    }

    /** Checks DML commands with joins executed via SqlFieldsQuery. */
    @Test
    public void testSqlFieldsDmlWithJoins() throws SQLException {
        runDml(new IgniteBiTuple<>(DmlQueryType.SQL_FIELDS, dmlCmdsWithJoins), false);
    }

    /** Checks that older plan entries are evicted when maximum history size is reached. */
    @Test
    public void testPlanHistoryEviction() throws Exception {
        assumeFalse("No SQL plans are written on the client node when using the H2 engine",
            isClient && sqlEngine == IndexingQueryEngineConfiguration.ENGINE_NAME);

        for (int i = 1; i <= (PLAN_HISTORY_SIZE + PLAN_HISTORY_EXCESS); i++) {
            try {
                executeQuery(new SqlFieldsQuery(SQL + " where A.fail()=" + i), (q) -> cacheQuery(q, "A"));
            }
            catch (Exception e) {
                if (e instanceof AssumptionViolatedException)
                    throw e;
            }
        }

        GridTestUtils.waitForCondition(() -> getSqlPlanHistory(queryNode()).size() == PLAN_HISTORY_SIZE, 1000);

        checkSqlPlanHistory(PLAN_HISTORY_SIZE);

        Set<String> qrys = getSqlPlanHistory(queryNode()).keySet().stream()
            .map(SqlPlan::query)
            .collect(Collectors.toSet());

        for (int i = 1; i <= PLAN_HISTORY_EXCESS; i++)
            assertFalse(qrys.contains(SQL + " where A.fail=" + i));
    }

    /**
     * Checks that older SQL plan history entries are replaced with newer ones with the same parameters (except for
     * the beginning time).
     */
    @Test
    public void testEntryReplacement() throws InterruptedException {
        assumeFalse("With the H2 engine, scan counts can be added to SQL plans for local queries ",
            loc && sqlEngine == IndexingQueryEngineConfiguration.ENGINE_NAME);

        assumeFalse("No SQL plans are written on the client node when using the H2 engine",
            isClient && sqlEngine == IndexingQueryEngineConfiguration.ENGINE_NAME);

        long[] timeStamps = new long[2];

        for (int i = 0; i < 2; i++) {
            executeQuery(sqlFieldsQry, (q) -> cacheQuery(q, "A"));

            checkSqlPlanHistory(1);

            timeStamps[i] = getSqlPlanHistory(queryNode()).values().stream().findFirst().get();

            Thread.sleep(10);
        }

        assertTrue(timeStamps[1] > timeStamps[0]);
    }

    /** Checks that SQL plan history remains empty if history size is set to zero. */
    @Test
    public void testEmptyPlanHistory() {
        queryNode().context().query().runningQueryManager().planHistoryTracker().setHistorySize(0);

        executeQuery(sqlFieldsQry, (q) -> cacheQuery(q, "A"));

        assertTrue(getSqlPlanHistory(queryNode()).isEmpty());
    }

    /**
     * @param qry Query.
     */
    public void runSuccessfulQuery(Query qry) {
        executeQuery(qry, (q) -> {
            for (int i = 0; i < 2; i++) {
                cacheQuery(q, "A");

                checkSqlPlanHistory(1);
            }
        });
    }

    /**
     * @param qry Query.
     */
    public void runFailedQuery(Query qry) {
        executeQuery(qry, (q) -> {
            try {
                cacheQuery(q, "A");
            }
            catch (Exception ignore) {
                //No-Op
            }

            checkSqlPlanHistory(1);
        });
    }

    /**
     * @param qry Query.
     */
    public void runQueryWithoutPlan(Query qry) {
        executeQuery(qry, (q) -> {
            cacheQuery(q, "A");

            checkSqlPlanHistory(0);
        });
    }

    /**
     * @param qry Query.
     * @param task Task to execute.
     */
    public void executeQuery(Query qry, Consumer<Query> task) {
        assumeFalse("Local queries can't be executed on client nodes", isClient && loc);

        qry.setLocal(loc);

        task.accept(qry);
    }

    /**
     * @param qry Query.
     */
    private void jdbcQuery(String qry) throws Exception {
        assumeFalse("There is no 'local query' parameter for JDBC queries", loc);

        try (
            Connection conn = GridTestUtils.connect(queryNode(), null);
            Statement stmt = conn.createStatement()
        ) {
            if (!isFullyFetched)
                stmt.setFetchSize(1);

            ResultSet rs = stmt.executeQuery(qry);

            assertTrue(rs.next());
        }
    }

    /**
     * @param qry Query.
     * @param cacheName Cache name.
     */
    public void cacheQuery(Query qry, String cacheName) {
        IgniteCache<Integer, String> cache = queryNode().getOrCreateCache(cacheName);

        if (isFullyFetched)
            assertFalse(cache.query(qry).getAll().isEmpty());
        else {
            qry.setPageSize(1);

            assertTrue(cache.query(qry).iterator().hasNext());

            cache.query(qry).iterator().next();
        }
    }

    /**
     * @param qrysInfo Information about the DML operations that need to be executed (type, list of commands).
     * @param isSimpleQry Simple query flag.
     */
    public void runDml(IgniteBiTuple<DmlQueryType, List<String>> qrysInfo, boolean isSimpleQry) throws SQLException {
        assumeFalse("Local queries can't be executed on client nodes", isClient && loc);

        assumeFalse("There is no lazy mode for DML operations", !isFullyFetched);

        DmlQueryType type = qrysInfo.get1();

        assumeFalse("There is no 'local query' parameter for JDBC queries", loc && type == DmlQueryType.JDBC);

        List<String> cmds = qrysInfo.get2();

        if (type == DmlQueryType.JDBC) {
            try (
                Connection conn = GridTestUtils.connect(queryNode(), null);
                Statement stmt = conn.createStatement()
            ) {
                for (String cmd : cmds)
                    stmt.execute(cmd);
            }
        }
        else if (type == DmlQueryType.SQL_FIELDS) {
            IgniteCache<Integer, String> cache = queryNode().getOrCreateCache("A");

            cmds.forEach(c -> cache.query(new SqlFieldsQuery(c).setLocal(loc)));
        }

        checkSqlPlanHistoryDml(3, isSimpleQry);
    }

    /**
     * @param node Ignite node to check SQL plan history for.
     */
    public Map<SqlPlan, Long> getSqlPlanHistory(IgniteEx node) {
        return node.context().query().runningQueryManager().planHistoryTracker().sqlPlanHistory();
    }

    /**
     * Prepares SQL plan history entries for futher checking.
     *
     * @param size Number of SQL plan entries expected to be in the history.
     */
    public void checkSqlPlanHistory(int size) {
        Map<SqlPlan, Long> sqlPlans = getSqlPlanHistory(queryNode());

        assertNotNull(sqlPlans);

        if (!isReducePhase && isClient && sqlEngine == IndexingQueryEngineConfiguration.ENGINE_NAME) {
            assertEquals(0, sqlPlans.size());

            sqlPlans = getSqlPlanHistory(mapNode());
        }

        checkMetrics(size, sqlPlans);
    }

    /**
     * Prepares SQL plan history entries for futher checking (DML operations).
     *
     * @param size Number of SQL plan entries expected to be in the history.
     * @param isSimpleQry Simple query flag.
     */
    public void checkSqlPlanHistoryDml(int size, boolean isSimpleQry) {
        Map<SqlPlan, Long> sqlPlans = getSqlPlanHistory(queryNode());

        assertNotNull(sqlPlans);

        if (sqlEngine == IndexingQueryEngineConfiguration.ENGINE_NAME) {
            String check;

            if (isSimpleQry)
                check = "no SELECT queries have been executed.";
            else
                check = "the following " + (loc ? "local " : "") + "query has been executed:";

            sqlPlans = sqlPlans.entrySet().stream()
                .filter(e -> e.getKey().plan().contains(check))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));

            checkMetrics((!isSimpleQry && !loc) ? size : 0, getSqlPlanHistory(mapNode()));
        }

        checkMetrics(size, sqlPlans);
    }

    /**
     * Checks metrics of provided SQL plan history entries.
     *
     * @param size Number of SQL plan entries expected to be in the history.
     * @param sqlPlans Sql plans recorded in the history.
     */
    public void checkMetrics(int size, Map<SqlPlan, Long> sqlPlans) {
        if (size == 1 && sqlPlans.size() == 2) {
            List<Map.Entry<SqlPlan, Long>> sortedPlans = new ArrayList<>(sqlPlans.entrySet()).stream()
                .sorted(Comparator.comparing(Map.Entry::getValue))
                .collect(Collectors.toList());

            String plan1 = sortedPlans.get(0).getKey().plan();
            String plan2 = sortedPlans.get(1).getKey().plan();

            assertTrue(plan2.contains(plan1) && plan2.contains("/* scanCount"));
        }
        else
            assertTrue(size == sqlPlans.size());

        if (size == 0)
            return;

        for (Map.Entry<SqlPlan, Long> plan : sqlPlans.entrySet()) {
            assertEquals(loc, plan.getKey().local());
            assertEquals(sqlEngine, plan.getKey().engine());

            assertNotNull(plan.getKey().plan());
            assertNotNull(plan.getKey().query());
            assertNotNull(plan.getKey().schema());

            assertTrue(plan.getValue() > 0);
        }
    }

    /** */
    private static class Person {
        /** */
        @QuerySqlField(index = true)
        int orgId;

        /** */
        @QuerySqlField(index = true)
        String name;

        /**
         * @param orgId Organization ID.
         * @param name Name.
         */
        public Person(int orgId, String name) {
            this.orgId = orgId;
            this.name = name;
        }
    }

    /** */
    private static class Organization {
        /** */
        @QuerySqlField
        String name;

        /**
         * @param name Organization name.
         */
        public Organization(String name) {
            this.name = name;
        }
    }

    /** */
    public static class Functions {
        /** */
        @QuerySqlFunction
        public static int fail() {
            throw new IgniteSQLException("SQL function fail for test purpuses");
        }
    }

    /** */
    private enum DmlQueryType {
        /** JDBC query. */
        JDBC,

        /** SqlFields query. */
        SQL_FIELDS
    }
}
