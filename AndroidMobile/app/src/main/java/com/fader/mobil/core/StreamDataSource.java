package com.fader.mobil.core;

import android.media.MediaDataSource;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.io.IOException;

/** ExoPlayer DataSource that reads sequentially from a random-access MediaDataSource. */
final class StreamDataSource extends BaseDataSource {
    private final MediaDataSource source;
    private long position;
    private long remaining;

    StreamDataSource(MediaDataSource source) {
        super(/* isNetwork */ true);
        this.source = source;
    }

    @Override public long open(DataSpec dataSpec) throws IOException {
        transferInitializing(dataSpec);
        position = dataSpec.position;
        long size = source.getSize();
        remaining = dataSpec.length != C.LENGTH_UNSET
                ? dataSpec.length
                : (size >= 0 ? size - dataSpec.position : C.LENGTH_UNSET);
        transferStarted(dataSpec);
        return remaining;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int n = source.readAt(position, buffer, offset, length);
        if (n <= 0) return C.RESULT_END_OF_INPUT;
        position += n;
        if (remaining != C.LENGTH_UNSET) remaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Override public Uri getUri() { return Uri.EMPTY; }

    @Override public void close() throws IOException { source.close(); }

    /** Builds a fresh StreamDataSource for a track, or a failing one when the source can't open. */
    static final class Factory implements DataSource.Factory {
        private final TrackItem item;
        Factory(TrackItem item) { this.item = item; }

        @Override public DataSource createDataSource() {
            try {
                return new StreamDataSource(item.nas.source(item.entry));
            } catch (IOException e) {
                MediaDataSource failing = new MediaDataSource() {
                    @Override public int readAt(long position, byte[] buffer, int offset, int size) throws IOException { throw e; }
                    @Override public long getSize() throws IOException { throw e; }
                    @Override public void close() { }
                };
                return new StreamDataSource(failing);
            }
        }
    }
}
