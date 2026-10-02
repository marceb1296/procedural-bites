package dev.proceduralbites.neoforge;

import com.mojang.logging.LogUtils;
import dev.proceduralbites.core.FrameCache;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import dev.proceduralbites.core.Routes;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;
import org.slf4j.Logger;

/** Procedural Bites: mod solo de cliente. */
@Mod(value = ProceduralBites.MODID, dist = Dist.CLIENT)
public final class ProceduralBites {
    public static final String MODID = "proceduralbites";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceLocation EAT_PROGRESS =
            ResourceLocation.fromNamespaceAndPath(MODID, "eat_progress");
    /**
     * Propiedades de Eating Animation, para los packs hechos para él: el original (Fabric)
     * usa {@code minecraft:eat}/{@code eating} y el fork de NeoForge, {@code eatinganimation:}.
     * Este mod registra las de cada uno si ese mod no está.
     */
    static final Map<String, List<ResourceLocation>> EATING_ANIMATION = Map.of(
            "eatinganimationid", List.of(ResourceLocation.withDefaultNamespace("eat"),
                    ResourceLocation.withDefaultNamespace("eating")),
            "eatinganimation", List.of(ResourceLocation.fromNamespaceAndPath("eatinganimation", "eat"),
                    ResourceLocation.fromNamespaceAndPath("eatinganimation", "eating")));

    private static final long CACHE_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000;

    private static volatile String version = "";

    /** Va en la clave de la caché: cada versión del mod genera sus fotogramas. */
    static String version() {
        return version;
    }

    static Path cacheDir() {
        return FMLPaths.GAMEDIR.get().resolve(".cache").resolve(MODID).resolve("frames");
    }

    public ProceduralBites(IEventBus modBus, ModContainer container) {
        version = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(ProceduralBites::onClientSetup);
        modBus.addListener(ProceduralBites::onRegisterSpriteSources);
        modBus.addListener(ProceduralBites::onModifyBakingResult);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        List<ResourceLocation[]> ours = new ArrayList<>();
        for (Map.Entry<String, List<ResourceLocation>> e : EATING_ANIMATION.entrySet()) {
            if (ModList.get().isLoaded(e.getKey())) {
                LOGGER.info("{} está cargado: registra él {}", e.getKey(), e.getValue());
            } else {
                ours.add(e.getValue().toArray(ResourceLocation[]::new));
            }
        }
        event.enqueueWork(() -> {
            int count = 0;
            for (Consumables.Entry e : Consumables.discover()) {
                if (e.plan().route() != Routes.Route.SKIP) {
                    Item item = BuiltInRegistries.ITEM.get(e.item());
                    ItemProperties.register(item, EAT_PROGRESS, (stack, level, entity, seed) -> eatProgress(stack, entity));
                    for (ResourceLocation[] pair : ours) {
                        ItemProperties.register(item, pair[0], (stack, level, entity, seed) -> eat(stack, entity));
                        ItemProperties.register(item, pair[1], (stack, level, entity, seed) ->
                                entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1f : 0f);
                    }
                    count++;
                }
            }
            LOGGER.info("Propiedades registradas para {} comidas: {} y {} pares de Eating Animation", count,
                    EAT_PROGRESS, ours.size());
        });
    }

    private static void onRegisterSpriteSources(RegisterSpriteSourceTypesEvent event) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MODID, "bites");
        event.register(id, BitesSpriteSource.TYPE);
        LOGGER.info("Fuente de sprites {} registrada", id);
    }

    private static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BitesSpriteSource.Planned> planned = BitesSpriteSource.planned;
        int wrapped = 0;
        for (BitesSpriteSource.Planned p : planned.values()) {
            ResourceLocation item = p.entry().item();
            ModelResourceLocation key = ModelResourceLocation.inventory(item);
            BakedModel original = event.getModels().get(key);
            if (original == null || original.isCustomRenderer() || original.isGui3d()) {
                LOGGER.info("Se omite {}: su modelo horneado no es un ítem plano", item);
                continue;
            }
            if (p.layers() == 1 && p.modelLayers() > 1 && tinted(original)) {
                // las capas se compusieron sin color: el fotograma no puede llevar el tinte de cada una
                LOGGER.info("Se omite {}: {} capas con tinte", item, p.modelLayers());
                continue;
            }
            if (!p.frames().get().generated()) {
                // sin pedir sus sprites: el atlas los descartó y NeoForge avisaría que faltan
                continue;
            }
            if (Config.disabled(p.frames().get().kind())) {
                LOGGER.info("Se omite {}: tipo {} apagado en la configuración", item, p.frames().get().kind());
                continue;
            }
            List<BakedModel> frames = new ArrayList<>();
            for (int i = 1; i <= EatingModel.THRESHOLDS.length && frames != null; i++) {
                List<TextureAtlasSprite> layers = new ArrayList<>();
                for (int j = 0; j < p.layers(); j++) {
                    ResourceLocation id = BitesSpriteSource.frameSprite(item, i, j);
                    TextureAtlasSprite sprite = event.getTextureGetter().apply(new Material(TextureAtlas.LOCATION_BLOCKS, id));
                    if (sprite == null || sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
                        frames = null;
                        break;
                    }
                    layers.add(sprite);
                }
                if (frames != null) {
                    frames.add(new EatingModel.Frame(original, layers));
                }
            }
            if (frames == null) {
                continue;
            }
            event.getModels().put(key, new EatingModel(original, frames));
            wrapped++;
            LOGGER.debug("Modelo de {} envuelto con {} fotogramas generados", item, frames.size());
        }
        LOGGER.info("Modelos envueltos con fotogramas generados: {} de {}", wrapped, planned.size());
        FrameCache cache = BitesSpriteSource.cache;
        LOGGER.info("Fotogramas: {} leídos de la caché y {} generados (suma de generación: {} ms); "
                + "{} ms desde que empezó la recarga", cache.hits(), cache.misses(), cache.generationMillis(),
                (System.nanoTime() - BitesSpriteSource.reloadStart) / 1_000_000);
        int removed = cache.save(CACHE_MAX_AGE_MILLIS);
        if (removed > 0) {
            LOGGER.info("Caché de fotogramas: {} entradas sin usar en 30 días borradas", removed);
        }
        if (cache.errors() > 0) {
            LOGGER.warn("Caché de fotogramas: {} fallos de disco (se generó igual); el último: {}", cache.errors(),
                    cache.lastError());
        }
        // un mod que agrega comida con ModifyDefaultComponentsEvent puede llegar después de la
        // primera carga de texturas (corre en paralelo): se anima desde la siguiente recarga
        for (Consumables.Entry e : Consumables.discover()) {
            if (e.plan().route() != Routes.Route.SKIP && !BitesSpriteSource.considered.contains(e.item())) {
                LOGGER.warn("{} se volvió comida después de cargar las texturas: se animará al recargar recursos (F3+T)",
                        e.item());
            }
        }
    }

    private static boolean tinted(BakedModel model) {
        for (BakedQuad q : model.getQuads(null, null, RandomSource.create(42L))) {
            if (q.isTinted()) {
                return true;
            }
        }
        return false;
    }

    /**
     * La propiedad {@code eat} de Eating Animation, con su misma fórmula: jugador propio,
     * ticks de uso / 30; otros jugadores, la fracción del uso (o ticks / 32 % 0,5 si dura 16 ticks o menos).
     */
    static float eat(ItemStack stack, LivingEntity entity) {
        if (entity == null || entity.getUseItem() != stack) {
            return 0f;
        }
        int total = stack.getUseDuration(entity);
        if (entity instanceof RemotePlayer) {
            if (total > 16) {
                return ((float) entity.getTicksUsingItem() / (float) total) % 1;
            }
            return ((float) entity.getTicksUsingItem() / 32f) % 0.5f;
        }
        return (total - entity.getUseItemRemainingTicks()) / 30f;
    }

    static float eatProgress(ItemStack stack, LivingEntity entity) {
        if (entity == null || !entity.isUsingItem() || entity.getUseItem() != stack) {
            return 0f;
        }
        int total = stack.getUseDuration(entity);
        if (total <= 0) {
            return 0f;
        }
        float used = total - entity.getUseItemRemainingTicks();
        return Math.min(1f, Math.max(0f, used / total));
    }
}
