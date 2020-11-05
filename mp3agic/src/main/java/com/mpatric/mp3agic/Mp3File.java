package com.mpatric.mp3agic;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.util.HashMap;
import java.util.Map;

public class Mp3File extends FileWrapper {

    private static final int DEFAULT_BUFFER_LENGTH = 65536;
    private static final int MINIMUM_BUFFER_LENGTH = 40;
    private static final int XING_MARKER_OFFSET_1 = 13;
    private static final int XING_MARKER_OFFSET_2 = 21;
    private static final int XING_MARKER_OFFSET_3 = 36;

    protected int bufferLength;
    protected DecryptionProvider decryptionProvider;
    private int xingOffset = -1;
    private int startOffset = -1;
    private int endOffset = -1;
    private int frameCount = 0;
    private Map<Integer, MutableInteger> bitrates = new HashMap<Integer, MutableInteger>();
    private int xingBitrate;
    private double bitrate = 0;
    private String channelMode;
    private String emphasis;
    private String layer;
    private String modeExtension;
    private int sampleRate;
    private boolean copyright;
    private boolean original;
    private String version;
    private ID3v1 id3v1Tag;
    private ID3v2 id3v2Tag;
    private byte[] customTag;
    private boolean scanFile;
    private byte[] iv;

    protected Mp3File() {
    }

    /**
     * @param filename
     * @param preferredLanguage ISO 639-1 code
     * @throws IOException
     * @throws UnsupportedTagException
     * @throws InvalidDataException
     */
    public Mp3File(String filename, String preferredLanguage, DecryptionProvider provider) throws IOException, UnsupportedTagException, InvalidDataException {
        this(filename, DEFAULT_BUFFER_LENGTH, true, preferredLanguage, provider);
    }

    public Mp3File(String filename, int bufferLength, boolean scanFile, String preferredLanguage, DecryptionProvider provider) throws IOException, UnsupportedTagException, InvalidDataException {
        super(filename);
        decryptionProvider = provider;
        init(bufferLength, scanFile, preferredLanguage);
    }

    private void init(int bufferLength, boolean scanFile, String preferredLanguage) throws IOException, UnsupportedTagException, InvalidDataException {
        if (bufferLength < MINIMUM_BUFFER_LENGTH + 1)
            throw new IllegalArgumentException("Buffer too small");

        this.bufferLength = bufferLength;
        this.scanFile = scanFile;

        RandomAccessFile randomAccessFile = new RandomAccessFile(file.getPath(), "r");
        FileInputStream fileInputStream = null;
        try {
            iv = new byte[decryptionProvider.getIvPadding()];
            fileInputStream = new FileInputStream(file);
            fileInputStream.read(iv);
            initId3v1Tag(randomAccessFile, preferredLanguage);
            scanFile(randomAccessFile);
            if (startOffset < 0) {
                throw new InvalidDataException("No mpegs frames found");
            }
            initId3v2Tag(randomAccessFile, preferredLanguage);
            if (scanFile) {
                initCustomTag(randomAccessFile);
            }
        } finally {
            randomAccessFile.close();
            if (fileInputStream != null) {
                fileInputStream.close();
            }
        }
    }

    protected int preScanFile(RandomAccessFile eFile) {
        byte[] bytes = new byte[AbstractID3v2Tag.HEADER_LENGTH];
        try {
            eFile.seek(decryptionProvider.getIvPadding());
            InputStream decryptInputStream = decryptionProvider.getDecryptInputStream(Channels.newInputStream(eFile.getChannel()), iv, 0);
            int bytesRead = readDataFromInputStream(decryptInputStream, bytes, AbstractID3v2Tag.HEADER_LENGTH);
            if (bytesRead == AbstractID3v2Tag.HEADER_LENGTH) {
                try {
                    ID3v2TagFactory.sanityCheckTag(bytes);
                    return AbstractID3v2Tag.HEADER_LENGTH + BufferTools.unpackSynchsafeInteger(bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET], bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET + 1], bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET + 2], bytes[AbstractID3v2Tag.DATA_LENGTH_OFFSET + 3]);
                } catch (NoSuchTagException e) {
                    // do nothing
                } catch (UnsupportedTagException e) {
                    // do nothing
                }
            }
        } catch (IOException e) {
            // do nothing
        }
        return 0;
    }

    private void scanFile(RandomAccessFile eFile) throws IOException, InvalidDataException {
        byte[] bytes = new byte[bufferLength];
        int fileOffset = preScanFile(eFile);
        int padding = fileOffset % decryptionProvider.getBlockLength() + decryptionProvider.getIvPadding();
        eFile.seek(fileOffset + decryptionProvider.getIvPadding() - padding);
        InputStream decryptInputStream = decryptionProvider.getDecryptInputStream(Channels.newInputStream(eFile.getChannel()), iv, padding);
        boolean lastBlock = false;
        int lastOffset = fileOffset;
        while (!lastBlock) {
            int bytesRead = readDataFromInputStream(decryptInputStream, bytes, bufferLength);
            if (bytesRead < bufferLength) lastBlock = true;
            if (bytesRead >= MINIMUM_BUFFER_LENGTH) {
                while (true) {
                    try {
                        int offset = 0;
                        if (startOffset < 0) {
                            offset = scanBlockForStart(bytes, bytesRead, fileOffset, offset);
                            if (startOffset >= 0 && !scanFile) {
                                return;
                            }
                            lastOffset = startOffset;
                        }
                        offset = scanBlock(bytes, bytesRead, fileOffset, offset);
                        fileOffset += offset;
                        padding = fileOffset % decryptionProvider.getBlockLength() + decryptionProvider.getIvPadding();
                        eFile.seek(fileOffset + decryptionProvider.getIvPadding() - padding);
                        decryptInputStream = decryptionProvider.getDecryptInputStream(Channels.newInputStream(eFile.getChannel()), iv, padding);
                        break;
                    } catch (InvalidDataException e) {
                        if (frameCount < 2) {
                            startOffset = -1;
                            xingOffset = -1;
                            frameCount = 0;
                            bitrates.clear();
                            lastBlock = false;
                            fileOffset = lastOffset + 1;
                            if (fileOffset == 0)
                                throw new InvalidDataException("Valid start of mpeg frames not found", e);

                            padding = fileOffset % decryptionProvider.getBlockLength() + decryptionProvider.getIvPadding();
                            eFile.seek(fileOffset + decryptionProvider.getIvPadding() - padding);
                            decryptInputStream = decryptionProvider.getDecryptInputStream(Channels.newInputStream(eFile.getChannel()), iv, padding);
                            break;
                        }
                        return;
                    }
                }
            }
        }
    }

    private int scanBlockForStart(byte[] bytes, int bytesRead, int absoluteOffset, int offset) {
        while (offset < bytesRead - MINIMUM_BUFFER_LENGTH) {
            if (bytes[offset] == (byte) 0xFF && (bytes[offset + 1] & (byte) 0xE0) == (byte) 0xE0) {
                try {
                    MpegFrame frame = new MpegFrame(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]);
                    if (xingOffset < 0 && isXingFrame(bytes, offset)) {
                        xingOffset = absoluteOffset + offset;
                        xingBitrate = frame.getBitrate();
                        offset += frame.getLengthInBytes();
                    } else {
                        startOffset = absoluteOffset + offset;
                        channelMode = frame.getChannelMode();
                        emphasis = frame.getEmphasis();
                        layer = frame.getLayer();
                        modeExtension = frame.getModeExtension();
                        sampleRate = frame.getSampleRate();
                        version = frame.getVersion();
                        copyright = frame.isCopyright();
                        original = frame.isOriginal();
                        frameCount++;
                        addBitrate(frame.getBitrate());
                        offset += frame.getLengthInBytes();
                        return offset;
                    }
                } catch (InvalidDataException e) {
                    offset++;
                }
            } else {
                offset++;
            }
        }
        return offset;
    }

    private int scanBlock(byte[] bytes, int bytesRead, int absoluteOffset, int offset) throws InvalidDataException {
        while (offset < bytesRead - MINIMUM_BUFFER_LENGTH) {
            MpegFrame frame = new MpegFrame(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]);
            sanityCheckFrame(frame, absoluteOffset + offset);
            int newEndOffset = absoluteOffset + offset + frame.getLengthInBytes() - 1;
            if (newEndOffset < maxEndOffset()) {
                endOffset = absoluteOffset + offset + frame.getLengthInBytes() - 1;
                frameCount++;
                addBitrate(frame.getBitrate());
                offset += frame.getLengthInBytes();
            } else {
                break;
            }
        }
        return offset;
    }

    private int maxEndOffset() {
        int maxEndOffset = (int) getLength();
        if (hasId3v1Tag()) maxEndOffset -= ID3v1Tag.TAG_LENGTH;
        return maxEndOffset;
    }

    private boolean isXingFrame(byte[] bytes, int offset) {
        if (bytes.length >= offset + XING_MARKER_OFFSET_1 + 3) {
            if ("Xing".equals(BufferTools.byteBufferToStringIgnoringEncodingIssues(bytes, offset + XING_MARKER_OFFSET_1, 4)))
                return true;
            if ("Info".equals(BufferTools.byteBufferToStringIgnoringEncodingIssues(bytes, offset + XING_MARKER_OFFSET_1, 4)))
                return true;
            if (bytes.length >= offset + XING_MARKER_OFFSET_2 + 3) {
                if ("Xing".equals(BufferTools.byteBufferToStringIgnoringEncodingIssues(bytes, offset + XING_MARKER_OFFSET_2, 4)))
                    return true;
                if ("Info".equals(BufferTools.byteBufferToStringIgnoringEncodingIssues(bytes, offset + XING_MARKER_OFFSET_2, 4)))
                    return true;
                if (bytes.length >= offset + XING_MARKER_OFFSET_3 + 3) {
                    if ("Xing".equals(BufferTools.byteBufferToStringIgnoringEncodingIssues(bytes, offset + XING_MARKER_OFFSET_3, 4)))
                        return true;
                    if ("Info".equals(BufferTools.byteBufferToStringIgnoringEncodingIssues(bytes, offset + XING_MARKER_OFFSET_3, 4)))
                        return true;
                }
            }
        }
        return false;
    }

    private void sanityCheckFrame(MpegFrame frame, int offset) throws InvalidDataException {
        if (sampleRate != frame.getSampleRate())
            throw new InvalidDataException("Inconsistent frame header");
        if (!layer.equals(frame.getLayer()))
            throw new InvalidDataException("Inconsistent frame header");
        if (!version.equals(frame.getVersion()))
            throw new InvalidDataException("Inconsistent frame header");
        if (offset + frame.getLengthInBytes() > getLength())
            throw new InvalidDataException("Frame would extend beyond end of file");
    }

    private void addBitrate(int bitrate) {
        Integer key = new Integer(bitrate);
        MutableInteger count = bitrates.get(key);
        if (count != null) {
            count.increment();
        } else {
            bitrates.put(key, new MutableInteger(1));
        }
        this.bitrate = ((this.bitrate * (frameCount - 1)) + bitrate) / frameCount;
    }

    private void initId3v1Tag(RandomAccessFile eFile, String preferredLanguage) throws IOException {
        byte[] bytes = new byte[ID3v1Tag.TAG_LENGTH];
        int padding = (int) ((getLength() - ID3v1Tag.TAG_LENGTH) % decryptionProvider.getBlockLength());
        eFile.seek(getLength() - ID3v1Tag.TAG_LENGTH - padding - decryptionProvider.getIvPadding());
        InputStream decryptInputStream = decryptionProvider.getDecryptInputStream(Channels.newInputStream(eFile.getChannel()), iv, padding);
        int bytesRead = readDataFromInputStream(decryptInputStream, bytes, ID3v1Tag.TAG_LENGTH);

        if (bytesRead < ID3v1Tag.TAG_LENGTH) throw new IOException("Not enough bytes read");
        try {
            id3v1Tag = new ID3v1Tag(bytes, preferredLanguage);
        } catch (NoSuchTagException e) {
            id3v1Tag = null;
        }
    }

    private void initId3v2Tag(RandomAccessFile eFile, String preferredLanguage) throws IOException, UnsupportedTagException, InvalidDataException {

        if (xingOffset == 0 || startOffset == 0) {
            id3v2Tag = null;
        } else {
            int bufferLength;
            if (hasXingFrame()) bufferLength = xingOffset;
            else bufferLength = startOffset;
            byte[] bytes = new byte[bufferLength];
            eFile.seek(decryptionProvider.getIvPadding());
            InputStream decryptInputStream = decryptionProvider.getDecryptInputStream(Channels.newInputStream(eFile.getChannel()), iv, 0);
            int bytesRead = readDataFromInputStream(decryptInputStream, bytes, bufferLength);
            if (bytesRead < bufferLength) throw new IOException("Not enough bytes read");
            try {
                id3v2Tag = ID3v2TagFactory.createTag(preferredLanguage, bytes);
            } catch (NoSuchTagException e) {
                id3v2Tag = null;
            }
        }
    }

    private void initCustomTag(RandomAccessFile eFile) throws IOException {
        int bufferLength = (int) (getLength() - (endOffset + 1));
        if (hasId3v1Tag()) bufferLength -= ID3v1Tag.TAG_LENGTH;
        if (bufferLength <= 0) {
            customTag = null;
        } else {
            customTag = new byte[bufferLength];
            int padding = (endOffset + 1) % decryptionProvider.getBlockLength();
            eFile.seek(endOffset + 1 - padding - decryptionProvider.getIvPadding());
            InputStream decryptInputStream = decryptionProvider.getDecryptInputStream(Channels.newInputStream(eFile.getChannel()), iv, padding);
            int bytesRead = readDataFromInputStream(decryptInputStream, customTag, bufferLength);
            if (bytesRead < bufferLength) throw new IOException("Not enough bytes read");
        }
    }

    public int getFrameCount() {
        return frameCount;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public long getLengthInMilliseconds() {
        double d = 8 * (endOffset - startOffset);
        return (long) ((d / bitrate) + 0.5);
    }

    public long getLengthInSeconds() {
        return ((getLengthInMilliseconds() + 500) / 1000);
    }

    public boolean isVbr() {
        return bitrates.size() > 1;
    }

    public int getBitrate() {
        return (int) (bitrate + 0.5);
    }

    public Map<Integer, MutableInteger> getBitrates() {
        return bitrates;
    }

    public String getChannelMode() {
        return channelMode;
    }

    public boolean isCopyright() {
        return copyright;
    }

    public String getEmphasis() {
        return emphasis;
    }

    public String getLayer() {
        return layer;
    }

    public String getModeExtension() {
        return modeExtension;
    }

    public boolean isOriginal() {
        return original;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public String getVersion() {
        return version;
    }

    public boolean hasXingFrame() {
        return (xingOffset >= 0);
    }

    public int getXingOffset() {
        return xingOffset;
    }

    public int getXingBitrate() {
        return xingBitrate;
    }

    public boolean hasId3v1Tag() {
        return id3v1Tag != null;
    }

    public ID3v1 getId3v1Tag() {
        return id3v1Tag;
    }

    public void setId3v1Tag(ID3v1 id3v1Tag) {
        this.id3v1Tag = id3v1Tag;
    }

    public void removeId3v1Tag() {
        this.id3v1Tag = null;
    }

    public boolean hasId3v2Tag() {
        return id3v2Tag != null;
    }

    public ID3v2 getId3v2Tag() {
        return id3v2Tag;
    }

    public void setId3v2Tag(ID3v2 id3v2Tag) {
        this.id3v2Tag = id3v2Tag;
    }

    public void removeId3v2Tag() {
        this.id3v2Tag = null;
    }

    public boolean hasCustomTag() {
        return customTag != null;
    }

    public byte[] getCustomTag() {
        return customTag;
    }

    public void setCustomTag(byte[] customTag) {
        this.customTag = customTag;
    }

    public void removeCustomTag() {
        this.customTag = null;
    }

    public void save(String newFilename) throws IOException, NotSupportedException {
        if (file.compareTo(new File(newFilename)) == 0) {
            throw new IllegalArgumentException("Save filename same as source filename");
        }
        RandomAccessFile saveFile = new RandomAccessFile(newFilename, "rw");
        try {
            if (hasId3v2Tag()) {
                saveFile.write(id3v2Tag.toBytes());
            }
            saveMpegFrames(saveFile);
            if (hasCustomTag()) {
                saveFile.write(customTag);
            }
            if (hasId3v1Tag()) {
                saveFile.write(id3v1Tag.toBytes());
            }
        } finally {
            saveFile.close();
        }
    }

    private void saveMpegFrames(RandomAccessFile saveFile) throws IOException {
        int filePos = xingOffset;
        if (filePos < 0) filePos = startOffset;
        if (filePos < 0) return;
        if (endOffset < filePos) return;
        RandomAccessFile randomAccessFile = new RandomAccessFile(this.file.getPath(), "r");
        byte[] bytes = new byte[bufferLength];
        try {
            randomAccessFile.seek(filePos);
            while (true) {
                int bytesRead = randomAccessFile.read(bytes, 0, bufferLength);
                if (filePos + bytesRead <= endOffset) {
                    saveFile.write(bytes, 0, bytesRead);
                    filePos += bytesRead;
                } else {
                    saveFile.write(bytes, 0, endOffset - filePos + 1);
                    break;
                }
            }
        } finally {
            randomAccessFile.close();
        }
    }

    private int readDataFromInputStream(InputStream is, byte[] buffer) throws IOException {
        return readDataFromInputStream(is, buffer, buffer.length);
    }

    private int readDataFromInputStream(InputStream is, byte[] buffer, int length) throws IOException {
        int bytesToread = Math.min(buffer.length, length);
        int countRead = 0;
        while (bytesToread > 0) {
            int read = is.read(buffer, buffer.length - bytesToread, bytesToread);
            if (read == -1) {
                break;
            }
            countRead += read;
            bytesToread -= read;
        }
        return countRead;
    }
}
