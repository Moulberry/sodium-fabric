package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.caffeinemc.mods.sodium.client.gl.shader.*;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformInt;
import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformMatrix4f;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class HierarchicalZBufferOccluder {

    private record ChunkTestBuffers(int inputBufferObject, int outputBufferObject, ByteBuffer inputBuffer, int size) {
        public static ChunkTestBuffers create(int size) {
            int inputBufferObject = GL30C.glGenBuffers();
            GL30C.glBindBuffer(GL45C.GL_SHADER_STORAGE_BUFFER, inputBufferObject);
            GL30C.glBufferData(GL45C.GL_SHADER_STORAGE_BUFFER, size * 4L * 4L, GL30C.GL_STREAM_DRAW);

            int outputBufferObject = GL30C.glGenBuffers();
            GL30C.glBindBuffer(GL45C.GL_SHADER_STORAGE_BUFFER, outputBufferObject);
            GL30C.glBufferData(GL45C.GL_SHADER_STORAGE_BUFFER, size * 4L, GL30C.GL_STREAM_READ);

            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(size * 4 * 4).order(ByteOrder.nativeOrder());

            return new ChunkTestBuffers(inputBufferObject, outputBufferObject, inputBuffer, size);
        }

        public void destroy() {
            GL30C.glDeleteBuffers(this.inputBufferObject);
            GL30C.glDeleteBuffers(this.outputBufferObject);
        }
    }
    private record ChunkTestInstance(ChunkTestBuffers buffers, long fence, List<RenderSection> occludables) { }

    // Depth mip chain
    private GlProgram<?> depthProgram = null;
    private int mipLevels = 0;
    private int lastWidth = -1;
    private int lastHeight = -1;
    private int depthTexture = -1;
    private int depthFbo = -1;
    private int depthVbo = -1;
    private int depthVao = -1;

    // Chunk testing
    private int minimumOutputBufferSize = 256;
    private List<ChunkTestBuffers> availableBuffers = new ArrayList<>();
    private List<ChunkTestInstance> activeTests = new ArrayList<>();
    private GlProgram<OcclusionProgramInterface> occlusionProgram = null;
    private int shadowSampler = -1;

    public void generateMipChain(int sourceDepthTexture, int width, int height) {
        int previousFramebuffer = GL32C.glGetInteger(GL32C.GL_FRAMEBUFFER_BINDING);

        if (this.lastWidth != width || this.lastHeight != height) {
            this.lastWidth = width;
            this.lastHeight = height;
            this.mipLevels = 1 + (int) Math.floor(Math.log(Math.max(width, height)) / Math.log(2));

            if (this.depthTexture != -1) {
                GL11.glDeleteTextures(this.depthTexture);
            }

            this.depthTexture = GL11.glGenTextures();

            GlStateManager._bindTexture(this.depthTexture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL30C.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30C.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30C.GL_CLAMP_TO_EDGE);

            int w = width;
            int h = height;
            for (int level = 0; level < this.mipLevels; level++) {
                w = Math.max(1, w / 2);
                h = Math.max(1, h / 2);
                GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_DEPTH_COMPONENT, w, h, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null);
            }
        }
        if (this.depthProgram == null) {
            var constants = ShaderConstants.builder().build();

            GlShader vertShader = ShaderLoader.loadShader(ShaderType.VERTEX,
                    ResourceLocation.fromNamespaceAndPath("sodium", "occlusion/hiz.vsh"), constants);

            GlShader fragShader = ShaderLoader.loadShader(ShaderType.FRAGMENT,
                    ResourceLocation.fromNamespaceAndPath("sodium", "occlusion/hiz.fsh"), constants);

            try {
                this.depthProgram = GlProgram.builder(ResourceLocation.fromNamespaceAndPath("sodium", "occlusion/hiz"))
                        .attachShader(vertShader)
                        .attachShader(fragShader)
                        .bindAttribute("corner", 0)
                        .link((shader) -> null);
                this.depthProgram.bind();
                GlUniformInt sampler0 = this.depthProgram.bindUniform("sampler0", GlUniformInt::new);
                sampler0.setInt(0);
            } finally {
                vertShader.delete();
                fragShader.delete();
            }
        }
        if (this.depthFbo == -1) {
            this.depthFbo = GL32C.glGenFramebuffers();
        }
        if (this.depthVbo == -1) {
            this.depthVbo = GlStateManager._glGenBuffers();

            BufferBuilder bufferBuilder = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLIT_SCREEN);
            bufferBuilder.addVertex(0.0F, 1.0F, 0.0F);
            bufferBuilder.addVertex(0.0F, 0.0F, 0.0F);
            bufferBuilder.addVertex(1.0F, 0.0F, 0.0F);
            bufferBuilder.addVertex(0.0F, 1.0F, 0.0F);
            bufferBuilder.addVertex(1.0F, 0.0F, 0.0F);
            bufferBuilder.addVertex(1.0F, 1.0F, 0.0F);
            ByteBuffer byteBuffer = bufferBuilder.buildOrThrow().vertexBuffer();

            GlStateManager._glBindBuffer(GL32C.GL_ARRAY_BUFFER, this.depthVbo);
            GlStateManager._glBufferData(GL32C.GL_ARRAY_BUFFER, byteBuffer.remaining(), GL32C.GL_STATIC_DRAW);
            GlStateManager._glBufferSubData(GL32C.GL_ARRAY_BUFFER, 0, byteBuffer);
        }
        if (this.depthVao == -1) {
            this.depthVao = GlStateManager._glGenVertexArrays();
            GlStateManager._glBindVertexArray(this.depthVao);

            GlStateManager._glBindBuffer(GL32C.GL_ARRAY_BUFFER, this.depthVbo);

            GlStateManager._enableVertexAttribArray(0);
            GlStateManager._vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0);
        } else {
            GlStateManager._glBindVertexArray(this.depthVao);
        }

//        int timerQuery = GL30C.glGenQueries();
//        GL30C.glBeginQuery(GL40C.GL_TIME_ELAPSED, timerQuery);

        this.depthProgram.bind();

        GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, this.depthFbo);

        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11.GL_ALWAYS);

        GlStateManager._activeTexture(GL32C.GL_TEXTURE0);
        GlStateManager._bindTexture(sourceDepthTexture);

        int w = width;
        int h = height;
        for (int mipLevel = 0; mipLevel < this.mipLevels; mipLevel++) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);

            if (mipLevel == 1) {
                GlStateManager._bindTexture(this.depthTexture);
            }
            if (mipLevel >= 1) {
                GL45C.glTextureParameteri(this.depthTexture, GL32C.GL_TEXTURE_BASE_LEVEL, mipLevel-1);
                GL45C.glTextureParameteri(this.depthTexture, GL32C.GL_TEXTURE_MAX_LEVEL, mipLevel-1);
            }
            GlStateManager._glFramebufferTexture2D(GL32C.GL_FRAMEBUFFER, GL32C.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, this.depthTexture, mipLevel);

            GL45C.glViewport(0, 0, w, h);

            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        }
        GL45C.glTextureParameteri(this.depthTexture, GL32C.GL_TEXTURE_BASE_LEVEL, 0);
        GL45C.glTextureParameteri(this.depthTexture, GL32C.GL_TEXTURE_MAX_LEVEL, 1000);

        GlStateManager._depthFunc(GL11.GL_LEQUAL);

        this.depthProgram.unbind();

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, previousFramebuffer);
        GL45C.glViewport(0, 0, width, height);

//        GL31C.glEndQuery(GL40C.GL_TIME_ELAPSED);
//        int took = GL31C.glGetQueryObjecti(timerQuery, GL31C.GL_QUERY_RESULT);
//        System.out.println("Took: " + took + " on the gpu");
//        GL30C.glDeleteQueries(timerQuery);
    }

    private static final class OcclusionProgramInterface {
        private final GlUniformMatrix4f projectionUniform;
        private final GlUniformMatrix4f modelViewUniform;
        private final GlUniformInt inputCountUniform;

        public OcclusionProgramInterface(ShaderBindingContext context) {
            this.projectionUniform = context.bindUniform("projectionMatrix", GlUniformMatrix4f::new);
            this.modelViewUniform = context.bindUniform("modelViewMatrix", GlUniformMatrix4f::new);
            this.inputCountUniform = context.bindUniform("inputCount", GlUniformInt::new);
        }
    }

    public void submitSectionTest(Matrix4fc projection, Matrix4fc modelView, double x, double y, double z, List<RenderSection> sections) {
        if (sections.isEmpty()) {
            return;
        }

        if (this.occlusionProgram == null) {
            var constants = ShaderConstants.builder().build();

            GlShader computeShader = ShaderLoader.loadShader(ShaderType.COMPUTE,
                    ResourceLocation.fromNamespaceAndPath("sodium", "occlusion/hiz_cull.csh"), constants);

            try {
                this.occlusionProgram = GlProgram.builder(ResourceLocation.fromNamespaceAndPath("sodium", "occlusion/hiz_cull"))
                        .attachShader(computeShader)
                        .link(OcclusionProgramInterface::new);
            } finally {
                computeShader.delete();
            }
        }

//        int query = GL45C.glGenQueries();
//        GL45C.glBeginQuery(GL45C.GL_TIME_ELAPSED, query);
//        long start = System.nanoTime();

        this.minimumOutputBufferSize = Math.max(this.minimumOutputBufferSize, Mth.smallestEncompassingPowerOfTwo(sections.size()));

        ChunkTestBuffers buffers = null;
        var iterator = this.availableBuffers.iterator();
        while (iterator.hasNext()) {
            ChunkTestBuffers candidate = iterator.next();

            if (candidate.size < this.minimumOutputBufferSize) {
                candidate.destroy();
                iterator.remove();
                continue;
            }

            buffers = candidate;
            iterator.remove();
            break;
        }
        if (buffers == null) {
            buffers = ChunkTestBuffers.create(this.minimumOutputBufferSize);
        }

        int sectionBasisX = Mth.floor(x) >> 4;
        int sectionBasisY = Mth.floor(y) >> 4;
        int sectionBasisZ = Mth.floor(z) >> 4;

        Matrix4f cameraModelView = new Matrix4f(modelView);
        cameraModelView.translate((float)(sectionBasisX*16 - x), (float)(sectionBasisY*16 - y), (float)(sectionBasisZ*16 - z));

        this.occlusionProgram.bind();
        OcclusionProgramInterface programInterface = this.occlusionProgram.getInterface();
        programInterface.projectionUniform.set(projection);
        programInterface.modelViewUniform.set(cameraModelView);
        programInterface.inputCountUniform.set(sections.size());

        buffers.inputBuffer.clear();

        for (RenderSection section : sections) {
            buffers.inputBuffer.putInt(section.getChunkX() - sectionBasisX);
            buffers.inputBuffer.putInt(section.getChunkY() - sectionBasisY);
            buffers.inputBuffer.putInt(section.getChunkZ() - sectionBasisZ);
            buffers.inputBuffer.putInt(0);
        }

        buffers.inputBuffer.flip();

        if (this.shadowSampler == -1) {
            this.shadowSampler = GL42C.glGenSamplers();
            GL42C.glSamplerParameteri(this.shadowSampler, GL42C.GL_TEXTURE_MIN_FILTER, GL42C.GL_LINEAR_MIPMAP_NEAREST);
            GL42C.glSamplerParameteri(this.shadowSampler, GL42C.GL_TEXTURE_MAG_FILTER, GL42C.GL_LINEAR);
            GL42C.glSamplerParameteri(this.shadowSampler, GL42C.GL_TEXTURE_WRAP_S, GL42C.GL_CLAMP_TO_EDGE);
            GL42C.glSamplerParameteri(this.shadowSampler, GL42C.GL_TEXTURE_WRAP_T, GL42C.GL_CLAMP_TO_EDGE);
            GL42C.glSamplerParameteri(this.shadowSampler, GL42C.GL_TEXTURE_COMPARE_MODE, GL42C.GL_COMPARE_REF_TO_TEXTURE);
            GL42C.glSamplerParameteri(this.shadowSampler, GL42C.GL_TEXTURE_COMPARE_FUNC, GL42C.GL_LEQUAL);
        }

        GL30C.glBindBuffer(GL45C.GL_SHADER_STORAGE_BUFFER, buffers.inputBufferObject);
        GlStateManager._glBufferSubData(GL45C.GL_SHADER_STORAGE_BUFFER, 0, buffers.inputBuffer);

        GL45C.glBindBufferBase(GL45C.GL_SHADER_STORAGE_BUFFER, 0, buffers.inputBufferObject);
        GL45C.glBindBufferBase(GL45C.GL_SHADER_STORAGE_BUFFER, 1, buffers.outputBufferObject);

        GL42C.glActiveTexture(GL42C.GL_TEXTURE0);
        GL42C.glBindTexture(GL42C.GL_TEXTURE_2D, this.depthTexture);
        GL42C.glBindSampler(0, this.shadowSampler);

        int groupsX = (sections.size() + 63) / 64;
        GL43C.glDispatchCompute(groupsX, 1, 1);

        GL42C.glBindSampler(0, 0);

//        System.out.println("Took: " + (System.nanoTime() - start) + " on the cpu");
//        GL45C.glEndQuery(GL45C.GL_TIME_ELAPSED);
//        int took = GL45C.glGetQueryObjecti(query, GL45C.GL_QUERY_RESULT);
//        System.out.println("Took: " + took + " on the gpu");
//        GL45C.glDeleteQueries(query);
        long fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        this.activeTests.add(new ChunkTestInstance(buffers, fence, sections));
    }

    public boolean pollAndApplySectionTests() {
        boolean passedVisibility = false;

        var iterator = this.activeTests.iterator();
        while (iterator.hasNext()) {
            ChunkTestInstance testInstance = iterator.next();

            if (GL45C.glGetSynci(testInstance.fence, GL45C.GL_SYNC_STATUS, null) != GL45C.GL_SIGNALED) {
                break;
            }

            int occludableCount = testInstance.occludables.size();

            GL30C.glBindBuffer(GL45C.GL_SHADER_STORAGE_BUFFER, testInstance.buffers.outputBufferObject);
            ByteBuffer result = GL43C.glMapBufferRange(GL45C.GL_SHADER_STORAGE_BUFFER, 0, 4L * occludableCount, GL32C.GL_MAP_READ_BIT);
            if (result == null) {
                // todo: log error and disable hzb occlusion!
                throw new IllegalStateException();
            }

            var resultIntBuffer = result.asIntBuffer();

            for (int i = 0; i < occludableCount; i++) {
                int state = resultIntBuffer.get(i);
                var renderSection = testInstance.occludables.get(i);

                boolean wasVisible = ZBufferVisibilityFlags.isVisible(renderSection.getZBufferVisibilityFlags());
                ZBufferVisibilityFlags.update(renderSection, state);
                if (!wasVisible && ZBufferVisibilityFlags.isVisible(renderSection.getZBufferVisibilityFlags())) {
                    passedVisibility = true;
                }
            }

            GL43C.glUnmapBuffer(GL45C.GL_SHADER_STORAGE_BUFFER);

            if (testInstance.buffers.size >= this.minimumOutputBufferSize) {
                this.availableBuffers.add(testInstance.buffers);
            } else {
                testInstance.buffers.destroy();
            }

            iterator.remove();
        }

        return passedVisibility;
    }

    // todo: remember to call destroy()
    public void destroy() {
        // todo: add chunk test stuff here!
        GL11.glDeleteTextures(this.depthTexture);
        this.depthTexture = -1;

        this.depthProgram.delete();
        this.depthProgram = null;

        GL30C.glDeleteFramebuffers(this.depthFbo);
        this.depthFbo = -1;

        GL30C.glDeleteBuffers(this.depthVbo);
        this.depthVbo = -1;

        GL30C.glDeleteVertexArrays(this.depthVao);
        this.depthVao = -1;
    }

}
