package com.fader.tv;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaDataSource;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only local/USB client backed by MediaStore, exactly like the native TV player.
 * MediaStore already indexes every audio file on all volumes (including a USB stick),
 * so this needs no file manager and no Storage Access Framework picker — only the
 * read-media permission. Folder structure is rebuilt from each file's relative path.
 */
final class LocalClient implements LibraryClient {
    private final Context context;
    private final String root = "/local/";
    private final Map<String, List<NasClient.Entry>> tree = new HashMap<>();
    private final Map<String, Uri> fileUris = new HashMap<>();

    LocalClient(Context context) {
        this.context = context;
        build();
    }

    @Override public String rootUrl() { return root; }
    @Override public String modeLabel() { return "USB / Local"; }

    private void build() {
        String[] proj = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.VOLUME_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        };
        Cursor c = null;
        try {
            c = context.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null);
            if (c == null) return;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int relCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH);
            int volCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int modCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);
            while (c.moveToNext()) {
                String name = c.getString(nameCol);
                if (name == null || !NasClient.audio(name)) continue;
                long id = c.getLong(idCol);
                String rel = c.getString(relCol);
                String vol = c.getString(volCol);
                long size = c.isNull(sizeCol) ? 0 : c.getLong(sizeCol);
                long modified = c.isNull(modCol) ? 0 : c.getLong(modCol);

                String volumeName = "external_primary".equals(vol) ? "Internal" : (vol == null ? "Media" : vol);
                String folderPath = root + volumeName + "/" + (rel == null || rel.isEmpty() ? "" : rel);
                String filePath = folderPath + name;

                ensureFolder(folderPath);
                addChild(folderPath, new NasClient.Entry(name, filePath, false, size, modified));
                fileUris.put(filePath, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id));
            }
        } catch(Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        for (List<NasClient.Entry> list : tree.values()) {
            list.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : LibraryPolicy.naturalCompare(a.name, b.name));
        }
    }

    private void ensureFolder(String folderPath) {
        String cur = root;
        String rest = folderPath.substring(root.length());
        for (String seg : rest.split("/")) {
            if (seg.isEmpty()) continue;
            String childUrl = cur + seg + "/";
            addFolderIfMissing(cur, seg, childUrl);
            cur = childUrl;
        }
    }

    private void addFolderIfMissing(String parent, String name, String url) {
        List<NasClient.Entry> siblings = tree.get(parent);
        if (siblings != null) {
            for (NasClient.Entry e : siblings) {
                if (e.directory && e.url.equals(url)) return;
            }
        }
        addChild(parent, new NasClient.Entry(name, url, true, 0, 0));
    }

    private void addChild(String parent, NasClient.Entry entry) {
        List<NasClient.Entry> list = tree.get(parent);
        if (list == null) { list = new ArrayList<>(); tree.put(parent, list); }
        list.add(entry);
    }

    private List<NasClient.Entry> listAll(String url) {
        List<NasClient.Entry> result = tree.get(url);
        if (result == null) return new ArrayList<>();
        List<NasClient.Entry> copy = new ArrayList<>(result);
        copy.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : LibraryPolicy.naturalCompare(a.name, b.name));
        return copy;
    }

    @Override public NasClient.Library quickLibrary(String url, String name) {
        List<NasClient.Entry> all = listAll(url);
        List<NasClient.Entry> folders = new ArrayList<>();
        List<NasClient.Entry> audio = new ArrayList<>();
        NasClient.Entry cover = null;
        for (NasClient.Entry e : all) {
            if (e.directory) folders.add(e);
            else if (NasClient.audio(e.name)) audio.add(e);
            else if (cover == null && MetaParser.isCoverName(e.name)) cover = e;
        }
        audio.sort((a, b) -> LibraryPolicy.naturalCompare(a.url, b.url));
        return new NasClient.Library(url, name, folders, audio, cover, false);
    }

    @Override public int countAudio(String url, NasClient.Cancel cancel, NasClient.Progress progress) throws IOException {
        int count = 0, visited = 0;
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(url);
        while (!queue.isEmpty() && visited < 100000 && count < LibraryPolicy.MAX_QUEUE) {
            if (cancel != null && cancel.cancelled()) throw new IOException("Cancelled");
            String dir = queue.poll();
            if (++visited > 100000) break;
            for (NasClient.Entry e : listAll(dir)) {
                if (e.directory) queue.add(e.url);
                else count++;
            }
            if (progress != null) progress.update(visited, count);
        }
        return count;
    }

    @Override public NasClient.Library indexLibrary(String url, String name, NasClient.Cancel cancel, NasClient.Progress progress, NasClient.Partial partial) throws IOException {
        List<NasClient.Entry> folders = new ArrayList<>();
        List<NasClient.Entry> audio = new ArrayList<>();
        int[] visited = {0}, truncated = {0};
        final NasClient.Entry[] cover = {null};
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(url);
        boolean partialSent = false;
        while (!queue.isEmpty() && visited[0] < 100000 && audio.size() < LibraryPolicy.MAX_QUEUE) {
            if (cancel != null && cancel.cancelled()) throw new IOException("Browse cancelled");
            final String dir = queue.poll();
            if (audio.size() >= LibraryPolicy.MAX_QUEUE) { truncated[0] = 1; break; }
            if (++visited[0] > 100000) { truncated[0] = 1; break; }
            List<NasClient.Entry> children = listAll(dir);
            for (NasClient.Entry e : children) {
                if (dir.equals(url) && cover[0] == null && MetaParser.isCoverName(e.name) && !e.directory) cover[0] = e;
                if (e.directory) {
                    if (dir.equals(url)) folders.add(e);
                    queue.add(e.url);
                } else if (NasClient.audio(e.name)) {
                    audio.add(e);
                    if (audio.size() >= LibraryPolicy.MAX_QUEUE) { truncated[0] = 1; break; }
                }
            }
            if (progress != null) progress.update(visited[0], audio.size());
            if (!partialSent && partial != null) {
                partialSent = true;
                partial.update(new NasClient.Library(url, name, new ArrayList<>(folders), new ArrayList<>(audio), cover[0], false));
            }
            if (cancel != null && cancel.cancelled()) throw new IOException("Browse cancelled");
        }
        audio.sort((a, b) -> LibraryPolicy.naturalCompare(a.url, b.url));
        return new NasClient.Library(url, name, folders, audio, cover[0], truncated[0] != 0);
    }

    @Override public MediaDataSource source(NasClient.Entry entry) throws IOException {
        Uri uri = fileUris.get(entry.url);
        if (uri == null) throw new IOException("No content URI for " + entry.url);
        return new UriSource(context, uri);
    }

    @Override public void close() { }

    private static final class UriSource extends MediaDataSource {
        private final Context context;
        private final Uri uri;
        private ParcelFileDescriptor pfd;
        private FileInputStream fis;
        private FileChannel channel;
        private long length = -1;
        private boolean closed;

        UriSource(Context context, Uri uri) { this.context = context; this.uri = uri; }

        private void open() throws IOException {
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new IOException("Cannot open " + uri);
            fis = new FileInputStream(pfd.getFileDescriptor());
            channel = fis.getChannel();
            length = channel.size();
        }

        @Override public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (closed) throw new IOException("Source closed");
            if (size == 0) return 0;
            if (channel == null) open();
            if (position >= length) return -1;
            channel.position(position);
            int want = (int) Math.min(size, length - position);
            ByteBuffer bb = ByteBuffer.wrap(buffer, offset, want);
            int total = 0;
            while (total < want) {
                int n = channel.read(bb);
                if (n <= 0) break;
                total += n;
            }
            return total == 0 ? -1 : total;
        }

        @Override public long getSize() {
            if (length < 0) {
                try { if (channel == null) open(); } catch(IOException e) { length = -1; }
            }
            return length < 0 ? -1 : length;
        }

        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            if (channel != null) { try { channel.close(); } catch(IOException ignored) { } channel = null; }
            if (fis != null) { try { fis.close(); } catch(IOException ignored) { } fis = null; }
            if (pfd != null) { try { pfd.close(); } catch(IOException ignored) { } pfd = null; }
        }
    }
}
