package com.beez.backend.document.storage;

import java.io.IOException;

public interface DocumentStorage {
    String store(byte[] bytes, String validatedExtension) throws IOException;
    byte[] read(String storageKey) throws IOException;
    void delete(String storageKey) throws IOException;
}
