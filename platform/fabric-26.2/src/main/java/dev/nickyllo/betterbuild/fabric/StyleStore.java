package dev.nickyllo.betterbuild.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.blueprint.Palette;
import dev.nickyllo.betterbuild.core.blueprint.PaletteSlot;
import dev.nickyllo.betterbuild.core.learn.StyleProfile;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Remembers what the Architect has been taught, across restarts.
 *
 * <p>A lesson that evaporates when you quit the game is not teaching. Profiles are
 * written to a small JSON file next to the config, flattened to plain strings and
 * numbers so the format stays readable and editable by hand.
 */
public final class StyleStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<String, Entry> styles = new LinkedHashMap<>();

    public StyleStore(Path file) {
        this.file = file;
        load();
    }

    /** Flat mirror of a StyleProfile — every field a string or a number. */
    private static final class Entry {
        String name;
        Map<String, String> palette;
        int wallHeight;
        int storeys;
        int roofPitch;
        int overhang;
        double windowRatio;
        int width;
        int depth;
    }

    public void put(StyleProfile profile) {
        Entry e = new Entry();
        e.name = profile.source();
        e.palette = new LinkedHashMap<>();
        for (PaletteSlot slot : PaletteSlot.values()) {
            e.palette.put(slot.name(), profile.palette().get(slot).id());
        }
        e.wallHeight = profile.wallHeight();
        e.storeys = profile.storeys();
        e.roofPitch = profile.roofPitch();
        e.overhang = profile.overhang();
        e.windowRatio = profile.windowRatio();
        e.width = profile.footprintWidth();
        e.depth = profile.footprintDepth();
        styles.put(profile.source().toLowerCase(), e);
        save();
    }

    public Optional<StyleProfile> get(String name) {
        Entry e = styles.get(name.toLowerCase());
        if (e == null) {
            return Optional.empty();
        }
        Palette.Builder p = Palette.builder();
        if (e.palette != null) {
            for (var slot : e.palette.entrySet()) {
                try {
                    p.set(PaletteSlot.valueOf(slot.getKey()), BlockRef.of(slot.getValue()));
                } catch (IllegalArgumentException ignored) {
                    // A slot renamed in a newer version: skip it, keep the rest.
                }
            }
        }
        return Optional.of(new StyleProfile(e.name, p.build(), e.wallHeight, e.storeys,
                e.roofPitch, e.overhang, e.windowRatio, e.width, e.depth, Map.of()));
    }

    /** Most recently taught, which is what an unqualified build should use. */
    public Optional<StyleProfile> latest() {
        String last = null;
        for (String key : styles.keySet()) {
            last = key;
        }
        return last == null ? Optional.empty() : get(last);
    }

    public Set<String> names() {
        return styles.keySet();
    }

    public boolean forget(String name) {
        boolean removed = styles.remove(name.toLowerCase()) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, Entry> read = GSON.fromJson(r,
                    new TypeToken<LinkedHashMap<String, Entry>>() { }.getType());
            if (read != null) {
                styles.putAll(read);
            }
        } catch (IOException | RuntimeException e) {
            // A corrupt file must not stop the mod loading; start from empty.
            styles.clear();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(styles, w);
            }
        } catch (IOException ignored) {
            // Losing a lesson is bad, crashing the server over it is worse.
        }
    }
}
