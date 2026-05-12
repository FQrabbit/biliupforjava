package top.sshh.bililiverecoder.util;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public final class ImageDimensionsReader {
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private ImageDimensionsReader() {
    }

    public static Optional<Dimensions> read(InputStream inputStream) throws IOException {
        byte[] data = inputStream.readAllBytes();
        if (isPng(data)) {
            return Optional.of(new Dimensions(readInt(data, 16), readInt(data, 20)));
        }
        if (isJpeg(data)) {
            return readJpeg(data);
        }
        return Optional.empty();
    }

    private static boolean isPng(byte[] data) {
        if (data.length < 24) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isJpeg(byte[] data) {
        return data.length > 3
                && (data[0] & 0xFF) == 0xFF
                && (data[1] & 0xFF) == 0xD8;
    }

    private static Optional<Dimensions> readJpeg(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
        in.readUnsignedShort();

        while (true) {
            nextMarkerStart(in);
            int marker = in.readUnsignedByte();
            while (marker == 0xFF) {
                marker = in.readUnsignedByte();
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return Optional.empty();
            }

            int length = in.readUnsignedShort();
            if (length < 2) {
                return Optional.empty();
            }
            int payloadLength = length - 2;
            if (isStartOfFrame(marker)) {
                if (payloadLength < 5) {
                    return Optional.empty();
                }
                in.readUnsignedByte();
                int height = in.readUnsignedShort();
                int width = in.readUnsignedShort();
                return Optional.of(new Dimensions(width, height));
            }
            if (!skipFully(in, payloadLength)) {
                return Optional.empty();
            }
        }
    }

    private static void nextMarkerStart(DataInputStream in) throws IOException {
        int value;
        do {
            value = in.readUnsignedByte();
        } while (value != 0xFF);
    }

    private static boolean skipFully(DataInputStream in, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            int skipped = in.skipBytes(remaining);
            if (skipped <= 0) {
                return false;
            }
            remaining -= skipped;
        }
        return true;
    }

    private static boolean isStartOfFrame(int marker) {
        return marker == 0xC0 || marker == 0xC1 || marker == 0xC2 || marker == 0xC3
                || marker == 0xC5 || marker == 0xC6 || marker == 0xC7
                || marker == 0xC9 || marker == 0xCA || marker == 0xCB
                || marker == 0xCD || marker == 0xCE || marker == 0xCF;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    public static class Dimensions {
        private final int width;
        private final int height;

        public Dimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }
}
