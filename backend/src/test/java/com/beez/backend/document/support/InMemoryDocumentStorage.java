package com.beez.backend.document.support;

import com.beez.backend.document.storage.DocumentStorage;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only fake for database transaction tests. Never registered as a product bean. */
public final class InMemoryDocumentStorage implements DocumentStorage {
    private final ConcurrentHashMap<String, byte[]> originals = new ConcurrentHashMap<>();
    @Override public String store(byte[] bytes, String extension) {
        String key = UUID.randomUUID() + "." + extension;
        originals.put(key, bytes.clone());
        return key;
    }
    @Override public byte[] read(String key) throws IOException {
        byte[] bytes = originals.get(key);
        if (bytes == null) throw new IOException("Missing test original");
        return bytes.clone();
    }
    @Override public void delete(String key) { originals.remove(key); }
    public int size() { return originals.size(); }
}
