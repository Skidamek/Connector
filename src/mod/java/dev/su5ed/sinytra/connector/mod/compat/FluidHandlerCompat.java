package dev.su5ed.sinytra.connector.mod.compat;

import dev.su5ed.sinytra.connector.loader.ConnectorEarlyLoader;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.fabricmc.fabric.impl.client.rendering.fluid.FluidRenderHandlerRegistryImpl;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class FluidHandlerCompat {
    public static final Map<Fluid, FluidType> FABRIC_FLUID_TYPES = new HashMap<>();
    public static final Map<ResourceLocation, FluidType> FABRIC_FLUID_TYPES_BY_NAME = new HashMap<>();

    public static void init(IEventBus bus) {
        initFabricFluidTypes();
        bus.addListener(FluidHandlerCompat::onRegisterFluids);
        bus.addListener(FluidHandlerCompat::onClientSetup);
    }

    private static void initFabricFluidTypes() {
        for (Map.Entry<ResourceKey<Fluid>, Fluid> entry : ForgeRegistries.FLUIDS.getEntries()) {
            // Allow Forge mods to access Fabric fluid properties
            ResourceKey<Fluid> key = entry.getKey();
            Fluid fluid = entry.getValue();
            if (ModList.get().getModContainerById(key.location().getNamespace()).map(c -> ConnectorEarlyLoader.isConnectorMod(c.getModId())).orElse(false)) {
                FluidRenderHandler renderHandler = FluidRenderHandlerRegistry.INSTANCE.get(fluid);
                if (renderHandler != null) {
                    FluidType type = new FabricFluidType(FluidType.Properties.create(), fluid, renderHandler);
                    FABRIC_FLUID_TYPES.put(fluid, type);
                    FABRIC_FLUID_TYPES_BY_NAME.put(key.location(), type);
                }
            }
        }
    }

    private static void onRegisterFluids(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.FLUID_TYPES, helper -> FABRIC_FLUID_TYPES_BY_NAME.forEach(helper::register));
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        // Use reflection to register forge handlers only to the "handlers" map and not "modHandlers"
        // This allows fabric mods to access render handlers for forge mods' fluids without them being
        // used for rendering fluids, as that should remain handled by forge
        Map<Fluid, FluidRenderHandler> registryHandlers = ObfuscationReflectionHelper.getPrivateValue(FluidRenderHandlerRegistryImpl.class, (FluidRenderHandlerRegistryImpl) FluidRenderHandlerRegistry.INSTANCE, "handlers");
        Map<FluidType, FluidRenderHandler> forgeHandlers = new HashMap<>();
        for (Map.Entry<ResourceKey<Fluid>, Fluid> entry : ForgeRegistries.FLUIDS.getEntries()) {
            Fluid fluid = entry.getValue();
            if (fluid != Fluids.EMPTY) {
                ResourceKey<Fluid> key = entry.getKey();
                if (!ConnectorEarlyLoader.isConnectorMod(key.location().getNamespace())) {
                    FluidRenderHandler handler = forgeHandlers.computeIfAbsent(fluid.getFluidType(), ForgeFluidRenderHandler::new);
                    registryHandlers.put(fluid, handler);
                }
            }
        }
    }

    private record ForgeFluidRenderHandler(FluidType fluidType) implements FluidRenderHandler {
        @Override
        public TextureAtlasSprite[] getFluidSprites(@Nullable BlockAndTintGetter view, @Nullable BlockPos pos, FluidState state) {
            TextureAtlasSprite[] forgeSprites = ForgeHooksClient.getFluidSprites(view, pos, state);
            return forgeSprites[2] == null ? Arrays.copyOfRange(forgeSprites, 0, 2) : forgeSprites;
        }

        @Override
        public int getFluidColor(@Nullable BlockAndTintGetter view, @Nullable BlockPos pos, FluidState state) {
            int color = IClientFluidTypeExtensions.of(this.fluidType).getTintColor(state, view, pos);
            return 0x00FFFFFF & color;
        }
    }

    private static class FabricFluidType extends FluidType {
        private final Fluid fluid;
        private final FluidRenderHandler renderHandler;
        private final Component name;

        public FabricFluidType(Properties properties, Fluid fluid, FluidRenderHandler renderHandler) {
            super(properties);
            this.fluid = fluid;
            this.renderHandler = renderHandler;
            this.name = FluidVariantAttributes.getName(FluidVariant.of(fluid));
        }

        @Override
        public Component getDescription() {
            return this.name;
        }

        @Override
        public Component getDescription(FluidStack stack) {
            return getDescription();
        }

        @Override
        public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
            consumer.accept(new IClientFluidTypeExtensions() {
                private TextureAtlasSprite[] getSprites() {
                    return renderHandler.getFluidSprites(null, null, fluid.defaultFluidState());
                }
                
                @Override
                public ResourceLocation getStillTexture() {
                    TextureAtlasSprite[] sprites = getSprites();
                    return sprites[0].contents().name();
                }

                @Override
                public ResourceLocation getFlowingTexture() {
                    TextureAtlasSprite[] sprites = getSprites();
                    return sprites[1].contents().name();
                }

                @Nullable
                @Override
                public ResourceLocation getOverlayTexture() {
                    TextureAtlasSprite[] sprites = getSprites();
                    return sprites.length > 2 ? sprites[2].contents().name() : null;
                }

                @Override
                public int getTintColor() {
                    int baseColor = renderHandler.getFluidColor(null, null, fluid.defaultFluidState());
                    return 0xFF000000 | baseColor;
                }

                @Override
                public int getTintColor(FluidState state, BlockAndTintGetter getter, BlockPos pos) {
                    int baseColor = renderHandler.getFluidColor(getter, pos, state);
                    return 0xFF000000 | baseColor;
                }
            });
        }
    }

    private FluidHandlerCompat() {}
}
