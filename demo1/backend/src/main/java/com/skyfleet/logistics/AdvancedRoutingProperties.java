package com.skyfleet.logistics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AdvancedRoutingProperties {
    public static final String RULE_VERSION = "advanced-ground-routing/1.1.0";
    public final boolean technicalPreviewEnabled;
    public final boolean tutorial02Enabled;
    public final boolean advancedEnabled;
    public final long clickMergeMs;
    public final long minimumRequestIntervalMs;
    public final double requestsPerSecond;
    public final int requestBurst;
    public final double roadSnapMeters;
    public final double maximumExtraMeters;
    public final double maximumRemainingRatio;
    public final double paceHeadStartSeconds;
    public final double mandatoryNodeToleranceMeters;
    public final double pointerMovePixels;
    public final long clickBurstWindowMs;
    public final int clickBurstThreshold;
    public final long clickBurstSuppressionMs;
    public final double candidateMaximumLengthRatio;
    public final double candidateMaximumOverlapRate;
    public final double candidateOverlapBufferMeters;

    AdvancedRoutingProperties(boolean technicalPreviewEnabled, boolean tutorial02Enabled, boolean advancedEnabled,
                              long clickMergeMs, long minimumRequestIntervalMs, double requestsPerSecond,
                              int requestBurst, double roadSnapMeters, double maximumExtraMeters,
                              double maximumRemainingRatio, double paceHeadStartSeconds) {
        this(technicalPreviewEnabled, tutorial02Enabled, advancedEnabled, clickMergeMs, minimumRequestIntervalMs,
                requestsPerSecond, requestBurst, roadSnapMeters, maximumExtraMeters, maximumRemainingRatio,
                paceHeadStartSeconds, 30, 6, 2000, 5, 10000, 1.8, .85, 20);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdvancedRoutingProperties(
            @Value("${mission.advanced-routing.technical-preview-enabled:false}") boolean technicalPreviewEnabled,
            @Value("${mission.advanced-routing.tutorial-02-enabled:false}") boolean tutorial02Enabled,
            @Value("${mission.advanced-routing.enabled:false}") boolean advancedEnabled,
            @Value("${mission.advanced-routing.click-merge-ms:300}") long clickMergeMs,
            @Value("${mission.advanced-routing.minimum-request-interval-ms:600}") long minimumRequestIntervalMs,
            @Value("${mission.advanced-routing.requests-per-second:2}") double requestsPerSecond,
            @Value("${mission.advanced-routing.request-burst:3}") int requestBurst,
            @Value("${mission.advanced-routing.road-snap-meters:40}") double roadSnapMeters,
            @Value("${mission.advanced-routing.maximum-extra-meters:1500}") double maximumExtraMeters,
            @Value("${mission.advanced-routing.maximum-remaining-ratio:1.6}") double maximumRemainingRatio,
            @Value("${mission.advanced-routing.pace-head-start-seconds:18}") double paceHeadStartSeconds,
            @Value("${mission.advanced-routing.mandatory-node-tolerance-meters:30}") double mandatoryNodeToleranceMeters,
            @Value("${mission.advanced-routing.pointer-move-pixels:6}") double pointerMovePixels,
            @Value("${mission.advanced-routing.click-burst-window-ms:2000}") long clickBurstWindowMs,
            @Value("${mission.advanced-routing.click-burst-threshold:5}") int clickBurstThreshold,
            @Value("${mission.advanced-routing.click-burst-suppression-ms:10000}") long clickBurstSuppressionMs,
            @Value("${mission.advanced-routing.candidate-maximum-length-ratio:1.8}") double candidateMaximumLengthRatio,
            @Value("${mission.advanced-routing.candidate-maximum-overlap-rate:0.85}") double candidateMaximumOverlapRate,
            @Value("${mission.advanced-routing.candidate-overlap-buffer-meters:20}") double candidateOverlapBufferMeters) {
        this.technicalPreviewEnabled = technicalPreviewEnabled;
        this.tutorial02Enabled = tutorial02Enabled;
        this.advancedEnabled = advancedEnabled;
        this.clickMergeMs = Math.max(0, clickMergeMs);
        this.minimumRequestIntervalMs = Math.max(100, minimumRequestIntervalMs);
        this.requestsPerSecond = Math.max(.1, requestsPerSecond);
        this.requestBurst = Math.max(1, requestBurst);
        this.roadSnapMeters = Math.max(5, roadSnapMeters);
        this.maximumExtraMeters = Math.max(0, maximumExtraMeters);
        this.maximumRemainingRatio = Math.max(1, maximumRemainingRatio);
        this.paceHeadStartSeconds = Math.max(0, paceHeadStartSeconds);
        this.mandatoryNodeToleranceMeters = Math.max(1, mandatoryNodeToleranceMeters);
        this.pointerMovePixels = Math.max(1, pointerMovePixels);
        this.clickBurstWindowMs = Math.max(100, clickBurstWindowMs);
        this.clickBurstThreshold = Math.max(1, clickBurstThreshold);
        this.clickBurstSuppressionMs = Math.max(1000, clickBurstSuppressionMs);
        this.candidateMaximumLengthRatio = Math.max(1, candidateMaximumLengthRatio);
        this.candidateMaximumOverlapRate = Math.max(0, Math.min(1, candidateMaximumOverlapRate));
        this.candidateOverlapBufferMeters = Math.max(1, candidateOverlapBufferMeters);
    }

    public boolean anyAdvancedCapabilityEnabled() {
        return technicalPreviewEnabled || tutorial02Enabled || advancedEnabled;
    }
}
