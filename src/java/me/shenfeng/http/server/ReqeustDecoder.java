package me.shenfeng.http.server;

import me.shenfeng.http.*;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import static me.shenfeng.http.HttpUtils.*;
import static me.shenfeng.http.HttpVersion.HTTP_1_0;
import static me.shenfeng.http.HttpVersion.HTTP_1_1;
import static me.shenfeng.http.server.ServerDecoderState.*;

public class ReqeustDecoder {

    static final int MAX_LINE = 2048;

    byte[] lineBuffer = new byte[MAX_LINE];
    int lineBufferCnt = 0;

    HttpRequest request;
    private Map<String, String> headers = new TreeMap<String, String>();
    int readRemaining = 0;
    ServerDecoderState state;
    byte[] content;
    int readContent = 0;
    private int maxBody;

    public ReqeustDecoder(int maxBody) {
        this.maxBody = maxBody;
        state = READ_INITIAL;
    }

    private void createRequest(String sb) throws ProtocolException {
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;

        aStart = findNonWhitespace(sb, 0);
        aEnd = findWhitespace(sb, aStart);

        bStart = findNonWhitespace(sb, aEnd);
        bEnd = findWhitespace(sb, bStart);

        cStart = findNonWhitespace(sb, bEnd);
        cEnd = findEndOfString(sb);

        if (cStart < cEnd) {
            HttpMethod method = HttpMethod.GET;
            String m = sb.substring(aStart, aEnd).toUpperCase();
            if (m.equals("GET")) {
                method = HttpMethod.GET;
            } else if (m.equals("POST")) {
                method = HttpMethod.POST;
            } else if (m.equals("PUT")) {
                method = HttpMethod.PUT;
            } else if (m.equals("DELETE")) {
                method = HttpMethod.DELETE;
            }
            HttpVersion version = HTTP_1_1;
            if ("HTTP/1.0".equals(sb.substring(cStart, cEnd))) {
                version = HTTP_1_0;
            }
            request = new HttpRequest(method, sb.substring(bStart, bEnd),
                    version);
        } else {
            throw new ProtocolException("not http?");
        }
    }

    public ServerDecoderState decode(ByteBuffer buffer)
            throws LineTooLargeException, ProtocolException,
            RequestTooLargeException {
        String line;
        while (buffer.hasRemaining() && state != ALL_READ) {
            switch (state) {
                case READ_INITIAL:
                    line = readLine(buffer);
                    if (line != null) {
                        createRequest(line);
                        state = READ_HEADER;
                    }
                    break;
                case READ_HEADER:
                    readHeaders(buffer);
                    break;
                case READ_CHUNK_SIZE:
                    line = readLine(buffer);
                    if (line != null) {
                        readRemaining = getChunkSize(line);
                        if (readRemaining == 0) {
                            state = READ_CHUNK_FOOTER;
                        } else {
                            throwIfBodyIsTooLarge(readRemaining);
                            if (content == null) {
                                content = new byte[readRemaining];
                            } else if (content.length - readContent < readRemaining) {
                                byte[] tmp = new byte[readRemaining + readContent];
                                System.arraycopy(content, 0, tmp, 0, readContent);
                                content = tmp;
                            }
                            state = READ_CHUNKED_CONTENT;
                        }
                    }
                    break;
                case READ_FIXED_LENGTH_CONTENT:
                    readFixedLength(buffer);
                    if (readRemaining == 0) {
                        finish();
                    }
                    break;
                case READ_CHUNKED_CONTENT:
                    readFixedLength(buffer);
                    if (readRemaining == 0) {
                        state = READ_CHUNK_DELIMITER;
                    }
                    break;
                case READ_CHUNK_FOOTER:
                    readEmptyLine(buffer);
                    finish();
                    break;
                case READ_CHUNK_DELIMITER:
                    readEmptyLine(buffer);
                    state = READ_CHUNK_SIZE;
                    break;
            }
        }
        return state;
    }

    private void finish() {
        state = ALL_READ;
        request.setBody(content, readContent);
    }

    void readEmptyLine(ByteBuffer buffer) {
        byte b = buffer.get();
        if (b == CR) {
            buffer.get(); // should be LF
        } else if (b == LF) {
        }
    }

    void readFixedLength(ByteBuffer buffer) {
        int toRead = Math.min(buffer.remaining(), readRemaining);
        buffer.get(content, readContent, toRead);
        readRemaining -= toRead;
        readContent += toRead;
    }

    private void readHeaders(ByteBuffer buffer) throws LineTooLargeException,
            RequestTooLargeException {
        String line = readLine(buffer);
        while (line != null && !line.isEmpty()) {
            HttpUtils.splitAndAddHeader(line, headers);
            line = readLine(buffer);
        }

        if (line == null) {
            return;
        }

        request.setHeaders(headers);

        String te = headers.get(TRANSFER_ENCODING);
        if (CHUNKED.equals(te)) {
            state = READ_CHUNK_SIZE;
        } else {
            String cl = headers.get(CONTENT_LENGTH);
            if (cl != null) {
                try {
                    readRemaining = Integer.parseInt(cl);
                    if (readRemaining > 0) {
                        throwIfBodyIsTooLarge(readRemaining);
                        content = new byte[readRemaining];
                        state = READ_FIXED_LENGTH_CONTENT;
                    } else {
                        state = ALL_READ;
                    }
                } catch (NumberFormatException e) {
                    state = PROTOCOL_ERROR;
                }
            } else {
                state = ALL_READ;
            }
        }
    }

    String readLine(ByteBuffer buffer) throws LineTooLargeException {
        byte b;
        boolean more = true;
        while (buffer.hasRemaining() && more) {
            b = buffer.get();
            if (b == CR) {
                if (buffer.get() == LF)
                    more = false;
            } else if (b == LF) {
                more = false;
            } else {
                if (lineBufferCnt == MAX_LINE - 2) {
                    throw new LineTooLargeException("line length exceed "
                            + MAX_LINE);
                }
                lineBuffer[lineBufferCnt] = b;
                ++lineBufferCnt;
            }
        }
        String line = null;
        if (!more) {
            line = new String(lineBuffer, 0, lineBufferCnt);
            lineBufferCnt = 0;
        }
        return line;
    }

    public void reset() {
        state = READ_INITIAL;
        headers.clear();
        readContent = 0;
    }

    private void throwIfBodyIsTooLarge(int body)
            throws RequestTooLargeException {
        if (body > maxBody) {
            throw new RequestTooLargeException("request body " + body
                    + "; max request body " + maxBody);
        }
    }
}
