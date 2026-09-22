package dev.nickyllo.betterbuild.core.design;

import dev.nickyllo.betterbuild.core.blueprint.BlockRef;
import dev.nickyllo.betterbuild.core.blueprint.Blueprint;
import dev.nickyllo.betterbuild.core.blueprint.Element;
import dev.nickyllo.betterbuild.core.blueprint.Palette;
import dev.nickyllo.betterbuild.core.blueprint.PaletteSlot;
import dev.nickyllo.betterbuild.core.geom.Box;
import dev.nickyllo.betterbuild.core.geom.Direction;
import dev.nickyllo.betterbuild.core.geom.Vec3i;
import dev.nickyllo.betterbuild.core.schematic.Schematic;
import dev.nickyllo.betterbuild.core.schematic.SchematicLibrary;

import java.util.Locale;

/**
 * Converts what the model returned into the internal model, clamping everything to
 * the plot on the way in.
 *
 * <p>This is the trust boundary. Nothing from the model is taken at face value: sizes
 * are clamped, unknown enum names are rejected, and any element that would leave the
 * plot is pulled back inside it. A malformed design fails here, before compilation,
 * where the failure is cheap and explainable.
 */
public final class BlueprintMapper {

    private final SchematicLibrary library;

    public BlueprintMapper() {
        this(SchematicLibrary.empty());
    }

    /** With a library, MODULE elements can name the schematics in it. */
    public BlueprintMapper(SchematicLibrary library) {
        this.library = library;
    }

    public Blueprint toBlueprint(BlueprintDto dto, Box plot) throws DesignProvider.DesignException {
        if (dto == null || dto.elements() == null || dto.elements().isEmpty()) {
            throw new DesignProvider.DesignException("the design came back empty");
        }

        Box localPlot = Box.atOrigin(plot.sizeX(), plot.sizeY(), plot.sizeZ());
        Blueprint.Builder builder = Blueprint.named(
                        dto.name() == null || dto.name().isBlank() ? "Building" : dto.name())
                .size(plot.sizeX(), plot.sizeY(), plot.sizeZ())
                .palette(toPalette(dto.palette()));

        int kept = 0;
        for (BlueprintDto.ElementDto e : dto.elements()) {
            Element element = toElement(e, localPlot);
            if (element != null) {
                builder.add(element);
                kept++;
            }
        }
        if (kept == 0) {
            throw new DesignProvider.DesignException("none of the design's elements could be used");
        }
        return builder.build();
    }

    private Palette toPalette(BlueprintDto.PaletteDto p) {
        if (p == null) {
            return Palette.defaultOak();
        }
        Palette.Builder b = Palette.builder();
        put(b, PaletteSlot.FOUNDATION, p.foundation());
        put(b, PaletteSlot.WALL, p.wall());
        put(b, PaletteSlot.BEAM, p.beam());
        put(b, PaletteSlot.FLOOR, p.floor());
        put(b, PaletteSlot.ROOF, p.roof());
        put(b, PaletteSlot.ROOF_EDGE, p.roofEdge());
        put(b, PaletteSlot.WINDOW, p.window());
        put(b, PaletteSlot.DOOR, p.door());
        put(b, PaletteSlot.LIGHT, p.light());
        put(b, PaletteSlot.ACCENT, p.accent());
        return b.build();
    }

    /** A blank or missing id simply leaves the slot on its default. */
    private void put(Palette.Builder b, PaletteSlot slot, String id) {
        if (id != null && !id.isBlank()) {
            b.set(slot, BlockRef.of(id.trim()));
        }
    }

    private Element toElement(BlueprintDto.ElementDto e, Box plot)
            throws DesignProvider.DesignException {
        String kind = require(e.kind(), "element kind").toUpperCase(Locale.ROOT);
        Box area = clamp(toBox(e.area()), plot);

        return switch (kind) {
            case "SOLID_FILL" -> new Element.SolidFill(area, slot(e.slot(), PaletteSlot.WALL));
            case "FLOOR_SLAB" -> new Element.FloorSlab(area, slot(e.slot(), PaletteSlot.FLOOR));
            case "PERIMETER_WALL" -> new Element.PerimeterWall(
                    area, slot(e.slot(), PaletteSlot.WALL), atLeast(e.thickness(), 1));
            case "PILLAR" -> new Element.Pillar(
                    area.min(), atLeast(e.height(), 1), slot(e.slot(), PaletteSlot.BEAM));
            case "CORNER_POSTS" -> new Element.CornerPosts(area, slot(e.slot(), PaletteSlot.BEAM));
            case "GABLE_ROOF" -> new Element.GableRoof(
                    area,
                    "Z".equalsIgnoreCase(e.ridgeAxis()) ? Element.Axis.Z : Element.Axis.X,
                    atLeast(e.pitch(), 1),
                    Math.max(0, e.overhang()),
                    slot(e.slot(), PaletteSlot.ROOF));
            case "FLAT_ROOF" -> new Element.FlatRoof(
                    area, Math.max(0, e.overhang()), slot(e.slot(), PaletteSlot.ROOF));
            case "OPENING" -> new Element.Opening(
                    area,
                    face(e.face()),
                    Math.max(0, e.offset()),
                    atLeast(e.width(), 1),
                    atLeast(e.openingHeight(), 1),
                    Math.max(0, e.sill()),
                    openingKind(e.openingKind()));
            case "MARKER" -> new Element.Marker(area.min(), slot(e.slot(), PaletteSlot.LIGHT));
            case "MODULE" -> module(e, area.min(), plot);
            default -> throw new DesignProvider.DesignException("unknown element kind: " + kind);
        };
    }

    /**
     * Resolves a module by name and pulls it inside the plot. A name the library does
     * not have, or a module bigger than the plot, drops that one element rather than
     * the whole design: a missing porch is a smaller loss than no house.
     */
    private Element module(BlueprintDto.ElementDto e, Vec3i at, Box plot) {
        if (e.module() == null) {
            return null;
        }
        Schematic s = library.get(e.module()).orElse(null);
        if (s == null) {
            return null;
        }
        int rotation = Math.floorMod(e.rotation(), 4);
        boolean turned = rotation % 2 == 1;
        int sx = turned ? s.sizeZ() : s.sizeX();
        int sz = turned ? s.sizeX() : s.sizeZ();
        if (sx > plot.sizeX() || s.sizeY() > plot.sizeY() || sz > plot.sizeZ()) {
            return null;
        }
        Vec3i fitted = new Vec3i(
                Math.min(at.x(), plot.max().x() - sx + 1),
                Math.min(at.y(), plot.max().y() - s.sizeY() + 1),
                Math.min(at.z(), plot.max().z() - sz + 1));
        return new Element.Module(s, fitted, rotation);
    }

    private Box toBox(BlueprintDto.BoxDto b) throws DesignProvider.DesignException {
        if (b == null) {
            throw new DesignProvider.DesignException("an element has no area");
        }
        return Box.between(new Vec3i(b.x1(), b.y1(), b.z1()), new Vec3i(b.x2(), b.y2(), b.z2()));
    }

    /** Pulls a region back inside the plot rather than rejecting the whole design. */
    private Box clamp(Box box, Box plot) {
        return new Box(
                new Vec3i(
                        clamp(box.min().x(), plot.min().x(), plot.max().x()),
                        clamp(box.min().y(), plot.min().y(), plot.max().y()),
                        clamp(box.min().z(), plot.min().z(), plot.max().z())),
                new Vec3i(
                        clamp(box.max().x(), plot.min().x(), plot.max().x()),
                        clamp(box.max().y(), plot.min().y(), plot.max().y()),
                        clamp(box.max().z(), plot.min().z(), plot.max().z())));
    }

    /** Java 17 has no Math.clamp, and the core targets 17 for Minecraft 1.20.x. */
    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private PaletteSlot slot(String name, PaletteSlot fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return PaletteSlot.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private Direction face(String name) throws DesignProvider.DesignException {
        try {
            Direction d = Direction.valueOf(require(name, "opening face").trim().toUpperCase(Locale.ROOT));
            if (!d.isHorizontal()) {
                throw new DesignProvider.DesignException("openings must face a wall, not " + d);
            }
            return d;
        } catch (IllegalArgumentException ex) {
            throw new DesignProvider.DesignException("unknown face: " + name);
        }
    }

    private Element.OpeningKind openingKind(String name) {
        if (name == null) {
            return Element.OpeningKind.VOID;
        }
        try {
            return Element.OpeningKind.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Element.OpeningKind.VOID;
        }
    }

    private int atLeast(int value, int min) {
        return Math.max(min, value);
    }

    private String require(String value, String what) throws DesignProvider.DesignException {
        if (value == null || value.isBlank()) {
            throw new DesignProvider.DesignException("missing " + what);
        }
        return value;
    }
}
