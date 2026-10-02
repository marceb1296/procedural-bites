package dev.proceduralbites.neoforge;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemOverride;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;

/**
 * Texturas de un ítem leídas de su JSON: la fuente de sprites corre en paralelo con la
 * carga de modelos y no puede usarlos. Solo sirven los modelos planos generados desde capas;
 * los que traen overrides sobre {@link #EAT_PROPERTIES} se ceden (fotogramas propios del pack).
 */
final class ItemModels {
    private ItemModels() {
    }

    static final Set<ResourceLocation> EAT_PROPERTIES = Stream.concat(Stream.of(ProceduralBites.EAT_PROGRESS),
            ProceduralBites.EATING_ANIMATION.values().stream().flatMap(List::stream)).collect(Collectors.toUnmodifiableSet());

    private static final int MAX_DEPTH = 32;

    record Layers(List<ResourceLocation> textures, String skipped) {
        static Layers skip(String why) {
            return new Layers(List.of(), why);
        }
    }

    /** @param parents padres ya leídos en esta recarga (vacío = no existe): casi todas las comidas comparten uno. */
    static Layers layers(ResourceManager resources, ResourceLocation item, Map<ResourceLocation, Optional<BlockModel>> parents) {
        try {
            return resolve(resources, item.withPrefix("item/"), parents);
        } catch (IOException | RuntimeException e) {
            return Layers.skip("modelo ilegible: " + e);
        }
    }

    private static Layers resolve(ResourceManager resources, ResourceLocation location,
            Map<ResourceLocation, Optional<BlockModel>> parents) throws IOException {
        Optional<BlockModel> top = read(resources, location);
        if (top.isEmpty()) {
            return Layers.skip("no existe el modelo " + location);
        }
        Set<ResourceLocation> seen = new HashSet<>();
        seen.add(location);
        BlockModel model = top.get();
        for (int depth = 0; model.getParentLocation() != null; depth++) {
            ResourceLocation parent = model.getParentLocation();
            if (depth >= MAX_DEPTH || !seen.add(parent)) {
                return Layers.skip("cadena de padres circular o demasiado larga en " + location);
            }
            String path = parent.getPath();
            if (path.equals("builtin/generated")) {
                model.parent = ModelBakery.GENERATION_MARKER;
                break;
            }
            if (path.startsWith("builtin/")) {
                return Layers.skip("modelo " + parent + " (renderizado propio)");
            }
            Optional<BlockModel> next = parents.get(parent);
            if (next == null) {
                // un padre ilegible lanza y no se guarda: cada ítem que lo use dirá el motivo
                next = read(resources, parent);
                parents.put(parent, next);
            }
            if (next.isEmpty()) {
                return Layers.skip("no existe el modelo padre " + parent);
            }
            model.parent = next.get();
            model = next.get();
        }
        BlockModel item = top.get();
        if (item.getRootModel() != ModelBakery.GENERATION_MARKER) {
            return Layers.skip("modelo de bloque o 3D (no hereda de builtin/generated)");
        }
        List<ResourceLocation> textures = new ArrayList<>();
        for (String layer : ItemModelGenerator.LAYERS) {
            // como ItemModelGenerator: las capas van seguidas desde layer0
            if (!item.hasTexture(layer)) {
                break;
            }
            textures.add(item.getMaterial(layer).texture());
        }
        if (textures.isEmpty()) {
            return Layers.skip("el modelo no tiene layer0");
        }
        for (ItemOverride override : item.getOverrides()) {
            Optional<ResourceLocation> eat = override.getPredicates().map(ItemOverride.Predicate::getProperty)
                    .filter(EAT_PROPERTIES::contains).findFirst();
            if (eat.isPresent()) {
                return Layers.skip("trae fotogramas propios (overrides con " + eat.get() + ")");
            }
        }
        return new Layers(List.copyOf(textures), null);
    }

    /** Vacío si no existe. Con {@code loader} se rechaza antes de deserializar: podría no estar registrado. */
    private static Optional<BlockModel> read(ResourceManager resources, ResourceLocation location) throws IOException {
        Optional<Resource> resource = resources.getResource(location.withPath(p -> "models/" + p + ".json"));
        if (resource.isEmpty()) {
            return Optional.empty();
        }
        JsonObject json;
        try (Reader reader = resource.get().openAsReader()) {
            json = GsonHelper.parse(reader);
        }
        if (json.has("loader")) {
            throw new IllegalStateException("usa el loader " + json.get("loader") + " (" + location + ")");
        }
        BlockModel model = BlockModel.fromString(json.toString());
        model.name = location.toString();
        return Optional.of(model);
    }
}
