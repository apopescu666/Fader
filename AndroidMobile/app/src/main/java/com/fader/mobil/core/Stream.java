package com.fader.mobil.core;

import android.media.MediaDataSource;
import android.net.Uri;
import java.io.IOException;
import java.util.Map;

/**
 * Source contract shared by the SMB client and the UPnP/DLNA client so the same playback pipeline
 * (crossfade, seek, visualizer) can stream either. Kept minimal to avoid a server on the device.
 */
interface Stream {
    MediaDataSource source(Entry entry) throws IOException;
    void close() throws IOException;

    /** Returns a directly-playable content URI (local MediaStore) or https URL (WebDAV), else null. */
    default Uri directUri(Entry entry) { return null; }

    /** HTTP headers for the direct URI (e.g. WebDAV Basic auth), or null. */
    default Map<String, String> httpHeaders(Entry entry) { return null; }
}
