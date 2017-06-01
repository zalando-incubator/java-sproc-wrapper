package de.zalando.sprocwrapper.proxy;

import java.lang.reflect.ParameterizedType;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import javax.sql.DataSource;
import javax.annotation.concurrent.Immutable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.RowMapper;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import de.zalando.sprocwrapper.SProcCall.AdvisoryLock;
import de.zalando.sprocwrapper.SProcService.WriteTransaction;
import de.zalando.sprocwrapper.dsprovider.DataSourceProvider;
import de.zalando.sprocwrapper.dsprovider.SameConnectionDatasource;
import de.zalando.sprocwrapper.globalvaluetransformer.GlobalValueTransformerLoader;
import de.zalando.sprocwrapper.proxy.executors.Executor;
import de.zalando.sprocwrapper.proxy.executors.ExecutorWrapper;
import de.zalando.sprocwrapper.proxy.executors.GlobalTransformerExecutorWrapper;
import de.zalando.sprocwrapper.proxy.executors.MultiRowSimpleTypeExecutor;
import de.zalando.sprocwrapper.proxy.executors.MultiRowTypeMapperExecutor;
import de.zalando.sprocwrapper.proxy.executors.SingleRowCustomMapperExecutor;
import de.zalando.sprocwrapper.proxy.executors.SingleRowSimpleTypeExecutor;
import de.zalando.sprocwrapper.proxy.executors.SingleRowTypeMapperExecutor;
import de.zalando.sprocwrapper.proxy.executors.ValidationExecutorWrapper;
import de.zalando.sprocwrapper.sharding.ShardedDataAccessException;
import de.zalando.sprocwrapper.sharding.ShardedObject;
import de.zalando.sprocwrapper.sharding.VirtualShardKeyStrategy;

import de.zalando.typemapper.core.ValueTransformer;

/**
 * @author  jmussler
 */
@Immutable
class StoredProcedure {

    private static final int TRUNCATE_DEBUG_PARAMS_MAX_LENGTH = 1024;
    private static final String TRUNCATE_DEBUG_PARAMS_ELLIPSIS = " ...";

    private static final Logger LOG = LoggerFactory.getLogger(StoredProcedure.class);

    private final String name;
    private final List<StoredProcedureParameter> params;
    private final int[] types;

    private final String sqlParameterList;
    private final String query;

    private final Class<?> returnType;

    private final VirtualShardKeyStrategy shardStrategy;
    private final List<ShardKeyParameter> shardKeyParameters;
    private final boolean autoPartition;

    // whether the result type is a collection (List)
    private final boolean collectionResult;
    private final boolean runOnAllShards;
    private final boolean searchShards;
    private final boolean parallel;
    private final boolean readOnly;
    private final WriteTransaction writeTransaction;

    private final Executor executor;

    private static final Executor MULTI_ROW_SIMPLE_TYPE_EXECUTOR = new MultiRowSimpleTypeExecutor();
    private static final Executor MULTI_ROW_TYPE_MAPPER_EXECUTOR = new MultiRowTypeMapperExecutor();
    private static final Executor SINGLE_ROW_SIMPLE_TYPE_EXECUTOR = new SingleRowSimpleTypeExecutor();
    private static final Executor SINGLE_ROW_TYPE_MAPPER_EXECUTOR = new SingleRowTypeMapperExecutor();

    private static final ExecutorService PARALLEL_THREAD_POOL = Executors.newCachedThreadPool();

    private final long timeout;
    private final AdvisoryLock adivsoryLock;

    public StoredProcedure(final String name, final String query, final List<StoredProcedureParameter> params, final java.lang.reflect.Type genericType,
                           final VirtualShardKeyStrategy sStrategy, final List<ShardKeyParameter> shardKeyParameters, final boolean runOnAllShards, final boolean searchShards,
                           final boolean parallel, final RowMapper<?> resultMapper, final long timeout,
                           final AdvisoryLock advisoryLock, final boolean useValidation, final boolean readOnly,
                           final WriteTransaction writeTransaction) throws InstantiationException, IllegalAccessException {
        this.name = name;
        this.params = new ArrayList<>(params);
        this.types = createTypes(params);

        this.sqlParameterList = createSqlParameterList(params);
        this.query = (query != null ? query : defaultQuery(name, sqlParameterList));

        this.shardStrategy = sStrategy;
        this.shardKeyParameters = new ArrayList<>(shardKeyParameters);
        this.autoPartition = isAutoPartition(shardKeyParameters);

        this.runOnAllShards = runOnAllShards;
        this.searchShards = searchShards;
        this.parallel = parallel;
        this.readOnly = readOnly;
        this.writeTransaction = writeTransaction;

        this.adivsoryLock = advisoryLock;
        this.timeout = timeout;

        ValueTransformer<?, ?> valueTransformerForClass = null;
        Executor exec;
        if (genericType instanceof ParameterizedType) {
            final ParameterizedType pType = (ParameterizedType) genericType;

            if (java.util.List.class.isAssignableFrom((Class<?>) pType.getRawType())
                    && pType.getActualTypeArguments().length > 0) {
                returnType = (Class<?>) pType.getActualTypeArguments()[0];

                // check if we have a value transformer (and initialize the registry):
                valueTransformerForClass = GlobalValueTransformerLoader.getValueTransformerForClass(returnType);

                if (valueTransformerForClass != null
                        || SingleRowSimpleTypeExecutor.SIMPLE_TYPES.containsKey(returnType)) {
                    exec = MULTI_ROW_SIMPLE_TYPE_EXECUTOR;
                } else {
                    exec = MULTI_ROW_TYPE_MAPPER_EXECUTOR;
                }

                collectionResult = true;
            } else {
                collectionResult = false;
                exec = SINGLE_ROW_TYPE_MAPPER_EXECUTOR;
                returnType = (Class<?>) pType.getRawType();
            }

        } else {
            collectionResult = false;
            returnType = (Class<?>) genericType;

            // check if we have a value transformer (and initialize the registry):
            valueTransformerForClass = GlobalValueTransformerLoader.getValueTransformerForClass(returnType);

            if (valueTransformerForClass != null || SingleRowSimpleTypeExecutor.SIMPLE_TYPES.containsKey(returnType)) {
                exec = SINGLE_ROW_SIMPLE_TYPE_EXECUTOR;
            } else {
                if (resultMapper != null) {
                    exec = new SingleRowCustomMapperExecutor(resultMapper);
                } else {
                    exec = SINGLE_ROW_TYPE_MAPPER_EXECUTOR;
                }
            }
        }

        if (this.timeout > 0 || (this.adivsoryLock != null && !(this.adivsoryLock.equals(AdvisoryLock.NoLock.LOCK)))) {

            // Wrapper provides locking and changing of session settings functionality
            exec = new ExecutorWrapper(exec, this.timeout, this.adivsoryLock);
        }

        if (useValidation) {
            exec = new ValidationExecutorWrapper(exec);
        }

        if (valueTransformerForClass != null) {

            // we need to transform the return value by the global value transformer.
            // add the transformation to the as a transformerExecutor
            exec = new GlobalTransformerExecutorWrapper(exec);
        }
        this.executor = exec;
    }

    public String getName() {
        return name;
    }

    private Object[] getParams(final Object[] origParams, final Connection connection) {
        final Object[] ps = new Object[params.size()];

        int i = 0;
        for (final StoredProcedureParameter p : params) {
            try {
                ps[i] = p.mapParam(origParams[p.getJavaPos()], connection);
            } catch (final Exception e) {
                final String errorMessage = "Could not map input parameter for stored procedure " + name + " of type "
                        + p.getType() + " at position " + p.getJavaPos() + ": "
                        + (p.isSensitive() ? "<SENSITIVE>" : origParams[p.getJavaPos()]);
                LOG.error(errorMessage, e);
                throw new IllegalArgumentException(errorMessage, e);
            }

            i++;
        }

        return ps;
    }

    private static int[] createTypes(final List<StoredProcedureParameter> params) {
        int[] types = new int[params.size()];
        int i = 0;
        for (final StoredProcedureParameter p : params) {
            types[i++] = p.getType();
        }
        return types;
    }

    private int getShardId(final Object[] objs) {
        if (shardKeyParameters.isEmpty()) {
            return shardStrategy.getShardId(null);
        }

        final Object[] keys = new Object[shardKeyParameters.size()];
        int i = 0;
        Object obj;
        for (final ShardKeyParameter p : shardKeyParameters) {
            obj = objs[p.getPos()];
            if (obj instanceof ShardedObject) {
                obj = ((ShardedObject) obj).getShardKey();
            }

            keys[i] = obj;
            i++;
        }

        return shardStrategy.getShardId(keys);
    }

    public String getSqlParameterList() {
        return sqlParameterList;
    }

    private static String createSqlParameterList(final List<StoredProcedureParameter> params) {
        String s = "";
        boolean first = true;
        for (int i = 1; i <= params.size(); ++i) {
            if (!first) {
                s += ",";
            }

            first = false;

            s += "?";
        }

        return s;
    }

    private static String defaultQuery(final String name, final String sqlParameterList) {
        return "SELECT * FROM " + name + " ( " + sqlParameterList + " )";
    }

    private static boolean isAutoPartition(final List<ShardKeyParameter> shardKeyParameters) {
        for (ShardKeyParameter p : shardKeyParameters) {
            if (List.class.isAssignableFrom(p.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * build execution string like create_or_update_multiple_objects({"(a,b)","(c,d)" }).
     *
     * @param   args
     *
     * @return
     */
    private String getDebugLog(final Object[] args) {
        final StringBuilder sb = new StringBuilder(name);
        sb.append('(');

        int i = 0;
        for (final Object param : args) {
            if (i > 0) {
                sb.append(',');
            }

            if (param == null) {
                sb.append("NULL");
            } else if (params.get(i).isSensitive()) {
                sb.append("<SENSITIVE>");
            } else {
                sb.append(param);
            }

            i++;
            if (sb.length() > TRUNCATE_DEBUG_PARAMS_MAX_LENGTH) {
                break;
            }
        }

        if (sb.length() > TRUNCATE_DEBUG_PARAMS_MAX_LENGTH) {

            // Truncate params for debug output
            return sb.substring(0, TRUNCATE_DEBUG_PARAMS_MAX_LENGTH) + TRUNCATE_DEBUG_PARAMS_ELLIPSIS + ")";
        } else {
            sb.append(')');
            return sb.toString();
        }
    }

    /**
     * split arguments by shard.
     *
     * @param   dataSourceProvider
     * @param   args                the original argument list
     *
     * @return  map of virtual shard ID to argument list (TreeMap with ordered keys: sorted by shard ID)
     */
    @SuppressWarnings("unchecked")
    private Map<Integer, Object[]> partitionArguments(final DataSourceProvider dataSourceProvider,
                                                      final Object[] args) {

        // use TreeMap here to maintain ordering by shard ID
        final Map<Integer, Object[]> argumentsByShardId = Maps.newTreeMap();

        // we need to partition by datasource instead of virtual shard ID (different virtual shard IDs are mapped to
        // the same datasource e.g. by VirtualShardMd5Strategy)
        final Map<DataSource, Integer> shardIdByDataSource = Maps.newHashMap();

        // TODO: currently only implemented for single shardKey argument as first argument!
        final List<Object> originalArgument = (List<Object>) args[0];
        if (originalArgument == null || originalArgument.isEmpty()) {
            throw new IllegalArgumentException("ShardKey (first argument) of sproc '" + name + "' not defined");
        }

        List<Object> partitionedArgument = null;
        Object[] partitionedArguments = null;
        int shardId;
        Integer existingShardId;
        DataSource dataSource;
        for (final Object key : originalArgument) {
            shardId = getShardId(new Object[] {key});
            dataSource = dataSourceProvider.getDataSource(shardId);
            existingShardId = shardIdByDataSource.get(dataSource);
            if (existingShardId != null) {

                // we already saw the same datasource => use the virtual shard ID of the first argument with the same
                // datasource
                shardId = existingShardId;
            } else {
                shardIdByDataSource.put(dataSource, shardId);
            }

            partitionedArguments = argumentsByShardId.get(shardId);
            if (partitionedArguments == null) {

                partitionedArgument = Lists.newArrayList();
                partitionedArguments = new Object[args.length];
                partitionedArguments[0] = partitionedArgument;
                if (args.length > 1) {
                    System.arraycopy(args, 1, partitionedArguments, 1, args.length - 1);
                }

                argumentsByShardId.put(shardId, partitionedArguments);
            } else {
                partitionedArgument = (List<Object>) partitionedArguments[0];
            }

            partitionedArgument.add(key);

        }

        return argumentsByShardId;
    }

    public Object execute(final DataSourceProvider dp, final InvocationContext invocation) {

        List<Integer> shardIds = null;
        Map<Integer, Object[]> partitionedArguments = null;
        if (runOnAllShards || searchShards) {

            shardIds = dp.getDistinctShardIds();
        } else {
            if (autoPartition) {
                partitionedArguments = partitionArguments(dp, invocation.getArgs());
                shardIds = Lists.newArrayList(partitionedArguments.keySet());
            } else {
                shardIds = Lists.newArrayList(getShardId(invocation.getArgs()));
            }
        }

        if (partitionedArguments == null) {
            partitionedArguments = Maps.newHashMap();
            for (final int shardId : shardIds) {
                partitionedArguments.put(shardId, invocation.getArgs());
            }
        }

        final DataSource firstDs = dp.getDataSource(shardIds.get(0));
        Connection connection = null;
        try {
            connection = firstDs.getConnection();

        } catch (final SQLException e) {
            throw new CannotGetJdbcConnectionException("Failed to acquire connection for virtual shard "
                    + shardIds.get(0) + " for " + name, e);
        }

        final List<Object[]> paramValues = Lists.newArrayList();
        try {
            for (final int shardId : shardIds) {
                paramValues.add(getParams(partitionedArguments.get(shardId), connection));
            }

        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (final Throwable t) {
                    LOG.warn("Could not release connection", t);
                }
            }
        }

        if (shardIds.size() == 1 && !autoPartition) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(getDebugLog(paramValues.get(0)));
            }

            // most common case: only one shard and no argument partitioning
            return execute(firstDs, paramValues.get(0), invocation);
        } else {
            Map<Integer, SameConnectionDatasource> transactionalDatasources = null;
            try {

                // we may need to start a transaction context
                transactionalDatasources = startTransaction(dp, shardIds);

                final List<?> results = Lists.newArrayList();
                Object sprocResult = null;
                final long start = System.currentTimeMillis();
                if (parallel) {
                    sprocResult = executeInParallel(dp, invocation, shardIds, paramValues, transactionalDatasources,
                            results, sprocResult);
                } else {
                    sprocResult = executeSequential(dp, invocation, shardIds, paramValues, transactionalDatasources,
                            results, sprocResult);
                }

                if (LOG.isTraceEnabled()) {
                    LOG.trace("[{}] execution of [{}] on [{}] shards took [{}] ms",
                            new Object[] {
                                    parallel ? "parallel" : "serial", name, shardIds.size(), System.currentTimeMillis() - start
                            });
                }

                // no error - we may need to commit
                commitTransaction(transactionalDatasources);

                if (collectionResult) {
                    return results;
                } else {

                    // return last result
                    return sprocResult;
                }

            } catch (final RuntimeException runtimeException) {

                LOG.trace("[{}] execution of [{}] on [{}] shards aborted by runtime exception [{}]",
                        new Object[] {
                                parallel ? "parallel" : "serial", name, shardIds.size(), runtimeException.getMessage(),
                                runtimeException
                        });

                // error occured, we may need to rollback all transactions.
                rollbackTransaction(transactionalDatasources);

                // re-throw
                throw runtimeException;
            } catch (final Throwable throwable) {

                LOG.trace("[{}] execution of [{}] on [{}] shards aborted by throwable exception [{}]",
                        new Object[] {
                                parallel ? "parallel" : "serial", name, shardIds.size(), throwable.getMessage(), throwable
                        });

                // error occured, we may need to rollback all transactions.
                rollbackTransaction(transactionalDatasources);

                // throw runtime:
                throw new RuntimeException(throwable);
            }

        }

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Object executeSequential(final DataSourceProvider dp, final InvocationContext invocation,
                                     final List<Integer> shardIds, final List<Object[]> paramValues,
                                     final Map<Integer, SameConnectionDatasource> transactionalDatasources, final List<?> results,
                                     Object sprocResult) {
        DataSource shardDs;
        int i = 0;
        final List<String> exceptions = Lists.newArrayList();
        final ImmutableMap.Builder<Integer, Throwable> causes = ImmutableMap.builder();
        for (final int shardId : shardIds) {
            shardDs = getShardDs(dp, transactionalDatasources, shardId);
            if (LOG.isDebugEnabled()) {
                LOG.debug(getDebugLog(paramValues.get(i)));
            }

            sprocResult = null;
            try {
                sprocResult = execute(shardDs, paramValues.get(i), invocation);
            } catch (final Exception e) {

                // remember all exceptions and go on
                exceptions.add("shardId: " + shardId + ", message: " + e.getMessage() + ", query: " + query);
                causes.put(shardId, e);
            }

            if (addResultsBreakWhenSharded(results, sprocResult)) {
                break;
            }

            i++;
        }

        if (!exceptions.isEmpty()) {
            throw new ShardedDataAccessException("Got exception(s) while executing sproc on shards: "
                    + Joiner.on(", ").join(exceptions), causes.build());
        }

        return sprocResult;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Object executeInParallel(final DataSourceProvider dp, final InvocationContext invocation,
                                     final List<Integer> shardIds, final List<Object[]> paramValues,
                                     final Map<Integer, SameConnectionDatasource> transactionalDatasources, final List<?> results,
                                     Object sprocResult) {

        final Map<Integer, FutureTask<Object>> tasks = Maps.newHashMapWithExpectedSize(shardIds.size());
        int i = 0;

        // Pre-evaluate values before concurrent run.
        getQuery();
        getTypes();

        for (final int shardId : shardIds) {
            final DataSource shardDs = getShardDs(dp, transactionalDatasources, shardId);
            if (LOG.isDebugEnabled()) {
                LOG.debug(getDebugLog(paramValues.get(i)));
            }

            final FutureTask<Object> task = new FutureTask<>(with(shardDs, paramValues.get(i), invocation));
            tasks.put(shardId, task);
            PARALLEL_THREAD_POOL.execute(task);
            i++;
        }

        final List<String> exceptions = Lists.newArrayList();
        final ImmutableMap.Builder<Integer, Throwable> causes = ImmutableMap.builder();

        for (final Entry<Integer, FutureTask<Object>> taskToFinish : tasks.entrySet()) {
            try {
                sprocResult = taskToFinish.getValue().get();
            } catch (final InterruptedException ex) {

                // remember all exceptions and go on
                exceptions.add("got sharding execution exception: " + ex.getMessage() + ", query: " + query);
                causes.put(taskToFinish.getKey(), ex);
            } catch (final ExecutionException ex) {

                // remember all exceptions and go on
                exceptions.add("got sharding execution exception: " + ex.getCause().getMessage() + ", query: "
                        + query);
                causes.put(taskToFinish.getKey(), ex.getCause());
            }

            if (addResultsBreakWhenSharded(results, sprocResult)) {
                break;
            }
        }

        if (!exceptions.isEmpty()) {
            throw new ShardedDataAccessException("Got exception(s) while executing sproc on shards: "
                    + Joiner.on(", ").join(exceptions), causes.build());
        }

        return sprocResult;
    }

    private Callable<Object> with(final DataSource shardDs, final Object[] params, final InvocationContext invocation) {
        return new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return StoredProcedure.this.execute(shardDs, params, invocation);
            }
        };
    }

    private Object execute(final DataSource shardDs, final Object[] params, final InvocationContext invocation) {
        return executor.executeSProc(shardDs, query, params, types, invocation, returnType);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean addResultsBreakWhenSharded(final Collection results, final Object sprocResult) {
        boolean breakSearch = false;

        if (collectionResult && sprocResult != null && !((Collection) sprocResult).isEmpty()) {

            // Result is a non-empty collection
            results.addAll((Collection) sprocResult);

            // Break if shardedSearch
            breakSearch = searchShards;
        } else if (!collectionResult && sprocResult != null && searchShards) {

            // Result is non-null, but not a collection
            // Break if shardedSearch
            breakSearch = true;
        }

        return breakSearch;
    }

    private DataSource getShardDs(final DataSourceProvider dp,
                                  final Map<Integer, SameConnectionDatasource> transactionIds, final int shardId) {
        if (transactionIds.isEmpty()) {
            return dp.getDataSource(shardId);
        }

        return transactionIds.get(shardId);
    }

    private Map<Integer, SameConnectionDatasource> startTransaction(final DataSourceProvider dp,
                                                                    final List<Integer> shardIds) throws SQLException {
        final Map<Integer, SameConnectionDatasource> ret = Maps.newHashMap();

        if (readOnly == false && writeTransaction != WriteTransaction.NONE) {
            for (final int shardId : shardIds) {
                final DataSource shardDs = dp.getDataSource(shardId);

                // we need to pin the calls to a single connection
                final SameConnectionDatasource sameConnDs = new SameConnectionDatasource(shardDs.getConnection());
                ret.put(shardId, sameConnDs);

                LOG.trace("startTransaction on shard [{}]", shardId);

                final Statement st = sameConnDs.getConnection().createStatement();
                st.execute("BEGIN");
                st.close();
            }
        }

        return ret;
    }

    private void commitTransaction(final Map<Integer, SameConnectionDatasource> datasources) {
        if (readOnly == false && writeTransaction != WriteTransaction.NONE) {
            if (writeTransaction == WriteTransaction.ONE_PHASE) {
                for (final Entry<Integer, SameConnectionDatasource> shardEntry : datasources.entrySet()) {
                    try {
                        LOG.trace("commitTransaction on shard [{}]", shardEntry.getKey());

                        final DataSource shardDs = shardEntry.getValue();
                        final Statement st = shardDs.getConnection().createStatement();
                        st.execute("COMMIT");
                        st.close();

                        shardEntry.getValue().close();
                    } catch (final Exception e) {

                        // do our best. we cannot rollback at this point.
                        // store other shards as much as possible.
                        LOG.error(
                                "ERROR: could not commitTransaction on shard [{}] - this will produce inconsistent data.",
                                shardEntry.getKey(), e);
                    }
                }
            } else if (writeTransaction == WriteTransaction.TWO_PHASE) {

                boolean commitFailed = false;

                final String transactionId = "sprocwrapper_" + UUID.randomUUID();
                final String prepareTransactionStatement = "PREPARE TRANSACTION '" + transactionId + "'";

                for (final Entry<Integer, SameConnectionDatasource> shardEntry : datasources.entrySet()) {
                    try {
                        LOG.trace("prepare transaction on shard [{}]", shardEntry.getKey());

                        final DataSource shardDs = shardEntry.getValue();
                        final Statement st = shardDs.getConnection().createStatement();

                        st.execute(prepareTransactionStatement);
                        st.close();

                    } catch (final Exception e) {
                        commitFailed = true;

                        // log, but go on, prepare other transactions - but they will be removed as well.
                        LOG.debug("prepare transaction [{}] on shard [{}] failed!",
                                new Object[] {transactionId, shardEntry.getKey(), e});
                    }
                }

                if (commitFailed) {
                    rollbackPrepared(datasources, transactionId);
                } else {
                    final String commitStatement = "COMMIT PREPARED '" + transactionId + "'";

                    for (final Entry<Integer, SameConnectionDatasource> shardEntry : datasources.entrySet()) {
                        try {
                            LOG.trace("commit prepared transaction [{}] on shard [{}]", transactionId,
                                    shardEntry.getKey());

                            final DataSource shardDs = shardEntry.getValue();
                            final Statement st = shardDs.getConnection().createStatement();
                            st.execute(commitStatement);
                            st.close();

                            shardEntry.getValue().close();
                        } catch (final Exception e) {
                            commitFailed = true;

                            // do our best. we cannot rollback at this point.
                            // store other shards as much as possible.
                            // the not yet stored transactions are visible in postgres prepared transactions
                            // a nagios check should detect them so that we can handle any errors
                            // that may be produced at this point.
                            LOG.error(
                                    "FAILED: could not commit prepared transaction [{}] on shard [{}] - this will produce inconsistent data.",
                                    new Object[] {transactionId, shardEntry.getKey(), e});
                        }
                    }

                    // for all failed commits:
                    if (commitFailed) {
                        rollbackPrepared(datasources, transactionId);
                    }
                }
            } else {
                throw new IllegalArgumentException("Unknown writeTransaction state: " + writeTransaction);
            }
        }
    }

    private void rollbackPrepared(final Map<Integer, SameConnectionDatasource> datasources,
                                  final String transactionId) {

        final String rollbackQuery = "ROLLBACK PREPARED '" + transactionId + "'";

        for (final Entry<Integer, SameConnectionDatasource> shardEntry : datasources.entrySet()) {
            try {
                LOG.error("rollback prepared transaction [{}] on shard [{}]", transactionId, shardEntry.getKey());

                final DataSource shardDs = shardEntry.getValue();
                final Statement st = shardDs.getConnection().createStatement();
                st.execute(rollbackQuery);
                st.close();

                shardEntry.getValue().close();
            } catch (final Exception e) {
                LOG.error(
                        "FAILED: could not rollback prepared transaction [{}] on shard [{}] - this will produce inconsistent data.",
                        new Object[] {transactionId, shardEntry.getKey(), e});
            }
        }
    }

    private void rollbackTransaction(final Map<Integer, SameConnectionDatasource> datasources) {
        if (readOnly == false && writeTransaction != WriteTransaction.NONE) {
            for (final Entry<Integer, SameConnectionDatasource> shardEntry : datasources.entrySet()) {
                try {
                    LOG.trace("rollbackTransaction on shard [{}]", shardEntry.getKey());

                    final DataSource shardDs = shardEntry.getValue();
                    final Statement st = shardDs.getConnection().createStatement();
                    st.execute("ROLLBACK");
                    st.close();

                    shardEntry.getValue().close();
                } catch (final Exception e) {
                    LOG.error("ERROR: could not rollback on shard [{}] - this will produce inconsistent data.",
                            shardEntry.getKey());
                }
            }
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(name);
        sb.append('(');

        boolean f = true;
        for (final StoredProcedureParameter p : params) {
            if (!f) {
                sb.append(',');
            }

            f = false;
            sb.append(p.getType());
            if (!"".equals(p.getTypeName())) {
                sb.append("=>").append(p.getTypeName());
            }
        }

        sb.append(')');
        return sb.toString();
    }
}
