package dev.proceduralbites.neoforge;

import com.google.common.base.Suppliers;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import dev.proceduralbites.core.BiteFrames;
import dev.proceduralbites.core.FrameCache;
import dev.proceduralbites.core.Hints;
import dev.proceduralbites.core.Potions;
import dev.proceduralbites.core.RgbaImage;
import dev.proceduralbites.core.Routes;
import dev.proceduralbites.core.Routes.Route;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;

/** Agrega al atlas los fotogramas de cada comida, generados a partir de su textura activa. */
final class BitesSpriteSource implements SpriteSource {
    static final BitesSpriteSource INSTANCE = new BitesSpriteSource();
    static final SpriteSourceType TYPE = new SpriteSourceType(MapCodec.unit(INSTANCE));

    /** {@code layers}: capas por fotograma (2 en las pociones). */
    record Planned(Consumables.Entry entry, int layers, int modelLayers, Supplier<BiteFrames.Result> frames) {
    }

    // lo último que planeó la recarga; lo lee ProceduralBites al hornear los modelos
    static volatile Map<ResourceLocation, Planned> planned = Map.of();
    static volatile Set<ResourceLocation> considered = Set.of();
    static volatile FrameCache cache = new FrameCache(null, "");
    static volatile long reloadStart;

    private BitesSpriteSource() {
    }

    static ResourceLocation frameSprite(ResourceLocation item, int frame, int layer) {
        return ResourceLocation.fromNamespaceAndPath(ProceduralBites.MODID,
                "bites/" + item.getNamespace() + "/" + item.getPath() + "_" + frame + "_" + layer);
    }

    @Override
    public void run(ResourceManager resourceManager, Output output) {
        long start = System.nanoTime();
        reloadStart = start;
        FrameCache frameCache = new FrameCache(Config.cache() ? ProceduralBites.cacheDir() : null,
                ProceduralBites.version());
        cache = frameCache;
        Map<ResourceLocation, Planned> next = new LinkedHashMap<>();
        Map<Route, Integer> counts = new EnumMap<>(Route.class);
        Map<ResourceLocation, ItemModels.Layers> containers = new HashMap<>();
        Map<ResourceLocation, Optional<BlockModel>> parents = new HashMap<>();
        int skipped = 0;
        int hinted = 0;
        // también las excluidas: sirven para reconocer el tazón que el mod repite en varias comidas
        Map<String, List<Template>> templates = new HashMap<>();
        List<Consumables.Entry> entries = Consumables.discover();
        Hints hints = HintFiles.load(resourceManager);
        long discovered = System.nanoTime();
        for (Consumables.Entry e : entries) {
            Route route = e.plan().route();
            if (route == Route.SKIP) {
                ProceduralBites.LOGGER.info("Se omite {}: {}", e.item(), e.plan().reason());
                skipped++;
                continue;
            }
            ItemModels.Layers layers = ItemModels.layers(resourceManager, e.item(), parents);
            Template self = null;
            if (layers.skipped() == null && route != Route.POTION) {
                List<Template> group = templates.computeIfAbsent(e.item().getNamespace(), ns -> new ArrayList<>());
                self = new Template(group.size(), Suppliers.memoize(() -> Flat.of(resourceManager, e.item(),
                        layers.textures())));
                group.add(self);
            }
            String excluded = Config.excluded(e.item());
            if (excluded != null) {
                ProceduralBites.LOGGER.info("Se omite {}: excluido en la configuración ({})", e.item(), excluded);
                skipped++;
                continue;
            }
            Hints.Type hint = hints.lookup(e.item().toString());
            if (hint == Hints.Type.NONE) {
                ProceduralBites.LOGGER.info("Se omite {}: pista none [{}]", e.item(),
                        e.container() == null ? route : route + " " + e.container());
                skipped++;
                continue;
            }
            if (route == Route.POTION && Config.disabled(BiteFrames.Kind.POTION)) {
                ProceduralBites.LOGGER.info("Se omite {}: pociones apagadas en la configuración", e.item());
                skipped++;
                continue;
            }
            if (route == Route.POTION && hint != null) {
                // una poción son dos capas por fotograma: las pistas de dibujo no aplican
                ProceduralBites.LOGGER.info("Pista {} de {} ignorada: es una poción", hint.key(), e.item());
                hint = null;
            }
            if (layers.skipped() != null) {
                ProceduralBites.LOGGER.info("Se omite {}: {}", e.item(), layers.skipped());
                skipped++;
                continue;
            }
            ItemModels.Layers empty = null;
            ItemModels.Layers glass = null;
            if (route == Route.CONTAINER) {
                empty = containers.computeIfAbsent(e.container(), c -> ItemModels.layers(resourceManager, c, parents));
                if (empty.skipped() != null && hint == null) {
                    ProceduralBites.LOGGER.info("Se omite {}: recipiente {}: {}", e.item(), e.container(), empty.skipped());
                    skipped++;
                    continue;
                }
            } else if (route.vessel() != null) {
                ResourceLocation vessel = ResourceLocation.parse(route.vessel());
                empty = containers.computeIfAbsent(vessel, c -> ItemModels.layers(resourceManager, c, parents));
                if (empty.skipped() != null) {
                    ProceduralBites.LOGGER.debug("Sin {} para buscar en {}: {}", vessel, e.item(), empty.skipped());
                    empty = null;
                }
                if (route == Route.GUESS_BOWL) {
                    glass = containers.computeIfAbsent(ResourceLocation.parse(Routes.GLASS_BOTTLE),
                            c -> ItemModels.layers(resourceManager, c, parents));
                    if (glass.skipped() != null) {
                        glass = null;
                    }
                }
            }
            if (route == Route.POTION && layers.textures().size() != 2) {
                ProceduralBites.LOGGER.info("Se omite {}: poción con {} capas (se esperaba overlay y botella)",
                        e.item(), layers.textures().size());
                skipped++;
                continue;
            }
            int perFrame = route == Route.POTION ? 2 : 1;
            ItemModels.Layers emptyLayers = empty;
            ItemModels.Layers glassLayers = glass;
            Hints.Type itemHint = hint;
            Template itemTemplate = self;
            List<Template> group = templates.getOrDefault(e.item().getNamespace(), List.of());
            // los proveedores de sprites corren en paralelo: el primero genera, los demás esperan
            Supplier<BiteFrames.Result> frames = Suppliers.memoize(
                    () -> generate(resourceManager, frameCache, e, itemHint, layers.textures(), itemTemplate,
                            emptyLayers, glassLayers, group));
            for (int i = 0; i < EatingModel.THRESHOLDS.length; i++) {
                for (int j = 0; j < perFrame; j++) {
                    int frame = i;
                    int layer = j;
                    ResourceLocation sprite = frameSprite(e.item(), i + 1, j);
                    output.add(sprite, loader -> sprite(sprite, frames.get(), frame, layer));
                }
            }
            next.put(e.item(), new Planned(e, perFrame, layers.textures().size(), frames));
            counts.merge(route, 1, Integer::sum);
            if (hint != null) {
                hinted++;
            }
        }
        planned = Map.copyOf(next);
        Set<ResourceLocation> seen = new HashSet<>();
        for (Consumables.Entry e : entries) {
            seen.add(e.item());
        }
        considered = Set.copyOf(seen);
        long end = System.nanoTime();
        ProceduralBites.LOGGER.info("Comidas descubiertas en {} ms (registro de ítems: {} ms, modelos: {} ms): "
                + "{} a generar {} ({} con pista), {} omitidas", (end - start) / 1_000_000,
                (discovered - start) / 1_000_000, (end - discovered) / 1_000_000, next.size(), counts, hinted, skipped);
    }

    @Override
    public SpriteSourceType type() {
        return TYPE;
    }

    private static BiteFrames.Result generate(ResourceManager resources, FrameCache frameCache, Consumables.Entry e,
            Hints.Type hint, List<ResourceLocation> textures, Template self, ItemModels.Layers empty,
            ItemModels.Layers glass, List<Template> group) {
        long start = System.nanoTime();
        Route route = e.plan().route();
        BiteFrames.Kind kind = route == Route.POTION ? BiteFrames.Kind.POTION
                : route == Route.CONTAINER || route == Route.GUESS_GLASS ? BiteFrames.Kind.DRINK : BiteFrames.Kind.SOLID;
        BiteFrames.Result result;
        try {
            result = null;
            if (hint == Hints.Type.SOLID) {
                result = frameCache.solid(full(resources, textures, self), e.item().getPath());
            } else if (hint == Hints.Type.TOP_CUP) {
                BiteFrames.Result cup = frameCache.topCup(full(resources, textures, self));
                if (cup.generated() || !BiteFrames.NO_SURFACE.equals(cup.skipped())) {
                    result = cup;
                }
            }
            if (result == null) {
                result = byRoute(resources, frameCache, e, textures, self, empty, glass, kind, group);
            }
        } catch (IOException | RuntimeException ex) {
            result = BiteFrames.Result.skip(kind, ex.getMessage() != null ? ex.getMessage() : ex.toString());
        }
        String how = (e.container() == null ? route.toString() : route + " " + e.container())
                + (hint == null ? "" : ", pista " + hint.key());
        if (result.generated()) {
            ProceduralBites.LOGGER.info("Fotogramas de {} ({}) listos en {} ms [{}]", e.item(), result.kind(),
                    (System.nanoTime() - start) / 1_000_000, how);
        } else {
            ProceduralBites.LOGGER.warn("Se omite {} ({}): {} [{}]", e.item(), result.kind(), result.skipped(), how);
        }
        return result;
    }

    private static BiteFrames.Result byRoute(ResourceManager resources, FrameCache frameCache, Consumables.Entry e,
            List<ResourceLocation> textures, Template self, ItemModels.Layers empty, ItemModels.Layers glass,
            BiteFrames.Kind kind, List<Template> group) throws IOException {
        Route route = e.plan().route();
        if (route == Route.POTION) {
            RgbaImage overlay = read(resources, textures.get(0));
            RgbaImage bottle = read(resources, textures.get(1));
            return frameCache.potion(bottle, overlay);
        }
        RgbaImage full = full(resources, textures, self);
        if (route == Route.CONTAINER) {
            if (empty.skipped() != null) {
                // con pista no se omitió al planear
                return BiteFrames.Result.skip(kind, "recipiente " + e.container() + ": " + empty.skipped());
            }
            return frameCache.container(full, flatten(resources, empty.textures()), false);
        }
        if (route.vessel() != null) {
            List<RgbaImage> stack = empty == null ? List.of() : vesselStack(resources, empty.textures());
            if (route == Route.GUESS_GLASS) {
                return frameCache.guessed(full, e.item().getPath(), true, List.of(), stack);
            }
            List<RgbaImage> bottles = glass == null ? List.of() : vesselStack(resources, glass.textures());
            return frameCache.guessed(full, e.item().getPath(), false, stack, bottles, othersOf(group, self));
        }
        return frameCache.solid(full, e.item().getPath());
    }

    /** Textura compuesta de una comida, leída una sola vez por recarga y compartida con las demás del mod. */
    private record Template(int index, Supplier<Flat> flat) {
    }

    private record Flat(RgbaImage image, Exception error) {
        static Flat of(ResourceManager resources, ResourceLocation item, List<ResourceLocation> textures) {
            try {
                return new Flat(flatten(resources, textures), null);
            } catch (IOException | RuntimeException ex) {
                ProceduralBites.LOGGER.debug("Textura de {} ilegible: {}", item, ex.toString());
                return new Flat(null, ex);
            }
        }

        RgbaImage get() throws IOException {
            if (error instanceof IOException io) {
                throw io;
            }
            if (error instanceof RuntimeException re) {
                throw re;
            }
            return image;
        }
    }

    private static RgbaImage full(ResourceManager resources, List<ResourceLocation> textures, Template self)
            throws IOException {
        return self != null ? self.flat().get().get() : flatten(resources, textures);
    }

    /** Cada comida empieza a leer desde su lugar en la lista, así las que piden a la vez no se esperan. */
    private static List<RgbaImage> othersOf(List<Template> group, Template self) {
        int n = group.size();
        for (int i = 1; i < n; i++) {
            group.get((self.index() + i) % n).flat().get();
        }
        List<RgbaImage> out = new ArrayList<>(n);
        for (Template t : group) {
            RgbaImage image = t.flat().get().image();
            if (t != self && image != null) {
                out.add(image);
            }
        }
        return out;
    }

    private static RgbaImage flatten(ResourceManager resources, List<ResourceLocation> textures) throws IOException {
        RgbaImage out = read(resources, textures.get(0));
        for (int i = 1; i < textures.size(); i++) {
            RgbaImage top = read(resources, textures.get(i));
            if (top.width != out.width || top.height != out.height) {
                throw new IOException("capas de distinto tamaño en el modelo: " + textures);
            }
            out = Potions.alphaComposite(out, top);
        }
        return out;
    }

    /**
     * Versiones del recipiente en la pila de recursos, de vanilla al pack activo: la comida de
     * un mod a 16x calza con el tazón vanilla aunque un pack lo cambie.
     */
    private static List<RgbaImage> vesselStack(ResourceManager resources, List<ResourceLocation> textures) {
        List<RgbaImage> out = new ArrayList<>();
        try {
            if (textures.size() != 1) {
                return List.of(flatten(resources, textures));
            }
            for (Resource version : resources.getResourceStack(TEXTURE_ID_CONVERTER.idToFile(textures.get(0)))) {
                try {
                    out.add(read(version, textures.get(0)));
                } catch (IOException | RuntimeException ex) {
                    ProceduralBites.LOGGER.debug("Versión de {} ilegible: {}", textures.get(0), ex.toString());
                }
            }
        } catch (IOException | RuntimeException ex) {
            ProceduralBites.LOGGER.debug("Recipiente {} ilegible: {}", textures, ex.toString());
        }
        return out;
    }

    private static RgbaImage read(ResourceManager resources, ResourceLocation texture) throws IOException {
        ResourceLocation file = TEXTURE_ID_CONVERTER.idToFile(texture);
        Optional<Resource> resource = resources.getResource(file);
        if (resource.isEmpty()) {
            throw new IOException("no existe " + file);
        }
        return read(resource.get(), texture);
    }

    private static RgbaImage read(Resource resource, ResourceLocation texture) throws IOException {
        if (resource.metadata().getSection(AnimationMetadataSection.SERIALIZER).isPresent()) {
            throw new IOException("textura animada " + texture);
        }
        try (InputStream in = resource.open(); NativeImage image = NativeImage.read(NativeImage.Format.RGBA, in)) {
            RgbaImage out = new RgbaImage(image.getWidth(), image.getHeight());
            for (int y = 0; y < out.height; y++) {
                for (int x = 0; x < out.width; x++) {
                    out.setAbgr(x, y, image.getPixelRGBA(x, y));
                }
            }
            return out;
        }
    }

    /** {@code null} si el ítem se omitió: el atlas lo descarta. */
    private static SpriteContents sprite(ResourceLocation id, BiteFrames.Result result, int frame, int layer) {
        if (!result.generated() || frame >= result.frames().size() || layer >= result.frames().get(frame).size()) {
            return null;
        }
        RgbaImage im = result.frames().get(frame).get(layer);
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, im.width, im.height, false);
        for (int y = 0; y < im.height; y++) {
            for (int x = 0; x < im.width; x++) {
                image.setPixelRGBA(x, y, im.abgr(x, y));
            }
        }
        return new SpriteContents(id, new FrameSize(im.width, im.height), image, ResourceMetadata.EMPTY);
    }
}
