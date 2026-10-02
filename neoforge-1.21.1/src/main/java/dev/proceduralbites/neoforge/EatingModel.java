package dev.proceduralbites.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import org.jetbrains.annotations.Nullable;

/** El modelo original hasta el primer umbral y después cada fotograma; los overrides del original siguen antes. */
final class EatingModel extends BakedModelWrapper<BakedModel> {
    static final float[] THRESHOLDS = {0.25f, 0.5f, 0.75f};

    private final ItemOverrides overrides;

    EatingModel(BakedModel original, List<BakedModel> frames) {
        super(original);
        ItemOverrides inner = original.getOverrides();
        this.overrides = new ItemOverrides() {
            @Override
            public @Nullable BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level,
                    @Nullable LivingEntity entity, int seed) {
                float progress = ProceduralBites.eatProgress(stack, entity);
                for (int i = THRESHOLDS.length - 1; i >= 0; i--) {
                    if (progress >= THRESHOLDS[i]) {
                        return frames.get(i);
                    }
                }
                return inner.resolve(model, stack, level, entity, seed);
            }
        };
    }

    @Override
    public ItemOverrides getOverrides() {
        return overrides;
    }

    /** Un fotograma con las transformaciones, la luz y el tipo de render del original; la capa i lleva el tinte i. */
    static final class Frame extends BakedModelWrapper<BakedModel> {
        private final List<BakedQuad> quads;

        Frame(BakedModel original, List<TextureAtlasSprite> layers) {
            super(original);
            List<BakedQuad> out = new ArrayList<>();
            for (int i = 0; i < layers.size(); i++) {
                TextureAtlasSprite sprite = layers.get(i);
                out.addAll(UnbakedGeometryHelper.bakeElements(
                        UnbakedGeometryHelper.createUnbakedItemElements(i, sprite), material -> sprite,
                        BlockModelRotation.X0_Y0));
            }
            this.quads = List.copyOf(out);
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return side == null ? quads : List.of();
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                ModelData data, @Nullable RenderType renderType) {
            return getQuads(state, side, rand);
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }

        // BakedModelWrapper devolvería el modelo original en estos dos: se dibujarían sus quads
        @Override
        public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean leftHand) {
            originalModel.applyTransform(context, poseStack, leftHand);
            return this;
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
            return List.of(this);
        }
    }
}
