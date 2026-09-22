package dev.nickyllo.betterbuild.core.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The schematics the Architect can learn from and build with.
 *
 * <p>Pointed at Litematica's own {@code schematics} folder, so anything a player has
 * saved or downloaded for Litematica is automatically something he can study,
 * reference and place — no importing, no second copy.
 */
public final class SchematicLibrary {

    /** Files beyond this size on disk are skipped before being read. */
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    /** Total blocks held across the library, so a folder of city maps cannot eat the heap. */
    private static final long MAX_TOTAL_VOLUME = 16L * 1024 * 1024;
    private static final int MAX_DEPTH = 4;

    public record Failure(String file, String reason) {
    }

    private final Map<String, Schematic> byName;
    private final List<Failure> failures;

    private SchematicLibrary(Map<String, Schematic> byName, List<Failure> failures) {
        this.byName = byName;
        this.failures = List.copyOf(failures);
    }

    public static SchematicLibrary empty() {
        return new SchematicLibrary(new LinkedHashMap<>(), List.of());
    }

    public static SchematicLibrary of(Collection<Schematic> schematics) {
        Map<String, Schematic> map = new LinkedHashMap<>();
        for (Schematic s : schematics) map.put(key(s.name()), s);
        return new SchematicLibrary(map, List.of());
    }

    /**
     * Loads every supported file under {@code dir}. A bad file is recorded with its
     * reason and skipped; it never stops the rest from loading.
     */
    public static SchematicLibrary load(Path dir) {
        Map<String, Schematic> map = new LinkedHashMap<>();
        List<Failure> failures = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return new SchematicLibrary(map, failures);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir, MAX_DEPTH)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(f -> SchematicFiles.isSupported(f.getFileName().toString()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            failures.add(new Failure(dir.toString(), "no se pudo leer la carpeta: " + e.getMessage()));
            return new SchematicLibrary(map, failures);
        }

        long volume = 0;
        for (Path f : files) {
            String shown = dir.relativize(f).toString();
            try {
                if (Files.size(f) > MAX_FILE_BYTES) {
                    failures.add(new Failure(shown, "más de 16 MB en disco"));
                    continue;
                }
                Schematic s = SchematicFiles.read(f);
                long v = (long) s.sizeX() * s.sizeY() * s.sizeZ();
                if (volume + v > MAX_TOTAL_VOLUME) {
                    failures.add(new Failure(shown, "la biblioteca ya ocupa demasiada memoria"));
                    continue;
                }
                volume += v;
                map.putIfAbsent(key(s.name()), s);
            } catch (IOException | RuntimeException e) {
                failures.add(new Failure(shown, e.getMessage()));
            }
        }
        return new SchematicLibrary(map, failures);
    }

    /** By file name without extension; case, accents, spaces and underscores ignored. */
    public Optional<Schematic> get(String name) {
        return Optional.ofNullable(byName.get(key(name)));
    }

    public List<Schematic> all() {
        return List.copyOf(byName.values());
    }

    public List<String> names() {
        return all().stream().map(Schematic::name).toList();
    }

    public List<Failure> failures() {
        return failures;
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * The schematics most worth showing the model for this request: ones whose name
     * shares words with what the player asked for first ("torre" finds
     * "torre_medieval"), then building-sized ones, so the examples are always
     * buildings rather than a single tree or an entire city.
     */
    public List<Schematic> relevantTo(String prompt, int limit) {
        Set<String> wanted = words(prompt);
        return byName.values().stream()
                .sorted(Comparator
                        .comparingInt((Schematic s) -> -overlap(wanted, words(s.name())))
                        .thenComparingLong(s -> Math.abs(s.blockCount() - 1500)))
                .filter(s -> s.blockCount() >= 20)
                .limit(limit)
                .toList();
    }

    private static int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String w : b) if (a.contains(w)) n++;
        return n;
    }

    /** Lower-case, accent-free, camelCase split; every alphanumeric run in order. */
    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        for (String t : plain.split("[^a-z0-9]+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Words worth matching on: short ones like "de" or "1" say nothing about a building. */
    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String t : tokens(text)) {
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }

    /**
     * Every token kept, in order, so "casa 1" and "casa 2" stay two schematics while
     * "Casa_Grande" and "casa grande" are the same one.
     */
    private static String key(String name) {
        List<String> t = tokens(name);
        return t.isEmpty() ? name.toLowerCase(Locale.ROOT) : String.join("_", t);
    }
}
