package org.onlab.onos.net.trivial.topology.impl;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import org.onlab.graph.DijkstraGraphSearch;
import org.onlab.graph.GraphPathSearch;
import org.onlab.graph.TarjanGraphSearch;
import org.onlab.onos.net.AbstractModel;
import org.onlab.onos.net.ConnectPoint;
import org.onlab.onos.net.DefaultPath;
import org.onlab.onos.net.DeviceId;
import org.onlab.onos.net.Link;
import org.onlab.onos.net.Path;
import org.onlab.onos.net.provider.ProviderId;
import org.onlab.onos.net.topology.ClusterId;
import org.onlab.onos.net.topology.DefaultTopologyCluster;
import org.onlab.onos.net.topology.GraphDescription;
import org.onlab.onos.net.topology.LinkWeight;
import org.onlab.onos.net.topology.Topology;
import org.onlab.onos.net.topology.TopologyCluster;
import org.onlab.onos.net.topology.TopologyEdge;
import org.onlab.onos.net.topology.TopologyGraph;
import org.onlab.onos.net.topology.TopologyVertex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.collect.ImmutableSetMultimap.Builder;
import static org.onlab.graph.GraphPathSearch.Result;
import static org.onlab.graph.TarjanGraphSearch.SCCResult;
import static org.onlab.onos.net.Link.Type.INDIRECT;

/**
 * Default implementation of the topology descriptor. This carries the
 * backing topology data.
 */
public class DefaultTopology extends AbstractModel implements Topology {

    private static final DijkstraGraphSearch<TopologyVertex, TopologyEdge> DIJKSTRA =
            new DijkstraGraphSearch<>();
    private static final TarjanGraphSearch<TopologyVertex, TopologyEdge> TARJAN =
            new TarjanGraphSearch<>();

    private static final ProviderId PID = new ProviderId("org.onlab.onos.net");

    private final long time;
    private final TopologyGraph graph;

    private final SCCResult<TopologyVertex, TopologyEdge> clusterResults;
    private final ImmutableMap<DeviceId, Result<TopologyVertex, TopologyEdge>> results;
    private final ImmutableSetMultimap<PathKey, Path> paths;

    private final ImmutableMap<ClusterId, TopologyCluster> clusters;
    private final ImmutableSet<ConnectPoint> infrastructurePoints;
    private final ImmutableSetMultimap<ClusterId, ConnectPoint> broadcastSets;

    private ImmutableMap<DeviceId, TopologyCluster> clustersByDevice;
    private ImmutableSetMultimap<TopologyCluster, DeviceId> devicesByCluster;
    private ImmutableSetMultimap<TopologyCluster, Link> linksByCluster;


    /**
     * Creates a topology descriptor attributed to the specified provider.
     *
     * @param providerId  identity of the provider
     * @param description data describing the new topology
     */
    DefaultTopology(ProviderId providerId, GraphDescription description) {
        super(providerId);
        this.time = description.timestamp();

        // Build the graph
        this.graph = new DefaultTopologyGraph(description.vertexes(),
                                              description.edges());

        this.results = searchForShortestPaths();
        this.paths = buildPaths();

        this.clusterResults = searchForClusters();
        this.clusters = buildTopologyClusters();

        buildIndexes();

        this.broadcastSets = buildBroadcastSets();
        this.infrastructurePoints = findInfrastructurePoints();
    }

    @Override
    public long time() {
        return time;
    }

    @Override
    public int clusterCount() {
        return clusters.size();
    }

    @Override
    public int deviceCount() {
        return graph.getVertexes().size();
    }

    @Override
    public int linkCount() {
        return graph.getEdges().size();
    }

    @Override
    public int pathCount() {
        return paths.size();
    }

    /**
     * Returns the backing topology graph.
     *
     * @return topology graph
     */
    TopologyGraph getGraph() {
        return graph;
    }

    /**
     * Returns the set of topology clusters.
     *
     * @return set of clusters
     */
    Set<TopologyCluster> getClusters() {
        return ImmutableSet.copyOf(clusters.values());
    }

    /**
     * Returns the set of cluster devices.
     *
     * @param cluster topology cluster
     * @return cluster devices
     */
    Set<DeviceId> getClusterDevices(TopologyCluster cluster) {
        return devicesByCluster.get(cluster);
    }

    /**
     * Returns the set of cluster links.
     *
     * @param cluster topology cluster
     * @return cluster links
     */
    Set<Link> getClusterLinks(TopologyCluster cluster) {
        return linksByCluster.get(cluster);
    }

    /**
     * Indicates whether the given point is an infrastructure link end-point.
     *
     * @param connectPoint connection point
     * @return true if infrastructure
     */
    boolean isInfrastructure(ConnectPoint connectPoint) {
        return infrastructurePoints.contains(connectPoint);
    }

    /**
     * Indicates whether the given point is part of a broadcast tree.
     *
     * @param connectPoint connection point
     * @return true if in broadcast tree
     */
    boolean isInBroadcastTree(ConnectPoint connectPoint) {
        // Any non-infrastructure, i.e. edge points are assumed to be OK
        if (!isInfrastructure(connectPoint)) {
            return true;
        }

        // Find the cluster to which the device belongs.
        TopologyCluster cluster = clustersByDevice.get(connectPoint.deviceId());
        if (cluster == null) {
            throw new IllegalArgumentException("No cluster found for device " + connectPoint.deviceId());
        }

        // If the broadcast tree is null or empty, or if the point explicitly
        // belongs to the broadcast tree points, return true;
        Set<ConnectPoint> points = broadcastSets.get(cluster.id());
        return points == null || points.isEmpty() || points.contains(connectPoint);
    }

    /**
     * Returns the set of pre-computed shortest paths between source and
     * destination devices.
     *
     * @param src source device
     * @param dst destination device
     * @return set of shortest paths
     */
    Set<Path> getPaths(DeviceId src, DeviceId dst) {
        return paths.get(new PathKey(src, dst));
    }

    /**
     * Computes on-demand the set of shortest paths between source and
     * destination devices.
     *
     * @param src source device
     * @param dst destination device
     * @return set of shortest paths
     */
    Set<Path> getPaths(DeviceId src, DeviceId dst, LinkWeight weight) {
        GraphPathSearch.Result<TopologyVertex, TopologyEdge> result =
                DIJKSTRA.search(graph, new DefaultTopologyVertex(src),
                                new DefaultTopologyVertex(dst), weight);
        ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
        for (org.onlab.graph.Path<TopologyVertex, TopologyEdge> path : result.paths()) {
            builder.add(networkPath(path));
        }
        return builder.build();
    }


    // Searches the graph for all shortest paths and returns the search results.
    private ImmutableMap<DeviceId, Result<TopologyVertex, TopologyEdge>> searchForShortestPaths() {
        ImmutableMap.Builder<DeviceId, Result<TopologyVertex, TopologyEdge>> builder = ImmutableMap.builder();

        // Search graph paths for each source to all destinations.
        LinkWeight weight = new HopCountLinkWeight(graph.getVertexes().size());
        for (TopologyVertex src : graph.getVertexes()) {
            builder.put(src.deviceId(), DIJKSTRA.search(graph, src, null, weight));
        }
        return builder.build();
    }

    // Builds network paths from the graph path search results
    private ImmutableSetMultimap<PathKey, Path> buildPaths() {
        Builder<PathKey, Path> builder = ImmutableSetMultimap.builder();
        for (DeviceId deviceId : results.keySet()) {
            Result<TopologyVertex, TopologyEdge> result = results.get(deviceId);
            for (org.onlab.graph.Path<TopologyVertex, TopologyEdge> path : result.paths()) {
                builder.put(new PathKey(path.src().deviceId(), path.dst().deviceId()),
                            networkPath(path));
            }
        }
        return builder.build();
    }

    // Converts graph path to a network path with the same cost.
    private Path networkPath(org.onlab.graph.Path<TopologyVertex, TopologyEdge> path) {
        List<Link> links = new ArrayList<>();
        for (TopologyEdge edge : path.edges()) {
            links.add(edge.link());
        }
        return new DefaultPath(PID, links, path.cost());
    }


    // Searches for SCC clusters in the network topology graph using Tarjan
    // algorithm.
    private SCCResult<TopologyVertex, TopologyEdge> searchForClusters() {
        return TARJAN.search(graph, new NoIndirectLinksWeight());
    }

    // Builds the topology clusters and returns the id-cluster bindings.
    private ImmutableMap<ClusterId, TopologyCluster> buildTopologyClusters() {
        ImmutableMap.Builder<ClusterId, TopologyCluster> clusterBuilder = ImmutableMap.builder();
        SCCResult<TopologyVertex, TopologyEdge> result =
                TARJAN.search(graph, new NoIndirectLinksWeight());

        // Extract both vertexes and edges from the results; the lists form
        // pairs along the same index.
        List<Set<TopologyVertex>> clusterVertexes = result.clusterVertexes();
        List<Set<TopologyEdge>> clusterEdges = result.clusterEdges();

        // Scan over the lists and create a cluster from the results.
        for (int i = 0, n = result.clusterCount(); i < n; i++) {
            Set<TopologyVertex> vertexSet = clusterVertexes.get(i);
            Set<TopologyEdge> edgeSet = clusterEdges.get(i);

            ClusterId cid = ClusterId.clusterId(i);
            DefaultTopologyCluster cluster =
                    new DefaultTopologyCluster(cid, vertexSet.size(), edgeSet.size(),
                                               findRoot(vertexSet).deviceId());
            clusterBuilder.put(cid, cluster);
        }
        return clusterBuilder.build();
    }

    // Finds the vertex whose device id is the lexicographical minimum in the
    // specified set.
    private TopologyVertex findRoot(Set<TopologyVertex> vertexSet) {
        TopologyVertex minVertex = null;
        for (TopologyVertex vertex : vertexSet) {
            if (minVertex == null ||
                    minVertex.deviceId().toString()
                            .compareTo(minVertex.deviceId().toString()) < 0) {
                minVertex = vertex;
            }
        }
        return minVertex;
    }

    // Processes a map of broadcast sets for each cluster.
    private ImmutableSetMultimap<ClusterId, ConnectPoint> buildBroadcastSets() {
        Builder<ClusterId, ConnectPoint> builder = ImmutableSetMultimap.builder();
        for (TopologyCluster cluster : clusters.values()) {
            addClusterBroadcastSet(cluster, builder);
        }
        return builder.build();
    }

    // Finds all broadcast points for the cluster. These are those connection
    // points which lie along the shortest paths between the cluster root and
    // all other devices within the cluster.
    private void addClusterBroadcastSet(TopologyCluster cluster,
                                        Builder<ClusterId, ConnectPoint> builder) {
        // Use the graph root search results to build the broadcast set.
        Result<TopologyVertex, TopologyEdge> result = results.get(cluster.root());
        for (Map.Entry<TopologyVertex, Set<TopologyEdge>> entry : result.parents().entrySet()) {
            TopologyVertex vertex = entry.getKey();

            // Ignore any parents that lead outside the cluster.
            if (clustersByDevice.get(vertex.deviceId()) != cluster) {
                continue;
            }

            // Ignore any back-link sets that are empty.
            Set<TopologyEdge> parents = entry.getValue();
            if (parents.isEmpty()) {
                continue;
            }

            // Use the first back-link source and destinations to add to the
            // broadcast set.
            Link link = parents.iterator().next().link();
            builder.put(cluster.id(), link.src());
            builder.put(cluster.id(), link.dst());
        }
    }

    // Collects and returns an set of all infrastructure link end-points.
    private ImmutableSet<ConnectPoint> findInfrastructurePoints() {
        ImmutableSet.Builder<ConnectPoint> builder = ImmutableSet.builder();
        for (TopologyEdge edge : graph.getEdges()) {
            builder.add(edge.link().src());
            builder.add(edge.link().dst());
        }
        return builder.build();
    }

    // Builds cluster-devices, cluster-links and device-cluster indexes.
    private void buildIndexes() {
        // Prepare the index builders
        ImmutableMap.Builder<DeviceId, TopologyCluster> clusterBuilder = ImmutableMap.builder();
        ImmutableSetMultimap.Builder<TopologyCluster, DeviceId> devicesBuilder = ImmutableSetMultimap.builder();
        ImmutableSetMultimap.Builder<TopologyCluster, Link> linksBuilder = ImmutableSetMultimap.builder();

        // Now scan through all the clusters
        for (TopologyCluster cluster : clusters.values()) {
            int i = cluster.id().index();

            // Scan through all the cluster vertexes.
            for (TopologyVertex vertex : clusterResults.clusterVertexes().get(i)) {
                devicesBuilder.put(cluster, vertex.deviceId());
                clusterBuilder.put(vertex.deviceId(), cluster);
            }

            // Scan through all the cluster edges.
            for (TopologyEdge edge : clusterResults.clusterEdges().get(i)) {
                linksBuilder.put(cluster, edge.link());
            }
        }

        // Finalize all indexes.
        clustersByDevice = clusterBuilder.build();
        devicesByCluster = devicesBuilder.build();
        linksByCluster = linksBuilder.build();
    }

    // Link weight for measuring link cost as hop count with indirect links
    // being as expensive as traversing the entire graph to assume the worst.
    private static class HopCountLinkWeight implements LinkWeight {
        private final int indirectLinkCost;

        HopCountLinkWeight(int indirectLinkCost) {
            this.indirectLinkCost = indirectLinkCost;
        }

        @Override
        public double weight(TopologyEdge edge) {
            // To force preference to use direct paths first, make indirect
            // links as expensive as the linear vertex traversal.
            return edge.link().type() == INDIRECT ? indirectLinkCost : 1;
        }
    }

    // Link weight for preventing traversal over indirect links.
    private static class NoIndirectLinksWeight implements LinkWeight {
        @Override
        public double weight(TopologyEdge edge) {
            return edge.link().type() == INDIRECT ? -1 : 1;
        }
    }

    @Override
    public String toString() {
        return toStringHelper(this)
                .add("time", time)
                .add("clusters", clusterCount())
                .add("devices", deviceCount())
                .add("links", linkCount())
                .add("pathCount", pathCount())
                .toString();
    }
}
