package top.sshh.bililiverecoder.util;

import com.google.zxing.common.BitMatrix;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

public final class PngBitMatrixWriter {
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private PngBitMatrixWriter() {
    }

    public static byte[] toPng(BitMatrix matrix) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(PNG_SIGNATURE);

        writeChunk(png, "IHDR", ihdr(matrix.getWidth(), matrix.getHeight()));
        writeChunk(png, "IDAT", idat(matrix));
        writeChunk(png, "IEND", new byte[0]);

        return png.toByteArray();
    }

    private static byte[] ihdr(int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(13);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(width);
        out.writeInt(height);
        out.writeByte(8);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] idat(BitMatrix matrix) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            for (int y = 0; y < height; y++) {
                deflater.write(0);
                for (int x = 0; x < width; x++) {
                    deflater.write(matrix.get(x, y) ? 0 : 255);
                }
            }
        }
        return compressed.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream png, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        DataOutputStream out = new DataOutputStream(png);
        out.writeInt(data.length);
        out.write(typeBytes);
        out.write(data);
        out.writeInt(crc(typeBytes, data));
    }

    private static int crc(byte[] type, byte[] data) {
        CRC32 crc32 = new CRC32();
        crc32.update(type);
        crc32.update(data);
        return (int) crc32.getValue();
    }
}
