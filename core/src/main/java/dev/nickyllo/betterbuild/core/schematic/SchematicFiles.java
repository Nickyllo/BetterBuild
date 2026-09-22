package dev.nickyllo.betterbuild.core.schematic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Reads any supported schematic file and writes Litematica ones. */
public final class SchematicFiles {

    private SchematicFiles() {
    }

    public static boolean isSupported(String fileName) {
        String f = fileName.toLowerCase(Locale.ROOT);
        return f.endsWith(".litematic") || f.endsWith(".schem")
                || f.endsWith(".schematic") || f.endsWith(".nbt");
    }

    /** The name a player types to refer to a file: its name without extension. */
    public static String baseName(String fileName) {
        String f = Path.of(fileName).getFileName().toString();
        int dot = f.lastIndexOf('.');
        return dot > 0 ? f.substring(0, dot) : f;
    }

    public static Schematic read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, file.getFileName().toString());
        }
    }

    /**
     * Picks the format from the content, not the extension: {@code .schematic} is used
     * both by old MCEdit files and by some Sponge exporters, and people rename things.
     */
    public static Schematic read(InputStream in, String fileName) throws IOException {
        Nbt.Root root = Nbt.read(in);
        Map<String, Object> tag = root.tag();
        String name = baseName(fileName);

        if (tag.containsKey("Regions")) {
            return LitematicaFormat.read(tag, name);
        }
        if (tag.containsKey("Schematic") || tag.containsKey("BlockData")
                || (tag.containsKey("Palette") && tag.containsKey("Width"))) {
            return SpongeFormat.read(tag, name);
        }
        if (tag.containsKey("blocks") && (tag.containsKey("palette") || tag.containsKey("palettes"))) {
            return StructureFormat.read(tag, name);
        }
        if (tag.containsKey("Blocks") && tag.containsKey("Materials")) {
            throw new IOException("old MCEdit schematic (before 1.13) with numeric block ids; "
                    + "open it in Litematica or WorldEdit and save it again to convert it");
        }
        throw new IOException("not a schematic format BetterBuild knows");
    }

    public static void writeLitematic(Schematic s, Path file, String author, String description,
                                      int dataVersion) throws IOException {
        Map<String, Object> root = LitematicaFormat.write(s, author, description, dataVersion,
                System.currentTimeMillis());
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            Nbt.write(out, "", root);
        }
    }
}
