package dev.proceduralbites.core;

import dev.proceduralbites.core.BiteFrames.Kind;
import dev.proceduralbites.core.BiteFrames.Result;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Caché en disco de los fotogramas. La clave es el SHA-256 de todo lo que decide el
 * resultado: versión del algoritmo, sal del loader, punto de entrada, nombre, opciones y
 * píxeles. Todo va en un solo archivo (con un archivo por entrada, abrir 1008 tardaba más
 * que generarlos). Nunca lanza: lo ilegible cuenta como que no está.
 */
public final class FrameCache {
    /** Subirla cada vez que cambia un píxel o un motivo de omisión; {@code FrameCacheTest} lo controla. */
    public static final int ALGORITHM_VERSION = 6;

    /** Entrada más grande que se acepta: 3 fotogramas de 2 capas a 256x sin comprimir, y margen. */
    static final int MAX_ENTRY_BYTES = 3 * 2 * BiteFrames.MAX_SIZE * BiteFrames.MAX_SIZE * 4 + 4096;
    /** Archivo más grande que se intenta leer; uno mayor se ignora y se reemplaza al guardar. */
    static final long MAX_PACK_BYTES = 256L << 20;
    static final int MAX_REASON_BYTES = 2048;
    static final String FILE = "frames.pbp";
    private static final byte[] MAGIC = {'P', 'B', 'C', '1'};
    private static final byte[] PACK_MAGIC = {'P', 'B', 'P', '1'};
    private static final int KEY_BYTES = 32;
    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    private static final Kind[] KINDS = Kind.values();

    private record Entry(byte[] data, long lastUsed) {
    }

    private final Path dir;
    private final String salt;
    private final LongSupplier clock;
    private final long maxPackBytes;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile boolean loaded;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger misses = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicLong generationNanos = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicInteger temp = new AtomicInteger();

    /** {@code dir}: carpeta de la caché, o {@code null} para no usar el disco; {@code salt}: la versión del mod. */
    public FrameCache(Path dir, String salt) {
        this(dir, salt, System::currentTimeMillis, MAX_PACK_BYTES);
    }

    FrameCache(Path dir, String salt, LongSupplier clock) {
        this(dir, salt, clock, MAX_PACK_BYTES);
    }

    FrameCache(Path dir, String salt, LongSupplier clock, long maxPackBytes) {
        this.dir = dir;
        this.salt = salt == null ? "" : salt;
        this.clock = clock;
        this.maxPackBytes = maxPackBytes;
    }

    public Result solid(RgbaImage texture, String name) {
        return cached(key("solid").text(name).image(texture).hex(), () -> BiteFrames.solid(texture, name));
    }

    public Result container(RgbaImage texture, RgbaImage empty, boolean eaten) {
        return cached(key("container").flag(eaten).image(texture).image(empty).hex(),
                () -> BiteFrames.container(texture, empty, eaten));
    }

    public Result guessed(RgbaImage texture, String name, boolean drink, List<RgbaImage> bowls,
            List<RgbaImage> bottles) {
        return cached(key("guessed").text(name).flag(drink).image(texture).images(bowls).images(bottles).hex(),
                () -> BiteFrames.guessed(texture, name, drink, bowls, bottles));
    }

    /**
     * La clave lleva solo las texturas de la misma plantilla: si cambia otra comida del mod, la
     * entrada sigue valiendo.
     */
    public Result guessed(RgbaImage texture, String name, boolean drink, List<RgbaImage> bowls,
            List<RgbaImage> bottles, List<RgbaImage> others) {
        List<RgbaImage> group = drink ? List.of() : BiteFrames.templateGroup(texture, others);
        return cached(key("guessedTemplate").text(name).flag(drink).image(texture).images(bowls).images(bottles)
                .images(group).hex(), () -> BiteFrames.guessed(texture, name, drink, bowls, bottles, group));
    }

    public Result topCup(RgbaImage texture) {
        return cached(key("topCup").image(texture).hex(), () -> BiteFrames.topCup(texture));
    }

    public Result potion(RgbaImage bottle, RgbaImage overlay) {
        return cached(key("potion").image(bottle).image(overlay).hex(), () -> BiteFrames.potion(bottle, overlay));
    }

    public int hits() {
        return hits.get();
    }

    public int misses() {
        return misses.get();
    }

    public int errors() {
        return errors.get();
    }

    public String lastError() {
        return lastError.get();
    }

    public long generationMillis() {
        return generationNanos.get() / 1_000_000;
    }

    private Result cached(String key, Supplier<Result> generate) {
        Result found = read(key);
        if (found != null) {
            hits.incrementAndGet();
            return found;
        }
        misses.incrementAndGet();
        long start = System.nanoTime();
        Result made = generate.get();
        generationNanos.addAndGet(System.nanoTime() - start);
        write(key, made);
        return made;
    }


    Key key(String entry) {
        return new Key().number(ALGORITHM_VERSION).text(salt).text(entry);
    }

    /** Cada campo lleva su tipo y su largo: dos listas distintas de campos nunca dan los mismos bytes. */
    static final class Key {
        private final MessageDigest digest;

        Key() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);   // toda JVM trae SHA-256
            }
        }

        private void tag(char type, int length) {
            digest.update((byte) type);
            digest.update(new byte[] {(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8),
                (byte) length});
        }

        Key number(int n) {
            tag('n', n);
            return this;
        }

        Key text(String s) {
            byte[] raw = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
            tag(s == null ? 'z' : 't', raw.length);
            digest.update(raw);
            return this;
        }

        Key flag(boolean b) {
            tag('f', b ? 1 : 0);
            return this;
        }

        Key image(RgbaImage im) {
            tag('i', im.width);
            tag('h', im.height);
            digest.update(im.rgba());
            return this;
        }

        Key images(List<RgbaImage> list) {
            tag('l', list.size());
            for (RgbaImage im : list) {
                image(im);
            }
            return this;
        }

        String hex() {
            return FrameCache.hex(digest.digest());
        }
    }

    // frames.pbp: "PBP1" | i32 cantidad | por entrada: 32 bytes de clave | i64 último uso
    // (ms) | i32 largo | la entrada (formato de abajo, con su CRC)

    private void failed(String what, Exception e) {
        errors.incrementAndGet();
        lastError.set(what + ": " + e);
    }

    private void load() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    readPack();
                    loaded = true;
                }
            }
        }
    }

    /** Agrega las entradas del archivo que no estén en memoria; un archivo cortado vale hasta donde se leyó. */
    private void readPack() {
        Path file = dir.resolve(FILE);
        long now = clock.getAsLong();
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > maxPackBytes) {
                return;
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
                byte[] magic = in.readNBytes(PACK_MAGIC.length);
                if (!Arrays.equals(magic, PACK_MAGIC)) {
                    return;
                }
                int count = in.readInt();
                for (int i = 0; i < count; i++) {   // sin reservar según count: puede ser falso
                    byte[] key = in.readNBytes(KEY_BYTES);
                    if (key.length != KEY_BYTES) {
                        return;
                    }
                    long used = Math.min(in.readLong(), now);   // una fecha futura no la haría eterna
                    int length = in.readInt();
                    if (length < 0 || length > MAX_ENTRY_BYTES) {
                        return;
                    }
                    byte[] data = in.readNBytes(length);
                    if (data.length != length) {
                        return;
                    }
                    entries.putIfAbsent(hex(key), new Entry(data, used));
                }
            }
        } catch (EOFException e) {
            // cortado: lo leído vale
        } catch (IOException | RuntimeException e) {
            failed("no se pudo leer " + FILE, e);
        }
    }

    Result read(String key) {
        if (dir == null) {
            return null;
        }
        load();
        Entry e = entries.get(key);
        if (e == null) {
            return null;
        }
        Result result = decode(e.data());
        if (result == null) {
            return null;   // dañada: se regenera y write() la reemplaza
        }
        long now = clock.getAsLong();
        if (now - e.lastUsed() > DAY_MILLIS && entries.replace(key, e, new Entry(e.data(), now))) {
            dirty.set(true);   // se anota el uso como mucho una vez al día: sin cambios no se reescribe
        }
        return result;
    }

    void write(String key, Result result) {
        if (dir == null) {
            return;
        }
        try {
            entries.put(key, new Entry(encode(result), clock.getAsLong()));
            dirty.set(true);
        } catch (RuntimeException e) {
            failed("no se pudo codificar " + key, e);
        }
    }

    /** Guarda si hubo cambios, sin las entradas sin usar en {@code maxAgeMillis}; antes une lo de otra instancia. */
    public synchronized int save(long maxAgeMillis) {
        if (dir == null) {
            return 0;
        }
        load();
        int before = entries.size();
        readPack();
        if (entries.size() != before) {
            dirty.set(true);
        }
        long limit = clock.getAsLong() - maxAgeMillis;
        int removed = 0;
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getValue().lastUsed() < limit && entries.remove(e.getKey(), e.getValue())) {
                removed++;
            }
        }
        if (!dirty.getAndSet(false) && removed == 0) {
            return 0;
        }
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            removeOrphans();
            tmp = dir.resolve(FILE + "." + ProcessHandle.current().pid() + "-" + temp.incrementAndGet() + ".tmp");
            List<Map.Entry<String, Entry>> all = new ArrayList<>(entries.entrySet());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
                out.write(PACK_MAGIC);
                out.writeInt(all.size());
                for (Map.Entry<String, Entry> e : all) {
                    out.write(unhex(e.getKey()));
                    out.writeLong(e.getValue().lastUsed());
                    out.writeInt(e.getValue().data().length);
                    out.write(e.getValue().data());
                }
            }
            try {
                Files.move(tmp, dir.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            dirty.set(true);   // se intenta de nuevo en el próximo save()
            failed("no se pudo guardar " + FILE, e);
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException | RuntimeException ignored) {
                    // el temporal huérfano lo borra un save() posterior
                }
            }
        }
        return removed;
    }

    private void removeOrphans() throws IOException {
        long limit = System.currentTimeMillis() - DAY_MILLIS;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, FILE + ".*.tmp")) {
            for (Path f : files) {
                if (Files.isRegularFile(f) && Files.getLastModifiedTime(f).toMillis() < limit) {
                    Files.delete(f);
                }
            }
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }

    private static byte[] unhex(String hex) {
        byte[] out = new byte[KEY_BYTES];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    // "PBC1" | u8 tipo | u8 generado
    //   omitido:  u16 largo | motivo UTF-8
    //   generado: u8 fotogramas | u8 capas | u16 ancho | u16 alto | i32 largo comprimido
    //             | las capas de todos los fotogramas, RGBA crudo, comprimidas (deflate)
    // | u32 CRC-32 de todo lo anterior

    static byte[] encode(Result result) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(MAGIC);
            out.writeByte(result.kind().ordinal());
            out.writeByte(result.generated() ? 1 : 0);
            if (!result.generated()) {
                byte[] why = result.skipped().getBytes(StandardCharsets.UTF_8);
                int n = Math.min(why.length, MAX_REASON_BYTES);
                out.writeShort(n);
                out.write(why, 0, n);
            } else {
                List<List<RgbaImage>> frames = result.frames();
                RgbaImage first = frames.get(0).get(0);
                int layers = frames.get(0).size();
                out.writeByte(frames.size());
                out.writeByte(layers);
                out.writeShort(first.width);
                out.writeShort(first.height);
                Deflater deflater = new Deflater(Deflater.BEST_SPEED);
                try {
                    ByteArrayOutputStream packed = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    for (List<RgbaImage> frame : frames) {
                        if (frame.size() != layers) {
                            throw new IllegalArgumentException("fotogramas con distinta cantidad de capas");
                        }
                        for (RgbaImage layer : frame) {
                            if (layer.width != first.width || layer.height != first.height) {
                                throw new IllegalArgumentException("capas de distinto tamaño");
                            }
                            deflater.setInput(layer.rgba());
                            while (!deflater.needsInput()) {
                                packed.write(buf, 0, deflater.deflate(buf));
                            }
                        }
                    }
                    deflater.finish();
                    while (!deflater.finished()) {
                        packed.write(buf, 0, deflater.deflate(buf));
                    }
                    out.writeInt(packed.size());
                    packed.writeTo(out);
                } finally {
                    deflater.end();
                }
            }
            out.flush();
            CRC32 crc = new CRC32();
            crc.update(bytes.toByteArray());
            out.writeInt((int) crc.getValue());
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // un ByteArrayOutputStream no falla
        }
        return bytes.toByteArray();
    }

    static Result decode(byte[] raw) {
        try {
            if (raw.length < MAGIC.length + 2 + 4) {
                return null;
            }
            CRC32 crc = new CRC32();
            crc.update(raw, 0, raw.length - 4);
            int stored = ((raw[raw.length - 4] & 255) << 24) | ((raw[raw.length - 3] & 255) << 16)
                    | ((raw[raw.length - 2] & 255) << 8) | (raw[raw.length - 1] & 255);
            if ((int) crc.getValue() != stored) {
                return null;
            }
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(raw, 0, raw.length - 4));
            for (byte b : MAGIC) {
                if (in.readByte() != b) {
                    return null;
                }
            }
            int kindId = in.readUnsignedByte();
            int generated = in.readUnsignedByte();
            if (kindId >= KINDS.length || generated > 1) {
                return null;
            }
            Kind kind = KINDS[kindId];
            if (generated == 0) {
                int n = in.readUnsignedShort();
                if (n == 0 || n > MAX_REASON_BYTES || n != in.available()) {
                    return null;
                }
                return Result.skip(kind, new String(in.readNBytes(n), StandardCharsets.UTF_8));
            }
            int frames = in.readUnsignedByte();
            int layers = in.readUnsignedByte();
            int w = in.readUnsignedShort();
            int h = in.readUnsignedShort();
            int packed = in.readInt();
            if (frames != Params.NUM_FRAMES || layers != (kind == Kind.POTION ? 2 : 1) || w < 1 || h < 1
                    || w > BiteFrames.MAX_SIZE || h > BiteFrames.MAX_SIZE || packed < 0 || packed != in.available()) {
                return null;
            }
            List<List<RgbaImage>> out = inflate(in, frames, layers, w, h);
            return out == null ? null : new Result(kind, out, null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Descomprime exactamente los bytes que tocan: ni menos (truncado) ni más (bomba de compresión). */
    private static List<List<RgbaImage>> inflate(InputStream in, int frames, int layers, int w, int h)
            throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(in.readAllBytes());
            List<List<RgbaImage>> out = new ArrayList<>();
            for (int f = 0; f < frames; f++) {
                List<RgbaImage> frame = new ArrayList<>();
                for (int l = 0; l < layers; l++) {
                    byte[] rgba = new byte[w * h * 4];
                    int done = 0;
                    while (done < rgba.length) {
                        int n = inflater.inflate(rgba, done, rgba.length - done);
                        if (n == 0) {
                            return null;   // se acabaron los datos antes de llenar la capa
                        }
                        done += n;
                    }
                    frame.add(new RgbaImage(w, h, rgba));
                }
                out.add(List.copyOf(frame));
            }
            byte[] extra = new byte[1];
            if (inflater.inflate(extra) != 0 || !inflater.finished()) {
                return null;
            }
            return List.copyOf(out);
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
