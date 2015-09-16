package com.thinkaurelius.titan.graphdb;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricFilter;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.attribute.Cmp;
import com.thinkaurelius.titan.core.schema.ConsistencyModifier;
import com.thinkaurelius.titan.core.schema.TitanGraphIndex;
import com.thinkaurelius.titan.diskstorage.Backend;
import static com.thinkaurelius.titan.diskstorage.Backend.*;
import com.thinkaurelius.titan.diskstorage.configuration.BasicConfiguration;
import com.thinkaurelius.titan.diskstorage.configuration.ModifiableConfiguration;
import com.thinkaurelius.titan.diskstorage.configuration.WriteConfiguration;
import com.thinkaurelius.titan.diskstorage.util.CacheMetricsAction;
import com.thinkaurelius.titan.diskstorage.util.MetricInstrumentedStore;
import static com.thinkaurelius.titan.diskstorage.util.MetricInstrumentedStore.*;


import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;
import static com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration.*;
import static com.thinkaurelius.titan.graphdb.database.cache.MetricInstrumentedSchemaCache.*;
import static com.thinkaurelius.titan.testutil.TitanAssert.assertCount;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

import com.thinkaurelius.titan.graphdb.internal.ElementCategory;
import com.thinkaurelius.titan.graphdb.internal.InternalRelationType;
import com.thinkaurelius.titan.graphdb.internal.InternalVertexLabel;
import com.thinkaurelius.titan.graphdb.types.CompositeIndexType;
import com.thinkaurelius.titan.graphdb.types.IndexType;
import com.thinkaurelius.titan.testcategory.SerialTests;
import com.thinkaurelius.titan.util.stats.MetricManager;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */
@Category({ SerialTests.class })
public abstract class TitanOperationCountingTest extends TitanGraphBaseTest {

    public MetricManager metric;
    public final String SYSTEM_METRICS  = GraphDatabaseConfiguration.METRICS_SYSTEM_PREFIX_DEFAULT;

    public abstract WriteConfiguration getBaseConfiguration();

    public abstract boolean storeUsesConsistentKeyLocker();

    @Override
    public WriteConfiguration getConfiguration() {
        WriteConfiguration config = getBaseConfiguration();
        ModifiableConfiguration mconf = new ModifiableConfiguration(GraphDatabaseConfiguration.ROOT_NS,config, BasicConfiguration.Restriction.NONE);
        mconf.set(BASIC_METRICS,true);
        mconf.set(METRICS_MERGE_STORES,false);
        mconf.set(PROPERTY_PREFETCHING,false);
        mconf.set(DB_CACHE, false);
        return config;
    }

    @Override
    public void open(WriteConfiguration config) {
        metric = MetricManager.INSTANCE;
        super.open(config);
    }


    private void verifyLockingOverwrite(String storeName, long num) {
        if (storeUsesConsistentKeyLocker()) {
            verifyStoreMetrics(storeName, ImmutableMap.of(M_GET_SLICE, 2*num));
            verifyStoreMetrics(storeName+LOCK_STORE_SUFFIX, ImmutableMap.of(M_GET_SLICE, num, M_MUTATE, 2*num));
        } else {
            verifyStoreMetrics(storeName, ImmutableMap.of(M_GET_SLICE, num, M_ACQUIRE_LOCK, num));
        }
    }

    @Test
    public void testIdCounts() {
        makeVertexIndexedUniqueKey("uid", Integer.class);
        mgmt.setConsistency(mgmt.getGraphIndex("uid"), ConsistencyModifier.LOCK);
        finishSchema();

        //Schema and relation id pools are tapped, Schema id pool twice because the renew is triggered. Each id acquisition requires 1 mutations and 2 reads
        verifyStoreMetrics(ID_STORE_NAME, SYSTEM_METRICS, ImmutableMap.of(M_MUTATE, 3l, M_GET_SLICE, 6l));
    }


    @Test
    public void testReadOperations() {
        testReadOperations(false);
    }

    @Test
    public void testReadOperationsWithCache() {
        testReadOperations(true);
    }

    public void testReadOperations(boolean cache) {
        metricsPrefix = "testReadOperations"+cache;

        resetEdgeCacheCounts();

        makeVertexIndexedUniqueKey("uid", Integer.class);
        mgmt.setConsistency(mgmt.getGraphIndex("uid"),ConsistencyModifier.LOCK);
        finishSchema();

        if (cache) clopen(option(DB_CACHE),true,option(DB_CACHE_CLEAN_WAIT),0,option(DB_CACHE_TIME),0);
        else clopen();

        TitanTransaction tx = graph.buildTransaction().groupName(metricsPrefix).start();
        tx.makePropertyKey("name").dataType(String.class).make();
        tx.makeEdgeLabel("knows").make();
        tx.makeVertexLabel("person").make();
        tx.commit();
        verifyStoreMetrics(EDGESTORE_NAME);
        verifyLockingOverwrite(INDEXSTORE_NAME, 3);
        verifyStoreMetrics(METRICS_STOREMANAGER_NAME, ImmutableMap.of(M_MUTATE, 1l));

        resetMetrics();

        metricsPrefix=GraphDatabaseConfiguration.METRICS_SCHEMA_PREFIX_DEFAULT;

        resetMetrics();

        //Test schema caching
        for (int t=0;t<10;t++) {
            tx = graph.buildTransaction().groupName(metricsPrefix).start();
            //Retrieve name by index (one backend call each)
            assertTrue(tx.containsRelationType("name"));
            assertTrue(tx.containsRelationType("knows"));
            assertTrue(tx.containsVertexLabel("person"));
            PropertyKey name = tx.getPropertyKey("name");
            EdgeLabel knows = tx.getEdgeLabel("knows");
            VertexLabel person = tx.getVertexLabel("person");
            PropertyKey uid = tx.getPropertyKey("uid");
            //Retrieve name as property (one backend call each)
            assertEquals("name",name.name());
            assertEquals("knows",knows.name());
            assertEquals("person",person.name());
            assertEquals("uid",uid.name());
            //Looking up the definition (one backend call each)
            assertEquals(Cardinality.SINGLE,name.cardinality());
            assertEquals(Multiplicity.MULTI,knows.multiplicity());
            assertFalse(person.isPartitioned());
            assertEquals(Integer.class,uid.dataType());
            //Retrieving in and out relations for the relation types...
            InternalRelationType namei = (InternalRelationType)name;
            InternalRelationType knowsi = (InternalRelationType)knows;
            InternalRelationType uidi = (InternalRelationType)uid;
            assertNull(namei.getBaseType());
            assertNull(knowsi.getBaseType());
            IndexType index = Iterables.getOnlyElement(uidi.getKeyIndexes());
            assertEquals(1,index.getFieldKeys().length);
            assertEquals(ElementCategory.VERTEX,index.getElement());
            assertEquals(ConsistencyModifier.LOCK,((CompositeIndexType)index).getConsistencyModifier());
            assertEquals(1, Iterables.size(uidi.getRelationIndexes()));
            assertEquals(1, Iterables.size(namei.getRelationIndexes()));
            assertEquals(namei, Iterables.getOnlyElement(namei.getRelationIndexes()));
            assertEquals(knowsi, Iterables.getOnlyElement(knowsi.getRelationIndexes()));
            //.. and vertex labels
            assertEquals(0,((InternalVertexLabel)person).getTTL());

            tx.commit();
            //Needs to read on first iteration, after that it doesn't change anymore
            verifyStoreMetrics(EDGESTORE_NAME, ImmutableMap.of(M_GET_SLICE, 19l));
            verifyStoreMetrics(INDEXSTORE_NAME,
                    ImmutableMap.of(M_GET_SLICE, 4l /* name, knows, person, uid */, M_ACQUIRE_LOCK, 0l));
        }

        //Create some graph data
        metricsPrefix = "add"+cache;

        tx = graph.buildTransaction().groupName(metricsPrefix).start();
        TitanVertex v = tx.addVertex(), u = tx.addVertex("person");
        v.property(VertexProperty.Cardinality.single, "uid",  1);
        u.property(VertexProperty.Cardinality.single, "name",  "juju");
        Edge e = v.addEdge("knows",u);
        e.property("name", "edge");
        tx.commit();
        verifyStoreMetrics(EDGESTORE_NAME);
        verifyLockingOverwrite(INDEXSTORE_NAME, 1);

        for (int i = 1; i <= 30; i++) {
            metricsPrefix = "op"+i+cache;
            tx = graph.buildTransaction().groupName(metricsPrefix).start();
            v = getOnlyElement(tx.query().has("uid",1).vertices());
            assertEquals(1,v.<Integer>value("uid").intValue());
            u = getOnlyElement(v.query().direction(Direction.BOTH).labels("knows").vertices());
            e = getOnlyElement(u.query().direction(Direction.IN).labels("knows").edges());
            assertEquals("juju",u.value("name"));
            assertEquals("edge",e.value("name"));
            tx.commit();
            if (!cache || i==0) {
                verifyStoreMetrics(EDGESTORE_NAME, ImmutableMap.of(M_GET_SLICE, 4l));
                verifyStoreMetrics(INDEXSTORE_NAME, ImmutableMap.of(M_GET_SLICE, 1l));
            } else if (cache && i>20) { //Needs a couple of iterations for cache to be cleaned
                verifyStoreMetrics(EDGESTORE_NAME);
                verifyStoreMetrics(INDEXSTORE_NAME);
            }

        }


    }

    @Test
    public void testSettingProperty() throws Exception {
        metricsPrefix = "testSettingProperty";

        mgmt.makePropertyKey("foo").dataType(String.class).cardinality(Cardinality.SINGLE).make();
        finishSchema();

        TitanVertex v = tx.addVertex();
        v.property("foo","bar");
        tx.commit();


        TitanTransaction tx = graph.buildTransaction().checkExternalVertexExistence(false).groupName(metricsPrefix).start();
        v = tx.getVertex(v.longId());
        v.property("foo", "bus");
//        printAllMetrics();
        tx.commit();
        verifyStoreMetrics(EDGESTORE_NAME);
        verifyStoreMetrics(INDEXSTORE_NAME);
        verifyStoreMetrics(METRICS_STOREMANAGER_NAME, ImmutableMap.of(M_MUTATE, 1l));

        tx = graph.buildTransaction().checkExternalVertexExistence(false).groupName(metricsPrefix).start();
        v = tx.getVertex(v.longId());
        v.property("foo", "band");
        assertEquals("band", v.property("foo").value());
        assertEquals(1, Iterators.size(v.properties("foo")));
        assertEquals(1, Iterators.size(v.properties()));
        tx.commit();
        verifyStoreMetrics(EDGESTORE_NAME, ImmutableMap.of(M_GET_SLICE, 2l));
        verifyStoreMetrics(INDEXSTORE_NAME);
        verifyStoreMetrics(METRICS_STOREMANAGER_NAME, ImmutableMap.of(M_MUTATE, 2l));
        verifyStoreMetrics(ID_STORE_NAME);
    }


    @Test
    public void testKCVSAccess1() throws InterruptedException {
        metricsPrefix = "testKCVSAccess1";

        TitanTransaction tx = graph.buildTransaction().groupName(metricsPrefix).start();
        TitanVertex v = tx.addVertex("age", 25, "name", "john");
        TitanVertex u = tx.addVertex("age", 35, "name", "mary");
        v.addEdge("knows", u);
        tx.commit();
        verifyStoreMetrics(EDGESTORE_NAME);
        verifyLockingOverwrite(INDEXSTORE_NAME, 3);
        verifyStoreMetrics(METRICS_STOREMANAGER_NAME, ImmutableMap.of(M_MUTATE, 1l + (features.hasTxIsolation()?0:1)));

        verifyTypeCacheMetrics(3, 0);

        //Check type name & definition caching
        tx = graph.buildTransaction().groupName(metricsPrefix).start();
        v = getV(tx,v);
        assertCount(2, v.properties());
        verifyStoreMetrics(EDGESTORE_NAME, ImmutableMap.of(M_GET_SLICE, 2l)); //1 verify vertex existence, 1 for query
        verifyTypeCacheMetrics(3, 4);
        tx.commit();

        tx = graph.buildTransaction().groupName(metricsPrefix).start();
        v = getV(tx,v);
        assertCount(2, v.properties());
        verifyStoreMetrics(EDGESTORE_NAME, ImmutableMap.of(M_GET_SLICE, 4l)); //1 verify vertex existence, 1 for query
        verifyTypeCacheMetrics(3, 4);
        tx.commit();

        //Check type index lookup caching
        tx = graph.buildTransaction().groupName(metricsPrefix).start();
        v = getV(tx,v);
        assertNotNull(v.value("age"));
        assertNotNull(v.value("name"));
        verifyStoreMetrics(EDGESTORE_NAME, ImmutableMap.of(M_GET_SLICE, 7l)); //1 verify vertex existence, 2 for query
        verifyTypeCacheMetrics(5, 8);
        tx.commit();

        tx = graph.buildTransaction().groupName(metricsPrefix).start();
        v = getV(tx,v);
        assertNotNull(v.value("age"));
        assertNotNull(v.value("name"));
        assertCount(1, v.query().direction(Direction.BOTH).edges());
        verifyStoreMetrics(EDGESTORE_NAME, ImmutableMap.of(M_GET_SLICE, 11l)); //1 verify vertex existence, 3 for query
        verifyTypeCacheMetrics(5, 10);
        tx.commit();

        verifyLockingOverwrite(INDEXSTORE_NAME, 3);
        verifyStoreMetrics(METRICS_STOREMANAGER_NAME, ImmutableMap.of(M_MUTATE, 1l + (features.hasTxIsolation()?0:1)));

    }

    @Test
    public void checkPropertyLockingAndIndex() {
        PropertyKey uid = makeKey("uid",String.class);
        TitanGraphIndex index = mgmt.buildIndex("uid",Vertex.class).unique().addKey(uid).buildCompositeIndex();
        mgmt.setConsistency(index, ConsistencyModifier.LOCK);
        mgmt.makePropertyKey("name").dataType(String.class).make();
        mgmt.makePropertyKey("age").dataType(Integer.class).make();
        finishSchema();

        metricsPrefix = "checkPropertyLockingAndIndex";

        TitanTransaction tx = graph.buildTransaction().groupName(metricsPrefix).start();
        TitanVertex v = tx.addVertex("uid", "v1", "age", 25, "name", "john");
        assertEquals(25,v.property("age").value());
        tx.commit();
        verifyStoreMetrics(EDGESTORE_NAME);
        verifyLockingOverwrite(INDEXSTORE_NAME, 1);
        verifyStoreMetrics(METRICS_STOREMANAGER_NAME, ImmutableMap.of(M_MUTATE, 1l));

        resetMetrics();

        tx = graph.buildTransaction().groupName(metricsPrefix).start();
        v = Iterables.getOnlyElement(tx.query().has("uid", Cmp.EQUAL, "v1").vertices());
        assertEquals(25,v.property("age").value());
        tx.commit();
        verifyStoreMetrics(EDGESTORE_NAME, ImmutableMap.of(M_GET_SLICE,1l));
        verifyStoreMetrics(INDEXSTORE_NAME, ImmutableMap.of(M_GET_SLICE,1l));
        verifyStoreMetrics(METRICS_STOREMANAGER_NAME);
    }


    @Test
    public void checkFastPropertyTrue() {
        checkFastProperty(true);
    }

    @Test
    public void checkFastPropertyFalse() {
        checkFastProperty(false);
    }


    public void checkFastProperty(boolean fastProperty) {
        makeKey("uid",String.class);
        makeKey("name", String.class);
        makeKey("age", String.class);
        finishSchema();

        clopen(option(GraphDatabaseConfiguration.PROPERTY_PREFETCHING), fastProperty);
        metricsPrefix = "checkFastProperty"+fastProperty;

        TitanTransaction tx = graph.buildTransaction().groupName(metricsPrefix).start();
        TitanVertex v = tx.addVertex("uid", "v1", "age", 25, "name", "john");
        tx.commit();
        verifyStoreMetrics(EDGESTORE_NAME);
        verifyStoreMetrics(INDEXSTORE_NAME);
        verifyStoreMetrics(METRICS_STOREMANAGER_NAME, ImmutableMap.of(M_MUTATE, 1l));

        tx = graph.buildTransaction().groupName(metricsPrefix).start();
        v = getV(tx, v);
        assertEquals("v1",v.property("uid").value());
        assertEquals("25",v.property("age").value());
        assertEquals("john",v.property("name").value());
        tx.commit();
        if (fastProperty)
            verifyStoreMetrics(EDGESTORE_NAME, ImmutableMap.of(M_GET_SLICE, 2l));
        else
            verifyStoreMetrics(EDGESTORE_NAME, ImmutableMap.of(M_GET_SLICE, 4l));
        verifyStoreMetrics(INDEXSTORE_NAME);
        verifyStoreMetrics(METRICS_STOREMANAGER_NAME, ImmutableMap.of(M_MUTATE, 1l));
    }

    private String metricsPrefix;

    public void verifyStoreMetrics(String storeName) {
        verifyStoreMetrics(storeName, new HashMap<String, Long>(0));
    }

    public void verifyStoreMetrics(String storeName, Map<String, Long> operationCounts) {
        verifyStoreMetrics(storeName, metricsPrefix, operationCounts);
    }

    public void verifyStoreMetrics(String storeName, String prefix, Map<String, Long> operationCounts) {
        for (String operation : OPERATION_NAMES) {
            Long count = operationCounts.get(operation);
            if (count==null) count = 0l;
            assertEquals(Joiner.on(".").join(prefix, storeName, operation, MetricInstrumentedStore.M_CALLS),count.longValue(), metric.getCounter(prefix, storeName, operation, MetricInstrumentedStore.M_CALLS).getCount());
        }
    }

    public void verifyTypeCacheMetrics(int nameMisses, int relationMisses) {
        verifyTypeCacheMetrics(metricsPrefix,nameMisses,relationMisses);
    }

    public void verifyTypeCacheMetrics(String prefix, int nameMisses, int relationMisses) {
//        assertEquals("On type cache name retrievals",nameRetrievals, metric.getCounter(GraphDatabaseConfiguration.METRICS_SYSTEM_PREFIX_DEFAULT, METRICS_NAME, METRICS_TYPENAME, CacheMetricsAction.RETRIEVAL.getName()).getCount());
        assertEquals("On type cache name misses",nameMisses, metric.getCounter(GraphDatabaseConfiguration.METRICS_SYSTEM_PREFIX_DEFAULT, METRICS_NAME, METRICS_TYPENAME, CacheMetricsAction.MISS.getName()).getCount());
        assertTrue(nameMisses <= metric.getCounter(GraphDatabaseConfiguration.METRICS_SYSTEM_PREFIX_DEFAULT, METRICS_NAME, METRICS_TYPENAME, CacheMetricsAction.RETRIEVAL.getName()).getCount());
//        assertEquals("On type cache relation retrievals",relationRetrievals, metric.getCounter(GraphDatabaseConfiguration.METRICS_SYSTEM_PREFIX_DEFAULT, METRICS_NAME, METRICS_RELATIONS, CacheMetricsAction.RETRIEVAL.getName()).getCount());
        assertEquals("On type cache relation misses", relationMisses, metric.getCounter(GraphDatabaseConfiguration.METRICS_SYSTEM_PREFIX_DEFAULT, METRICS_NAME, METRICS_RELATIONS, CacheMetricsAction.MISS.getName()).getCount());
        assertTrue(relationMisses <= metric.getCounter(GraphDatabaseConfiguration.METRICS_SYSTEM_PREFIX_DEFAULT, METRICS_NAME, METRICS_RELATIONS, CacheMetricsAction.RETRIEVAL.getName()).getCount());
    }

//    public void verifyCacheMetrics(String storeName) {
//        verifyCacheMetrics(storeName,0,0);
//    }
//
//    public void verifyCacheMetrics(String storeName, int misses, int retrievals) {
//        verifyCacheMetrics(storeName, metricsPrefix, misses, retrievals);
//    }
//
//    public void verifyCacheMetrics(String storeName, String prefix, int misses, int retrievals) {
//        assertEquals("On "+storeName+"-cache retrievals",retrievals, metric.getCounter(prefix, storeName + Backend.METRICS_CACHE_SUFFIX, CacheMetricsAction.RETRIEVAL.getName()).getCount());
//        assertEquals("On "+storeName+"-cache misses",misses, metric.getCounter(prefix, storeName + Backend.METRICS_CACHE_SUFFIX, CacheMetricsAction.MISS.getName()).getCount());
//    }

    public void printAllMetrics() {
        printAllMetrics(metricsPrefix);
    }

    public void printAllMetrics(String prefix) {
        List<String> storeNames = new ArrayList<>();
        storeNames.add(EDGESTORE_NAME);
        storeNames.add(INDEXSTORE_NAME);
        storeNames.add(ID_STORE_NAME);
        storeNames.add(METRICS_STOREMANAGER_NAME);
        if (storeUsesConsistentKeyLocker()) {
            storeNames.add(EDGESTORE_NAME+LOCK_STORE_SUFFIX);
            storeNames.add(INDEXSTORE_NAME+LOCK_STORE_SUFFIX);
        }

        for (String store : storeNames) {
            System.out.println("######## Store: " + store + " (" + prefix + ")");
            for (String operation : MetricInstrumentedStore.OPERATION_NAMES) {
                System.out.println("-- Operation: " + operation);
                System.out.print("\t"); System.out.println(metric.getCounter(prefix, store, operation, MetricInstrumentedStore.M_CALLS).getCount());
                System.out.print("\t"); System.out.println(metric.getTimer(prefix, store, operation, MetricInstrumentedStore.M_TIME).getMeanRate());
                if (operation==MetricInstrumentedStore.M_GET_SLICE) {
                    System.out.print("\t"); System.out.println(metric.getCounter(prefix, store, operation, MetricInstrumentedStore.M_ENTRIES_COUNT).getCount());
                }
            }
        }
    }

    @Test
    public void testCacheConcurrency() throws InterruptedException {
        metricsPrefix = "tCC";
        Object[] newConfig = {option(GraphDatabaseConfiguration.DB_CACHE),true,
                option(GraphDatabaseConfiguration.DB_CACHE_TIME),0,
                option(GraphDatabaseConfiguration.DB_CACHE_CLEAN_WAIT),0,
                option(GraphDatabaseConfiguration.DB_CACHE_SIZE),0.25,
                option(GraphDatabaseConfiguration.BASIC_METRICS),true,
                option(GraphDatabaseConfiguration.METRICS_MERGE_STORES),false,
                option(GraphDatabaseConfiguration.METRICS_PREFIX),metricsPrefix};
        clopen(newConfig);
        final String prop = "someProp";
        makeKey(prop,Integer.class);
        finishSchema();

        final int numV = 100;
        final long[] vids = new long[numV];
        for (int i=0;i<numV;i++) {
            TitanVertex v = graph.addVertex(prop,0);
            graph.tx().commit();
            vids[i]=getId(v);
        }
        clopen(newConfig);
        resetEdgeCacheCounts();

        final AtomicBoolean[] precommit = new AtomicBoolean[numV];
        final AtomicBoolean[] postcommit = new AtomicBoolean[numV];
        for (int i=0;i<numV;i++) {
            precommit[i]=new AtomicBoolean(false);
            postcommit[i]=new AtomicBoolean(false);
        }
        final AtomicInteger lookups = new AtomicInteger(0);
        final Random random = new Random();
        final int updateSleepTime = 40;
        final int readSleepTime = 2;
        final int numReads = Math.round((numV*updateSleepTime)/readSleepTime*2.0f);

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                int reads = 0;
                while (reads<numReads) {
                    final int pos = random.nextInt(vids.length);
                    long vid = vids[pos];
                    TitanVertex v = getV(graph,vid);
                    assertNotNull(v);
                    boolean postCommit = postcommit[pos].get();
                    Integer value = v.value(prop);
                    lookups.incrementAndGet();
                    assertNotNull("On pos [" + pos + "]", value);
                    if (!precommit[pos].get()) assertEquals(0, value.intValue());
                    else if (postCommit) assertEquals(1, value.intValue());
                    graph.tx().commit();
                    try {
                        Thread.sleep(readSleepTime);
                    } catch (InterruptedException e) {
                        return;
                    }
                    reads++;
                }
            }
        });
        reader.start();

        Thread updater = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i=0;i<numV;i++) {
                    try {
                        TitanVertex v = getV(graph,vids[i]);
                        v.property(VertexProperty.Cardinality.single, prop, 1);
                        precommit[i].set(true);
                        graph.tx().commit();
                        postcommit[i].set(true);
                        Thread.sleep(updateSleepTime);
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Unexpected interruption",e);
                    }
                }
            }
        });
        updater.start();
        updater.join();
//        reader.start();
        reader.join();

        System.out.println("Retrievals: " + getEdgeCacheRetrievals());
        System.out.println("Hits: " + (getEdgeCacheRetrievals()-getEdgeCacheMisses()));
        System.out.println("Misses: " + getEdgeCacheMisses());
        assertEquals(numReads, lookups.get());
        assertEquals(2 * numReads + 1 * numV, getEdgeCacheRetrievals());
        int minMisses = 2*numV;
        assertTrue("Min misses ["+minMisses+"] vs actual ["+getEdgeCacheMisses()+"]",minMisses<=getEdgeCacheMisses() && 4*minMisses>=getEdgeCacheMisses());
    }

    private long getEdgeCacheRetrievals() {
        return metric.getCounter(metricsPrefix, EDGESTORE_NAME + METRICS_CACHE_SUFFIX, CacheMetricsAction.RETRIEVAL.getName()).getCount();
    }

    private long getEdgeCacheMisses() {
        return metric.getCounter(metricsPrefix, EDGESTORE_NAME + METRICS_CACHE_SUFFIX, CacheMetricsAction.MISS.getName()).getCount();
    }

    private void resetEdgeCacheCounts() {
        Counter counter = metric.getCounter(metricsPrefix, EDGESTORE_NAME + METRICS_CACHE_SUFFIX, CacheMetricsAction.RETRIEVAL.getName());
        counter.dec(counter.getCount());
        counter = metric.getCounter(metricsPrefix, EDGESTORE_NAME + METRICS_CACHE_SUFFIX, CacheMetricsAction.MISS.getName());
        counter.dec(counter.getCount());
    }

    private void resetMetrics() {
        MetricManager.INSTANCE.getRegistry().removeMatching(MetricFilter.ALL);
    }

    /**
     * Tests cache performance
     */
    @Test
    public void testCacheSpeedup() {
        Object[] newConfig = {option(GraphDatabaseConfiguration.DB_CACHE),true,
                option(GraphDatabaseConfiguration.DB_CACHE_TIME),0};
        clopen(newConfig);

        int numV = 1000;

        TitanVertex previous = null;
        for (int i=0;i<numV;i++) {
            TitanVertex v = graph.addVertex("name", "v" + i);
            if (previous!=null)
                v.addEdge("knows",previous);
            previous = v;
        }
        graph.tx().commit();
        long vertexId = getId(previous);
        assertCount(numV, graph.query().vertices());

        clopen(newConfig);

        double timecoldglobal=0, timewarmglobal=0,timehotglobal=0;

        int outerRepeat = 20;
        int measurements = 10;
        assertTrue(measurements<outerRepeat);
        int innerRepeat = 2;
        for (int c=0;c<outerRepeat;c++) {

            double timecold = testAllVertices(vertexId,numV);

            double timewarm = 0;
            double timehot = 0;
            for (int i = 0;i<innerRepeat;i++) {
                graph.tx().commit();
                timewarm += testAllVertices(vertexId,numV);
                for (int j=0;j<innerRepeat;j++) {
                    timehot += testAllVertices(vertexId,numV);
                }
            }
            timewarm = timewarm / innerRepeat;
            timehot = timehot / (innerRepeat*innerRepeat);

            if (c>=(outerRepeat-measurements)) {
                timecoldglobal += timecold;
                timewarmglobal += timewarm;
                timehotglobal  += timehot;
            }
//            System.out.println(timecold + "\t" + timewarm + "\t" + timehot);
            clopen(newConfig);
        }
        timecoldglobal = timecoldglobal/measurements;
        timewarmglobal = timewarmglobal/measurements;
        timehotglobal = timehotglobal/measurements;

        System.out.println(round(timecoldglobal) + "\t" + round(timewarmglobal) + "\t" + round(timehotglobal));
        assertTrue(timecoldglobal + " vs " + timewarmglobal, timecoldglobal>timewarmglobal*2);
        //assertTrue(timewarmglobal + " vs " + timehotglobal, timewarmglobal>timehotglobal); Sometimes, this is not true
    }

    private double testAllVertices(long vid, int numV) {
        long start = System.nanoTime();
        TitanVertex v = getV(graph,vid);
        for (int i=1; i<numV; i++) {
            v = getOnlyElement(v.query().direction(Direction.OUT).labels("knows").vertices());
        }
        return ((System.nanoTime()-start)/1000000.0);
    }

}
