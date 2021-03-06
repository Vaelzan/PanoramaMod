package com.suppergerrie2.panorama;

import net.minecraft.client.MainWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.client.renderer.RenderSkybox;
import net.minecraft.client.renderer.RenderSkyboxCube;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Util;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Random;

import static com.suppergerrie2.panorama.Config.panoramaSaveFolder;

public class PanoramaClientEvents {

    public static final KeyBinding createPanoramaKey = new KeyBinding(PanoramaMod.MOD_ID + ".key.createPanorama",
                                                                      GLFW.GLFW_KEY_H,
                                                                      "key.categories." + PanoramaMod.MOD_ID);
    private static final Logger LOGGER = LogManager.getLogger();
    static HashMap<Path, DynamicTexture[]> skyboxTextureCache = new HashMap<>();

    static {
        ClientRegistry.registerKeyBinding(createPanoramaKey);
    }

    public PanoramaClientEvents() {
        MinecraftForge.EVENT_BUS.addListener(this::renderEvent);
        MinecraftForge.EVENT_BUS.addListener(this::cameraSetupEvent);
        MinecraftForge.EVENT_BUS.addListener(this::fovModifier);
        MinecraftForge.EVENT_BUS.addListener(this::inputEvent);
        MinecraftForge.EVENT_BUS.addListener(this::openMainMenu);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(Config::onModConfigEvent);

        panoramaSaveFolder = Minecraft.getInstance().gameDir.toPath().resolve("panoramas");
    }

    boolean makePanorama = false;
    long startTime = System.currentTimeMillis();
    Vector3f[] stages = new Vector3f[]{
            new Vector3f(0, 0, 0),
            new Vector3f(90, 0, 0),
            new Vector3f(180, 0, 0),
            new Vector3f(-90, 0, 0),
            new Vector3f(0, -90, 0),
            new Vector3f(0, 90, 0)
    };
    int stage = 0;


    private static void takeScreenshot(final int stage, final long time) {
        MainWindow window = Minecraft.getInstance().getMainWindow();
        final NativeImage screenshot = ScreenShotHelper
                .createScreenshot(window.getFramebufferWidth(), window.getFramebufferHeight(),
                                  Minecraft.getInstance().getFramebuffer());

        Util.func_240992_g_().execute(() -> {
            NativeImage squareScreenshot = null;
            try {
                Path panoramaFolder = panoramaSaveFolder.resolve(
                        String.format("%s", time));

                if (!panoramaFolder.toFile().exists() || !panoramaFolder.toFile().isDirectory()) {
                    if (!panoramaFolder.toFile().mkdirs()) {
                        throw new IOException(
                                String.format("Failed to create folder %s", panoramaFolder.toAbsolutePath()));
                    }
                }

                int width = screenshot.getWidth();
                int height = screenshot.getHeight();
                int x = 0;
                int y = 0;

                //Make it square!
                int size = Math.min(width, height);

                if (width > height) {
                    x = (width - height) / 2;
                } else {
                    y = (height - width) / 2;
                }

                squareScreenshot = new NativeImage(size, size, false);
                screenshot.resizeSubRectTo(x, y, size, size, squareScreenshot);

                Path path = panoramaFolder.resolve(String.format("panorama_%d.png", stage));

                LOGGER.info("Writing to {}", path.toAbsolutePath());
                squareScreenshot.write(path);

            } catch (Exception e) {
                LOGGER.error("Failed to save screenshot!");
                e.printStackTrace();
            } finally {
                screenshot.close();
                if (squareScreenshot != null) squareScreenshot.close();
            }
        });
    }

    /**
     * Get a random panorama from the {@link Config#panoramaSaveFolder}.
     * Panoramas are saved in the following format:
     * {@link Config#panoramaSaveFolder}/{unix timestamp}/panorama_%d.png
     * Where %d is a number between 0 and 5 (inclusive)
     * <p>
     * If no panorama is found null is returned
     *
     * @return A {@link DynamicTexture} array with size 6, or null if no panorama is found
     */
    @Nullable
    static DynamicTexture[] getRandomPanorama() {
        Random random = new Random();

        try {
            //Make sure the panorama save folder exists and create it if it doesnt
            if (!panoramaSaveFolder.toFile().exists()) {
                if (!panoramaSaveFolder.toFile().mkdirs()) {
                    LOGGER.error("Failed to create panorama save folder: {}", panoramaSaveFolder.toAbsolutePath());
                    return null;
                }
            }

            //Filter out any folders that dont have the needed images
            Path[] paths = Files.list(panoramaSaveFolder).filter(path -> {
                for (int i = 0; i < 6; i++) {
                    if (!path.resolve(String.format("panorama_%d.png", i)).toFile().exists()) {
                        return false;
                    }
                }
                return true;
            }).toArray(Path[]::new);

            //If no paths are remaining return null
            if (paths.length == 0) {
                return null;
            } else {
                //If there are paths choose a random one
                Path theChosenOne = paths[random.nextInt(paths.length)];

                //Check if the images are loaded already, and if not load them
                return skyboxTextureCache.computeIfAbsent(theChosenOne, (path) -> {

                    try {
                        DynamicTexture[] textures = new DynamicTexture[6];

                        for (int i = 0; i < textures.length; i++) {
                            InputStream stream = Files
                                    .newInputStream(path.resolve(String.format("panorama_%d.png", i)));
                            NativeImage image = NativeImage.read(stream);
                            textures[i] = new DynamicTexture(image);
                            image.close();
                            stream.close();
                        }

                        return textures;
                    } catch (Exception e) {
                        return null;
                    }
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Set a random panorama on the given {@link MainMenuScreen}.
     *
     * @param screen The screen to set the random panorama to, if null only the resources will be set and not the renderer itself
     */
    private void setRandomPanorama(@Nullable MainMenuScreen screen) {

        //If custom panoramas are disabled make sure the vanilla resources are set
        DynamicTexture[] textures = Config.useCustomPanorama ? getRandomPanorama() : null;
        MainMenuScreen.PANORAMA_RESOURCES = textures != null ? new RenderDynamicSkyboxCube(
                textures) : new RenderSkyboxCube(
                new ResourceLocation("textures/gui/title/background/panorama"));
        if (screen != null) screen.panorama = new RenderSkybox(MainMenuScreen.PANORAMA_RESOURCES);
    }

    public void openMainMenu(GuiOpenEvent event) {
        if (event.getGui() instanceof MainMenuScreen) {
            setRandomPanorama((MainMenuScreen) event.getGui());
        }
    }

    @SubscribeEvent
    void renderEvent(RenderWorldLastEvent event) {
        if (Minecraft.getInstance().world != null && makePanorama) {
            takeScreenshot(stage, startTime);

            stage++;

            makePanorama = stage < stages.length;
            Minecraft.getInstance().gameSettings.hideGUI = makePanorama;
        }
    }

    @SubscribeEvent
    void cameraSetupEvent(EntityViewRenderEvent.CameraSetup cameraSetup) {
        if (makePanorama) {
            Vector3f rotation = stages[stage];
            cameraSetup.setYaw(rotation.getX());
            cameraSetup.setPitch(rotation.getY());
            cameraSetup.setRoll(rotation.getZ());
        }
    }

    @SubscribeEvent
    void fovModifier(EntityViewRenderEvent.FOVModifier fovModifier) {
        if (makePanorama) {
            fovModifier.setFOV(90);
        }
    }

    @SubscribeEvent
    void inputEvent(InputEvent.KeyInputEvent event) {
        if (createPanoramaKey.isPressed() && !makePanorama) {
            Minecraft.getInstance().gameSettings.hideGUI = true;

            makePanorama = true;
            stage = 0;
            startTime = System.currentTimeMillis();
            LOGGER.info("Pressed create panorama key");
        }
    }
}
