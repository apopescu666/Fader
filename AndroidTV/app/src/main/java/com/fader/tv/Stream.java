package com.fader.tv;

import android.media.MediaDataSource;
import java.io.IOException;

/**
 * Source contract shared by the SMB client and the UPnP/DLNA client so the same playback pipeline
 * (crossfade, seek, visualizer) can stream either. Kept minimal to avoid a server on the device.
 */
interface Stream {
    MediaDataSource source(NasClient.Entry entry) throws IOException;
    void close() throws IOException;
}
