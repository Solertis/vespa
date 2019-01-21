// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.nodeadmin;

import com.yahoo.concurrent.ThreadFactoryFactory;
import com.yahoo.vespa.hosted.dockerapi.metrics.CounterWrapper;
import com.yahoo.vespa.hosted.dockerapi.metrics.Dimensions;
import com.yahoo.vespa.hosted.dockerapi.metrics.GaugeWrapper;
import com.yahoo.vespa.hosted.dockerapi.metrics.MetricReceiverWrapper;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeSpec;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgent;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContext;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContextFactory;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentContextManager;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentFactory;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentScheduler;
import com.yahoo.vespa.hosted.node.admin.util.PrefixLogger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Administers a host (for now only docker hosts) and its nodes (docker containers nodes).
 *
 * @author stiankri
 */
public class NodeAdminImpl implements NodeAdmin {
    private static final PrefixLogger logger = PrefixLogger.getNodeAdminLogger(NodeAdmin.class);
    private static final Duration NODE_AGENT_FREEZE_TIMEOUT = Duration.ofSeconds(5);

    private final ScheduledExecutorService metricsScheduler =
            Executors.newScheduledThreadPool(1, ThreadFactoryFactory.getDaemonThreadFactory("metricsscheduler"));

    private final NodeAgentWithSchedulerFactory nodeAgentWithSchedulerFactory;
    private final NodeAgentContextFactory nodeAgentContextFactory;

    private final Clock clock;
    private boolean previousWantFrozen;
    private boolean isFrozen;
    private Instant startOfFreezeConvergence;

    private final Map<String, NodeAgentWithScheduler> nodeAgentWithSchedulerByHostname = new ConcurrentHashMap<>();

    private final GaugeWrapper numberOfContainersInLoadImageState;
    private final CounterWrapper numberOfUnhandledExceptionsInNodeAgent;

    public NodeAdminImpl(NodeAgentFactory nodeAgentFactory,
                         NodeAgentContextFactory nodeAgentContextFactory,
                         MetricReceiverWrapper metricReceiver,
                         Clock clock) {
        this((NodeAgentWithSchedulerFactory) nodeAgentContext -> create(clock, nodeAgentFactory, nodeAgentContext),
                nodeAgentContextFactory, metricReceiver, clock);
    }

    NodeAdminImpl(NodeAgentWithSchedulerFactory nodeAgentWithSchedulerFactory,
                  NodeAgentContextFactory nodeAgentContextFactory,
                  MetricReceiverWrapper metricReceiver,
                  Clock clock) {
        this.nodeAgentWithSchedulerFactory = nodeAgentWithSchedulerFactory;
        this.nodeAgentContextFactory = nodeAgentContextFactory;

        this.clock = clock;
        this.previousWantFrozen = true;
        this.isFrozen = true;
        this.startOfFreezeConvergence = clock.instant();

        Dimensions dimensions = new Dimensions.Builder().add("role", "docker").build();
        this.numberOfContainersInLoadImageState = metricReceiver.declareGauge(MetricReceiverWrapper.APPLICATION_DOCKER, dimensions, "nodes.image.loading");
        this.numberOfUnhandledExceptionsInNodeAgent = metricReceiver.declareCounter(MetricReceiverWrapper.APPLICATION_DOCKER, dimensions, "nodes.unhandled_exceptions");
    }

    @Override
    public void refreshContainersToRun(List<NodeSpec> containersToRun) {
        final Map<String, NodeAgentContext> nodeAgentContextsByHostname = containersToRun.stream()
                .collect(Collectors.toMap(NodeSpec::getHostname, nodeAgentContextFactory::create));

        // Stop and remove NodeAgents that should no longer be running
        diff(nodeAgentWithSchedulerByHostname.keySet(), nodeAgentContextsByHostname.keySet())
                .forEach(hostname -> nodeAgentWithSchedulerByHostname.remove(hostname).stop());

        // Start NodeAgent for hostnames that should be running, but aren't yet
        diff(nodeAgentContextsByHostname.keySet(), nodeAgentWithSchedulerByHostname.keySet()).forEach(hostname ->  {
            NodeAgentWithScheduler naws = nodeAgentWithSchedulerFactory.create(nodeAgentContextsByHostname.get(hostname));
            naws.start();
            nodeAgentWithSchedulerByHostname.put(hostname, naws);
        });

        // At this point, nodeAgentContextsByHostname and nodeAgentWithSchedulerByHostname should have the same keys
        nodeAgentContextsByHostname.forEach((hostname, context) ->
            nodeAgentWithSchedulerByHostname.get(hostname).scheduleTickWith(context)
        );
    }

    private void updateNodeAgentMetrics() {
        int numberContainersWaitingImage = 0;
        int numberOfNewUnhandledExceptions = 0;

        for (NodeAgentWithScheduler nodeAgentWithScheduler : nodeAgentWithSchedulerByHostname.values()) {
            if (nodeAgentWithScheduler.isDownloadingImage()) numberContainersWaitingImage++;
            numberOfNewUnhandledExceptions += nodeAgentWithScheduler.getAndResetNumberOfUnhandledExceptions();
        }

        numberOfContainersInLoadImageState.sample(numberContainersWaitingImage);
        numberOfUnhandledExceptionsInNodeAgent.add(numberOfNewUnhandledExceptions);
    }

    @Override
    public boolean setFrozen(boolean wantFrozen) {
        if (wantFrozen != previousWantFrozen) {
            if (wantFrozen) {
                this.startOfFreezeConvergence = clock.instant();
            } else {
                this.startOfFreezeConvergence = null;
            }

            previousWantFrozen = wantFrozen;
        }

        // Use filter with count instead of allMatch() because allMatch() will short circuit on first non-match
        boolean allNodeAgentsConverged = nodeAgentWithSchedulerByHostname.values().parallelStream()
                .filter(nodeAgentScheduler -> !nodeAgentScheduler.setFrozen(wantFrozen, NODE_AGENT_FREEZE_TIMEOUT))
                .count() == 0;

        if (wantFrozen) {
            if (allNodeAgentsConverged) isFrozen = true;
        } else isFrozen = false;

        return allNodeAgentsConverged;
    }

    @Override
    public boolean isFrozen() {
        return isFrozen;
    }

    @Override
    public Duration subsystemFreezeDuration() {
        if (startOfFreezeConvergence == null) {
            return Duration.ofSeconds(0);
        } else {
            return Duration.between(startOfFreezeConvergence, clock.instant());
        }
    }

    @Override
    public void stopNodeAgentServices(List<String> hostnames) {
        // Each container may spend 1-1:30 minutes stopping
        hostnames.parallelStream()
                .filter(nodeAgentWithSchedulerByHostname::containsKey)
                .map(nodeAgentWithSchedulerByHostname::get)
                .forEach(nodeAgent -> {
                    nodeAgent.suspend();
                    nodeAgent.stopServices();
                });
    }

    @Override
    public void start() {
        metricsScheduler.scheduleAtFixedRate(() -> {
            try {
                updateNodeAgentMetrics();
                nodeAgentWithSchedulerByHostname.values().forEach(NodeAgent::updateContainerNodeMetrics);
            } catch (Throwable e) {
                logger.warning("Metric fetcher scheduler failed", e);
            }
        }, 10, 55, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        metricsScheduler.shutdown();

        // Stop all node-agents in parallel, will block until the last NodeAgent is stopped
        nodeAgentWithSchedulerByHostname.values().parallelStream().forEach(NodeAgent::stop);

        do {
            try {
                metricsScheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                logger.info("Was interrupted while waiting for metricsScheduler to shutdown");
            }
        } while (!metricsScheduler.isTerminated());
    }

    // Set-difference. Returns minuend minus subtrahend.
    private static <T> Set<T> diff(final Set<T> minuend, final Set<T> subtrahend) {
        final HashSet<T> result = new HashSet<>(minuend);
        result.removeAll(subtrahend);
        return result;
    }

    static class NodeAgentWithScheduler implements NodeAgent, NodeAgentScheduler {
        private final NodeAgent nodeAgent;
        private final NodeAgentScheduler nodeAgentScheduler;

        private NodeAgentWithScheduler(NodeAgent nodeAgent, NodeAgentScheduler nodeAgentScheduler) {
            this.nodeAgent = nodeAgent;
            this.nodeAgentScheduler = nodeAgentScheduler;
        }

        @Override public void stopServices() { nodeAgent.stopServices(); }
        @Override public void suspend() { nodeAgent.suspend(); }
        @Override public void start() { nodeAgent.start(); }
        @Override public void stop() { nodeAgent.stop(); }
        @Override public void updateContainerNodeMetrics() { nodeAgent.updateContainerNodeMetrics(); }
        @Override public boolean isDownloadingImage() { return nodeAgent.isDownloadingImage(); }
        @Override public int getAndResetNumberOfUnhandledExceptions() { return nodeAgent.getAndResetNumberOfUnhandledExceptions(); }

        @Override public void scheduleTickWith(NodeAgentContext context) { nodeAgentScheduler.scheduleTickWith(context); }
        @Override public boolean setFrozen(boolean frozen, Duration timeout) { return nodeAgentScheduler.setFrozen(frozen, timeout); }
    }

    @FunctionalInterface
    interface NodeAgentWithSchedulerFactory {
        NodeAgentWithScheduler create(NodeAgentContext context);
    }

    private static NodeAgentWithScheduler create(Clock clock, NodeAgentFactory nodeAgentFactory, NodeAgentContext context) {
        NodeAgentContextManager contextManager = new NodeAgentContextManager(clock, context);
        NodeAgent nodeAgent = nodeAgentFactory.create(contextManager);
        return new NodeAgentWithScheduler(nodeAgent, contextManager);
    }
}
