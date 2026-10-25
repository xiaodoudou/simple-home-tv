package com.simplehometv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * The user's pinned tiles, kept as an ordered comma-separated list of tile ids. Pure logic with no Android
 * dependencies, so it is covered by unit tests ({@code PinsTest}).
 */
public final class Pins {

    private final List<String> ids = new ArrayList<>();

    public Pins(String saved) {
        if (saved != null) {
            for (String raw : saved.split(",")) {
                String id = raw.trim();
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
        }
    }

    public boolean isPinned(String id) {
        return ids.contains(id);
    }

    /** Pins an id if absent, unpins it if present. */
    public void toggle(String id) {
        if (!ids.remove(id)) ids.add(id);
    }

    public void pin(String id) {
        if (!ids.contains(id)) ids.add(id);
    }

    public void unpin(String id) {
        ids.remove(id);
    }

    public List<String> ids() {
        return new ArrayList<>(ids);
    }

    public int count() {
        return ids.size();
    }

    public String serialize() {
        return String.join(",", ids);
    }

    /** Returns {@code items} with the pinned ones first (in pin order), the rest keeping their original order. */
    public <T> List<T> order(List<T> items, Function<T, String> idOf) {
        List<T> pinned = new ArrayList<>();
        List<T> rest = new ArrayList<>();
        for (T item : items) {
            if (isPinned(idOf.apply(item))) pinned.add(item);
            else rest.add(item);
        }
        pinned.sort(Comparator.comparingInt(item -> ids.indexOf(idOf.apply(item))));
        List<T> out = new ArrayList<>(pinned);
        out.addAll(rest);
        return out;
    }
}
