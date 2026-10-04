package dev.offeringaltar.client;

import dev.offeringaltar.OfferingAltar;
import dev.offeringaltar.TitheCollector;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/**
 * Vanilla humanoid model with a hooded robe texture; size comes from the SCALE attribute.
 * Shared by the Collector and its lesser kin, which only differ in texture.
 */
public class TitheCollectorRenderer<T extends TitheCollector>
        extends HumanoidMobRenderer<T, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
    private final Identifier texture;

    public TitheCollectorRenderer(EntityRendererProvider.Context context, String textureName) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5f);
        this.texture = Identifier.fromNamespaceAndPath(OfferingAltar.MOD_ID, "textures/entity/" + textureName + ".png");
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return texture;
    }
}
