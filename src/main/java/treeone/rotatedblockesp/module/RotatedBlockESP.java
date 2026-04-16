package treeone.rotatedblockesp.module;

import treeone.rotatedblockesp.Addon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.render.blockesp.*;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.RainbowColors;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.DimensionType;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RotatedBlockESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
            .name("blocks")
            .description("Blocks to search for rotated variants.")
            .filter(block -> {
                BlockState state = block.getDefaultState();
                return state.contains(Properties.FACING) || state.contains(Properties.AXIS);
            })
            .onChanged(blocks1 -> {
                if (isActive() && Utils.canUpdate()) onActivate();
            })
            .build()
    );

    private final Setting<ESPBlockData> defaultBlockConfig = sgGeneral.add(new GenericSetting.Builder<ESPBlockData>()
            .name("default-block-config")
            .description("Default block config.")
            .defaultValue(
                    new ESPBlockData(
                            ShapeMode.Lines,
                            new SettingColor(0, 255, 200),
                            new SettingColor(0, 255, 200, 25),
                            true,
                            new SettingColor(0, 255, 200, 125)
                    )
            )
            .build()
    );

    private final Setting<Map<Block, ESPBlockData>> blockConfigs = sgGeneral.add(new BlockDataSetting.Builder<ESPBlockData>()
            .name("block-configs")
            .description("Config for each block.")
            .defaultData(defaultBlockConfig)
            .build()
    );

    private final BlockPos.Mutable blockPos = new BlockPos.Mutable();

    private final Long2ObjectMap<ESPChunk> chunks = new Long2ObjectOpenHashMap<>();
    private final ExecutorService workerThread = Executors.newSingleThreadExecutor();

    private DimensionType lastDimension;

    public RotatedBlockESP() {
        super(Addon.CATEGORY, "rotated-block-esp", "Shows only rotated blocks.");

        RainbowColors.register(this::onTickRainbow);
    }

    @Override
    public void onActivate() {
        synchronized (chunks) {
            chunks.clear();
        }

        for (Chunk chunk : Utils.chunks()) {
            searchChunk(chunk);
        }

        if (mc.world != null) lastDimension = mc.world.getDimension();
    }

    @Override
    public void onDeactivate() {
        synchronized (chunks) {
            chunks.clear();
        }
    }

    private void onTickRainbow() {
        if (!isActive()) return;

        defaultBlockConfig.get().tickRainbow();
        for (ESPBlockData blockData : blockConfigs.get().values()) blockData.tickRainbow();
    }

    private static boolean isRotated(BlockState state) {
        if (state.contains(Properties.AXIS)) {
            return state.get(Properties.AXIS) != Direction.Axis.Y;
        }
        if (state.contains(Properties.FACING)) {
            return state.get(Properties.FACING) != Direction.NORTH;
        }
        return false;
    }

    private ESPBlockData getBlockData(Block block) {
        ESPBlockData data = blockConfigs.get().get(block);
        return data != null ? data : defaultBlockConfig.get();
    }

    private void renderBlock(Render3DEvent event, ESPBlock block) {
        if (mc.world == null) return;

        BlockPos pos = new BlockPos(block.x, block.y, block.z);
        BlockState state = mc.world.getBlockState(pos);

        double x1 = block.x, y1 = block.y, z1 = block.z;
        double x2 = block.x + 1, y2 = block.y + 1, z2 = block.z + 1;

        VoxelShape shape = state.getOutlineShape(mc.world, pos);
        if (!shape.isEmpty()) {
            x1 = block.x + shape.getMin(Direction.Axis.X);
            y1 = block.y + shape.getMin(Direction.Axis.Y);
            z1 = block.z + shape.getMin(Direction.Axis.Z);
            x2 = block.x + shape.getMax(Direction.Axis.X);
            y2 = block.y + shape.getMax(Direction.Axis.Y);
            z2 = block.z + shape.getMax(Direction.Axis.Z);
        }

        ESPBlockData data = getBlockData(state.getBlock());
        event.renderer.box(x1, y1, z1, x2, y2, z2, data.sideColor, data.lineColor, data.shapeMode, 0);
    }

    private void updateChunk(int x, int z) {
        ESPChunk chunk = chunks.get(ChunkPos.toLong(x, z));
        if (chunk != null) chunk.update();
    }

    private void updateBlock(int x, int y, int z) {
        ESPChunk chunk = chunks.get(ChunkPos.toLong(x >> 4, z >> 4));
        if (chunk != null) chunk.update(x, y, z);
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        searchChunk(event.chunk());
    }

    private void searchChunk(Chunk chunk) {
        workerThread.submit(() -> {
            if (!isActive()) return;
            ESPChunk espChunk = ESPChunk.searchChunk(chunk, blocks.get());
            espChunk.blocks.values().removeIf(block -> {
                if (mc.world == null) return true;
                BlockState state = mc.world.getBlockState(new BlockPos(block.x, block.y, block.z));
                return !isRotated(state);
            });

            if (espChunk.size() > 0) {
                synchronized (chunks) {
                    chunks.put(chunk.getPos().toLong(), espChunk);
                    espChunk.update();

                    updateChunk(chunk.getPos().x - 1, chunk.getPos().z);
                    updateChunk(chunk.getPos().x + 1, chunk.getPos().z);
                    updateChunk(chunk.getPos().x, chunk.getPos().z - 1);
                    updateChunk(chunk.getPos().x, chunk.getPos().z + 1);
                }
            }
        });
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        int bx = event.pos.getX();
        int by = event.pos.getY();
        int bz = event.pos.getZ();

        int chunkX = bx >> 4;
        int chunkZ = bz >> 4;
        long key = ChunkPos.toLong(chunkX, chunkZ);

        boolean added = blocks.get().contains(event.newState.getBlock()) && isRotated(event.newState)
                && !(blocks.get().contains(event.oldState.getBlock()) && isRotated(event.oldState));
        boolean removed = !added
                && !(blocks.get().contains(event.newState.getBlock()) && isRotated(event.newState))
                && blocks.get().contains(event.oldState.getBlock()) && isRotated(event.oldState);

        if (added || removed) {
            workerThread.submit(() -> {
                synchronized (chunks) {
                    ESPChunk chunk = chunks.get(key);

                    if (chunk == null) {
                        chunk = new ESPChunk(chunkX, chunkZ);
                        if (chunk.shouldBeDeleted()) return;

                        chunks.put(key, chunk);
                    }

                    blockPos.set(bx, by, bz);

                    if (added) chunk.add(blockPos);
                    else chunk.remove(blockPos);

                    for (int x = -1; x < 2; x++) {
                        for (int z = -1; z < 2; z++) {
                            for (int y = -1; y < 2; y++) {
                                if (x == 0 && y == 0 && z == 0) continue;

                                updateBlock(bx + x, by + y, bz + z);
                            }
                        }
                    }
                }
            });
        }
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onPostTick(TickEvent.Post event) {
        if (mc.world == null) return;
        DimensionType dimension = mc.world.getDimension();

        if (lastDimension != dimension) onActivate();
        lastDimension = dimension;
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onRender(Render3DEvent event) {
        synchronized (chunks) {
            for (Iterator<ESPChunk> it = chunks.values().iterator(); it.hasNext();) {
                ESPChunk chunk = it.next();

                if (chunk.shouldBeDeleted()) {
                    workerThread.submit(() -> {
                        for (ESPBlock block : chunk.blocks.values()) {
                            block.group.remove(block, false);
                            block.loaded = false;
                        }
                    });

                    it.remove();
                } else {
                    for (ESPBlock block : chunk.blocks.values()) {
                        renderBlock(event, block);
                    }
                }
            }
        }
    }

    @Override
    public String getInfoString() {
        return "%s chunks".formatted(chunks.size());
    }
}