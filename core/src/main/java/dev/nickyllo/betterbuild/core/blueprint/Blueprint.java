package dev.nickyllo.betterbuild.core.blueprint;

import dev.nickyllo.betterbuild.core.geom.Box;
import java.util.List;

/**
 * A complete design, expressed in primitives and relative to its own origin
 * (0,0,0 = the box's minimum corner). Placing it in the world is a translation,
 * which is why the same blueprint can be previewed, moved and rebuilt for free.
 *
 * @param name        human-readable, shown by the Architect when he talks about it
 * @param size        the design's own bounding box, origin-anchored
 * @param palette     block roles used by every element
 * @param elements    applied in order; later elements overwrite earlier ones
 */
public record Blueprint(String name, Box size, Palette palette, List<Element> elements) {

    public Blueprint {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("blueprint needs a name");
        }
        elements = List.copyOf(elements);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private Box size = Box.atOrigin(16, 16, 16);
        private Palette palette = Palette.defaultOak();
        private final List<Element> elements = new java.util.ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder size(int x, int y, int z) {
            this.size = Box.atOrigin(x, y, z);
            return this;
        }

        public Builder palette(Palette palette) {
            this.palette = palette;
            return this;
        }

        public Builder add(Element element) {
            elements.add(element);
            return this;
        }

        public Blueprint build() {
            return new Blueprint(name, size, palette, elements);
        }
    }
}
