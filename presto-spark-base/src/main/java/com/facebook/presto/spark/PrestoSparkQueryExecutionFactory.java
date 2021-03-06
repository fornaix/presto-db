/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spark;

import com.facebook.airlift.json.JsonCodec;
import com.facebook.airlift.log.Logger;
import com.facebook.presto.Session;
import com.facebook.presto.block.BlockEncodingManager;
import com.facebook.presto.common.Page;
import com.facebook.presto.common.block.BlockBuilder;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.event.QueryMonitor;
import com.facebook.presto.execution.QueryIdGenerator;
import com.facebook.presto.execution.QueryInfo;
import com.facebook.presto.execution.QueryPreparer;
import com.facebook.presto.execution.QueryPreparer.PreparedQuery;
import com.facebook.presto.execution.scheduler.ExecutionWriterTarget;
import com.facebook.presto.execution.scheduler.StreamingPlanSection;
import com.facebook.presto.execution.scheduler.StreamingSubPlan;
import com.facebook.presto.execution.scheduler.TableWriteInfo;
import com.facebook.presto.execution.warnings.WarningCollector;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.operator.TaskStats;
import com.facebook.presto.security.AccessControl;
import com.facebook.presto.server.QuerySessionSupplier;
import com.facebook.presto.server.SessionContext;
import com.facebook.presto.spark.classloader_interface.IPrestoSparkQueryExecution;
import com.facebook.presto.spark.classloader_interface.IPrestoSparkQueryExecutionFactory;
import com.facebook.presto.spark.classloader_interface.PrestoSparkRow;
import com.facebook.presto.spark.classloader_interface.PrestoSparkSerializedPage;
import com.facebook.presto.spark.classloader_interface.PrestoSparkSession;
import com.facebook.presto.spark.classloader_interface.PrestoSparkTaskExecutorFactoryProvider;
import com.facebook.presto.spark.classloader_interface.PrestoSparkTaskInputs;
import com.facebook.presto.spark.classloader_interface.SerializedPrestoSparkTaskDescriptor;
import com.facebook.presto.spark.classloader_interface.SerializedTaskStats;
import com.facebook.presto.spark.planner.PrestoSparkPlanFragmenter;
import com.facebook.presto.spark.planner.PrestoSparkQueryPlanner;
import com.facebook.presto.spark.planner.PrestoSparkQueryPlanner.PlanAndUpdateType;
import com.facebook.presto.spark.planner.PrestoSparkRddFactory;
import com.facebook.presto.spark.util.PrestoSparkUtils;
import com.facebook.presto.spi.ConnectorId;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.connector.ConnectorCapabilities;
import com.facebook.presto.spi.page.PagesSerde;
import com.facebook.presto.sql.planner.PlanFragment;
import com.facebook.presto.sql.planner.SubPlan;
import com.facebook.presto.sql.planner.plan.PlanFragmentId;
import com.facebook.presto.transaction.TransactionId;
import com.facebook.presto.transaction.TransactionInfo;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.BasicSliceInput;
import io.airlift.slice.SliceInput;
import io.airlift.slice.Slices;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.util.CollectionAccumulator;
import scala.Some;
import scala.Tuple2;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.facebook.airlift.concurrent.MoreFutures.getFutureValue;
import static com.facebook.presto.execution.scheduler.StreamingPlanSection.extractStreamingSections;
import static com.facebook.presto.execution.scheduler.TableWriteInfo.createTableWriteInfo;
import static com.facebook.presto.spark.util.PrestoSparkUtils.transformRowsToPages;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.spi.connector.ConnectorCapabilities.SUPPORTS_PAGE_SINK_COMMIT;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.COORDINATOR_DISTRIBUTION;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.FIXED_BROADCAST_DISTRIBUTION;
import static com.facebook.presto.sql.planner.planPrinter.PlanPrinter.textDistributedPlan;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Iterators.transform;
import static java.util.Objects.requireNonNull;

public class PrestoSparkQueryExecutionFactory
        implements IPrestoSparkQueryExecutionFactory
{
    private static final Logger log = Logger.get(PrestoSparkQueryExecutionFactory.class);

    private final QueryIdGenerator queryIdGenerator;
    private final QuerySessionSupplier sessionSupplier;
    private final QueryPreparer queryPreparer;
    private final PrestoSparkQueryPlanner queryPlanner;
    private final PrestoSparkPlanFragmenter planFragmenter;
    private final PrestoSparkRddFactory rddFactory;
    private final QueryMonitor queryMonitor;
    private final JsonCodec<TaskStats> taskStatsJsonCodec;
    private final JsonCodec<PrestoSparkTaskDescriptor> sparkTaskDescriptorJsonCodec;
    private final TransactionManager transactionManager;
    private final AccessControl accessControl;
    private final Metadata metadata;
    private final BlockEncodingManager blockEncodingManager;

    private final Set<PrestoSparkCredentialsProvider> credentialsProviders;
    private final Set<PrestoSparkAuthenticatorProvider> authenticatorProviders;

    @Inject
    public PrestoSparkQueryExecutionFactory(
            QueryIdGenerator queryIdGenerator,
            QuerySessionSupplier sessionSupplier,
            QueryPreparer queryPreparer,
            PrestoSparkQueryPlanner queryPlanner,
            PrestoSparkPlanFragmenter planFragmenter,
            PrestoSparkRddFactory rddFactory,
            QueryMonitor queryMonitor,
            JsonCodec<TaskStats> taskStatsJsonCodec,
            JsonCodec<PrestoSparkTaskDescriptor> sparkTaskDescriptorJsonCodec,
            TransactionManager transactionManager,
            AccessControl accessControl,
            Metadata metadata,
            BlockEncodingManager blockEncodingManager,
            Set<PrestoSparkCredentialsProvider> credentialsProviders,
            Set<PrestoSparkAuthenticatorProvider> authenticatorProviders)
    {
        this.queryIdGenerator = requireNonNull(queryIdGenerator, "queryIdGenerator is null");
        this.sessionSupplier = requireNonNull(sessionSupplier, "sessionSupplier is null");
        this.queryPreparer = requireNonNull(queryPreparer, "queryPreparer is null");
        this.queryPlanner = requireNonNull(queryPlanner, "queryPlanner is null");
        this.planFragmenter = requireNonNull(planFragmenter, "planFragmenter is null");
        this.rddFactory = requireNonNull(rddFactory, "rddFactory is null");
        this.queryMonitor = requireNonNull(queryMonitor, "queryMonitor is null");
        this.taskStatsJsonCodec = requireNonNull(taskStatsJsonCodec, "taskStatsJsonCodec is null");
        this.sparkTaskDescriptorJsonCodec = requireNonNull(sparkTaskDescriptorJsonCodec, "sparkTaskDescriptorJsonCodec is null");
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.blockEncodingManager = requireNonNull(blockEncodingManager, "blockEncodingManager is null");
        this.credentialsProviders = ImmutableSet.copyOf(requireNonNull(credentialsProviders, "credentialsProviders is null"));
        this.authenticatorProviders = ImmutableSet.copyOf(requireNonNull(authenticatorProviders, "authenticatorProviders is null"));
    }

    @Override
    public IPrestoSparkQueryExecution create(
            SparkContext sparkContext,
            PrestoSparkSession prestoSparkSession,
            String sql,
            PrestoSparkTaskExecutorFactoryProvider executorFactoryProvider)
    {
        QueryId queryId = queryIdGenerator.createNextQueryId();
        SessionContext sessionContext = PrestoSparkSessionContext.createFromSessionInfo(
                prestoSparkSession,
                credentialsProviders,
                authenticatorProviders);

        // TODO: implement warning collection
        WarningCollector warningCollector = WarningCollector.NOOP;

        // TODO: implement query monitor
        // queryMonitor.queryCreatedEvent();

        TransactionId transactionId = transactionManager.beginTransaction(true);
        Session session = sessionSupplier.createSession(queryId, sessionContext)
                .beginTransactionId(transactionId, transactionManager, accessControl);

        try {
            PreparedQuery preparedQuery = queryPreparer.prepareQuery(session, sql, warningCollector);
            PlanAndUpdateType planAndUpdateType = queryPlanner.createQueryPlan(session, preparedQuery, warningCollector);
            SubPlan fragmentedPlan = planFragmenter.fragmentQueryPlan(session, planAndUpdateType.getPlan(), warningCollector);
            log.info(textDistributedPlan(fragmentedPlan, metadata.getFunctionManager(), session, true));
            TableWriteInfo tableWriteInfo = getTableWriteInfo(session, fragmentedPlan);

            JavaSparkContext javaSparkContext = new JavaSparkContext(sparkContext);
            CollectionAccumulator<SerializedTaskStats> taskStatsCollector = new CollectionAccumulator<>();
            taskStatsCollector.register(sparkContext, new Some<>("taskStatsCollector"), false);

            return new PrestoSparkQueryExecution(
                    javaSparkContext,
                    session,
                    queryMonitor,
                    taskStatsCollector,
                    executorFactoryProvider,
                    fragmentedPlan,
                    planAndUpdateType.getUpdateType(),
                    taskStatsJsonCodec,
                    sparkTaskDescriptorJsonCodec,
                    rddFactory,
                    tableWriteInfo,
                    transactionManager,
                    new PagesSerde(blockEncodingManager, Optional.empty(), Optional.empty(), Optional.empty()));
        }
        catch (RuntimeException executionFailure) {
            try {
                rollback(session, transactionManager);
            }
            catch (RuntimeException rollbackFailure) {
                if (executionFailure != rollbackFailure) {
                    executionFailure.addSuppressed(rollbackFailure);
                }
            }
            try {
                // TODO: implement query monitor
                // queryMonitor.queryImmediateFailureEvent();
            }
            catch (RuntimeException eventFailure) {
                if (executionFailure != eventFailure) {
                    executionFailure.addSuppressed(eventFailure);
                }
            }
            throw executionFailure;
        }
    }

    private TableWriteInfo getTableWriteInfo(Session session, SubPlan plan)
    {
        StreamingPlanSection streamingPlanSection = extractStreamingSections(plan);
        StreamingSubPlan streamingSubPlan = streamingPlanSection.getPlan();
        TableWriteInfo tableWriteInfo = createTableWriteInfo(streamingSubPlan, metadata, session);
        if (tableWriteInfo.getWriterTarget().isPresent()) {
            checkPageSinkCommitIsSupported(session, tableWriteInfo.getWriterTarget().get());
        }
        return tableWriteInfo;
    }

    private void checkPageSinkCommitIsSupported(Session session, ExecutionWriterTarget writerTarget)
    {
        ConnectorId connectorId;
        if (writerTarget instanceof ExecutionWriterTarget.DeleteHandle) {
            throw new PrestoException(NOT_SUPPORTED, "delete queries are not supported by presto on spark");
        }
        else if (writerTarget instanceof ExecutionWriterTarget.CreateHandle) {
            connectorId = ((ExecutionWriterTarget.CreateHandle) writerTarget).getHandle().getConnectorId();
        }
        else if (writerTarget instanceof ExecutionWriterTarget.InsertHandle) {
            connectorId = ((ExecutionWriterTarget.InsertHandle) writerTarget).getHandle().getConnectorId();
        }
        else {
            throw new IllegalArgumentException("unexpected writer target type: " + writerTarget.getClass());
        }
        verify(connectorId != null, "connectorId is null");
        Set<ConnectorCapabilities> connectorCapabilities = metadata.getConnectorCapabilities(session, connectorId);
        if (!connectorCapabilities.contains(SUPPORTS_PAGE_SINK_COMMIT)) {
            throw new PrestoException(NOT_SUPPORTED, "catalog does not support page sink commit: " + connectorId);
        }
    }

    private static void commit(Session session, TransactionManager transactionManager)
    {
        getFutureValue(transactionManager.asyncCommit(getTransactionInfo(session, transactionManager).getTransactionId()));
    }

    private static void rollback(Session session, TransactionManager transactionManager)
    {
        getFutureValue(transactionManager.asyncAbort(getTransactionInfo(session, transactionManager).getTransactionId()));
    }

    private static TransactionInfo getTransactionInfo(Session session, TransactionManager transactionManager)
    {
        Optional<TransactionInfo> transaction = session.getTransactionId()
                .flatMap(transactionManager::getOptionalTransactionInfo);
        checkState(transaction.isPresent(), "transaction is not present");
        checkState(transaction.get().isAutoCommitContext(), "transaction doesn't have auto commit context enabled");
        return transaction.get();
    }

    public static class PrestoSparkQueryExecution
            implements IPrestoSparkQueryExecution
    {
        private final JavaSparkContext sparkContext;
        private final Session session;
        private final QueryMonitor queryMonitor;
        private final CollectionAccumulator<SerializedTaskStats> taskStatsCollector;
        private final PrestoSparkTaskExecutorFactoryProvider executorFactoryProvider;
        private final SubPlan plan;
        private final Optional<String> updateType;
        private final JsonCodec<TaskStats> taskStatsJsonCodec;
        private final JsonCodec<PrestoSparkTaskDescriptor> sparkTaskDescriptorJsonCodec;
        private final PrestoSparkRddFactory rddFactory;
        private final TableWriteInfo tableWriteInfo;
        private final TransactionManager transactionManager;
        private final PagesSerde pagesSerde;

        private PrestoSparkQueryExecution(
                JavaSparkContext sparkContext,
                Session session,
                QueryMonitor queryMonitor,
                CollectionAccumulator<SerializedTaskStats> taskStatsCollector,
                PrestoSparkTaskExecutorFactoryProvider executorFactoryProvider,
                SubPlan plan,
                Optional<String> updateType,
                JsonCodec<TaskStats> taskStatsJsonCodec,
                JsonCodec<PrestoSparkTaskDescriptor> sparkTaskDescriptorJsonCodec,
                PrestoSparkRddFactory rddFactory,
                TableWriteInfo tableWriteInfo,
                TransactionManager transactionManager,
                PagesSerde pagesSerde)
        {
            this.sparkContext = requireNonNull(sparkContext, "sparkContext is null");
            this.session = requireNonNull(session, "session is null");
            this.queryMonitor = requireNonNull(queryMonitor, "queryMonitor is null");
            this.taskStatsCollector = requireNonNull(taskStatsCollector, "taskStatsCollector is null");
            this.executorFactoryProvider = requireNonNull(executorFactoryProvider, "executorFactoryProvider is null");
            this.plan = requireNonNull(plan, "plan is null");
            this.updateType = updateType;
            this.taskStatsJsonCodec = requireNonNull(taskStatsJsonCodec, "taskStatsJsonCodec is null");
            this.sparkTaskDescriptorJsonCodec = requireNonNull(sparkTaskDescriptorJsonCodec, "sparkTaskDescriptorJsonCodec is null");
            this.rddFactory = requireNonNull(rddFactory, "rddFactory is null");
            this.tableWriteInfo = requireNonNull(tableWriteInfo, "tableWriteInfo is null");
            this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
            this.pagesSerde = requireNonNull(pagesSerde, "pagesSerde is null");
        }

        @Override
        public List<List<Object>> execute()
        {
            List<Tuple2<Integer, PrestoSparkRow>> rddResults;
            try {
                rddResults = doExecute(plan);
                commit(session, transactionManager);
            }
            catch (RuntimeException executionFailure) {
                try {
                    rollback(session, transactionManager);
                }
                catch (RuntimeException rollbackFailure) {
                    if (executionFailure != rollbackFailure) {
                        executionFailure.addSuppressed(rollbackFailure);
                    }
                }
                try {
                    queryCompletedEvent(Optional.of(executionFailure));
                }
                catch (RuntimeException eventFailure) {
                    if (executionFailure != eventFailure) {
                        executionFailure.addSuppressed(eventFailure);
                    }
                }
                throw executionFailure;
            }

            // successfully finished
            queryCompletedEvent(Optional.empty());

            ConnectorSession connectorSession = session.toConnectorSession();
            List<Type> types = plan.getFragment().getTypes();
            ImmutableList.Builder<List<Object>> result = ImmutableList.builder();
            for (Tuple2<Integer, PrestoSparkRow> tuple : rddResults) {
                PrestoSparkRow row = tuple._2;
                SliceInput sliceInput = new BasicSliceInput(Slices.wrappedBuffer(row.getBytes(), 0, row.getLength()));
                ImmutableList.Builder<Object> columns = ImmutableList.builder();
                for (Type type : types) {
                    BlockBuilder blockBuilder = type.createBlockBuilder(null, 1);
                    blockBuilder.readPositionFrom(sliceInput);
                    columns.add(type.getObjectValue(connectorSession.getSqlFunctionProperties(), blockBuilder, 0));
                }
                result.add(columns.build());
            }
            return result.build();
        }

        public List<Type> getOutputTypes()
        {
            return plan.getFragment().getTypes();
        }

        public Optional<String> getUpdateType()
        {
            return updateType;
        }

        private List<Tuple2<Integer, PrestoSparkRow>> doExecute(SubPlan root)
        {
            PlanFragment rootFragment = root.getFragment();

            if (rootFragment.getPartitioning().equals(COORDINATOR_DISTRIBUTION)) {
                checkArgument(root.getChildren().size() == 1, "exactly one children fragment is expected");

                PrestoSparkTaskDescriptor taskDescriptor = new PrestoSparkTaskDescriptor(
                        session.toSessionRepresentation(),
                        session.getIdentity().getExtraCredentials(),
                        rootFragment,
                        ImmutableList.of(),
                        tableWriteInfo);
                SerializedPrestoSparkTaskDescriptor serializedTaskDescriptor = new SerializedPrestoSparkTaskDescriptor(sparkTaskDescriptorJsonCodec.toJsonBytes(taskDescriptor));

                SubPlan child = getOnlyElement(root.getChildren());
                RddAndMore rdd = createRdd(child);
                List<Tuple2<Integer, PrestoSparkRow>> sparkDriverInput = rdd.collectAndDestroyDependencies();
                return ImmutableList.copyOf(executorFactoryProvider.get().create(
                        0,
                        0,
                        serializedTaskDescriptor,
                        new PrestoSparkTaskInputs(ImmutableMap.of(child.getFragment().getId().toString(), sparkDriverInput.iterator()), ImmutableMap.of()),
                        taskStatsCollector));
            }

            RddAndMore rootRdd = createRdd(root);
            return rootRdd.collectAndDestroyDependencies();
        }

        private RddAndMore createRdd(SubPlan subPlan)
        {
            ImmutableMap.Builder<PlanFragmentId, JavaPairRDD<Integer, PrestoSparkRow>> rddInputs = ImmutableMap.builder();
            ImmutableMap.Builder<PlanFragmentId, Broadcast<List<PrestoSparkSerializedPage>>> broadcastInputs = ImmutableMap.builder();
            ImmutableList.Builder<Broadcast<?>> broadcastDependencies = ImmutableList.builder();

            for (SubPlan child : subPlan.getChildren()) {
                PlanFragment childFragment = child.getFragment();
                RddAndMore childRdd = createRdd(child);
                if (childFragment.getPartitioningScheme().getPartitioning().getHandle().equals(FIXED_BROADCAST_DISTRIBUTION)) {
                    List<Tuple2<Integer, PrestoSparkRow>> broadcastRows = childRdd.collectAndDestroyDependencies();
                    // TODO: Transform rows to pages on executors (using `RDD#map` function)
                    // TODO: Transforming it on coordinator results in 2x memory utilization as both,
                    // TODO: rows and pages have to be kept in memory all at the same time
                    Iterator<Page> pagesIterator = transformRowsToPages(transform(broadcastRows.iterator(), Tuple2::_2), childFragment.getTypes());
                    Iterator<PrestoSparkSerializedPage> serializedPagesIterator = transform(transform(pagesIterator, pagesSerde::serialize), PrestoSparkUtils::toPrestoSparkSerializedPage);
                    List<PrestoSparkSerializedPage> serializedPages = new ArrayList<>();
                    serializedPagesIterator.forEachRemaining(serializedPages::add);
                    Broadcast<List<PrestoSparkSerializedPage>> broadcast = sparkContext.broadcast(serializedPages);
                    broadcastInputs.put(childFragment.getId(), broadcast);
                    broadcastDependencies.add(broadcast);
                }
                else {
                    rddInputs.put(childFragment.getId(), childRdd.getRdd());
                    broadcastDependencies.addAll(childRdd.getBroadcastDependencies());
                }
            }
            JavaPairRDD<Integer, PrestoSparkRow> rdd = rddFactory.createSparkRdd(
                    sparkContext,
                    session,
                    subPlan.getFragment(),
                    rddInputs.build(),
                    broadcastInputs.build(),
                    executorFactoryProvider,
                    taskStatsCollector,
                    tableWriteInfo);
            return new RddAndMore(rdd, broadcastDependencies.build());
        }

        private void queryCompletedEvent(Optional<Throwable> failure)
        {
            // TODO: implement query monitor and collect query info
            // QueryInfo queryInfo = createQueryInfo(failure);
            // queryMonitor.queryCompletedEvent(queryInfo);
        }

        private QueryInfo createQueryInfo(Optional<Throwable> failure)
        {
            List<SerializedTaskStats> serializedTaskStats = taskStatsCollector.value();
            List<TaskStats> taskStats = serializedTaskStats.stream()
                    .map(SerializedTaskStats::getBytes)
                    .map(taskStatsJsonCodec::fromJson)
                    .collect(toImmutableList());
            // TODO: create query info
            return null;
        }
    }

    private static class RddAndMore
    {
        private final JavaPairRDD<Integer, PrestoSparkRow> rdd;
        private final List<Broadcast<?>> broadcastDependencies;

        private boolean collected;

        private RddAndMore(JavaPairRDD<Integer, PrestoSparkRow> rdd, List<Broadcast<?>> broadcastDependencies)
        {
            this.rdd = requireNonNull(rdd, "rdd is null");
            this.broadcastDependencies = ImmutableList.copyOf(requireNonNull(broadcastDependencies, "broadcastDependencies is null"));
        }

        public List<Tuple2<Integer, PrestoSparkRow>> collectAndDestroyDependencies()
        {
            checkState(!collected, "already collected");
            collected = true;
            List<Tuple2<Integer, PrestoSparkRow>> result = rdd.collect();
            broadcastDependencies.forEach(Broadcast::destroy);
            return result;
        }

        public JavaPairRDD<Integer, PrestoSparkRow> getRdd()
        {
            return rdd;
        }

        public List<Broadcast<?>> getBroadcastDependencies()
        {
            return broadcastDependencies;
        }
    }
}
