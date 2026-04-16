package treeone.rotatedblockesp.module;

import treeone.rotatedblockesp.Addon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.DimensionType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static meteordevelopment.meteorclient.utils.Utils.getRenderDistance;

public class RotatedBlockESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors  = settings.createGroup("Colors");
    private final SettingGroup sgTracers = settings.createGroup("Tracers");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
            .name("blocks")
            .description("Blocks to search for rotated variants.")
            .filter(block -> {
                BlockState state = block.getDefaultState();
                return state.contains(Properties.FACING) || state.contains(Properties.AXIS);
            })
            .onChanged(b -> { if (isActive() && Utils.canUpdate()) onActivate(); })
            .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How boxes are rendered.")
            .defaultValue(ShapeMode.Lines)
            .build()
    );

    private final Setting<SettingColor> lineColor = sgColors.add(new ColorSetting.Builder()
            .name("line-color")
            .description("Color of box outlines.")
            .defaultValue(new SettingColor(0, 255, 200))
            .build()
    );

    private final Setting<SettingColor> sideColor = sgColors.add(new ColorSetting.Builder()
            .name("side-color")
            .description("Color of box sides.")
            .defaultValue(new SettingColor(0, 255, 200, 25))
            .build()
    );

    private final Setting<Boolean> tracers = sgTracers.add(new BoolSetting.Builder()
            .name("tracers")
            .description("Render tracer lines to found blocks.")
            .defaultValue(false)
            .build()
    );

    private final Setting<SettingColor> tracerColor = sgTracers.add(new ColorSetting.Builder()
            .name("tracer-color")
            .description("Color of tracer lines.")
            .defaultValue(new SettingColor(0, 255, 200, 125))
            .build()
    );

    private final Long2ObjectMap<List<BlockPos>> chunks = new Long2ObjectOpenHashMap<>();
    private final ExecutorService workerThread = Executors.newSingleThreadExecutor();
    private DimensionType lastDimension;

    public RotatedBlockESP() {
        super(Addon.CATEGORY, "rotated-block-esp", "Shows only rotated blocks.");
    }

    @Override
    public void onActivate() {
        synchronized (chunks) { chunks.clear(); }
        for (Chunk chunk : Utils.chunks()) searchChunk(chunk);
        if (mc.world != null) lastDimension = mc.world.getDimension();
    }

    @Override
    public void onDeactivate() {
        synchronized (chunks) { chunks.clear(); }
    }

    private static boolean isRotated(BlockState state) {
        if (state.contains(Properties.AXIS))   return state.get(Properties.AXIS)   != Direction.Axis.Y;
        if (state.contains(Properties.FACING)) return state.get(Properties.FACING) != Direction.NORTH;
        return false;
    }

    private boolean isTarget(BlockState state) {
        return blocks.get().contains(state.getBlock()) && isRotated(state);
    }

    private boolean chunkShouldBeDeleted(int chunkX, int chunkZ) {
        if (mc.player == null) return true;
        int viewDist = getRenderDistance() + 1;
        int px = ChunkSectionPos.getSectionCoord(mc.player.getBlockPos().getX());
        int pz = ChunkSectionPos.getSectionCoord(mc.player.getBlockPos().getZ());
        return chunkX > px + viewDist || chunkX < px - viewDist
                || chunkZ > pz + viewDist || chunkZ < pz - viewDist;
    }

    private void searchChunk(Chunk chunk) {
        workerThread.submit(() -> {
            if (!isActive() || mc.world == null) return;

            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;
            if (chunkShouldBeDeleted(cx, cz)) return;

            List<BlockPos> found = new ArrayList<>();
            BlockPos.Mutable bp = new BlockPos.Mutable();

            for (int x = chunk.getPos().getStartX(); x <= chunk.getPos().getEndX(); x++) {
                for (int z = chunk.getPos().getStartZ(); z <= chunk.getPos().getEndZ(); z++) {
                    for (int y = mc.world.getBottomY(); y < mc.world.getTopYInclusive(); y++) {
                        bp.set(x, y, z);
                        if (isTarget(chunk.getBlockState(bp))) found.add(new BlockPos(x, y, z));
                    }
                }
            }

            if (!found.isEmpty()) {
                synchronized (chunks) { chunks.put(ChunkPos.toLong(cx, cz), found); }
            }
        });
    }

    @SuppressWarnings("unused")
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        searchChunk(event.chunk());
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

        boolean wasTarget = isTarget(event.oldState);
        boolean isNowTarget = isTarget(event.newState);
        if (wasTarget == isNowTarget) return;

        workerThread.submit(() -> {
            synchronized (chunks) {
                List<BlockPos> list = chunks.get(key);

                if (isNowTarget) {
                    if (list == null) {
                        if (chunkShouldBeDeleted(chunkX, chunkZ)) return;
                        list = new ArrayList<>();
                        chunks.put(key, list);
                    }
                    list.add(new BlockPos(bx, by, bz));
                } else {
                    if (list != null) {
                        list.removeIf(p -> p.getX() == bx && p.getY() == by && p.getZ() == bz);
                        if (list.isEmpty()) chunks.remove(key);
                    }
                }
            }
        });
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
        if (mc.world == null) return;

        LongList toRemove = new LongArrayList();

        synchronized (chunks) {
            for (Long2ObjectMap.Entry<List<BlockPos>> entry : chunks.long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                int cx = ChunkPos.getPackedX(key);
                int cz = ChunkPos.getPackedZ(key);

                if (chunkShouldBeDeleted(cx, cz)) {
                    toRemove.add(key);
                    continue;
                }

                for (BlockPos pos : entry.getValue()) renderBlock(event, pos);
            }

            for (int i = 0; i < toRemove.size(); i++) chunks.remove(toRemove.getLong(i));
        }
    }

    private void renderBlock(Render3DEvent event, BlockPos pos) {
        if (mc.world == null) return;

        BlockState state = mc.world.getBlockState(pos);
        if (!isTarget(state)) return;

        double x1 = pos.getX(), y1 = pos.getY(), z1 = pos.getZ();
        double x2 = x1 + 1,    y2 = y1 + 1,    z2 = z1 + 1;

        VoxelShape shape = state.getOutlineShape(mc.world, pos);
        if (!shape.isEmpty()) {
            x1 = pos.getX() + shape.getMin(Direction.Axis.X);
            y1 = pos.getY() + shape.getMin(Direction.Axis.Y);
            z1 = pos.getZ() + shape.getMin(Direction.Axis.Z);
            x2 = pos.getX() + shape.getMax(Direction.Axis.X);
            y2 = pos.getY() + shape.getMax(Direction.Axis.Y);
            z2 = pos.getZ() + shape.getMax(Direction.Axis.Z);
        }

        event.renderer.box(x1, y1, z1, x2, y2, z2, sideColor.get(), lineColor.get(), shapeMode.get(), 0);

        if (tracers.get()) {
            double cx = (x1 + x2) / 2.0;
            double cy = (y1 + y2) / 2.0;
            double cz = (z1 + z2) / 2.0;
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, cx, cy, cz, tracerColor.get());
        }
    }

    @Override
    public String getInfoString() {
        return "%s chunks".formatted(chunks.size());
    }
}