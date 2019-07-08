package com.mpatric.mp3agic;


import java.io.IOException;
import java.io.InputStream;

public interface DecriptionProvider {
    InputStream getDecryptInputStream(InputStream inputStream, byte[] iv, int padding) throws IOException;

    int getIvPadding();

}
