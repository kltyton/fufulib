package com.kltyton.fufulib.shape.cache;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.kltyton.fufulib.Fufulib;
import com.kltyton.fufulib.api.FufuShapeMode;
import com.kltyton.fufulib.config.FufuLibConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.fufulib.shape.debug.ShapeProfiler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

public final class FufuModelShapeCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CACHE_ALGORITHM_VERSION = "fufus-lib-model-shape-v1";
    private static final int MAX_FULL_ELEMENTS = 4096;
    private static final double MIN_ELEMENT_THICKNESS_16 = 0.1;
    private static final double EPS = 1.0E-7;

    private static final Map<String, SplitShapeSet> SHAPE_SETS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> OCCUPANCY = new ConcurrentHashMap<>();
    private static final Map<String, IntBounds> BOUNDS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ModelGeometry> GEOMETRY = new ConcurrentHashMap<>();
    private static final Map<String, String> FINGERPRINTS = new ConcurrentHashMap<>();
    private static final Map<String, CachedShapeSet> PERSISTENT = new ConcurrentHashMap<>();
    private static final AtomicBoolean SAVE_RUNNING = new AtomicBoolean(false);
    private static volatile boolean persistentLoaded;
    private static volatile boolean persistentDirty;

    private FufuModelShapeCache() {
    }

    public static VoxelShape getOrCreateLocalShape(
            ResourceLocation modelId,
            Direction facing,
            int offsetX,
            int offsetY,
            int offsetZ,
            FufuShapeMode mode) {
        SplitShapeSet set = getOrCreateShapeSet(modelId, facing, mode);
        VoxelShape shape = set.shapes().get(packOffset(offsetX, offsetY, offsetZ));
        return shape == null ? Shapes.empty() : shape;
    }

    public static boolean hasLocalShape(ResourceLocation modelId, Direction facing, int offsetX, int offsetY, int offsetZ, FufuShapeMode mode) {
        if (mode == FufuShapeMode.BLOCK) {
            return offsetX == 0 && offsetY == 0 && offsetZ == 0;
        }

        String setKey = shapeSetKey(modelId, facing, mode);
        SplitShapeSet loadedSet = SHAPE_SETS.get(setKey);
        long packed = packOffset(offsetX, offsetY, offsetZ);
        if (loadedSet != null) {
            return loadedSet.shapes().containsKey(packed);
        }

        CachedShapeSet cached = persistentCached(modelId, facing, mode);
        if (cached != null) {
            List<AABB> boxes = cached.boxes().get(packed);
            return boxes != null && !boxes.isEmpty();
        }

        IntBounds bounds = getOrCreateIntBounds(modelId, facing, mode);
        if (bounds.isEmpty()
                || offsetX < bounds.minX() || offsetX > bounds.maxX()
                || offsetY < bounds.minY() || offsetY > bounds.maxY()
                || offsetZ < bounds.minZ() || offsetZ > bounds.maxZ()) {
            return false;
        }

        String key = modelId + "|" + horizontalIndex(facing) + "|" + offsetX + "," + offsetY + "," + offsetZ + "|" + mode.name();
        return OCCUPANCY.computeIfAbsent(key, ignored -> !optimizeBoxes(computeLocalBoxes(modelId, facing, offsetX, offsetY, offsetZ, mode)).isEmpty());
    }

    public static boolean isMultiBlock(ResourceLocation modelId, Direction facing) {
        IntBounds bounds = getOrCreateIntBounds(modelId, facing, FufuShapeMode.BOUNDS);
        return bounds.minX() < 0 || bounds.maxX() > 0 || bounds.minY() < 0 || bounds.maxY() > 0 || bounds.minZ() < 0 || bounds.maxZ() > 0;
    }

    public static IntBounds getOrCreateIntBounds(ResourceLocation modelId, Direction facing, FufuShapeMode mode) {
        String key = modelId + "|" + horizontalIndex(facing) + "|" + mode.name();
        return BOUNDS.computeIfAbsent(key, ignored -> computeIntBounds(modelId, facing, mode));
    }

    private static SplitShapeSet getOrCreateShapeSet(ResourceLocation modelId, Direction facing, FufuShapeMode mode) {
        return SHAPE_SETS.computeIfAbsent(shapeSetKey(modelId, facing, mode), ignored -> buildShapeSet(modelId, facing, mode));
    }

    private static SplitShapeSet buildShapeSet(ResourceLocation modelId, Direction facing, FufuShapeMode mode) {
        long start = System.nanoTime();
        CachedShapeSet cached = persistentCached(modelId, facing, mode);
        if (cached != null) {
            SplitShapeSet set = cached.toSplitShapeSet();
            LOGGER.debug("[Fufu's Lib] loaded model shape: model={}, facing={}, boxes={}, time={}ms",
                    modelId, facing, cached.boxCount(), elapsedMillis(start));
            return set;
        }

        IntBounds bounds = getOrCreateIntBounds(modelId, facing, mode);
        Map<Long, VoxelShape> shapes = new ConcurrentHashMap<>();
        if (!bounds.isEmpty()) {
            for (int dx = bounds.minX(); dx <= bounds.maxX(); dx++) {
                for (int dy = bounds.minY(); dy <= bounds.maxY(); dy++) {
                    for (int dz = bounds.minZ(); dz <= bounds.maxZ(); dz++) {
                        List<AABB> boxes = optimizeBoxes(computeLocalBoxes(modelId, facing, dx, dy, dz, mode));
                        if (!boxes.isEmpty()) {
                            VoxelShape shape = shapeFromBoxes(boxes);
                            ShapeProfiler.registerShape(shape, "model=" + modelId + ",facing=" + facing + ",offset=" + dx + "," + dy + "," + dz + ",mode=" + mode.name());
                            shapes.put(packOffset(dx, dy, dz), shape);
                        }
                    }
                }
            }
        }
        SplitShapeSet set = new SplitShapeSet(bounds, shapes);
        cachePersistent(modelId, facing, mode, set);
        LOGGER.debug("[Fufu's Lib] rebuilt model shape: model={}, facing={}, cells={}, time={}ms",
                modelId, facing, shapes.size(), elapsedMillis(start));
        return set;
    }

    private static CachedShapeSet persistentCached(ResourceLocation modelId, Direction facing, FufuShapeMode mode) {
        if (!FufuLibConfig.PERSISTENT_MODEL_SHAPE_CACHE.get()) {
            return null;
        }
        CachedShapeSet cached = loadPersistent().get(persistentKey(modelId, facing, mode));
        return cached != null && modelFingerprint(modelId, mode).equals(cached.fingerprint()) ? cached.optimized() : null;
    }

    private static void cachePersistent(ResourceLocation modelId, Direction facing, FufuShapeMode mode, SplitShapeSet set) {
        if (!FufuLibConfig.PERSISTENT_MODEL_SHAPE_CACHE.get()) {
            return;
        }
        Map<Long, List<AABB>> boxes = new HashMap<>();
        for (Map.Entry<Long, VoxelShape> entry : set.shapes().entrySet()) {
            List<AABB> aabbs = optimizeBoxes(entry.getValue().toAabbs());
            if (!aabbs.isEmpty()) {
                boxes.put(entry.getKey(), aabbs);
            }
        }
        loadPersistent().put(persistentKey(modelId, facing, mode), new CachedShapeSet(modelFingerprint(modelId, mode), set.bounds(), boxes));
        persistentDirty = true;
        savePersistentIfDirty();
    }

    private static IntBounds computeIntBounds(ResourceLocation modelId, Direction facing, FufuShapeMode mode) {
        if (mode == FufuShapeMode.BLOCK) {
            return new IntBounds(0, 0, 0, 0, 0, 0);
        }
        ModelGeometry geometry = GEOMETRY.computeIfAbsent(modelId, FufuModelShapeCache::loadModelGeometry);
        if (geometry.bounds16() == null) {
            return new IntBounds(0, -1, 0, -1, 0, -1);
        }
        AABB bounds16 = snapBounds16ToCellEdges(clampToReasonableRange(rotate90Y16(geometry.bounds16(), horizontalIndex(facing))),
                FufuLibConfig.MULTIBLOCK_BOUNDS_SNAP_16.get());
        int minX = (int) Math.floor(bounds16.minX / 16.0 + EPS);
        int minY = (int) Math.floor(bounds16.minY / 16.0 + EPS);
        int minZ = (int) Math.floor(bounds16.minZ / 16.0 + EPS);
        int maxX = (int) Math.floor(bounds16.maxX / 16.0 - EPS);
        int maxY = (int) Math.floor(bounds16.maxY / 16.0 - EPS);
        int maxZ = (int) Math.floor(bounds16.maxZ / 16.0 - EPS);
        return new IntBounds(minX, maxX, Math.max(0, minY), Math.max(0, maxY), minZ, maxZ);
    }

    private static List<AABB> computeLocalBoxes(ResourceLocation modelId, Direction facing, int offsetX, int offsetY, int offsetZ, FufuShapeMode mode) {
        if (mode == FufuShapeMode.BLOCK) {
            return offsetX == 0 && offsetY == 0 && offsetZ == 0 ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : List.of();
        }
        ModelGeometry geometry = GEOMETRY.computeIfAbsent(modelId, FufuModelShapeCache::loadModelGeometry);
        if (geometry.bounds16() == null) {
            return List.of();
        }

        int turns = horizontalIndex(facing);
        double cellMinX = offsetX * 16.0;
        double cellMinY = offsetY * 16.0;
        double cellMinZ = offsetZ * 16.0;
        double cellMaxX = cellMinX + 16.0;
        double cellMaxY = cellMinY + 16.0;
        double cellMaxZ = cellMinZ + 16.0;
        List<AABB> boxes = new ArrayList<>();

        if (mode == FufuShapeMode.BOUNDS || geometry.elements() == null || geometry.elementCount() > MAX_FULL_ELEMENTS) {
            addLocalBoxIfIntersects(boxes, cellMinX, cellMinY, cellMinZ, cellMaxX, cellMaxY, cellMaxZ,
                    clampToReasonableRange(rotate90Y16(geometry.bounds16(), turns)));
            return boxes;
        }

        for (ModelElement element : geometry.elements()) {
            if (element.isRotated()) {
                for (AABB voxelBox : element.voxelBoxes16()) {
                    addLocalBoxIfIntersects(boxes, cellMinX, cellMinY, cellMinZ, cellMaxX, cellMaxY, cellMaxZ, rotate90Y16(voxelBox, turns));
                }
            } else {
                addLocalBoxIfIntersects(boxes, cellMinX, cellMinY, cellMinZ, cellMaxX, cellMaxY, cellMaxZ, rotate90Y16(element.bounds16(), turns));
            }
        }
        return boxes;
    }

    private static ModelGeometry loadModelGeometry(ResourceLocation modelId) {
        JsonArray elements = resolveElements(modelId, 16);
        if (elements == null || elements.isEmpty()) {
            return new ModelGeometry(new AABB(0, 0, 0, 16, 16, 16), null, 0);
        }
        int count = elements.size();
        List<ModelElement> parsed = count > MAX_FULL_ELEMENTS ? null : new ArrayList<>(count);
        AABB bounds = null;
        for (JsonElement raw : elements) {
            if (!raw.isJsonObject()) {
                continue;
            }
            ModelElement element = parseElement(raw.getAsJsonObject());
            if (element == null) {
                continue;
            }
            bounds = bounds == null ? element.bounds16() : bounds.minmax(element.bounds16());
            if (parsed != null) {
                parsed.add(element);
            }
        }
        return new ModelGeometry(bounds == null ? new AABB(0, 0, 0, 16, 16, 16) : clampToReasonableRange(bounds), parsed, count);
    }
    @SuppressWarnings("all")
    private static JsonArray resolveElements(ResourceLocation modelId, int maxDepth) {
        ResourceLocation current = modelId;
        for (int i = 0; i < maxDepth; i++) {
            JsonObject json = readJson(modelPath(current));
            if (json == null) {
                return null;
            }
            JsonArray elements = json.getAsJsonArray("elements");
            if (elements != null && !elements.isEmpty()) {
                return elements;
            }
            JsonElement parent = json.get("parent");
            if (parent == null || !parent.isJsonPrimitive()) {
                return null;
            }
            String value = parent.getAsString();
            current = value.contains(":") ? new ResourceLocation(value) : new ResourceLocation(current.getNamespace(), value);
        }
        return null;
    }

    private static JsonObject readJson(String path) {
        try (InputStream in = FufuModelShapeCache.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (Exception e) {
            LOGGER.debug("[Fufu's Lib] failed to read model json: {}", path, e);
            return null;
        }
    }

    private static String readResourceString(String path) {
        try (InputStream in = FufuModelShapeCache.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String modelPath(ResourceLocation modelId) {
        return "assets/%s/models/%s.json".formatted(modelId.getNamespace(), modelId.getPath());
    }

    private static ModelElement parseElement(JsonObject json) {
        JsonArray from = json.getAsJsonArray("from");
        JsonArray to = json.getAsJsonArray("to");
        if (from == null || to == null || from.size() < 3 || to.size() < 3) {
            return null;
        }
        AABB box = new AABB(from.get(0).getAsDouble(), from.get(1).getAsDouble(), from.get(2).getAsDouble(),
                to.get(0).getAsDouble(), to.get(1).getAsDouble(), to.get(2).getAsDouble());
        ElementRotation rotation = parseRotation(json.getAsJsonObject("rotation"));
        AABB bounds = rotation == null ? box : rotatedBounds(box, rotation);
        List<AABB> voxels = rotation == null ? List.of() : buildVoxelizedElementBoxes(box, rotation, bounds,
                FufuLibConfig.ROTATED_ELEMENT_VOXEL_STEP_16.get());
        return new ModelElement(box, rotation, bounds, voxels);
    }

    private static ElementRotation parseRotation(JsonObject json) {
        if (json == null) {
            return null;
        }
        JsonArray origin = json.getAsJsonArray("origin");
        JsonElement axis = json.get("axis");
        JsonElement angle = json.get("angle");
        if (origin == null || origin.size() < 3 || axis == null || angle == null) {
            return null;
        }
        return new ElementRotation(axis.getAsString(), Math.toRadians(angle.getAsDouble()),
                origin.get(0).getAsDouble(), origin.get(1).getAsDouble(), origin.get(2).getAsDouble());
    }

    private static List<AABB> buildVoxelizedElementBoxes(AABB box, ElementRotation rotation, AABB bounds, double step16) {
        List<AABB> boxes = new ArrayList<>();
        double step = clamp(step16, 0.25, 4.0);
        double startX = Math.floor(bounds.minX / step) * step;
        double startY = Math.floor(bounds.minY / step) * step;
        double startZ = Math.floor(bounds.minZ / step) * step;
        double expansion = Math.max(step * 0.5, MIN_ELEMENT_THICKNESS_16);
        for (double x = startX; x < bounds.maxX - EPS; x += step) {
            for (double y = startY; y < bounds.maxY - EPS; y += step) {
                for (double z = startZ; z < bounds.maxZ - EPS; z += step) {
                    double x1 = Math.min(x + step, bounds.maxX);
                    double y1 = Math.min(y + step, bounds.maxY);
                    double z1 = Math.min(z + step, bounds.maxZ);
                    double cx = (Math.max(x, bounds.minX) + x1) * 0.5;
                    double cy = (Math.max(y, bounds.minY) + y1) * 0.5;
                    double cz = (Math.max(z, bounds.minZ) + z1) * 0.5;
                    double[] local = rotatePoint(cx, cy, cz, rotation.originX(), rotation.originY(), rotation.originZ(), rotation.axis(), -rotation.angleRad());
                    if (local[0] >= box.minX - expansion && local[0] <= box.maxX + expansion
                            && local[1] >= box.minY - expansion && local[1] <= box.maxY + expansion
                            && local[2] >= box.minZ - expansion && local[2] <= box.maxZ + expansion) {
                        boxes.add(new AABB(Math.max(x, bounds.minX), Math.max(y, bounds.minY), Math.max(z, bounds.minZ), x1, y1, z1));
                    }
                }
            }
        }
        return boxes;
    }

    private static AABB rotatedBounds(AABB box, ElementRotation rotation) {
        AABB out = null;
        for (double x : new double[] {box.minX, box.maxX}) {
            for (double y : new double[] {box.minY, box.maxY}) {
                for (double z : new double[] {box.minZ, box.maxZ}) {
                    double[] p = rotatePoint(x, y, z, rotation.originX(), rotation.originY(), rotation.originZ(), rotation.axis(), rotation.angleRad());
                    AABB point = new AABB(p[0], p[1], p[2], p[0], p[1], p[2]);
                    out = out == null ? point : out.minmax(point);
                }
            }
        }
        return out == null ? box : out;
    }

    private static double[] rotatePoint(double x, double y, double z, double ox, double oy, double oz, String axis, double angle) {
        double px = x - ox;
        double py = y - oy;
        double pz = z - oz;
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        return switch (axis) {
            case "x" -> new double[] {ox + px, oy + py * cos - pz * sin, oz + py * sin + pz * cos};
            case "y" -> new double[] {ox + px * cos + pz * sin, oy + py, oz - px * sin + pz * cos};
            case "z" -> new double[] {ox + px * cos - py * sin, oy + px * sin + py * cos, oz + pz};
            default -> new double[] {x, y, z};
        };
    }

    private static void addLocalBoxIfIntersects(List<AABB> boxes, double cellMinX, double cellMinY, double cellMinZ,
            double cellMaxX, double cellMaxY, double cellMaxZ, AABB box16) {
        double minX = Math.max(box16.minX, cellMinX);
        double minY = Math.max(box16.minY, cellMinY);
        double minZ = Math.max(box16.minZ, cellMinZ);
        double maxX = Math.min(box16.maxX, cellMaxX);
        double maxY = Math.min(box16.maxY, cellMaxY);
        double maxZ = Math.min(box16.maxZ, cellMaxZ);
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            return;
        }
        boxes.add(new AABB((minX - cellMinX) / 16.0, clamp((minY - cellMinY) / 16.0, 0.0, 1.0), (minZ - cellMinZ) / 16.0,
                (maxX - cellMinX) / 16.0, clamp((maxY - cellMinY) / 16.0, 0.0, 1.0), (maxZ - cellMinZ) / 16.0));
    }

    private static VoxelShape shapeFromBoxes(List<AABB> rawBoxes) {
        List<AABB> boxes = optimizeBoxes(rawBoxes);
        if (boxes.isEmpty()) {
            return Shapes.empty();
        }
        if (boxes.size() == 1) {
            return Shapes.create(boxes.get(0));
        }
        VoxelShape shape = Shapes.empty();
        for (AABB box : boxes) {
            shape = Shapes.joinUnoptimized(shape, Shapes.create(box), BooleanOp.OR);
        }
        return shape.optimize();
    }

    private static List<AABB> optimizeBoxes(List<AABB> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return List.of();
        }
        List<AABB> current = new ArrayList<>();
        for (AABB box : boxes) {
            if (box.maxX > box.minX && box.maxY > box.minY && box.maxZ > box.minZ) {
                current.add(box);
            }
        }
        int previous;
        do {
            previous = current.size();
            current = mergeBoxesOnAxis(current, Direction.Axis.X);
            current = mergeBoxesOnAxis(current, Direction.Axis.Z);
            current = mergeBoxesOnAxis(current, Direction.Axis.Y);
        } while (current.size() < previous);
        return current;
    }

    private static List<AABB> mergeBoxesOnAxis(List<AABB> boxes, Direction.Axis axis) {
        if (boxes.size() <= 1) {
            return boxes;
        }
        List<AABB> sorted = new ArrayList<>(boxes);
        sorted.sort((a, b) -> compareForMerge(a, b, axis));
        List<AABB> merged = new ArrayList<>(sorted.size());
        AABB current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            AABB next = sorted.get(i);
            if (canMerge(current, next, axis)) {
                current = merge(current, next, axis);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static int compareForMerge(AABB a, AABB b, Direction.Axis axis) {
        int cmp;
        if (axis == Direction.Axis.X) {
            cmp = compareD(a.minY, b.minY); if (cmp != 0) return cmp;
            cmp = compareD(a.maxY, b.maxY); if (cmp != 0) return cmp;
            cmp = compareD(a.minZ, b.minZ); if (cmp != 0) return cmp;
            cmp = compareD(a.maxZ, b.maxZ); if (cmp != 0) return cmp;
            cmp = compareD(a.minX, b.minX); return cmp != 0 ? cmp : compareD(a.maxX, b.maxX);
        }
        if (axis == Direction.Axis.Y) {
            cmp = compareD(a.minX, b.minX); if (cmp != 0) return cmp;
            cmp = compareD(a.maxX, b.maxX); if (cmp != 0) return cmp;
            cmp = compareD(a.minZ, b.minZ); if (cmp != 0) return cmp;
            cmp = compareD(a.maxZ, b.maxZ); if (cmp != 0) return cmp;
            cmp = compareD(a.minY, b.minY); return cmp != 0 ? cmp : compareD(a.maxY, b.maxY);
        }
        cmp = compareD(a.minX, b.minX); if (cmp != 0) return cmp;
        cmp = compareD(a.maxX, b.maxX); if (cmp != 0) return cmp;
        cmp = compareD(a.minY, b.minY); if (cmp != 0) return cmp;
        cmp = compareD(a.maxY, b.maxY); if (cmp != 0) return cmp;
        cmp = compareD(a.minZ, b.minZ); return cmp != 0 ? cmp : compareD(a.maxZ, b.maxZ);
    }

    private static boolean canMerge(AABB a, AABB b, Direction.Axis axis) {
        if (axis == Direction.Axis.X) {
            return same(a.minY, b.minY) && same(a.maxY, b.maxY) && same(a.minZ, b.minZ) && same(a.maxZ, b.maxZ) && same(a.maxX, b.minX);
        }
        if (axis == Direction.Axis.Y) {
            return same(a.minX, b.minX) && same(a.maxX, b.maxX) && same(a.minZ, b.minZ) && same(a.maxZ, b.maxZ) && same(a.maxY, b.minY);
        }
        return same(a.minX, b.minX) && same(a.maxX, b.maxX) && same(a.minY, b.minY) && same(a.maxY, b.maxY) && same(a.maxZ, b.minZ);
    }

    private static AABB merge(AABB a, AABB b, Direction.Axis axis) {
        if (axis == Direction.Axis.X) return new AABB(a.minX, a.minY, a.minZ, b.maxX, a.maxY, a.maxZ);
        if (axis == Direction.Axis.Y) return new AABB(a.minX, a.minY, a.minZ, a.maxX, b.maxY, a.maxZ);
        return new AABB(a.minX, a.minY, a.minZ, a.maxX, a.maxY, b.maxZ);
    }

    private static Map<String, CachedShapeSet> loadPersistent() {
        if (persistentLoaded) {
            return PERSISTENT;
        }
        synchronized (PERSISTENT) {
            if (persistentLoaded) {
                return PERSISTENT;
            }
            Path path = persistentPath();
            if (Files.isRegularFile(path)) {
                try (InputStream in = new GZIPInputStream(Files.newInputStream(path));
                     InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonObject entries = root.getAsJsonObject("entries");
                    if (entries != null) {
                        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                            CachedShapeSet cached = readCachedShapeSet(entry.getValue());
                            if (cached != null) {
                                PERSISTENT.put(entry.getKey(), cached);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[Fufu's Lib] failed to load persistent model shape cache: {}", path, e);
                    PERSISTENT.clear();
                }
            }
            persistentLoaded = true;
            return PERSISTENT;
        }
    }

    private static void savePersistentIfDirty() {
        if (!persistentDirty || !persistentLoaded) {
            return;
        }
        if (!SAVE_RUNNING.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(FufuModelShapeCache::savePersistentNow, Fufulib.MODID + "-model-shape-cache-save");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        thread.start();
    }

    private static void savePersistentNow() {
        synchronized (PERSISTENT) {
            try {
                if (!persistentDirty) {
                    return;
                }
                Map<String, CachedShapeSet> snapshot = new HashMap<>(PERSISTENT);
                persistentDirty = false;
                writePersistentSnapshot(snapshot);
            } finally {
                SAVE_RUNNING.set(false);
            }
        }
        if (persistentDirty) {
            savePersistentIfDirty();
        }
    }

    private static void writePersistentSnapshot(Map<String, CachedShapeSet> snapshot) {
        try {
            Path path = persistentPath();
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject entries = new JsonObject();
            for (Map.Entry<String, CachedShapeSet> entry : snapshot.entrySet()) {
                entries.add(entry.getKey(), writeCachedShapeSet(entry.getValue()));
            }
            root.add("entries", entries);
            try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(path));
                 OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                writer.write(root.toString());
            }
        } catch (Exception e) {
            persistentDirty = true;
            LOGGER.warn("[Fufu's Lib] failed to save persistent model shape cache", e);
        }
    }

    private static CachedShapeSet readCachedShapeSet(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            return null;
        }
        JsonObject json = raw.getAsJsonObject();
        JsonElement fingerprint = json.get("fingerprint");
        JsonArray bounds = json.getAsJsonArray("bounds");
        JsonObject shapes = json.getAsJsonObject("shapes");
        if (fingerprint == null || bounds == null || bounds.size() < 6 || shapes == null) {
            return null;
        }
        IntBounds intBounds = new IntBounds(bounds.get(0).getAsInt(), bounds.get(1).getAsInt(), bounds.get(2).getAsInt(),
                bounds.get(3).getAsInt(), bounds.get(4).getAsInt(), bounds.get(5).getAsInt());
        Map<Long, List<AABB>> boxes = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : shapes.entrySet()) {
            JsonArray array = entry.getValue().getAsJsonArray();
            List<AABB> list = new ArrayList<>(array.size());
            for (JsonElement boxRaw : array) {
                JsonArray box = boxRaw.getAsJsonArray();
                if (box.size() >= 6) {
                    list.add(new AABB(box.get(0).getAsDouble(), box.get(1).getAsDouble(), box.get(2).getAsDouble(),
                            box.get(3).getAsDouble(), box.get(4).getAsDouble(), box.get(5).getAsDouble()));
                }
            }
            if (!list.isEmpty()) {
                boxes.put(Long.parseLong(entry.getKey()), list);
            }
        }
        return new CachedShapeSet(fingerprint.getAsString(), intBounds, boxes);
    }

    private static JsonObject writeCachedShapeSet(CachedShapeSet cached) {
        JsonObject json = new JsonObject();
        json.addProperty("fingerprint", cached.fingerprint());
        JsonArray bounds = new JsonArray();
        bounds.add(cached.bounds().minX()); bounds.add(cached.bounds().maxX());
        bounds.add(cached.bounds().minY()); bounds.add(cached.bounds().maxY());
        bounds.add(cached.bounds().minZ()); bounds.add(cached.bounds().maxZ());
        json.add("bounds", bounds);
        JsonObject shapes = new JsonObject();
        for (Map.Entry<Long, List<AABB>> entry : cached.boxes().entrySet()) {
            JsonArray boxes = new JsonArray();
            for (AABB box : entry.getValue()) {
                JsonArray b = new JsonArray();
                b.add(box.minX); b.add(box.minY); b.add(box.minZ);
                b.add(box.maxX); b.add(box.maxY); b.add(box.maxZ);
                boxes.add(b);
            }
            shapes.add(Long.toString(entry.getKey()), boxes);
        }
        json.add("shapes", shapes);
        return json;
    }

    private static String modelFingerprint(ResourceLocation modelId, FufuShapeMode mode) {
        String key = modelId + "|" + mode.name() + "|voxel=" + FufuLibConfig.ROTATED_ELEMENT_VOXEL_STEP_16.get()
                + "|snap=" + FufuLibConfig.MULTIBLOCK_BOUNDS_SNAP_16.get();
        return FINGERPRINTS.computeIfAbsent(key, ignored -> {
            StringBuilder data = new StringBuilder(CACHE_ALGORITHM_VERSION).append("|").append(key);
            appendModelChainFingerprint(data, modelId);
            return sha256(data.toString());
        });
    }
    @SuppressWarnings("all")
    private static void appendModelChainFingerprint(StringBuilder data, ResourceLocation modelId) {
        ResourceLocation current = modelId;
        for (int i = 0; i < 16; i++) {
            String path = modelPath(current);
            String raw = readResourceString(path);
            data.append("|path=").append(path).append("|json=").append(raw == null ? "<missing>" : raw);
            if (raw == null) return;
            JsonObject json;
            try {
                json = JsonParser.parseString(raw).getAsJsonObject();
            } catch (Exception e) {
                return;
            }
            JsonArray elements = json.getAsJsonArray("elements");
            if (elements != null && !elements.isEmpty()) return;
            JsonElement parent = json.get("parent");
            if (parent == null || !parent.isJsonPrimitive()) return;
            String value = parent.getAsString();
            current = value.contains(":") ? new ResourceLocation(value) : new ResourceLocation(current.getNamespace(), value);
        }
    }

    private static String sha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(data.hashCode());
        }
    }

    private static Path persistentPath() {
        return FMLPaths.GAMEDIR.get().resolve(Fufulib.MODID).resolve("model_shape_cache_v1.json.gz");
    }

    private static String shapeSetKey(ResourceLocation modelId, Direction facing, FufuShapeMode mode) {
        return modelId + "|" + horizontalIndex(facing) + "|" + mode.name();
    }

    private static String persistentKey(ResourceLocation modelId, Direction facing, FufuShapeMode mode) {
        return shapeSetKey(modelId, facing, mode);
    }

    private static AABB rotate90Y16(AABB box, int turns) {
        AABB result = box;
        for (int i = 0; i < Math.floorMod(turns, 4); i++) {
            result = new AABB(16.0 - result.maxZ, result.minY, result.minX, 16.0 - result.minZ, result.maxY, result.maxX);
        }
        return result;
    }

    private static AABB clampToReasonableRange(AABB box) {
        double minX = clamp(box.minX, -64.0, 80.0);
        double minY = clamp(box.minY, -64.0, 80.0);
        double minZ = clamp(box.minZ, -64.0, 80.0);
        double maxX = clamp(box.maxX, -64.0, 80.0);
        double maxY = clamp(box.maxY, -64.0, 80.0);
        double maxZ = clamp(box.maxZ, -64.0, 80.0);
        return maxX <= minX || maxY <= minY || maxZ <= minZ ? new AABB(0, 0, 0, 16, 16, 16) : new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static AABB snapBounds16ToCellEdges(AABB box, double threshold) {
        return new AABB(snapMin(box.minX, threshold), snapMin(box.minY, threshold), snapMin(box.minZ, threshold),
                snapMax(box.maxX, threshold), snapMax(box.maxY, threshold), snapMax(box.maxZ, threshold));
    }

    private static double snapMin(double value, double threshold) {
        double edge = Math.floor(value / 16.0) * 16.0;
        return value - edge <= threshold ? edge : value;
    }

    private static double snapMax(double value, double threshold) {
        double edge = Math.ceil(value / 16.0) * 16.0;
        return edge - value <= threshold ? edge : value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int horizontalIndex(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    private static long packOffset(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (long) (z & 0x1FFFFF);
    }

    private static int compareD(double a, double b) {
        return same(a, b) ? 0 : Double.compare(a, b);
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    private static long elapsedMillis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    public record IntBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public boolean isEmpty() {
            return maxX < minX || maxY < minY || maxZ < minZ;
        }
    }

    private record SplitShapeSet(IntBounds bounds, Map<Long, VoxelShape> shapes) {
    }

    private record CachedShapeSet(String fingerprint, IntBounds bounds, Map<Long, List<AABB>> boxes) {
        int boxCount() {
            int total = 0;
            for (List<AABB> value : boxes.values()) total += value.size();
            return total;
        }

        SplitShapeSet toSplitShapeSet() {
            Map<Long, VoxelShape> shapes = new ConcurrentHashMap<>();
            for (Map.Entry<Long, List<AABB>> entry : boxes.entrySet()) {
                VoxelShape shape = shapeFromBoxes(entry.getValue());
                if (!shape.isEmpty()) shapes.put(entry.getKey(), shape);
            }
            return new SplitShapeSet(bounds, shapes);
        }

        CachedShapeSet optimized() {
            Map<Long, List<AABB>> optimized = new HashMap<>();
            for (Map.Entry<Long, List<AABB>> entry : boxes.entrySet()) {
                List<AABB> value = optimizeBoxes(entry.getValue());
                if (!value.isEmpty()) optimized.put(entry.getKey(), value);
            }
            return new CachedShapeSet(fingerprint, bounds, optimized);
        }
    }

    private record ModelGeometry(AABB bounds16, List<ModelElement> elements, int elementCount) {
    }

    private record ModelElement(AABB box16, ElementRotation rotation, AABB bounds16, List<AABB> voxelBoxes16) {
        boolean isRotated() {
            return rotation != null && Math.abs(rotation.angleRad()) > EPS;
        }
    }

    private record ElementRotation(String axis, double angleRad, double originX, double originY, double originZ) {
    }
}

