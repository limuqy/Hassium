import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Local benchmark controller. No dependency on Hassium or a Minecraft mapping. */
public final class SparkBenchBridge {
    private static final Path ROOT = Path.of(System.getProperty("sparkbench.dir"));
    private static final String SIDE = System.getProperty("sparkbench.side");
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final ConcurrentLinkedQueue<String[]> MAIN = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> LIGHT_ROWS = new ConcurrentLinkedQueue<>();
    private static volatile Object platform;
    private static Object client;
    private static String phase;
    private static long[] samples = new long[500000];
    private static int count;
    private static long loopStart, periodStart, cpuStart, processStart;
    private static boolean moving;
    private static int direction = 1, turns;
    private static double startX, minimumX, maximumX;
    private static boolean overflow;
    private static boolean clientReady;
    private static long playerPresentSince;
    private static final LongAdder ENCODED = new LongAdder();
    private static long encodedStart, dataPackets, movePackets, teleportPackets;
    private static volatile Method readableBytes;
    private static StringBuilder scenes;
    private static long lastScene;
    private static long lastLightSnapshot;
    private static volatile boolean lightSnapshotUnavailable;
    private static volatile boolean lightSnapshotFailed;
    private static volatile Method lightSnapshotMethod;
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Map<String, Field> FIELDS = new HashMap<>();

    public static void start() throws Exception {
        System.getProperties().put("sparkbench.beginLoop", (java.util.function.Consumer<Object>) SparkBenchBridge::beginLoop);
        System.getProperties().put("sparkbench.endLoop", (java.util.function.Consumer<Object>) SparkBenchBridge::endLoop);
        System.getProperties().put("sparkbench.captureSpark", (java.util.function.Consumer<Object>) SparkBenchBridge::captureSpark);
        System.getProperties().put("sparkbench.packet", (java.util.function.Consumer<Object>) SparkBenchBridge::packet);
        System.getProperties().put("sparkbench.encoded", (java.util.function.Consumer<Object>) SparkBenchBridge::encoded);
        Files.createDirectories(ROOT);
        if (CPU.isThreadCpuTimeSupported() && !CPU.isThreadCpuTimeEnabled()) CPU.setThreadCpuTimeEnabled(true);
        Thread control = new Thread(() -> {
            try (WatchService watch = FileSystems.getDefault().newWatchService()) {
                ROOT.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                Files.writeString(ROOT.resolve("light-confirmation.csv"), "elapsed_ns,phase,available,pending,high_water,overflow,marked,coalesced,examined,max_wait_ns,oldest_ns,closed,reason\n");
                while (true) {
                    for (String row; (row = LIGHT_ROWS.poll()) != null;) {
                        Files.writeString(ROOT.resolve("light-confirmation.csv"), row + "\n", StandardOpenOption.APPEND);
                    }
                    try (var paths = Files.list(ROOT)) {
                        for (Path file : paths.filter(p -> p.toString().endsWith(".cmd")).sorted().toList()) {
                            String id = file.getFileName().toString().replace(".cmd", "");
                            String command = Files.readString(file).trim();
                            Files.delete(file);
                            try {
                                if (command.startsWith("spark ")) {
                                    spark(command.substring(6)); ack(id, "OK");
                                } else MAIN.add(new String[]{id, command});
                            } catch (Throwable error) { fail(id, error); }
                        }
                    }
                    WatchKey key = watch.poll(250, TimeUnit.MILLISECONDS);
                    if (key != null) { key.pollEvents(); key.reset(); }
                }
            } catch (Throwable error) { error.printStackTrace(); }
        }, "spark-bench-control");
        control.setDaemon(true); control.start();
    }

    public static void captureSpark(Object value) {
        try {
            Object plugin = value.getClass().getMethod("getPlugin").invoke(value);
            String type = plugin.getClass().getName();
            if ((SIDE.equals("client") && type.contains("Client")) || (SIDE.equals("server") && type.contains("Server"))) {
                platform = value;
                Files.writeString(ROOT.resolve("spark-ready.txt"), type);
            }
        } catch (Throwable error) { error.printStackTrace(); }
    }

    private static void spark(String command) throws Exception {
        Object value = platform;
        if (value == null) throw new IllegalStateException("spark platform is not ready");
        ClassLoader loader = value.getClass().getClassLoader();
        // Spark creates workers lazily. They must inherit its mod loader, not the agent loader.
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> senderType = Class.forName("me.lucko.spark.common.command.sender.CommandSender", true, loader);
        Object sender = Proxy.newProxyInstance(loader, new Class<?>[]{senderType}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getName", "toString" -> "LocalSparkBenchmark";
                case "getUniqueId" -> null;
                case "isPlayer" -> false;
                case "hasPermission" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toData" -> Class.forName(senderType.getName() + "$Data", true, loader)
                        .getConstructor(String.class, UUID.class).newInstance("LocalSparkBenchmark", null);
                case "sendMessage" -> { System.out.println("[SPARK-BENCH-RESPONSE] " + args[0]); yield null; }
                default -> throw new UnsupportedOperationException(method.toString());
            };
        });
        Object result = value.getClass().getMethod("executeCommand", senderType, String[].class)
                .invoke(value, sender, command.split(" "));
        if (result instanceof Future<?> future) future.get(60, TimeUnit.SECONDS);
    }

    private static boolean target() {
        return Thread.currentThread().getName().equals(SIDE.equals("client") ? "Render thread" : "Server thread");
    }

    public static void beginLoop(Object instance) {
        if (!target()) return;
        if (SIDE.equals("client")) client = instance;
        if (client != null && !clientReady) try {
            if (player() != null) {
                if (playerPresentSince == 0) playerPresentSince = System.nanoTime();
                if (System.nanoTime() - playerPresentSince > 2_000_000_000L) {
                    Files.writeString(ROOT.resolve("client-ready.txt"), "ready"); clientReady = true;
                }
            }
        } catch (Throwable error) { fail("ready", error); }
        for (String[] message; (message = MAIN.poll()) != null;) {
            try { command(message[1]); ack(message[0], "OK"); }
            catch (Throwable error) { fail(message[0], error); }
        }
        if (moving) {
            try { move(); }
            catch (Throwable error) { moving = false; fail("movement", error); }
        }
        loopStart = System.nanoTime();
    }

    public static void endLoop(Object instance) {
        if (!target() || phase == null) return;
        if (SIDE.equals("client")) sampleLightConfirmation();
        if (count == samples.length) { overflow = true; return; }
        samples[count++] = System.nanoTime() - loopStart;
    }

    private static void sampleLightConfirmation() {
        if (lightSnapshotUnavailable || lightSnapshotFailed) return;
        long now = System.nanoTime();
        if (now - lastLightSnapshot < 1_000_000_000L) return;
        lastLightSnapshot = now;
        try {
            ClassLoader loader = client.getClass().getClassLoader();
            Class<?> registryType;
            try {
                registryType = Class.forName("io.github.limuqy.mc.hassium.shadow.server.ShadowServerRegistry", false, loader);
            } catch (ClassNotFoundException absent) {
                lightUnavailable(now, "registry-class-missing");
                return;
            }
            Object registry = registryType.getMethod("getInstance").invoke(null);
            Object server = registryType.getMethod("get").invoke(registry);
            if (server == null) return;
            Method accessor = lightSnapshotMethod;
            if (accessor == null) {
                try {
                    accessor = server.getClass().getMethod("lightConfirmationSnapshot");
                    lightSnapshotMethod = accessor;
                } catch (NoSuchMethodException absent) {
                    lightUnavailable(now, "snapshot-api-missing");
                    return;
                }
            }
            Object snapshot = accessor.invoke(server);
            String[] fields = {"pending", "highWater", "overflow", "marked", "coalesced", "examined", "maxWaitNs", "oldestNs", "closed"};
            StringBuilder row = new StringBuilder().append(now - periodStart).append(',').append(phase).append(",true");
            for (String field : fields) row.append(',').append(snapshot.getClass().getMethod(field).invoke(snapshot));
            row.append(',');
            LIGHT_ROWS.add(row.toString());
        } catch (Throwable error) {
            lightSnapshotFailed = true;
            LIGHT_ROWS.add((now - periodStart) + "," + phase + ",error,NA,NA,NA,NA,NA,NA,NA,NA,NA,api-call-failed");
            fail("light-snapshot", error);
        }
    }

    private static void lightUnavailable(long now, String reason) {
        lightSnapshotUnavailable = true;
        LIGHT_ROWS.add((now - periodStart) + "," + phase + ",false,NA,NA,NA,NA,NA,NA,NA,NA,NA," + reason);
        System.err.println("[SPARK-BENCH] light confirmation telemetry unavailable: " + reason);
    }

    private static long processCpu() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }

    public static void packet(Object packet) {
        if (phase == null || !Thread.currentThread().getName().equals("Render thread")) return;
        String name = packet.getClass().getSimpleName();
        if (name.equals("ClientboundSetEntityDataPacket")) dataPackets++;
        else if (name.equals("ClientboundTeleportEntityPacket")) teleportPackets++;
        else movePackets++;
    }

    public static void encoded(Object buffer) {
        try {
            Method method = readableBytes;
            if (method == null) readableBytes = method = Class.forName("io.netty.buffer.ByteBuf", false,
                    buffer.getClass().getClassLoader()).getMethod("readableBytes");
            int n = (int) method.invoke(buffer);
            int prefix = n < 128 ? 1 : n < 16384 ? 2 : n < 2097152 ? 3 : 4;
            ENCODED.add(n + prefix);
        } catch (Throwable error) { fail("encoded", error); }
    }

    private static void command(String command) throws Exception {
        String[] parts = command.split(" ");
        switch (parts[0]) {
            case "begin" -> {
                if (phase != null) throw new IllegalStateException("measurement already active");
                count = 0; overflow = false; phase = parts[1]; turns = 0;
                encodedStart = ENCODED.sum(); dataPackets = movePackets = teleportPackets = 0;
                scenes = new StringBuilder("elapsed_s,x,y,z,loaded_chunks,renderer\n"); lastScene = 0;
                periodStart = System.nanoTime(); cpuStart = CPU.getCurrentThreadCpuTime(); processStart = processCpu();
                if (client != null) { double x = positionX(); minimumX = maximumX = x; }
            }
            case "end" -> {
                long elapsed = System.nanoTime() - periodStart;
                long cpu = CPU.getCurrentThreadCpuTime() - cpuStart, process = processCpu() - processStart;
                String name = phase; phase = null;
                if (name == null || overflow || count == 0) throw new IllegalStateException("invalid measurement");
                StringBuilder csv = new StringBuilder("duration_ns\n");
                for (int i = 0; i < count; i++) csv.append(samples[i]).append('\n');
                Files.writeString(ROOT.resolve(name + ".csv"), csv);
                String json = String.format(Locale.ROOT,
                    "{\"phase\":\"%s\",\"side\":\"%s\",\"elapsedNs\":%d,\"threadCpuNs\":%d,\"processCpuNs\":%d,\"count\":%d,\"turns\":%d,\"minX\":%.3f,\"maxX\":%.3f,\"encodedBytes\":%d,\"entityDataPackets\":%d,\"entityMovePackets\":%d,\"entityTeleportPackets\":%d}",
                    name, SIDE, elapsed, cpu, process, count, turns, minimumX, maximumX, ENCODED.sum() - encodedStart, dataPackets, movePackets, teleportPackets);
                Files.writeString(ROOT.resolve(name + ".json"), json);
                if (client != null) Files.writeString(ROOT.resolve(name + "-scene.csv"), scenes);
            }
            case "move" -> {
                startX = Double.parseDouble(parts[1]); direction = 1; moving = true;
                move();
            }
            case "halt" -> { moving = false; key(false); }
            case "quit" -> { moving = false; key(false); invoke(client, new String[]{"stop", "m_91395_"}); }
            default -> throw new IllegalArgumentException(command);
        }
    }

    private static Object field(Object object, String... names) throws Exception {
        String cacheKey = object.getClass().getName() + names[0];
        Field cached = FIELDS.get(cacheKey);
        if (cached != null) return cached.get(object);
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            for (String name : names) try { Field f = type.getDeclaredField(name); f.setAccessible(true); FIELDS.put(cacheKey, f); return f.get(object); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(object.getClass() + " " + Arrays.toString(names));
    }

    private static void boolField(Object object, boolean value, String... names) throws Exception {
        String cacheKey = object.getClass().getName() + names[0];
        Field cached = FIELDS.get(cacheKey);
        if (cached != null) { cached.setBoolean(object, value); return; }
        for (String name : names) try { Field f = object.getClass().getField(name); FIELDS.put(cacheKey, f); f.setBoolean(object, value); return; }
        catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(Arrays.toString(names));
    }

    private static Object invoke(Object object, String[] names, Object... args) throws Exception {
        String cacheKey = object.getClass().getName() + names[0] + args.length;
        Method cached = METHODS.get(cacheKey);
        if (cached != null) return cached.invoke(object, args);
        for (String name : names) for (Method method : object.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) { METHODS.put(cacheKey, method); return method.invoke(object, args); }
        }
        throw new NoSuchMethodException(object.getClass() + " " + Arrays.toString(names));
    }

    private static Object player() throws Exception { return field(client, "player", "f_91074_"); }
    private static double positionX() throws Exception { return ((Number) invoke(player(), new String[]{"getX", "m_20185_"})).doubleValue(); }
    private static void key(boolean down) throws Exception {
        Object options = field(client, "options", "f_91066_");
        invoke(field(options, "keyUp", "f_92085_"), new String[]{"setDown", "m_7249_"}, down);
    }

    private static void move() throws Exception {
        Object player = player();
        if (player == null) throw new IllegalStateException("client disconnected");
        Object abilities = invoke(player, new String[]{"getAbilities", "m_150110_"});
        boolField(abilities, true, "flying", "f_35935_");
        double x = positionX(); minimumX = Math.min(minimumX, x); maximumX = Math.max(maximumX, x);
        if (direction > 0 && x >= startX + 224 || direction < 0 && x <= startX) { direction = -direction; turns++; }
        invoke(player, new String[]{"setYRot", "m_146922_"}, direction > 0 ? -90f : 90f);
        invoke(player, new String[]{"setXRot", "m_146926_"}, 12f);
        // Key state is applied at frame entry; vanilla input and player physics do the movement.
        key(true);
        long now = System.nanoTime();
        if (phase != null && now - lastScene > 1_000_000_000L) {
            lastScene = now;
            Object level = field(client, "level", "f_91073_");
            Object chunks = invoke(level, new String[]{"getChunkSource", "m_7726_"});
            Object renderer = field(client, "levelRenderer", "f_91060_");
            scenes.append(String.format(Locale.ROOT, "%.3f,%.3f,%s,%s,%s,\"%s\"\n", (now-periodStart)/1e9, x,
                    invoke(player,new String[]{"getY","m_20186_"}), invoke(player,new String[]{"getZ","m_20189_"}),
                    invoke(chunks,new String[]{"getLoadedChunksCount","m_8482_"}),
                    invoke(renderer,new String[]{"getChunkStatistics","getSectionStatistics","m_109820_"})));
        }
    }

    private static void ack(String id, String value) throws Exception { Files.writeString(ROOT.resolve(id + ".ack"), value, StandardCharsets.UTF_8); }
    private static void fail(String id, Throwable error) {
        error.printStackTrace();
        try { ack(id, "ERROR " + error); } catch (Exception ignored) { }
    }
}
