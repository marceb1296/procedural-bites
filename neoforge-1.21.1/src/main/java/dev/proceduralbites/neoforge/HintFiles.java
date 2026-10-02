package dev.proceduralbites.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.proceduralbites.core.Hints;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Lee los {@code assets/<ns>/proceduralbites/hints.json} de la pila de recursos; un pack activo gana a lo de abajo. */
final class HintFiles {
    static final String PATH = ProceduralBites.MODID + "/hints.json";

    private HintFiles() {
    }

    private record Found(int pack, String namespace, Resource resource) {
    }

    static Hints load(ResourceManager resources) {
        Map<String, Integer> order = new HashMap<>();
        List<PackResources> packs = resources.listPacks().toList();
        for (int i = 0; i < packs.size(); i++) {
            order.putIfAbsent(packs.get(i).packId(), i);
        }
        List<Found> found = new ArrayList<>();
        for (String namespace : resources.getNamespaces()) {
            ResourceLocation file = ResourceLocation.fromNamespaceAndPath(namespace, PATH);
            for (Resource r : resources.getResourceStack(file)) {
                found.add(new Found(order.getOrDefault(r.sourcePackId(), -1), namespace, r));
            }
        }
        // en el mismo pack (los recursos de los mods pueden venir juntos), el del mod va abajo
        found.sort(Comparator.comparingInt(Found::pack)
                .thenComparing(f -> !f.namespace().equals(ProceduralBites.MODID))
                .thenComparing(Found::namespace));
        List<Map<String, List<String>>> layers = new ArrayList<>();
        for (Found f : found) {
            try (Reader in = f.resource().openAsReader()) {
                layers.add(parse(JsonParser.parseReader(in)));
            } catch (Exception ex) {
                ProceduralBites.LOGGER.warn("Pistas ilegibles en {}:{} del pack {}: {}", f.namespace(), PATH,
                        f.resource().sourcePackId(), ex.toString());
            }
        }
        Hints hints = new Hints(layers);
        ProceduralBites.LOGGER.info("Pistas: {} patrones en {} archivos", hints.size(), layers.size());
        return hints;
    }

    /** Lo que no es una lista de textos bajo un tipo conocido se ignora. */
    static Map<String, List<String>> parse(JsonElement json) {
        Map<String, List<String>> layer = new HashMap<>();
        if (!json.isJsonObject()) {
            return layer;
        }
        JsonObject object = json.getAsJsonObject();
        for (Hints.Type type : Hints.Type.values()) {
            JsonElement value = object.get(type.key());
            if (value == null || !value.isJsonArray()) {
                continue;
            }
            List<String> patterns = new ArrayList<>();
            JsonArray array = value.getAsJsonArray();
            for (JsonElement e : array) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    patterns.add(e.getAsString());
                }
            }
            layer.put(type.key(), patterns);
        }
        return layer;
    }
}
