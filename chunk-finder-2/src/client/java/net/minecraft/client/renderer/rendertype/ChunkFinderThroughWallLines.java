package net.minecraft.client.renderer.rendertype;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

/** A line render type with depth testing disabled for server-authorized highlights. */
public final class ChunkFinderThroughWallLines {
    private static final RenderType TYPE = create();

    private ChunkFinderThroughWallLines() { }

    public static RenderType renderType() { return TYPE; }

    private static RenderType create() {
        RenderPipeline.Snippet linesSnippet = findLinesSnippet();
        RenderPipeline pipeline = RenderPipeline.builder(linesSnippet)
                .withLocation("pipeline/chunkfinder_through_wall_lines")
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build();
        registerPipeline(pipeline);
        RenderSetup setup = RenderSetup.builder(pipeline)
                .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
                .createRenderSetup();
        return RenderType.create("chunkfinder_through_wall_lines", setup);
    }

    private static RenderPipeline.Snippet findLinesSnippet() {
        try {
            for (Field field : net.minecraft.client.renderer.RenderPipelines.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != RenderPipeline.Snippet.class) continue;
                field.setAccessible(true);
                RenderPipeline.Snippet snippet = (RenderPipeline.Snippet) field.get(null);
                Optional<String> vertexShader = snippet.vertexShader().map(Object::toString);
                if (vertexShader.orElse("").endsWith("core/rendertype_lines")
                        && snippet.vertexFormat().orElse(null)
                        == com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH) {
                    return snippet;
                }
            }
            throw new IllegalStateException("Minecraft line render pipeline snippet was not found");
        } catch (IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void registerPipeline(RenderPipeline pipeline) {
        try {
            for (Method method : net.minecraft.client.renderer.RenderPipelines.class.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == RenderPipeline.class
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == RenderPipeline.class) {
                    method.setAccessible(true);
                    method.invoke(null, pipeline);
                    return;
                }
            }
            throw new IllegalStateException("Minecraft render pipeline registry was not found");
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
