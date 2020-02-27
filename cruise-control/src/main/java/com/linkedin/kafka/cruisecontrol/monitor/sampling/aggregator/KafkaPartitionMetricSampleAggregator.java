/*
 * Copyright 2017 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 */

package com.linkedin.kafka.cruisecontrol.monitor.sampling.aggregator;

import com.linkedin.cruisecontrol.exception.NotEnoughValidWindowsException;
import com.linkedin.cruisecontrol.monitor.sampling.aggregator.AggregationOptions;
import com.linkedin.cruisecontrol.monitor.sampling.aggregator.MetricSampleAggregationResult;
import com.linkedin.cruisecontrol.monitor.sampling.aggregator.MetricSampleCompleteness;
import com.linkedin.cruisecontrol.monitor.sampling.aggregator.MetricSampleAggregator;
import com.linkedin.kafka.cruisecontrol.async.progress.OperationProgress;
import com.linkedin.kafka.cruisecontrol.async.progress.RetrievingMetrics;
import com.linkedin.kafka.cruisecontrol.config.KafkaCruiseControlConfig;
import com.linkedin.kafka.cruisecontrol.config.constants.MonitorConfig;
import com.linkedin.kafka.cruisecontrol.monitor.ModelCompletenessRequirements;
import com.linkedin.kafka.cruisecontrol.monitor.metricdefinition.KafkaMetricDef;
import com.linkedin.kafka.cruisecontrol.monitor.sampling.holder.PartitionEntity;
import com.linkedin.kafka.cruisecontrol.monitor.sampling.holder.PartitionMetricSample;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class aggregates the partition metric samples generated by the MetricFetcher.
 * <p>
 * The partition metric sample aggregator performs the sanity check on the samples and aggregate the samples into the
 * corresponding window. we assume the sample we get are only from the leaders, and we are going to derive the
 * follower metrics based on the leader metrics.
 * </p>
 * @see MetricSampleAggregator
 */
public class KafkaPartitionMetricSampleAggregator extends MetricSampleAggregator<String, PartitionEntity> {
  private static final Logger LOG = LoggerFactory.getLogger(KafkaPartitionMetricSampleAggregator.class);
  private final int _maxAllowedExtrapolationsPerPartition;
  private final Metadata _metadata;

  /**
   * Construct the metric sample aggregator.
   *
   * @param config   The load monitor configurations.
   * @param metadata The metadata of the cluster.
   */
  public KafkaPartitionMetricSampleAggregator(KafkaCruiseControlConfig config, Metadata metadata) {
    super(config.getInt(MonitorConfig.NUM_PARTITION_METRICS_WINDOWS_CONFIG),
          config.getLong(MonitorConfig.PARTITION_METRICS_WINDOW_MS_CONFIG),
          config.getInt(MonitorConfig.MIN_SAMPLES_PER_PARTITION_METRICS_WINDOW_CONFIG).byteValue(),
          config.getInt(MonitorConfig.PARTITION_METRIC_SAMPLE_AGGREGATOR_COMPLETENESS_CACHE_SIZE_CONFIG),
          KafkaMetricDef.commonMetricDef());
    _metadata = metadata;
    _maxAllowedExtrapolationsPerPartition =
        config.getInt(MonitorConfig.MAX_ALLOWED_EXTRAPOLATIONS_PER_PARTITION_CONFIG);
    _sampleType = SampleType.PARTITION;

  }

  /**
   * Add a sample to the metric aggregator. This method is thread safe.
   *
   * @param sample The metric sample to add.
   * @return True if the sample is accepted, false if the sample is ignored.
   */
  public boolean addSample(PartitionMetricSample sample) {
    return addSample(sample, true);
  }

  /**
   * Add a sample to the metric aggregator. This method is thread safe.
   *
   * @param sample The metric sample to add.
   * @param leaderValidation whether perform the leader validation or not.
   *
   * @return True if the sample is accepted, false if the sample is ignored.
   */
  public boolean addSample(PartitionMetricSample sample, boolean leaderValidation) {
    // Sanity check the sample
    return isValidSample(sample, leaderValidation) && super.addSample(sample);
  }

  /**
   * Collect the aggregated metrics for all the topic partitions.
   * <p>
   * If a topic has at least one window that does not have enough samples, that topic will be excluded from the
   * returned aggregated metrics. This is because:
   * <ol>
   *   <li>
   *     We assume that only new topics would have insufficient data. So we only balance the existing topics and
   *     allow more time to collect enough utilization data for the new topics.
   *   </li>
   *   <li>
   *     If we don't have enough data to make a replica movement decision, it is better not to take any action.
   *   </li>
   * </ol>
   *
   * @param cluster Kafka cluster.
   * @param now the current time.
   * @param operationProgress to report the async operation progress.
   * @return The {@link MetricSampleAggregationResult} for all the partitions.
   */
  public MetricSampleAggregationResult<String, PartitionEntity> aggregate(Cluster cluster,
                                                                          long now,
                                                                          OperationProgress operationProgress)
      throws NotEnoughValidWindowsException {
    ModelCompletenessRequirements requirements = new ModelCompletenessRequirements(1, 0.0, false);
    return aggregate(cluster, -1L, now, requirements, operationProgress);
  }

  /**
   * Collect the aggregated metrics for all the topic partitions for a time window.
   * <p>
   * If a topic has at least one window that does not have enough samples, that topic will be excluded from the
   * returned aggregated metrics. This is because:
   * <ol>
   *   <li>
   *     We assume that only new topics would have insufficient data. So we only balance the existing topics and
   *     allow more time to collect enough utilization data for the new topics.
   *   </li>
   *   <li>
   *     If we don't have enough data to make a replica movement decision, it is better not to take any action.
   *   </li>
   * </ol>
   *
   * @param cluster Kafka cluster.
   * @param from the start of the time window
   * @param to the end of the time window
   * @param requirements the {@link ModelCompletenessRequirements} for the aggregation result.
   * @param operationProgress to report the operation progress.
   * @return The {@link MetricSampleAggregationResult} for all the partitions.
   */
  public MetricSampleAggregationResult<String, PartitionEntity> aggregate(Cluster cluster,
                                                                          long from,
                                                                          long to,
                                                                          ModelCompletenessRequirements requirements,
                                                                          OperationProgress operationProgress)
      throws NotEnoughValidWindowsException {
    RetrievingMetrics step = new RetrievingMetrics();
    try {
      operationProgress.addStep(step);
      return aggregate(from, to, toAggregationOptions(cluster, requirements));
    } finally {
      step.done();
    }
  }

  /**
   * Get the metric sample completeness for a given period.
   *
   * @param cluster the current cluster topology
   * @param from the start of the period
   * @param to the end of the period
   * @param requirements the model completeness requirements.
   * @return The metric sample completeness based on the completeness requirements.
   */
  public MetricSampleCompleteness<String, PartitionEntity> completeness(Cluster cluster,
                                                                        long from,
                                                                        long to,
                                                                        ModelCompletenessRequirements requirements) {
    return completeness(from, to, toAggregationOptions(cluster, requirements));
  }

  /**
   * Get a sorted set of valid windows in the aggregator. A valid window is a window with
   * {@link MonitorConfig#MIN_VALID_PARTITION_RATIO_CONFIG enough valid partitions}
   * being monitored. A valid partition must be valid in all the windows in the returned set.
   *
   * @param cluster Kafka cluster.
   * @param minMonitoredPartitionsPercentage the minimum required monitored partitions percentage.
   * @return A sorted set of valid windows in the aggregator.
   */
  public SortedSet<Long> validWindows(Cluster cluster, double minMonitoredPartitionsPercentage) {
    AggregationOptions<String, PartitionEntity> options = new AggregationOptions<>(minMonitoredPartitionsPercentage,
                                                                                   0.0,
                                                                                   1,
                                                                                   _maxAllowedExtrapolationsPerPartition,
                                                                                   allPartitions(cluster),
                                                                                   AggregationOptions.Granularity.ENTITY,
                                                                                   true);
    MetricSampleCompleteness<String, PartitionEntity> completeness = completeness(-1, Long.MAX_VALUE, options);
    return windowIndicesToWindows(completeness.validWindowIndices(), _windowMs);
  }

  /**
   * Get the valid partitions percentage across all the windows.
   *
   * @param cluster Kafka cluster.
   * @return The percentage of valid partitions across all the windows.
   */
  public double monitoredPercentage(Cluster cluster) {
    AggregationOptions<String, PartitionEntity> options = new AggregationOptions<>(0.0,
                                                                                   0.0,
                                                                                   1,
                                                                                   _maxAllowedExtrapolationsPerPartition,
                                                                                   allPartitions(cluster),
                                                                                   AggregationOptions.Granularity.ENTITY,
                                                                                   true);
    MetricSampleCompleteness<String, PartitionEntity> completeness = completeness(-1, Long.MAX_VALUE, options);
    return completeness.validEntityRatio();
  }

  /**
   * Get the monitored partition percentage in each window.
   * @param cluster Kafka cluster.
   * @return A mapping from window to the monitored partitions percentage.
   */
  public SortedMap<Long, Float> validPartitionRatioByWindows(Cluster cluster) {
    AggregationOptions<String, PartitionEntity> options = new AggregationOptions<>(0.0,
                                                                                   0.0,
                                                                                   1,
                                                                                   _maxAllowedExtrapolationsPerPartition,
                                                                                   allPartitions(cluster),
                                                                                   AggregationOptions.Granularity.ENTITY,
                                                                                   true);
    MetricSampleCompleteness<String, PartitionEntity> completeness = completeness(-1, Long.MAX_VALUE, options);
    return windowIndicesToWindows(completeness.validEntityRatioWithGroupGranularityByWindowIndex(), _windowMs);
  }

  private Set<PartitionEntity> allPartitions(Cluster cluster) {
    Set<PartitionEntity> allPartitions = new HashSet<>();
    for (String topic : cluster.topics()) {
      for (PartitionInfo partitionInfo : cluster.partitionsForTopic(topic)) {
        TopicPartition tp = new TopicPartition(partitionInfo.topic(), partitionInfo.partition());
        PartitionEntity partitionEntity = new PartitionEntity(tp);
        allPartitions.add(identity(partitionEntity));
      }
    }
    return allPartitions;
  }

  private SortedSet<Long> windowIndicesToWindows(SortedSet<Long> original, long windowMs) {
    SortedSet<Long> result = new TreeSet<>(Collections.reverseOrder());
    original.forEach(idx -> result.add(idx * windowMs));
    return result;
  }

  private <T> SortedMap<Long, T> windowIndicesToWindows(SortedMap<Long, T> original, long windowMs) {
    SortedMap<Long, T> result = new TreeMap<>(Collections.reverseOrder());
    original.forEach((key, value) -> result.put(key * windowMs, value));
    return result;
  }

  /**
   * This is a simple sanity check on the sample data. We only verify that
   * <p>
   * 1. the broker of the sampled data is from the broker who holds the leader replica. If it is not, we simply
   * discard the data because leader migration may have occurred so the metrics on the old data might not be
   * accurate anymore.
   * <p>
   * 2. The sample contains metric for all the resources.
   *
   * @param sample the sample to do the sanity check.
   * @param leaderValidation whether do the leader validation or not.
   * @return <tt>true</tt> if the sample is valid.
   */
  private boolean isValidSample(PartitionMetricSample sample, boolean leaderValidation) {
    boolean validLeader = true;
    if (leaderValidation) {
      Node leader = _metadata.fetch().leaderFor(sample.entity().tp());
      validLeader = (leader != null) && (sample.brokerId() == leader.id());
      if (!validLeader) {
        LOG.warn("The metric sample is discarded due to invalid leader. Current leader {}, Sample: {}", leader, sample);
      }
    }

    // TODO: We do not have the replication bytes rate at this point. Use the default validation after they are available.
    boolean completeMetrics = sample.isValid(_metricDef) || (sample.allMetricValues().size() == _metricDef.size() - 2
                                                             && sample.allMetricValues().containsKey(_metricDef.metricInfo(
                                                                 KafkaMetricDef.REPLICATION_BYTES_IN_RATE.name()).id())
                                                             && sample.allMetricValues().containsKey(_metricDef.metricInfo(
                                                                 KafkaMetricDef.REPLICATION_BYTES_OUT_RATE.name()).id()));

    if (!completeMetrics) {
      LOG.warn("The metric sample is discarded due to missing metrics. Sample: {}", sample);
    }
    return validLeader && completeMetrics;
  }

  private AggregationOptions<String, PartitionEntity> toAggregationOptions(Cluster cluster,
                                                                           ModelCompletenessRequirements requirements) {
    Set<PartitionEntity> allPartitions = allPartitions(cluster);
    return new AggregationOptions<>(requirements.minMonitoredPartitionsPercentage(),
                                    0.0,
                                    requirements.minRequiredNumWindows(),
                                    _maxAllowedExtrapolationsPerPartition,
                                    allPartitions,
                                    AggregationOptions.Granularity.ENTITY,
                                    requirements.includeAllTopics());
  }

}
