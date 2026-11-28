package com.drivehub.kamera.side;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;

/** Fixed-size UV grids. The key identifies the projection algorithm and the validated calibration. */
final class WheelLensMapCache {
    private final Path file;

    WheelLensMapCache(File directory) {
        file = directory.toPath().resolve("curbside-maps.bin");
    }

    private static int floatCount(int index) {
        int height = index < 4 ? SideCameraGeometry.WHEEL_HEIGHT : SideCameraGeometry.SIDE_HEIGHT;
        return SideCameraGeometry.TILE_WIDTH * height * 2;
    }

    float[][] read(String key) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (!key.equals(in.readUTF())) return null;
            CRC32 crc = new CRC32();
            float[][] maps = new float[6][];
            for (int i = 0; i < maps.length; i++) {
                byte[] bytes = new byte[floatCount(i) * Float.BYTES];
                in.readFully(bytes);
                crc.update(bytes);
                maps[i] = new float[floatCount(i)];
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(maps[i]);
            }
            if (in.readLong() != crc.getValue() || in.read() != -1) throw new IOException("Invalid curbside map checksum or length");
            return maps;
        }
    }

    void write(String key, float[][] maps) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "curbside-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
                CRC32 crc = new CRC32();
                out.writeUTF(key);
                for (float[] map : maps) {
                    ByteBuffer bytes = ByteBuffer.allocate(map.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                    bytes.asFloatBuffer().put(map);
                    crc.update(bytes.array());
                    out.write(bytes.array());
                }
                out.writeLong(crc.getValue());
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
