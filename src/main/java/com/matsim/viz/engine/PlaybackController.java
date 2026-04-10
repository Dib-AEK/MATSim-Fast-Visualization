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
    private final Object stateLock = new Object();

    private final Set<Integer> activeTraversalIndexes = new HashSet<>();
    private final Map<String, List<Integer>> activeTraversalIndexesByLink = new HashMap<>();
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
        synchronized (stateLock) {
            if (!playing) {
                return;
            }

            double previousTime = currentTime;
            currentTime = Math.min(endTime, currentTime + deltaSeconds * speedMultiplier);
            applyTransitionsForward(previousTime, currentTime);

            if (currentTime >= endTime) {
                playing = false;
            }
        }
        notifyListeners();
    }

    public void seek(double targetTime) {
        synchronized (stateLock) {
            currentTime = Math.max(startTime, Math.min(endTime, targetTime));
            rebuildStateAt(currentTime);
        }
        notifyListeners();
    }

    public void setPlaying(boolean playing) {
        synchronized (stateLock) {
            this.playing = playing;
        }
        notifyListeners();
    }

    public void togglePlaying() {
        setPlaying(!playing);
    }

    public boolean isPlaying() {
        synchronized (stateLock) {
            return playing;
        }
    }

    public void setSpeedMultiplier(double speedMultiplier) {
        synchronized (stateLock) {
            this.speedMultiplier = Math.max(0.1, speedMultiplier);
        }
    }

    public double getSpeedMultiplier() {
        synchronized (stateLock) {
            return speedMultiplier;
        }
    }

    public double getCurrentTime() {
        synchronized (stateLock) {
            return currentTime;
        }
    }

    public double getStartTime() {
        return startTime;
    }

    public double getEndTime() {
        return endTime;
    }

    public Set<Integer> getActiveTraversalIndexes() {
        synchronized (stateLock) {
            return Collections.unmodifiableSet(new HashSet<>(activeTraversalIndexes));
        }
    }

    public int getQueueCountForLink(String linkId) {
        synchronized (stateLock) {
            return linkQueueCounts.getOrDefault(linkId, 0);
        }
    }

    public List<Integer> getActiveTraversalIndexesForLink(String linkId) {
        synchronized (stateLock) {
            List<Integer> traversals = activeTraversalIndexesByLink.get(linkId);
            return traversals == null ? List.of() : List.copyOf(traversals);
        }
    }

    public Map<String, Integer> getLinkQueueCountsView() {
        synchronized (stateLock) {
            return Collections.unmodifiableMap(new HashMap<>(linkQueueCounts));
        }
    }

    public Map<String, LinkFrameSnapshot> snapshotLinkState(Set<String> linkIds) {
        Map<String, LinkFrameSnapshot> snapshot = new HashMap<>();
        synchronized (stateLock) {
            for (String linkId : linkIds) {
                List<Integer> traversals = activeTraversalIndexesByLink.get(linkId);
                int queueCount = linkQueueCounts.getOrDefault(linkId, 0);
                if ((traversals == null || traversals.isEmpty()) && queueCount <= 0) {
                    continue;
                }

                int[] traversalIndexes = traversals == null
                        ? new int[0]
                        : copyTraversalIndexes(traversals);
                snapshot.put(linkId, new LinkFrameSnapshot(traversalIndexes, queueCount));
            }
        }
        return snapshot;
    }

    public void addListener(PlaybackListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
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
    }

    private void rebuildStateAt(double time) {
        activeTraversalIndexes.clear();
        activeTraversalIndexesByLink.clear();
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
    }

    private void applyTransition(int transitionIndex) {
        boolean enter = model.transitionEnter(transitionIndex);
        int traversalIndex = model.transitionTraversalIndex(transitionIndex);
        String linkId = model.transitionLinkId(transitionIndex);

        if (enter) {
            activeTraversalIndexes.add(traversalIndex);
            activeTraversalIndexesByLink.computeIfAbsent(linkId, key -> new ArrayList<>()).add(traversalIndex);
            linkQueueCounts.merge(linkId, 1, Integer::sum);
        } else {
            activeTraversalIndexes.remove(traversalIndex);
            List<Integer> linkTraversals = activeTraversalIndexesByLink.get(linkId);
            if (linkTraversals != null) {
                linkTraversals.removeIf(index -> index == traversalIndex);
                if (linkTraversals.isEmpty()) {
                    activeTraversalIndexesByLink.remove(linkId);
                }
            }
            linkQueueCounts.compute(linkId, (k, value) -> {
                if (value == null || value <= 1) {
                    return null;
                }
                return value - 1;
            });
        }
    }

    private void notifyListeners() {
        List<PlaybackListener> snapshot;
        synchronized (listeners) {
            snapshot = List.copyOf(listeners);
        }
        for (PlaybackListener listener : snapshot) {
            listener.onPlaybackUpdated();
        }
    }

    private static int[] copyTraversalIndexes(List<Integer> traversals) {
        int[] copied = new int[traversals.size()];
        for (int i = 0; i < traversals.size(); i++) {
            copied[i] = traversals.get(i);
        }
        return copied;
    }

    public record LinkFrameSnapshot(int[] traversalIndexes, int queueCount) {
    }
}
