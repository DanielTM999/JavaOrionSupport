package dtm.ide.swingdesigner.host;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

final class Frames {

    static final int MAX_FRAME = 256 * 1024 * 1024;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final DataInputStream in;
    private final DataOutputStream out;

    Frames(DataInputStream in, DataOutputStream out) {
        this.in = in;
        this.out = out;
    }

    Frame read() throws IOException {
        int headerLength;
        try {
            headerLength = in.readInt();
        } catch (EOFException end) {
            return null;
        }
        byte[] header = readBlock(headerLength);
        int blobLength = in.readInt();
        byte[] blob = blobLength == 0 ? new byte[0] : readBlock(blobLength);
        return new Frame(Json.object(Json.parse(new String(header, UTF_8))), blob);
    }

    synchronized void write(Map<String, Object> header, byte[] blob) throws IOException {
        byte[] bytes = Json.write(header).getBytes(UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
        if (blob == null || blob.length == 0) {
            out.writeInt(0);
        } else {
            out.writeInt(blob.length);
            out.write(blob);
        }
        out.flush();
    }

    private byte[] readBlock(int length) throws IOException {
        if (length < 0 || length > MAX_FRAME) {
            throw new IOException("invalid frame length " + length);
        }
        byte[] block = new byte[length];
        in.readFully(block);
        return block;
    }

    static final class Frame {
        final Map<String, Object> header;
        final byte[] blob;

        Frame(Map<String, Object> header, byte[] blob) {
            this.header = header;
            this.blob = blob;
        }
    }
}
