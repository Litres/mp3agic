package com.mpatric.mp3agic;


import java.io.IOException;
import java.io.InputStream;

public interface DecryptionProvider {

    /**
     * @param inputStream data stream
     * @param iv          initial vector
     * @param padding     padding from begin of block data
     * @return decrypted data stream
     * @throws IOException
     */
    InputStream getDecryptInputStream(InputStream inputStream, byte[] iv, int padding) throws IOException;

    /**
     * Initial vector size, to skip it in file
     */
    int getIvPadding();

    /**
     * Min block of data to use. Usually it equals 1 byte, because we read every byte. But if we use encryption
     * min block of data for read may ba different.
     * Must no be zero.
     */
    int getBlockLength();

}
