package com.mpatric.mp3agic;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

public class FileWrapper {

    protected File file;
    protected long length;
    protected long lastModified;
    protected DecriptionProvider decriptionProvider;

    protected FileWrapper() {
    }

    public FileWrapper(String filename, DecriptionProvider provider) throws IOException {
        this.file = new File(filename);
        decriptionProvider = provider;
        init();
    }

    public FileWrapper(File file) throws IOException {
        if (file == null) throw new NullPointerException();
        this.file = file;
        init();
    }

    private void init() throws IOException {
        if (!file.exists()) throw new FileNotFoundException("File not found " + file.getPath());
        if (!file.canRead()) throw new IOException("File not readable");

        InputStream decryptInputStream = null;
        try {
            decryptInputStream = decriptionProvider.getDecryptInputStream(new FileInputStream(file));
            length = 0;
            byte[] arr = new byte[8 * 1024];
            int bytesRead;
            while ((bytesRead = decryptInputStream.read(arr)) > 0) {
                length += bytesRead;
            }
        } catch (Exception e) {
            length = file.length();
        } finally {
            if (decryptInputStream != null) {
                decryptInputStream.close();
            }
        }

        lastModified = file.lastModified();
    }

    public String getFilename() {
        return file.getPath();
    }

    public long getLength() {
        return length;
    }

    public long getLastModified() {
        return lastModified;
    }
}
