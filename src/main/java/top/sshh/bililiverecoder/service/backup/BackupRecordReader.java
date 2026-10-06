package top.sshh.bililiverecoder.service.backup;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;

/** 旧 JSON 也按单条记录限制大小，不限制整个历史数组 */
final class BackupRecordReader extends FilterReader {
    private static final int LIMIT = 16 * 1024 * 1024;
    private int depth;
    private int recordLength;
    private int stringLength;
    private boolean inString;
    private boolean escaped;
    private final boolean envelope;
    private boolean rootKey = true;
    private boolean metadata;
    private boolean rootValue;
    private int metadataLength;
    private final StringBuilder key = new StringBuilder();

    BackupRecordReader(Reader reader) { this(reader,true); }
    BackupRecordReader(Reader reader,boolean envelope) { super(reader);this.envelope=envelope; }

    @Override public int read(char[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        for (int i = 0; i < count; i++) inspect(buffer[offset + i]);
        return count;
    }

    @Override public int read() throws IOException {
        int value = super.read();
        if (value != -1) inspect((char) value);
        return value;
    }

    private void inspect(char value) throws IOException {
        if(envelope && rootValue && metadata && ++metadataLength>1024*1024)
            throw new IOException("备份元数据过大");
        if (depth >= 3 && ++recordLength > LIMIT) throw new IOException("单条备份记录过大");
        if (inString) {
            if (++stringLength > LIMIT) throw new IOException("备份字段过大");
            if (escaped) escaped = false;
            else if (value == '\\') escaped = true;
            else if (value == '"') {
                inString = false;
                if(envelope && depth==1 && rootKey)metadata=!BackupSchema.SECTIONS.containsKey(key.toString());
            }
            else if(envelope && depth==1 && rootKey) {
                if(key.length()>=255)throw new IOException("备份数据段名称过长");key.append(value);
            }
        } else if (value == '"') {
            inString = true; stringLength = 0;
            if(envelope && depth==1 && rootKey)key.setLength(0);
        } else if(envelope && depth==1 && value==':') {
            rootKey=false;rootValue=true;metadataLength=0;
        } else if(envelope && depth==1 && value==',') {
            rootKey=true;rootValue=false;
        } else if (value == '{' || value == '[') {
            depth++;
            if(depth>128)throw new IOException("备份记录嵌套过深");
            if (depth == 3) recordLength = 1;
        } else if (value == '}' || value == ']') {
            depth--;
        }
    }
}
