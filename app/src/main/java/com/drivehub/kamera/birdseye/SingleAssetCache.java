package com.drivehub.kamera.birdseye;

import java.io.IOException;
import java.util.Objects;

/** One bounded successful value. Concurrent callers share a load; failures never reuse a different key. */
final class SingleAssetCache<T> {
    interface Loader<T> { T load() throws IOException; }
    private String key;
    private T value;
    synchronized T get(String requested,Loader<T> loader) throws IOException {
        Objects.requireNonNull(requested);
        if(requested.equals(key)&&value!=null)return value;
        T loaded=Objects.requireNonNull(loader.load());
        key=requested;value=loaded;
        return loaded;
    }
}
