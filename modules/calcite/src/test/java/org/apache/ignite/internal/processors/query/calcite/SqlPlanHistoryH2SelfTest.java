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

package org.apache.ignite.internal.processors.query.calcite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.processors.query.IgniteSQLException;
import org.apache.ignite.internal.processors.query.QueryEngineConfigurationEx;
import org.apache.ignite.internal.processors.query.running.SqlPlan;
import org.apache.ignite.internal.processors.query.running.SqlPlanHistoryTracker;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Tests for SQL plan history (H2 engine). */
@RunWith(Parameterized.class)
public class SqlPlanHistoryH2SelfTest extends GridCommonAbstractTest {
    /** SQL Plan history size. */
    private static final int PLAN_HISTORY_SIZE = 10;

    /** Plan history size excess. */
    private static final int PLAN_HISTORY_EXCESS = 2;

    /** Simple query. */
    private static final String SQL = "SELECT * FROM A.String";

    /** Failed query. */
    private static final String SQL_FAILED = "select * from A.String where A.fail()=1";

    /** Cross-cache query. */
    private static final String SQL_CROSS_CACHE = "SELECT * FROM B.String";

    /** Failed cross-cache query. */
    private static final String SQL_CROSS_CACHE_FAILED = "select * from A.String where A.fail()=1";

    /** Query with reduce phase. */
    private static final String SQL_WITH_REDUCE_PHASE = "select o.name n1, p.name n2 from \"pers\".Person p, " +
        "\"org\".Organization o where p.orgId=o._key and o._key=101" +
        " union select o.name n1, p.name n2 from \"pers\".Person p, \"org\".Organization o" +
        " where p.orgId=o._key and o._key=102";

    /** Sets of DML commands of various complexity including failed commands. */
    List<List<String>> dmlCmds = Arrays.asList(
        Arrays.asList(
            "insert into A.String (_key, _val) values(101, '101')",
            "update A.String set _val='111' where _key=101",
            "delete from A.String where _key=101"
        ),
        Arrays.asList(
            "insert into A.String (_key, _val) values(101, '101'), (102, '102'), (103, '103')",
            "update A.String set _val = case _key " +
                "when 101 then '111' " +
                "when 102 then '112' " +
                "when 103 then '113' " +
                "end " +
                "where _key in (101, 102, 103)",
            "delete from A.String where _key in (101, 102, 103)"
        ),
        Arrays.asList(
            "insert into A.String (_key, _val) select o._key, p.name " +
                "from \"pers\".Person p, \"org\".Organization o where p.orgId=o._key",
            "update A.String set _val = 'updated' where _key in " +
                "(select o._key from \"pers\".Person p, \"org\".Organization o where p.orgId=o._key)",
            "delete from A.String where _key in (select orgId from \"pers\".Person)"
        ),
        Arrays.asList(
            "insert into A.String (_key, _val) select o._key, p.name from \"pers\".Person p, \"org\".Organization o " +
                "where A.fail()=1",
            "update A.String set _val = 'failed' where A.fail()=1",
            "delete from A.String where A.fail()=1"
        )
    );

    /** Strings for checking SQL plan history after executing DML commands. */
    List<String> dmlCheckStrings = Arrays.asList("mode=INSERT", "mode=UPDATE", "mode=DELETE");

    /** Successful SqlFields query. */
    SqlFieldsQuery sqlFieldsQry = new SqlFieldsQuery(SQL);

    /** Failed SqlFields query. */
    SqlFieldsQuery sqlFieldsQryFailed = new SqlFieldsQuery(SQL_FAILED);

    /** Successful cross-cache SqlFields query. */
    SqlFieldsQuery sqlFieldsCrossCacheQry = new SqlFieldsQuery(SQL_CROSS_CACHE);

    /** Failed cross-cache SqlFields query. */
    SqlFieldsQuery sqlFieldsCrossCacheQryFailed = new SqlFieldsQuery(SQL_CROSS_CACHE_FAILED);

    /** Successful Sql query. */
    SqlQuery sqlQry = new SqlQuery<>("String", "from String");

    /** Failed Sql query. */
    SqlQuery sqlQryFailed = new SqlQuery<>("String", "from String where fail()=1");

    /** Scan query. */
    ScanQuery<Integer, String> scanQry = new ScanQuery<>();

    /** Text query. */
    TextQuery<Integer, String> textQry = new TextQuery<>("String", "2");

    /** */
    private final SqlFieldsQuery sqlFieldsQryWithReducePhase = new SqlFieldsQuery(SQL_WITH_REDUCE_PHASE)
        .setDistributedJoins(true);

    /** */
    private final SqlFieldsQuery[] sqlFieldsQryWithReducePhaseFailed = F.asArray(
        new SqlFieldsQuery(SQL_WITH_REDUCE_PHASE.replace("o._key=101", "fail()")).setDistributedJoins(true),
        new SqlFieldsQuery(SQL_WITH_REDUCE_PHASE.replace("o._key=102", "fail()")).setDistributedJoins(true)
    );

    /** Client mode flag. */
    private boolean isClient;

    /** Sql engine. */
    private SqlPlanHistoryTracker.SqlEngine sqlEngine;

    /** Local query flag. */
    @Parameterized.Parameter
    public boolean loc;

    /** Fully fetched query flag. */
    @Parameterized.Parameter(1)
    public boolean isFullyFetched;

    /** */
    @Parameterized.Parameters(name = "loc={0}, fullyFetched={1}")
    public static Collection<Object[]> params() {
        return Arrays.asList(new Object[][] {
            {true, true}, {true, false}, {false, true}, {false, false}
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
            configureCahce("A", Integer.class, String.class),
            configureCahce("B", Integer.class, String.class),
            configureCahce("pers", Integer.class, Person.class),
            configureCahce("org", Integer.class, Organization.class)
        );
    }

    /** */
    protected QueryEngineConfigurationEx configureSqlEngine() {
        return new IndexingQueryEngineConfiguration();
    }

    /**
     * @param name Name.
     * @param idxTypes Index types.
     * @return Cache configuration.
     */
    @SuppressWarnings("unchecked")
    private CacheConfiguration configureCahce(String name, Class<?>... idxTypes) {
        return new CacheConfiguration()
            .setName(name)
            .setIndexedTypes(idxTypes)
            .setSqlFunctionClasses(Functions.class);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        super.beforeTestsStarted();

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
        cachePers.put(105, new Person(103, "p3"));
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        super.afterTestsStopped();

        stopAllGrids();
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        resetPlanHistory();

        sqlEngine = isCalcite() ? SqlPlanHistoryTracker.SqlEngine.CALCITE : SqlPlanHistoryTracker.SqlEngine.H2;

        try {
            if (grid(1).configuration().isClientMode())
                isClient = true;
        }
        catch (Exception ignore) {
            //No-Op
        }
    }

    /** */
    public boolean isCalcite() {
        return grid(0).context().config().getSqlConfiguration().getQueryEnginesConfiguration()[0] instanceof
            CalciteQueryEngineConfiguration;
    }

    /** */
    public void resetPlanHistory() {
        queryNode().context().query().runningQueryManager().resetPlanHistoryMetrics();
    }

    /** Checks successful JDBC queries. */
    @Test
    public void testJdbcQuery() throws SQLException {
        if (loc)
            return;

        for (int i = 0; i < 2; i++) {
            runJdbcQuery(SQL);

            checkSqlPlanHistory(getExpectedHistorySize());
        }
    }

    /** Checks failed JDBC queries. */
    @Test
    public void testJdbcQueryFailed() {
        if (loc)
            return;

        try {
            runJdbcQuery(SQL_FAILED);
        }
        catch (Exception ignore) {
            //No-Op
        }

        checkSqlPlanHistory(getExpectedHistorySize());
    }

    /** Checks successful SqlFields queries. */
    @Test
    public void testSqlFieldsQuery() {
        executeSuccessfulQuery(sqlFieldsQry);
    }

    /** Checks failed SqlFields queries. */
    @Test
    public void testSqlFieldsQueryFailed() {
        executeFailedQuery(sqlFieldsQryFailed);
    }

    /** Checks successful cross-cache SqlFields queries. */
    @Test
    public void testSqlFieldsCrossCacheQuery() {
        executeSuccessfulQuery(sqlFieldsCrossCacheQry);
    }

    /** Checks failed cross-cache SqlFields queries. */
    @Test
    public void testSqlFieldsCrossCacheQueryFailed() {
        executeFailedQuery(sqlFieldsCrossCacheQryFailed);
    }

    /** Checks successful SqlFields queries with reduce phase. */
    @Test
    public void testSqlFieldsQueryWithReducePhase() {
        if (loc)
            return;

        runCacheQuery(sqlFieldsQryWithReducePhase, "pers");

        checkSqlPlanHistory((!isClient && sqlEngine == SqlPlanHistoryTracker.SqlEngine.H2) ? 3 : 1);
    }

    /** Checks failed SqlFields queries with reduce phase. */
    @Test
    public void testSqlFieldsQueryWithReducePhaseFailed() {
        if (loc)
            return;

        for (int i = 0; i < sqlFieldsQryWithReducePhaseFailed.length; i++) {
            try {
                runCacheQuery(sqlFieldsQryWithReducePhaseFailed[i], "pers");
            }
            catch (Exception ignore) {
                //No-Op
            }

            checkSqlPlanHistory((!isClient && sqlEngine == SqlPlanHistoryTracker.SqlEngine.H2) ? i + 1 : 0);

            resetPlanHistory();
        }
    }

    /** Checks successful Sql queries. */
    @Test
    public void testSqlQuery() {
        executeSuccessfulQuery(sqlQry);
    }

    /** Checks failed Sql queries. */
    @Test
    public void testSqlQueryFailed() {
        executeFailedQuery(sqlQryFailed);
    }

    /** Checks scan queries. */
    @Test
    public void testScanQuery() {
        executeQueryWithoutPlan(scanQry);
    }

    /** Checks text queries. */
    @Test
    public void testTextQuery() {
        executeQueryWithoutPlan(textQry);
    }

    /** Checks DML commands executed via JDBC. */
    @Test
    public void testJdbcDml() throws Exception {
        if (loc || !isFullyFetched)
            return;

        try (
            Connection conn = GridTestUtils.connect(queryNode(), null);
            Statement stmt = conn.createStatement()
        ) {
            for (List<String> setOfCmds : dmlCmds) {
                for (String cmd : setOfCmds) {
                    try {
                        stmt.execute(cmd);
                    }
                    catch (Exception ignore) {
                        //No-Op
                    }
                }

                checkSqlPlanHistoryDml(3);

                resetPlanHistory();
            }
        }
    }

    /** Checks DML commands executed via SqlField queries. */
    @Test
    public void testSqlFieldsDml() {
        if (isClient && loc || !isFullyFetched)
            return;

        for (List<String> setOfCmds : dmlCmds) {
            setOfCmds.forEach(this::runDmlCommand);

            checkSqlPlanHistoryDml(3);

            resetPlanHistory();
        }
    }

    /** Checks that older plan entries are evicted when maximum history size is reached. */
    @Test
    public void testPlanHistoryEviction() throws IgniteInterruptedCheckedException {
        if (loc || (!loc && isFullyFetched))
            return;

        for (int i = 1; i <= (PLAN_HISTORY_SIZE + PLAN_HISTORY_EXCESS); i++) {
            try {
                runCacheQuery(new SqlFieldsQuery(SQL + " where A.fail()=" + i), "A");
            }
            catch (Exception ignore) {
                //No-Op
            }
        }

        GridTestUtils.waitForCondition(() -> getSqlPlanHistoryValues().size() == PLAN_HISTORY_SIZE, 1000);

        checkSqlPlanHistory((isClient && sqlEngine == SqlPlanHistoryTracker.SqlEngine.H2) ? 0 : PLAN_HISTORY_SIZE);

        Set<String> qrys = getSqlPlanHistoryValues().stream().map(SqlPlan::query).collect(Collectors.toSet());

        for (int i = 1; i <= PLAN_HISTORY_EXCESS; i++)
            assertFalse(qrys.contains(SQL + " where A.fail=" + i));
    }

    /**
     * @param qry Query.
     */
    public void executeSuccessfulQuery(Query qry) {
        executeQuery(qry, () -> {
            for (int i = 0; i < 2; i++) {
                runCacheQuery(qry, "A");

                checkSqlPlanHistory(getExpectedHistorySize());
            }
        });
    }

    /**
     * @param qry Query.
     */
    public void executeFailedQuery(Query qry) {
        executeQuery(qry, () -> {
            try {
                runCacheQuery(qry, "A");
            }
            catch (Exception ignore) {
                //No-Op
            }

            checkSqlPlanHistory(getExpectedHistorySize());
        });
    }

    /**
     * @param qry Query.
     */
    public void executeQueryWithoutPlan(Query qry) {
        executeQuery(qry, () -> {
            runCacheQuery(qry, "A");

            checkSqlPlanHistory(0);
        });
    }

    /**
     * @param qry Query.
     * @param task Task to execute.
     */
    public void executeQuery(Query qry, Runnable task) {
        if (isClient && loc)
            return;

        qry.setLocal(loc);

        task.run();
    }

    /**
     * @param qry Query.
     */
    private void runJdbcQuery(String qry) throws SQLException {
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
    public void runCacheQuery(Query qry, String cacheName) {
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
     * @param cmd DML command.
     */
    public void runDmlCommand(String cmd) {
        IgniteCache<Integer, String> cache = queryNode().getOrCreateCache("A");

        try {
            cache.query(new SqlFieldsQuery(cmd).setLocal(loc));
        }
        catch (Exception ignore) {
            //No-Op
        }
    }

    /** */
    public Collection<SqlPlan> getSqlPlanHistoryValues() {
        return queryNode().context().query().runningQueryManager().planHistoryTracker()
            .sqlPlanHistory().values();
    }

    /** */
    public int getExpectedHistorySize() {
        return (isClient && sqlEngine == SqlPlanHistoryTracker.SqlEngine.H2) ? 0 : 1;
    }

    /**
     * @param size Number of SQL plan entries expected to be in the history.
     */
    public void checkSqlPlanHistory(int size) {
        Collection<SqlPlan> sqlPlans = getSqlPlanHistoryValues();

        assertNotNull(sqlPlans);

        checkMetrics(size, sqlPlans);
    }

    /**
     * @param size Number of SQL plan entries expected to be in the history.
     */
    public void checkSqlPlanHistoryDml(int size) {
        Collection<SqlPlan> sqlPlans = getSqlPlanHistoryValues();

        assertNotNull(sqlPlans);

        if (sqlEngine == SqlPlanHistoryTracker.SqlEngine.H2) {
            Collection<SqlPlan> sqlPlans0 = new ArrayList<>();

            for (String str : dmlCheckStrings)
                sqlPlans0.addAll(sqlPlans.stream().filter(p -> p.plan().contains(str)).collect(Collectors.toList()));

            sqlPlans = sqlPlans0;
        }

        checkMetrics(size, sqlPlans);
    }

    /**
     * @param size Number of SQL plan entries expected to be in the history.
     * @param sqlPlans Sql plans recorded in the history.
     */
    public void checkMetrics(int size, Collection<SqlPlan> sqlPlans) {
        if (size == 1 && sqlPlans.size() == 2) {
            String plan1 = new ArrayList<>(sqlPlans).get(0).plan();
            String plan2 = new ArrayList<>(sqlPlans).get(1).plan();

            assertTrue(plan2.contains(plan1) && plan2.contains("/* scanCount"));
        }
        else
            assertTrue(size == sqlPlans.size());

        if (size == 0)
            return;

        for (SqlPlan plan : sqlPlans) {
            assertEquals(loc, plan.local());
            assertEquals(sqlEngine.toString(), plan.engine());

            assertNotNull(plan.plan());
            assertNotNull(plan.query());
            assertNotNull(plan.schema());

            assertTrue(plan.startTime() > 0);
        }
    }

    /**
     * @return Ignite instance for quering.
     */
    protected IgniteEx queryNode() {
        IgniteEx node = grid(0);

        assertFalse(node.context().clientNode());

        return node;
    }

    /**
     * Starts Ignite instance.
     *
     * @throws Exception In case of failure.
     */
    protected void startTestGrid() throws Exception {
        startGrid(0);
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
}
