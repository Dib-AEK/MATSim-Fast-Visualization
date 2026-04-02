package com.matsim.viz.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlaybackController {
    private final SimulationModel model;
    private final double startTime;
    private final double endTime;

    private final Set<Integer> activeTraversalIndexes = new HashSet<>();
    private final Map<String, Integer> linkQueueCounts = new HashMap<>();
    private final List<PlaybackListener> listeners = new ArrayList<>();

    private boolean playing;
    private double speedMultiplier;
    private double currentTime;
    private int nextTransitionIndex;

    public PlaybackController(SimulationModel model, double startTime, double endTime, double speedMultiplier) {
        this.model = model;
        this.startTime = Math.max(startTime, model.minTime());
        this.endTime = Math.min(endTime, Math.max(model.maxTime(), this.startTime));
        this.speedMultiplier = Math.max(0.1, speedMultiplier);
        this.currentTime = this.startTime;
        seek(this.currentTime);
    }

    public void tick(double deltaSeconds) {
        if (!playing) {
            return;
        }

        double previousTime = currentTime;
        currentTime = Math.min(endTime, currentTime + deltaSeconds * speedMultiplier);
        applyTransitionsForward(previousTime, currentTime);

        if (currentTime >= endTime) {
            playing = false;
        }
        notifyListeners();
    }

    public void seek(double targetTime) {
        currentTime = Math.max(startTime, Math.min(endTime, targetTime));
        rebuildStateAt(currentTime);
        notifyListeners();
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        notifyListeners();
    }

    public void togglePlaying() {
        setPlaying(!playing);
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setSpeedMultiplier(double speedMultiplier) {
        this.speedMultiplier = Math.max(0.1, speedMultiplier);
    }

    public double getSpeedMultiplier() {
        return speedMultiplier;
    }

    public double getCurrentTime() {
        return currentTime;
    }

    public double getStartTime() {
        return startTime;
    }

    public double getEndTime() {
        return endTime;
    }

    public Set<Integer> getActiveTraversalIndexes() {
        return Collections.unmodifiableSet(activeTraversalIndexes);
    }

    public int getQueueCountForLink(String linkId) {
        return linkQueueCounts.getOrDefault(linkId, 0);
    }

    public Map<String, Integer> getLinkQueueCountsView() {
        return Collections.unmodifiableMap(linkQueueCounts);
    }

    public void addListener(PlaybackListener listener) {
        listeners.add(listener);
    }

    private void applyTransitionsForward(double fromTime, double toTime) {
        int transitionCount = model.transitionCount();
        while (nextTransitionIndex < transitionCount) {
            double transitionTime = model.transitionTime(nextTransitionIndex);
            if (transitionTime > toTime) {
                break;
            }
            if (transitionTime > fromTime) {
                applyTransition(nextTransitionIndex);
            }
            nextTransitionIndex++;
        }

        // Keep active set consistent in case of tiny time jumps around boundaries.
        activeTraversalIndexes.removeIf(index -> {
            double leave = model.traversalLeaveTime(index);
            double enter = model.traversalEnterTime(index);
            return leave <= currentTime || enter > currentTime;
        });
    }

    private void rebuildStateAt(double time) {
        activeTraversalIndexes.clear();
        linkQueueCounts.clear();
        nextTransitionIndex = 0;

        int transitionCount = model.transitionCount();
        while (nextTransitionIndex < transitionCount) {
            double transitionTime = model.transitionTime(nextTransitionIndex);
            if (transitionTime > time) {
                break;
            }
            applyTransition(nextTransitionIndex);
            nextTransitionIndex++;
        }

        activeTraversalIndexes.removeIf(index -> {
            double leave = model.traversalLeaveTime(index);
            double enter = model.traversalEnterTime(index);
            return leave <= time || enter > time;
        });
    }

    private void applyTransition(int transitionIndex) {
        boolean enter = model.transitionEnter(transitionIndex);
        int traversalIndex = model.transitionTraversalIndex(transitionIndex);
        String linkId = model.transitionLinkId(transitionIndex);

        if (enter) {
            activeTraversalIndexes.add(traversalIndex);
            linkQueueCounts.merge(linkId, 1, Integer::sum);
        } else {
            activeTraversalIndexes.remove(traversalIndex);
            linkQueueCounts.compute(linkId, (k, value) -> {
                if (value == null || value <= 1) {
                    return null;
                }
                return value - 1;
            });
        }
    }

    private void notifyListeners() {
        for (PlaybackListener listener : listeners) {
            listener.onPlaybackUpdated();
        }
    }
}
