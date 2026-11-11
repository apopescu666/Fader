package com.fader.tv;

import android.media.MediaDataSource;
import jcifs.CIFSContext;
import jcifs.context.BaseContext;
import jcifs.config.PropertyConfiguration;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.*;

/** Read-only SMB access. Credentials remain in memory, never in URLs or preferences. */
final class NasClient implements LibraryClient {
    /** Tried in order: strict first, then the smallest compatibility fallback needed. */
    enum Mode {
        STRICT("SMB2/3 signed"),
        COMPATIBLE("SMB2/3 compat"),
        LEGACY("SMB1 compat");
        final String label;
        Mode(String label){this.label=label;}
    }
    interface AttemptListener { void trying(Mode mode); }
    interface Cancel { boolean cancelled(); }
    interface Progress { void update(int folders, int songs); }
    interface Partial { void update(Library library); }

    final CIFSContext context;
    final String root;
    final Mode mode;
    private volatile List<Entry> rootEntries;
    private static final java.util.concurrent.ExecutorService CLOSER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fader-smb-close");
        t.setDaemon(true);
        return t;
    });

    private NasClient(String address, String domain, String user, String password, Mode mode) throws IOException {
        root = normalize(address);
        this.mode = mode;
        boolean legacy = mode == Mode.LEGACY;
        Properties p = new Properties();
        p.setProperty("jcifs.smb.client.minVersion", legacy ? "SMB1" : "SMB202");
        p.setProperty("jcifs.smb.client.maxVersion", legacy ? "SMB1" : "SMB311");
        p.setProperty("jcifs.smb.client.useSMB2Negotiation", Boolean.toString(!legacy));
        p.setProperty("jcifs.smb.client.port139.enabled", Boolean.toString(legacy));
        p.setProperty("jcifs.resolveOrder", mode == Mode.STRICT ? "DNS" : "DNS,BCAST");
        p.setProperty("jcifs.smb.client.disablePlainTextPasswords", "true");
        p.setProperty("jcifs.smb.client.requireSecureNegotiate", Boolean.toString(mode == Mode.STRICT));
        p.setProperty("jcifs.smb.client.signingPreferred", Boolean.toString(mode == Mode.STRICT));
        p.setProperty("jcifs.smb.client.signingEnforced", Boolean.toString(mode == Mode.STRICT && !user.trim().isEmpty()));
        p.setProperty("jcifs.smb.client.connTimeout", "8000");
        p.setProperty("jcifs.smb.client.responseTimeout", "30000");
        p.setProperty("jcifs.smb.client.soTimeout", "30000");
        p.setProperty("jcifs.smb.client.ipcSigningEnforced", Boolean.toString(mode == Mode.STRICT));
        CIFSContext base = new BaseContext(new PropertyConfiguration(p));
        context = user.trim().isEmpty() ? base.withGuestCrendentials()
            : base.withCredentials(new NtlmPasswordAuthenticator(domain, user, password));
    }

    @Override public String rootUrl() { return root; }
    @Override public String modeLabel() { return mode.label; }

    /**
     * Opens and validates a share, preferring signed SMB2/3. Older servers that cannot
     * negotiate signing or SMB2 fall back to compatibility modes only when needed.
     */
    static NasClient open(String address, String domain, String user, String password, AttemptListener listener) throws ConnectFailure {
        String root = normalize(address);
        List<Attempt> attempts = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            NasClient client = null;
            try {
                if (listener != null) listener.trying(mode);
                client = new NasClient(root, domain, user, password, mode);
                client.validate(); // lists the share root: validates connection, credentials, share and read access
                return client;
            } catch (IOException e) {
                if (client != null) try { client.close(); } catch (IOException ignored) {}
                attempts.add(new Attempt(mode, e));
                // A rejected password is definitive; retrying other SMB versions only risks a lockout.
                if (e instanceof SmbAuthException) break;
            }
        }
        throw new ConnectFailure(root, attempts);
    }

    static String normalize(String address) {
        String a = address.trim();
        if (!a.startsWith("smb://")) a = "smb://" + a;
        java.net.URI uri = java.net.URI.create(a);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Use smb://server/share, without credentials in the address");
        if (uri.getPort() != -1 && uri.getPort() != 445)
            throw new IllegalArgumentException("SMB connections use port 445");
        return a.endsWith("/") ? a : a + "/";
    }

    private void validate() throws IOException {
        rootEntries = listAll(root);
    }

    List<Entry> list(String url) throws IOException {
        List<Entry> all;
        if (rootEntries != null && url.equals(root)) { all = rootEntries; rootEntries = null; }
        else all = listAll(url);
        List<Entry> result = new ArrayList<>(all.size());
        for (Entry e : all) if (e.directory || audio(e.name)) result.add(e);
        return result;
    }

    private List<Entry> listAll(String url) throws IOException {
        List<Entry> result = new ArrayList<>();
        try (SmbFile directory = new SmbFile(url, context)) {
            SmbFile[] children = directory.listFiles();
            try {
                for (SmbFile f : children) {
                    String name = f.getName();
                    boolean dir = f.isDirectory();
                    result.add(new Entry(name.replaceAll("/$", ""), f.getCanonicalPath(), dir, dir ? 0 : f.length(), dir ? 0 : f.lastModified()));
                }
            } finally { for (SmbFile f : children) f.close(); }
        }
        result.sort((a,b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : LibraryPolicy.naturalCompare(a.name,b.name));
        return result;
    }

    static boolean audio(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".wav") || n.endsWith(".aiff") || n.endsWith(".aif") || n.endsWith(".flac") || n.endsWith(".ogg");
    }

    /**
     * Immediate contents of one folder, without scanning its subfolders. Used at the
     * share root so a 50,000-track library is not indexed just to show its folders.
     */
    public Library quickLibrary(String url, String name) throws IOException {
        List<Entry> all;
        if (url.equals(root) && rootEntries != null) { all = rootEntries; rootEntries = null; }
        else all = listAll(url);
        List<Entry> folders = new ArrayList<>();
        List<Entry> audio = new ArrayList<>();
        Entry cover = null;
        for (Entry e : all) {
            if (e.directory) folders.add(e);
            else if (audio(e.name)) audio.add(e);
            else if (cover == null && MetaParser.isCoverName(e.name)) cover = e;
        }
        folders.sort((a, b) -> LibraryPolicy.naturalCompare(a.name, b.name));
        audio.sort((a, b) -> LibraryPolicy.naturalCompare(a.url, b.url));
        return new Library(url, name, folders, audio, cover, false);
    }

    /** Counts every audio file below a folder without keeping the entries in memory. */
    public int countAudio(String url, Cancel cancel, Progress progress) throws IOException {
        int count = 0;
        int visited = 0;
        int failures = 0;
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(url);
        while (!queue.isEmpty() && visited < MAX_FOLDERS && count < MAX_AUDIO) {
            if (cancel != null && cancel.cancelled()) throw new IOException("Cancelled");
            String dir = queue.poll();
            if (++visited > MAX_FOLDERS) break;
            List<Entry> all;
            try {
                all = listAll(dir);
                failures = 0;
            } catch (IOException e) {
                if (dir.equals(url)) throw e;
                if (++failures >= 5) throw e;
                continue;
            }
            for (Entry e : all) {
                if (e.directory) queue.add(e.url);
                else if (audio(e.name)) count++;
            }
            if (progress != null) progress.update(visited, count);
        }
        return count;
    }

    @Override public MediaDataSource source(Entry entry) throws IOException { return new Source(entry); }
    @Override public void close() throws IOException { try { context.close(); } catch (IOException ignored) { } }

    static final class Attempt {
        final Mode mode;
        final IOException error;
        Attempt(Mode mode, IOException error) { this.mode = mode; this.error = error; }
    }

    /** Connection failure carrying a short user-facing reason and the per-mode technical details. */
    static final class ConnectFailure extends IOException {
        final String summary, details;
        ConnectFailure(String address, List<Attempt> attempts) {
            super(summary(attempts));
            this.summary = summary(attempts);
            this.details = details(address, attempts);
        }
        private static String summary(List<Attempt> attempts) {
            for (Attempt a : attempts) if (hasType(a.error, SmbAuthException.class))
                return "Authentication failed. Check the username, password and domain. On Windows, use the account name and domain (MicrosoftAccount for Microsoft accounts).";
            for (Attempt a : attempts) if (hasType(a.error, UnknownHostException.class) || has(a, "unknown host", "no such host"))
                return "Server not found. Check the address or use the server's IP address instead of its name.";
            for (Attempt a : attempts) if (has(a, "network name cannot be found", "STATUS_BAD_NETWORK_NAME", "network name is no longer available"))
                return "Share not found. Check the share name in the address, for example smb://server/music.";
            for (Attempt a : attempts) if (has(a, "access is denied", "STATUS_ACCESS_DENIED"))
                return "Access denied. The account needs read permission on the share.";
            for (Attempt a : attempts) if (has(a, "signature validation failed", "signing"))
                return "The server's SMB signing is not compatible with this TV, and compatibility mode did not help.";
            for (Attempt a : attempts) if (has(a, "connection refused", "timed out", "no route to host", "network is unreachable", "connection reset", "failed to connect"))
                return "The server is not reachable on port 445. Check that the TV and the server are on the same network, that the server is on, and that SMB is enabled.";
            Attempt last = attempts.get(attempts.size() - 1);
            String message = last.error.getMessage();
            return "Cannot connect to the network share." + (message == null || message.isEmpty() ? "" : " " + message);
        }
        private static boolean hasType(Throwable error, Class<?> type) {
            for (Throwable t = error; t != null; t = t.getCause()) if (type.isInstance(t)) return true;
            return false;
        }
        private static boolean has(Attempt attempt, String... needles) {
            for (Throwable t = attempt.error; t != null; t = t.getCause()) {
                String message = t.getMessage();
                if (message == null) continue;
                String lower = message.toLowerCase(Locale.ROOT);
                for (String needle : needles) if (lower.contains(needle.toLowerCase(Locale.ROOT))) return true;
            }
            return false;
        }
        private static String details(String address, List<Attempt> attempts) {
            StringBuilder text = new StringBuilder("Address: ").append(address).append('\n');
            for (Attempt a : attempts) {
                text.append(a.mode.label).append(": ");
                for (Throwable t = a.error; t != null; t = t.getCause()) {
                    if (t != a.error) text.append(" <- ");
                    text.append(t.getClass().getSimpleName());
                    String message = t.getMessage();
                    if (message != null && !message.isEmpty()) text.append(": ").append(message);
                }
                text.append('\n');
            }
            return text.toString().trim();
        }
    }

    /** A browsable slice of a share: immediate subfolders plus all audio descendants, bounded. */
    static final class Library {
        final String url, name;
        final List<Entry> folders;   // immediate subfolders, natural order
        final List<Entry> audio;     // audio found recursively, natural order
        final Entry cover;           // folder.jpg / cover.* artwork, if present at this level
        final boolean truncated;     // true when the scan hit a visited-folder or audio cap
        Library(String url, String name, List<Entry> folders, List<Entry> audio, Entry cover, boolean truncated) {
            this.url=url; this.name=name; this.folders=folders; this.audio=audio; this.cover=cover; this.truncated=truncated;
        }
    }

    private static final int MAX_AUDIO = LibraryPolicy.MAX_QUEUE;
    private static final int MAX_FOLDERS = 100000;

    Library indexLibrary(String url, String name) throws IOException {
        return indexLibrary(url, name, null, null, null);
    }

    /**
     * Collect every audio file under {@code url} (recursively, natural order) plus the immediate
     * subfolders, so the right-hand list can play "all in this folder" like a TV library. The scan
     * is bounded on audio count and on folders visited so a huge share cannot exhaust memory or hang.
     * A subfolder that fails is skipped and the result is marked partial; the folder the user opened
     * must itself be readable. The scan stops promptly when {@code cancel} says the UI moved on.
     */
    Library indexLibrary(String url, String name, Cancel cancel, Progress progress) throws IOException {
        return indexLibrary(url, name, cancel, progress, null);
    }

    /**
     * Same as above, with a {@code partial} callback delivered after the opened folder's immediate
     * contents are known. The UI can show that folder at once while the recursive scan continues.
     */
    public Library indexLibrary(String url, String name, Cancel cancel, Progress progress, Partial partial) throws IOException {
        List<Entry> folders = new ArrayList<>();
        List<Entry> audio = new ArrayList<>();
        int[] visited = {0}, truncated = {0};
        final Entry[] cover = {null};
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(url);
        List<Entry> prefetched = null;
        if (url.equals(root) && rootEntries != null) { prefetched = rootEntries; rootEntries = null; }
        int failures = 0;
        boolean partialSent = false;
        while (!queue.isEmpty() && visited[0] < MAX_FOLDERS && audio.size() < MAX_AUDIO) {
            if (cancel != null && cancel.cancelled()) throw new IOException("Browse cancelled");
            final String dir = queue.poll();
            if (audio.size() >= MAX_AUDIO) { truncated[0] = 1; break; }
            if (++visited[0] > MAX_FOLDERS) { truncated[0] = 1; break; }
            List<Entry> all;
            try {
                if (dir.equals(url) && prefetched != null) { all = prefetched; prefetched = null; }
                else all = listAll(dir);
                failures = 0;
            } catch (IOException e) {
                if (dir.equals(url)) throw e; // the folder the user opened must be readable
                if (++failures >= 5) throw e; // the server is probably gone; stop instead of waiting for every folder
                truncated[0] = 1;
                continue;
            }
            List<Entry> children = new ArrayList<>(all.size());
            for (Entry e : all) {
                // Folder artwork (folder.jpg / cover.*) is not audio, so capture it here
                // at the top level before the dir/audio filter drops it from the children.
                if (dir.equals(url) && cover[0] == null && MetaParser.isCoverName(e.name) && !e.directory) cover[0] = e;
                if (e.directory || audio(e.name)) children.add(e);
            }
            children.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : LibraryPolicy.naturalCompare(a.name, b.name));
            for (Entry e : children) {
                if (e.directory) {
                    if (dir.equals(url)) folders.add(e); // only immediate subfolders go in the nav list
                    queue.add(e.url);                    // every subfolder is scanned for audio
                } else {
                    audio.add(e);
                    if (audio.size() >= MAX_AUDIO) { truncated[0] = 1; break; }
                }
            }
            if (progress != null) progress.update(visited[0], audio.size());
            if (!partialSent && partial != null) {
                partialSent = true;
                partial.update(new Library(url, name, new ArrayList<>(folders), new ArrayList<>(audio), cover[0], false));
            }
            if (cancel != null && cancel.cancelled()) throw new IOException("Browse cancelled");
        }
        audio.sort((a, b) -> LibraryPolicy.naturalCompare(a.url, b.url));
        return new Library(url, name, folders, audio, cover[0], truncated[0] != 0);
    }

    final class Source extends MediaDataSource {
        private final Entry entry;
        private SmbFile file;
        private SmbRandomAccessFile input;
        private boolean closed;
        Source(Entry e) { entry = e; }
        @Override public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (closed) throw new IOException("Source closed");
            if (size == 0) return 0;
            if (position >= entry.size) return -1;
            if (input == null) { file = new SmbFile(entry.url, context); input = file.openRandomAccess("r"); }
            input.seek(position);
            int want = (int)Math.min(size, entry.size-position);
            int total = 0;
            // MediaMetadataRetriever expects a full buffer for a successful read. SMB may
            // return short packets, which looked like random metadata/cover failures.
            while (total < want) {
                int n = input.read(buffer, offset + total, want - total);
                if (n <= 0) break;
                total += n;
            }
            return total == 0 ? -1 : total;
        }
        @Override public long getSize() { return entry.size; }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            final SmbRandomAccessFile in = input;
            final SmbFile f = file;
            input = null;
            file = null;
            if (in == null && f == null) return;
            // MediaPlayer.release() calls this on the UI thread. Never do SMB I/O there.
            Runnable task = () -> {
                try { if (in != null) in.close(); } catch (IOException ignored) {}
                finally { if (f != null) { try { f.close(); } catch (Exception ignored) {} } }
            };
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) CLOSER.execute(task);
            else task.run();
        }
    }
    static final class Entry {
        final String name, url; final boolean directory; final long size, modified;
        Entry(String name,String url,boolean directory,long size,long modified) {
            this.name=name;this.url=url;this.directory=directory;this.size=size;this.modified=modified;
        }
    }
}
