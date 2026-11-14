package com.fader.mobil.core;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-wide player.
 *
 * Two ExoPlayer instances take turns: while one plays the current track, the other
 * starts the next one, and their volumes cross over. With the fade set to Off the
 * queue is a single ExoPlayer playlist instead, which gives gap-free playback.
 */
final class PlayerCore {
    static final PlayerCore INSTANCE = new PlayerCore();

    interface Listener {
        void onChanged();
    }

    private final List<Listener> listeners = new ArrayList<>();
    private final List<TrackItem> queue = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final ExecutorService PREFETCH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fader-prefetch");
        t.setDaemon(true);
        return t;
    });

    private AudioManager audioManager;

    private ExoPlayer[] engines;
    private int activeIndex = 0;
    private int queueIndex = -1;
    private int crossfadeSeconds = 0;
    private boolean gaplessEnabled = true;
    private boolean shuffleEnabled = false;
    /** One fixed shuffle order, like ExoPlayer's own shuffle mode. */
    private final List<Integer> shuffleOrder = new ArrayList<>();
    /** Queue index already prepared on the standby engine, or -1. */
    private int preparedTarget = -1;
    /** Last playback decisions, to diagnose rare transition problems. */
    private final java.util.ArrayDeque<String> journal = new java.util.ArrayDeque<>();
    /** 0 = off, 1 = repeat queue, 2 = repeat one. */
    private int repeatMode = 1;
    private boolean crossfading = false;
    private int fadeGeneration = 0;
    private long fadeStartedAt = 0;
    private String lastError = null;
    /** Container id of the item the car handed over, when there is one. */
    private volatile String externalContainer = null;

    /** The user's own queue: a standalone list he manages. */
    private final List<TrackItem> userQueue = new ArrayList<>();
    private boolean inUserQueue = false;
    /** True while playback was paused because of a phone call. */
    private boolean pausedForCall = false;
    private boolean pendingNext = false;
    private List<TrackItem> returnQueue = null;
    private int returnIndex = 0;
    private int failures = 0;
    private String lastFailedUrl = null;
    private static Context appContext;

    /** Media items handed to the car session keep their track here, by media id. */
    static final java.util.Map<String, TrackItem> ITEMS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Same tracks, by the fader:// URI the data source receives. */
    static final java.util.Map<String, TrackItem> URI_ITEMS = new java.util.concurrent.ConcurrentHashMap<>();

    static MediaItem sessionItem(Stream stream, String url, String artworkUri) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        Entry entry = new Entry(name, url, false, 0, 0);
        return sessionItemFor(new TrackItem(stream, entry), artworkUri);
    }

    /** Same, but keeps the metadata the library already knows. */
    static MediaItem sessionItem(Stream stream, String url, String title, String artist,
                                 String album, String artworkUri) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        Entry entry = new Entry(name, url, false, 0, 0);
        TrackItem item = new TrackItem(stream, entry);
        if (title != null && !title.isEmpty()) item.title = title;
        if (artist != null && !artist.isEmpty()) item.artist = artist;
        if (album != null) item.album = album;
        return sessionItemFor(item, artworkUri);
    }

    static MediaItem sessionItemFor(TrackItem item, String artworkUri) {
        String uri = "fader://" + java.util.UUID.randomUUID();
        String mediaId = "track:" + item.entry.url;
        ITEMS.put(mediaId, item);
        URI_ITEMS.put(uri, item);
        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .setAlbumTitle(item.album);
        if (artworkUri != null && !artworkUri.isEmpty()) {
            meta.setArtworkUri(Uri.parse(artworkUri));
        }
        return new MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(Uri.parse(uri))
                .setMediaMetadata(meta.build())
                .build();
    }

    /** Opens a session item: resolves the real URL and the auth headers. */
    static final class TrackDataSource implements androidx.media3.datasource.DataSource {
        private androidx.media3.datasource.DataSource delegate;
        private Uri current;

        @Override public long open(androidx.media3.datasource.DataSpec spec) throws IOException {
            TrackItem item = URI_ITEMS.get(spec.uri.toString());
            if (item == null) throw new IOException("Unknown session item: " + spec.uri);
            Uri real = item.nas.directUri(item.entry);
            if (real == null) throw new IOException("No stream available for " + item.entry.name);
            java.util.Map<String, String> headers = item.nas.httpHeaders(item.entry);
            androidx.media3.datasource.DataSpec.Builder builder = spec.buildUpon().setUri(real);
            if (headers != null && !headers.isEmpty()) {
                builder.setHttpRequestHeaders(headers);
            }
            String scheme = real.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                delegate = new DefaultHttpDataSource.Factory().createDataSource();
            } else {
                delegate = new androidx.media3.datasource.DefaultDataSource.Factory(appContext)
                        .createDataSource();
            }
            current = real;
            return delegate.open(builder.build());
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate.read(buffer, offset, length);
        }

        @Override public void close() throws IOException {
            if (delegate != null) {
                try {
                    delegate.close();
                } finally {
                    delegate = null;
                }
            }
        }

        @Override public Uri getUri() {
            return current;
        }

        @Override public void addTransferListener(
                androidx.media3.datasource.TransferListener transferListener) {
            // Progress reporting is not used for the car session.
        }
    }

    static final class TrackDataSourceFactory implements androidx.media3.datasource.DataSource.Factory {
        @Override public androidx.media3.datasource.DataSource createDataSource() {
            return new TrackDataSource();
        }
    }

    private PlayerCore() { }

    static void init(Context c) {
        appContext = c.getApplicationContext();
        INSTANCE.setup();
    }

    private void setup() {
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        engines = new ExoPlayer[2];
        for (int i = 0; i < 2; i++) {
            engines[i] = buildEngine();
        }
        handler.postDelayed(ticker, 400);
    }

    private ExoPlayer buildEngine() {
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20000, 50000, 300, 800)
                .build();
        ExoPlayer engine = new ExoPlayer.Builder(appContext)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(
                        new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                                new TrackDataSourceFactory()))
                .build();
        // Focus is handled once for both engines; otherwise the second one
        // silences the first the moment a crossfade starts.
        engine.setAudioAttributes(audioAttributes(), false);
        engine.setHandleAudioBecomingNoisy(true);
        engine.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (engine != active()) return;
                if (state == Player.STATE_ENDED) {
                    if (crossfading) return;
                    note("ended");
                    if (inUserQueue) {
                        if (repeatMode == 2) {
                            active().seekTo(0);
                            active().play();
                            return;
                        }
                        exitUserQueue();
                        return;
                    }
                    if (repeatMode == 2) {
                        active().seekTo(0);
                        active().play();
                        return;
                    }
                    autoAdvance();
                    return;
                }
                notifyChanged();
            }

            @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                if (engine != active()) return;
                String why;
                switch (reason) {
                    case Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST:
                        why = "user";
                        break;
                    case Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS:
                        why = "focus";
                        break;
                    case Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY:
                        why = "noisy";
                        break;
                    case Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE:
                        why = "remote";
                        break;
                    case Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM:
                        why = "end-of-item";
                        break;
                    default:
                        why = "reason " + reason;
                        break;
                }
                note("playWhenReady=" + playWhenReady + " (" + why + ")");
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (engine != active()) return;
                if (isPlaying) {
                    failures = 0;
                    lastError = null;
                }
                notifyChanged();
            }

            @Override public void onPlayerError(PlaybackException error) {
                // A failing incoming track aborts the fade and keeps the old one playing.
                if (engine == standby()) {
                    preparedTarget = -1;
                    if (crossfading) {
                        resetCrossfadeState();
                        stopEngine(engine);
                        try {
                            active().setVolume(1f);
                            if (!active().isPlaying()) active().play();
                        } catch (Exception ignored) { }
                        lastError = "Couldn't start the next track: "
                                + reasonFor(error.getErrorCodeName());
                        note("standby error: " + error.getErrorCodeName());
                        notifyChanged();
                    }
                    return;
                }
                if (engine != active()) return;
                Log.e("FaderPlayer", "playback error", error);
                TrackItem item = current();
                String name = item == null ? "" : decodeName(item.entry.name);
                String reason = reasonFor(error.getErrorCodeName());
                lastError = reason + (name.isEmpty() ? "" : " · " + name) + " (" + error.getErrorCodeName() + ")";
                note("active error: " + error.getErrorCodeName() + " " + decodeName(name));
                failures++;
                notifyChanged();

                if (failures > 6) {
                    lastError = "Several tracks failed to play. Check the connection.";
                    stop();
                    return;
                }
                String url = item == null ? null : item.entry.url;
                boolean retry = failures <= 2 && (url == null || !url.equals(lastFailedUrl));
                lastFailedUrl = url;
                handler.postDelayed(() -> {
                    if (engines == null) return;
                    if (retry) {
                        int at = playlistMode() ? active().getCurrentMediaItemIndex() : queueIndex;
                        active().prepare();
                        active().seekTo(Math.max(0, at), 0L);
                        active().play();
                    } else if (queue.size() > 1) {
                        next();
                    }
                }, retry ? 900 : 400);
            }
        });
        return engine;
    }

    private static AudioAttributes audioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
    }

    /** Only the engine that is actually playing owns the audio focus. */
    private void enableFocus(ExoPlayer engine) {
        try {
            engine.setAudioAttributes(audioAttributes(), true);
        } catch (Exception ignored) { }
    }

    private void disableFocus(ExoPlayer engine) {
        try {
            engine.setAudioAttributes(audioAttributes(), false);
        } catch (Exception ignored) { }
    }

    /** Aborts any fade in flight; the caller decides what plays next. */
    private void resetCrossfadeState() {
        fadeGeneration++;
        crossfading = false;
        fadeStartedAt = 0;
    }

    /** Recovers when a fade was interrupted: keep the incoming engine playing. */
    private void recoverStuckFade() {
        if (!crossfading) return;
        resetCrossfadeState();
        try {
            stopEngine(standby());
            activeIndex = 1 - activeIndex;
            active().setVolume(1f);
            if (!active().isPlaying()) active().play();
        } catch (Exception ignored) { }
        notifyChanged();
    }

    private ExoPlayer active() {
        return engines[activeIndex];
    }

    private ExoPlayer standby() {
        return engines[1 - activeIndex];
    }

    private boolean crossfadeMode() {
        return crossfadeSeconds > 0;
    }

    /** The queue plays as one ExoPlayer playlist, which is what removes gaps. */
    private boolean playlistMode() {
        return crossfadeSeconds == 0 && gaplessEnabled;
    }

    void prepareExternalQueue() {
        if (engines == null) return;
        active().setPauseAtEndOfMediaItems(false);
        active().setRepeatMode(androidx.media3.common.Player.REPEAT_MODE_OFF);
    }

    void setGapless(boolean enabled) {
        gaplessEnabled = enabled;
    }

    private boolean carMode = false;

    /** Marks that a car client is connected; the fade setting stays untouched. */
    void enterCarMode() {
        carMode = true;
    }

    boolean isCarMode() {
        return carMode;
    }

    void exitCarMode() {
        carMode = false;
    }

    /** The engine a media session should follow; during a fade it is the incoming one. */
    androidx.media3.common.Player sessionPlayer() {
        if (engines == null) return null;
        return crossfading && !externalQueue() ? standby() : active();
    }

    // ---- queue ---------------------------------------------------------

    void play(List<TrackItem> tracks, int pos) {
        inUserQueue = false;
        returnQueue = null;
        startPlayback(tracks, pos);
    }

    private void startPlayback(List<TrackItem> tracks, int pos) {
        queue.clear();
        queue.addAll(tracks);
        if (queue.isEmpty() || engines == null) {
            notifyChanged();
            return;
        }
        lastError = null;
        disableFocus(standby());
        enableFocus(active());
        note("play start: " + (queue.isEmpty() ? "-" : queue.get(clamp(pos)).title));
        resetCrossfadeState();
        preparedTarget = -1;
        pendingNext = false;
        stopEngine(standby());
        int start = clamp(pos);
        if (shuffleEnabled) rebuildShuffleOrder(start);
        if (!playlistMode()) {
            queueIndex = start;
            stopEngine(standby());
            if (carMode) {
                active().setRepeatMode(Player.REPEAT_MODE_OFF);
                active().setPauseAtEndOfMediaItems(true);
                active().setMediaSources(sourcesForQueue(), start, 0L);
                active().prepare();
                active().play();
                active().setVolume(1f);
            } else {
                loadSingle(active(), start, 0);
                active().play();
                active().setVolume(1f);
            }
        } else {
            List<MediaSource> sources = new ArrayList<>(queue.size());
            for (TrackItem item : queue) sources.add(mediaSourceFor(item));
            active().setRepeatMode(repeatModeToExo());
            active().setPauseAtEndOfMediaItems(false);
            active().setMediaSources(sources, start, 0L);
            active().prepare();
            active().play();
            active().setVolume(1f);
        }
        prefetch(start + 1);
        notifyChanged();
    }

    /** Brings back the last song (paused, at its saved position) after a restart. */
    boolean restoreLastTrack(Stream stream, String url, String title, String artist,
                             String album, int positionMs) {
        if (engines == null || stream == null || url == null) return false;
        lastError = null;
        resetCrossfadeState();
        preparedTarget = -1;
        stopEngine(standby());
        inUserQueue = false;
        returnQueue = null;
        String name = url.substring(url.lastIndexOf('/') + 1);
        TrackItem item = new TrackItem(stream, new Entry(name, url, false, 0, 0));
        if (title != null && !title.isEmpty()) item.title = title;
        if (artist != null && !artist.isEmpty()) item.artist = artist;
        if (album != null && !album.isEmpty()) item.album = album;
        queue.clear();
        queue.add(item);
        queueIndex = 0;
        loadSingle(active(), 0, Math.max(0, positionMs));
        active().setVolume(1f);
        active().pause();
        note("restored last track: " + item.title);
        notifyChanged();
        return true;
    }

    private void loadSingle(ExoPlayer engine, int index, long startMs) {
        engine.setRepeatMode(Player.REPEAT_MODE_OFF);
        engine.setPauseAtEndOfMediaItems(false);
        engine.setMediaSource(mediaSourceFor(queue.get(index)));
        engine.prepare();
        if (startMs > 0) engine.seekTo(startMs);
    }

    private List<MediaSource> sourcesForQueue() {
        List<MediaSource> sources = new ArrayList<>(queue.size());
        for (TrackItem item : queue) sources.add(mediaSourceFor(item));
        return sources;
    }

    private MediaSource mediaSourceFor(TrackItem item) {
        Uri uri = item.nas.directUri(item.entry);
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .setAlbumTitle(item.album)
                .build();
        java.util.Map<String, String> headers = item.nas.httpHeaders(item.entry);
        if (uri != null && appContext != null) {
            MediaItem mediaItem = new MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build();
            String scheme = uri.getScheme();
            if (("http".equals(scheme) || "https".equals(scheme)) && headers != null && !headers.isEmpty()) {
                return new ProgressiveMediaSource.Factory(
                        new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers))
                        .createMediaSource(mediaItem);
            }
            return new ProgressiveMediaSource.Factory(
                    new androidx.media3.datasource.DefaultDataSource.Factory(appContext))
                    .createMediaSource(mediaItem);
        }
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(Uri.parse("stream://" + Integer.toHexString(System.identityHashCode(item))))
                .setMediaMetadata(metadata)
                .build();
        return new ProgressiveMediaSource.Factory(new StreamDataSource.Factory(item))
                .createMediaSource(mediaItem);
    }

    private static String reasonFor(String code) {
        if (code == null) return "Can't play this track";
        switch (code) {
            case "ERROR_CODE_IO_BAD_HTTP_STATUS":
            case "ERROR_CODE_IO_FILE_NOT_FOUND":
                return "File not found on the source";
            case "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED":
            case "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT":
            case "ERROR_CODE_TIMEOUT":
                return "Can't reach the source";
            case "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED":
                return "Plain HTTP is blocked";
            case "ERROR_CODE_PARSING_CONTAINER_MALFORMED":
            case "ERROR_CODE_PARSING_MANIFEST_MALFORMED":
            case "ERROR_CODE_DECODING_FAILED":
            case "ERROR_CODE_DECODER_INIT_FAILED":
                return "Damaged or unsupported file";
            case "ERROR_CODE_IO_NO_PERMISSION":
                return "No permission to read this file";
            case "ERROR_CODE_DRM_UNSPECIFIED":
                return "Protected file";
            default:
                return "Can't play this track";
        }
    }

    private static String decodeName(String value) {
        if (value == null || !value.contains("%")) return value;
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private void pauseAll() {
        if (engines == null) return;
        active().pause();
        standby().pause();
    }

    // ---- transport -----------------------------------------------------

    /** Resume the restored track, retrying once if the engine needed to buffer. */
    void resumeRestored() {
        if (engines == null) return;
        note("restore: resume");
        enableFocus(active());
        try {
            long duration = active().getDuration();
            long position = active().getCurrentPosition();
            if (duration > 0 && position >= duration - 500) active().seekTo(0);
            if (active().getPlaybackState() == Player.STATE_IDLE) active().prepare();
            active().play();
            if (!active().isPlaying()) active().play();
        } catch (Exception ignored) { }
        handler.postDelayed(() -> {
            if (engines == null) return;
            if (!active().isPlaying() && active().getPlayWhenReady()) {
                note("restore: retry");
                try {
                    active().prepare();
                    active().play();
                } catch (Exception ignored) { }
            }
        }, 1200);
        notifyChanged();
    }

    void play() {
        if (engines == null) return;
        note("play (command)");
        enableFocus(active());
        try {
            long duration = active().getDuration();
            long position = active().getCurrentPosition();
            if (active().getPlaybackState() == Player.STATE_ENDED
                    || (duration > 0 && position >= duration - 300)) {
                active().seekTo(0);
            }
            if (active().getPlaybackState() == Player.STATE_IDLE) active().prepare();
        } catch (Exception ignored) { }
        active().play();
        notifyChanged();
    }

    /** Idempotent pause. */
    void pause() {
        if (engines == null) return;
        note("pause (command)");
        pausedForCall = false;
        active().pause();
        notifyChanged();
    }

    void toggle() {
        if (engines == null) return;
        if (crossfading && !active().isPlaying()) {
            recoverStuckFade();
            return;
        }
        if (active().getPlayWhenReady()) {
            note("pause");
            pausedForCall = false;
            active().pause();
        } else {
            note("play");
            enableFocus(active());
            try {
                if (active().getPlaybackState() == Player.STATE_IDLE) active().prepare();
                long duration = active().getDuration();
                long position = active().getCurrentPosition();
                if (duration > 0 && position >= duration - 300) {
                    active().seekTo(0);
                }
            } catch (Exception ignored) { }
            active().play();
        }
        notifyChanged();
    }

    /** True when the playlist was handed over by the car session. */
    private boolean externalQueue() {
        if (engines == null) return false;
        try {
            MediaItem playing = active().getCurrentMediaItem();
            return playing != null && playing.mediaId != null && ITEMS.containsKey(playing.mediaId);
        } catch (Exception e) {
            return false;
        }
    }

    void next() {
        note("next");
        if (crossfading) {
            pendingNext = true;
            note("next queued");
            return;
        }
        if (inUserQueue) {
            int at = currentIndex();
            if (at >= 0 && at + 1 < queue.size()) moveTo(at + 1);
            else exitUserQueue();
            return;
        }
        if (externalQueue()) {
            note("next: car list");
            fadeExternal(1);
            return;
        }
        int target = nextIndex();
        if (target < 0) {
            stop();
            return;
        }
        note("next target: " + (target < queue.size() ? queue.get(target).title : "?"));
        moveTo(target);
    }

    /** Automatic advance (end of track): same target the UI shows. */
    private void autoAdvance() {
        int target = nextIndex();
        if (target < 0) {
            stop();
            return;
        }
        note("auto next: " + (target < queue.size() ? queue.get(target).title : "?"));
        moveTo(target);
    }

    void prev() {
        note("prev");
        if (inUserQueue) {
            int at = currentIndex();
            if (at > 0) moveTo(at - 1);
            return;
        }
        if (externalQueue()) {
            fadeExternal(-1);
            return;
        }
        int target = prevIndex();
        if (target >= 0) moveTo(target);
    }

    private int currentIndex() {
        if (playlistMode() && engines != null) {
            int at = active().getCurrentMediaItemIndex();
            return at >= 0 ? at : queueIndex;
        }
        return queueIndex;
    }

    private void moveTo(int target) {
        if (target < 0 || target >= queue.size()) return;
        if (target == currentIndex() && queue.size() > 1) return;
        if (crossfadeMode()) {
            crossfadeTo(target);
        } else if (playlistMode()) {
            queueIndex = target;
            active().seekTo(target, 0L);
            notifyChanged();
        } else {
            switchTo(target);
        }
    }

    private void switchTo(int target) {
        queueIndex = target;
        loadSingle(active(), target, 0);
        active().play();
        notifyChanged();
    }

    // ---- shuffle / repeat ----------------------------------------------

    void setShuffle(boolean enabled) {
        shuffleEnabled = enabled;
        preparedTarget = -1;
        if (enabled) rebuildShuffleOrder(currentIndex());
        else shuffleOrder.clear();
        notifyChanged();
    }

    /** Builds the fixed random order once; the current song stays first. */
    private void rebuildShuffleOrder(int current) {
        shuffleOrder.clear();
        if (queue.isEmpty()) return;
        int first = Math.max(0, Math.min(current, queue.size() - 1));
        java.util.List<Integer> rest = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            if (i != first) rest.add(i);
        }
        java.util.Collections.shuffle(rest);
        shuffleOrder.add(first);
        shuffleOrder.addAll(rest);
    }

    /** Next song in the current order, or -1 when the list is over. */
    private int nextIndex() {
        if (queue.isEmpty()) return -1;
        int current = currentIndex();
        if (shuffleEnabled) {
            if (shuffleOrder.size() != queue.size()) rebuildShuffleOrder(current);
            int pos = shuffleOrder.indexOf(current);
            if (pos < 0) {
                rebuildShuffleOrder(current);
                pos = 0;
            }
            int nextPos = pos + 1;
            if (nextPos < shuffleOrder.size()) return shuffleOrder.get(nextPos);
            if (repeatMode == 1) {
                rebuildShuffleOrder(current);
                return shuffleOrder.size() > 1 ? shuffleOrder.get(1) : shuffleOrder.get(0);
            }
            return -1;
        }
        if (current + 1 < queue.size()) return current + 1;
        return repeatMode == 1 ? 0 : -1;
    }

    private int prevIndex() {
        if (queue.isEmpty()) return -1;
        int current = currentIndex();
        if (shuffleEnabled) {
            if (shuffleOrder.size() != queue.size()) rebuildShuffleOrder(current);
            int pos = shuffleOrder.indexOf(current);
            if (pos > 0) return shuffleOrder.get(pos - 1);
            return repeatMode == 1 ? shuffleOrder.get(shuffleOrder.size() - 1) : current;
        }
        if (current > 0) return current - 1;
        return repeatMode == 1 ? queue.size() - 1 : current;
    }

    boolean isShuffle() {
        return shuffleEnabled;
    }

    void cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        if (playlistMode() && engines != null) {
            active().setRepeatMode(repeatModeToExo());
        }
        notifyChanged();
    }

    int getRepeat() {
        return repeatMode;
    }

    private int repeatModeToExo() {
        if (inUserQueue) {
            return repeatMode == 2 ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF;
        }
        if (repeatMode == 2) return Player.REPEAT_MODE_ONE;
        if (repeatMode == 1) return Player.REPEAT_MODE_ALL;
        return Player.REPEAT_MODE_OFF;
    }

    // ---- user queue ----------------------------------------------------

    List<QueueEntry> userQueueSnapshot() {
        List<QueueEntry> out = new ArrayList<>();
        for (TrackItem item : userQueue) out.add(toEntry(item));
        return out;
    }

    void playUserQueue(int index) {
        if (userQueue.isEmpty() || engines == null) return;
        if (!inUserQueue) {
            returnQueue = new ArrayList<>(queue);
            returnIndex = Math.min(currentIndex() + 1, Math.max(0, returnQueue.size() - 1));
        }
        inUserQueue = true;
        startPlayback(new ArrayList<>(userQueue), index);
    }

    void exitUserQueue() {
        inUserQueue = false;
        if (returnQueue == null || returnQueue.isEmpty()) {
            returnQueue = null;
            stop();
            return;
        }
        List<TrackItem> target = returnQueue;
        int index = returnIndex;
        returnQueue = null;
        startPlayback(new ArrayList<>(target), Math.max(0, Math.min(index, target.size() - 1)));
    }

    void removeUserQueueAt(int index) {
        if (index < 0 || index >= userQueue.size()) return;
        userQueue.remove(index);
        if (inUserQueue) {
            int at = Math.min(index, queue.size() - 1);
            if (at >= 0 && at < queue.size()) {
                queue.remove(at);
                if (playlistMode()) {
                    active().removeMediaItem(at);
                }
            }
        }
        notifyChanged();
    }

    void replaceUserQueue(List<TrackItem> items) {
        userQueue.clear();
        userQueue.addAll(items);
        if (!inUserQueue) notifyChanged();
    }

    private void captureContainer(MediaItem item) {
        try {
            if (item == null || item.mediaMetadata == null) return;
            android.os.Bundle extras = item.mediaMetadata.extras;
            if (extras == null) return;
            String container = extras.getString("fader.container");
            if (container != null) externalContainer = container;
        } catch (Exception ignored) { }
    }

    String getExternalContainer() {
        return externalContainer;
    }

    boolean isPlayingExternal() {
        return externalQueue();
    }

    boolean isInUserQueue() {
        return inUserQueue;
    }

    List<QueueEntry> queueSnapshot() {
        List<QueueEntry> out = new ArrayList<>();
        if (engines == null) return out;
        if (externalQueue()) {
            int count = active().getMediaItemCount();
            for (int i = 0; i < count; i++) {
                MediaItem item = active().getMediaItemAt(i);
                TrackItem track = item.mediaId == null ? null : ITEMS.get(item.mediaId);
                if (track != null) out.add(toEntry(track));
            }
            return out;
        }
        for (TrackItem item : queue) out.add(toEntry(item));
        return out;
    }

    private static QueueEntry toEntry(TrackItem item) {
        String url = item.entry.url;
        boolean remoteUrl = url != null && (url.startsWith("http://") || url.startsWith("https://"));
        return new QueueEntry(url, item.title, item.artist, item.album, remoteUrl);
    }

    void enqueue(Stream stream, String url, boolean playNext) {
        addToUserQueue(stream, url);
    }

    void addToUserQueue(Stream stream, String url) {
        if (engines == null || url == null || stream == null) return;
        String name = url.substring(url.lastIndexOf('/') + 1);
        TrackItem item = new TrackItem(stream, new Entry(name, url, false, 0, 0));
        for (TrackItem queued : userQueue) {
            if (url.equals(queued.entry.url)) return;
        }
        userQueue.add(item);
        if (inUserQueue) {
            queue.add(item);
            if (playlistMode()) {
                active().addMediaSource(queue.size() - 1, mediaSourceFor(item));
            }
        }
        notifyChanged();
    }

    long currentSize() {
        TrackItem item = current();
        return item == null ? 0 : item.entry.size;
    }

    String currentName() {
        TrackItem item = current();
        return item == null ? "" : item.entry.name;
    }

    void seekTo(int ms) {
        if (engines == null) return;
        if (crossfading && !externalQueue()) standby().seekTo(ms);
        else active().seekTo(ms);
    }

    void stop() {
        if (engines == null) return;
        note("stop");
        stopEngine(active());
        stopEngine(standby());
        preparedTarget = -1;
        resetCrossfadeState();
        notifyChanged();
    }

    private void stopEngine(ExoPlayer engine) {
        try {
            engine.setPlayWhenReady(false);
            engine.stop();
            engine.clearMediaItems();
            engine.setVolume(1f);
        } catch (Exception ignored) { }
    }

    // ---- crossfade -----------------------------------------------------

    /** Loads the upcoming track long before the fade, so it is buffered in time. */
    private void prepareIncoming(int target) {
        if (engines == null || target < 0 || target >= queue.size()) return;
        if (target == currentIndex()) return;
        ExoPlayer in = standby();
        try {
            if (in.isPlaying()) return;
            in.setRepeatMode(Player.REPEAT_MODE_OFF);
            in.setPauseAtEndOfMediaItems(false);
            in.setVolume(0f);
            in.pause();
            in.setMediaSource(mediaSourceFor(queue.get(target)));
            in.prepare();
            preparedTarget = target;
        } catch (Exception e) {
            preparedTarget = -1;
        }
    }

    private void crossfadeTo(int target) {
        if (crossfading || engines == null || queue.isEmpty()) return;
        if (target == currentIndex() && queue.size() > 1) return;
        final int fadeMs = Math.max(500, crossfadeSeconds * 1000);
        ExoPlayer out = active();
        ExoPlayer in = standby();

        boolean preloaded = !carMode && preparedTarget == target
                && in.getPlaybackState() != Player.STATE_IDLE;
        try {
            in.setRepeatMode(Player.REPEAT_MODE_OFF);
            if (preloaded) {
                // The prepared engine must start from the beginning of the song.
                in.seekTo(0);
                in.setVolume(0f);
                in.play();
            } else if (carMode) {
                in.setPauseAtEndOfMediaItems(true);
                in.setMediaSources(sourcesForQueue(), target, 0L);
                in.prepare();
            } else {
                in.setPauseAtEndOfMediaItems(false);
                in.setMediaSource(mediaSourceFor(queue.get(target)));
                in.prepare();
                in.seekTo(0);
            }
            if (!preloaded) {
                in.setVolume(0f);
                in.play();
            }
        } catch (Exception e) {
            Log.e("FaderPlayer", "crossfade start failed", e);
            lastError = "Open failed: " + e.getMessage();
            note("fade failed: " + e.getMessage());
            notifyChanged();
            return;
        }

        disableFocus(in);
        note("fade -> " + (target < queue.size() ? queue.get(target).title : "?"));
        resetCrossfadeState();
        crossfading = true;
        fadeStartedAt = System.currentTimeMillis();
        final int generation = fadeGeneration;
        // The new track is "current" from the first moment of the overlap,
        // so the title, artwork and notification switch with the audio.
        queueIndex = target;
        final long start = System.currentTimeMillis();
        notifyChanged();
        final ExoPlayer fadingOut = out;
        final ExoPlayer fadingIn = in;
        final int targetIndex = target;

        handler.post(new Runnable() {
            private long rampStart = 0;

            @Override public void run() {
                if (generation != fadeGeneration) return;
                // Wait for the incoming track before touching the volumes: fading
                // into a still-buffering stream would leave a silent gap.
                if (fadingIn.getPlaybackState() != Player.STATE_READY) {
                    if (System.currentTimeMillis() - start < 3000) {
                        handler.postDelayed(this, 100);
                        return;
                    }
                    resetCrossfadeState();
                    preparedTarget = -1;
                    stopEngine(fadingIn);
                    try {
                        fadingOut.setVolume(1f);
                        if (!fadingOut.isPlaying()) fadingOut.play();
                    } catch (Exception ignored) { }
                    lastError = "Next track did not start in time";
                    notifyChanged();
                    return;
                }
                if (rampStart == 0) rampStart = System.currentTimeMillis();
                float progress = Math.min(1f, (System.currentTimeMillis() - rampStart) / (float) fadeMs);
                fadingOut.setVolume(1f - progress);
                fadingIn.setVolume(progress);
                if (progress < 1f) {
                    handler.postDelayed(this, 40);
                } else {
                    stopEngine(fadingOut);
                    activeIndex = 1 - activeIndex;
                    enableFocus(active());
                    try {
                        active().setVolume(1f);
                        if (!active().isPlaying() && active().getPlayWhenReady()) {
                            active().play();
                        }
                    } catch (Exception ignored) { }
                    crossfading = false;
                    fadeStartedAt = 0;
                    preparedTarget = -1;
                    note("fade done");
                    if (pendingNext) {
                        pendingNext = false;
                        handler.postDelayed(() -> next(), 60);
                    }
                    prefetch(targetIndex + 1);
                    notifyChanged();
                }
            }
        });
    }

    /** Next/Prev inside a playlist handed over by the car: fades out, jumps, fades in. */
    private void fadeExternal(int direction) {
        if (engines == null) return;
        int count = active().getMediaItemCount();
        if (count < 2) return;
        int current = active().getCurrentMediaItemIndex();
        int target = ((current + direction) % count + count) % count;
        if (!crossfadeMode()) {
            active().seekTo(target, 0L);
            notifyChanged();
            return;
        }
        if (crossfading) {
            pendingNext = true;
            return;
        }
        final long fadeMs = Math.max(500, crossfadeSeconds * 1000L);
        final long half = Math.max(250, fadeMs / 2);
        crossfading = true;
        fadeStartedAt = System.currentTimeMillis();
        note("fade (car list)");
        final long start = System.currentTimeMillis();
        handler.post(new Runnable() {
            @Override public void run() {
                if (!crossfading) return;
                float p = Math.min(1f, (System.currentTimeMillis() - start) / (float) half);
                try {
                    active().setVolume(1f - p);
                } catch (Exception ignored) { }
                if (p < 1f) {
                    handler.postDelayed(this, 30);
                    return;
                }
                try {
                    active().seekTo(target, 0L);
                } catch (Exception ignored) { }
                final long second = System.currentTimeMillis();
                handler.post(new Runnable() {
                    @Override public void run() {
                        float q = Math.min(1f, (System.currentTimeMillis() - second) / (float) half);
                        try {
                            active().setVolume(q);
                        } catch (Exception ignored) { }
                        if (q < 1f) {
                            handler.postDelayed(this, 30);
                            return;
                        }
                        try {
                            active().setVolume(1f);
                        } catch (Exception ignored) { }
                        crossfading = false;
                        fadeStartedAt = 0;
                        note("fade done (car list)");
                        if (pendingNext) {
                            pendingNext = false;
                            handler.postDelayed(() -> next(), 60);
                        }
                        notifyChanged();
                    }
                });
            }
        });
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try {
                if (audioManager != null && engines != null) {
                    int mode = audioManager.getMode();
                    boolean inCall = mode == AudioManager.MODE_IN_CALL
                            || mode == AudioManager.MODE_IN_COMMUNICATION
                            || mode == AudioManager.MODE_RINGTONE;
                    if (inCall && active().isPlaying()) {
                        pausedForCall = true;
                        note("call: pause");
                        pauseAll();
                    } else if (!inCall && pausedForCall) {
                        pausedForCall = false;
                        note("call: resume");
                        enableFocus(active());
                        active().play();
                        notifyChanged();
                    }
                }
                if (crossfading && fadeStartedAt > 0
                        && System.currentTimeMillis() - fadeStartedAt > crossfadeSeconds * 1000L + 3000) {
                    recoverStuckFade();
                }
                if (crossfadeMode() && !crossfading && !carMode && !inUserQueue
                        && !externalQueue() && queue.size() > 1) {
                    long position = active().getCurrentPosition();
                    long duration = active().getDuration();
                    long fadeMs = crossfadeSeconds * 1000L;
                    long remaining = duration - position;
                    if (duration > 0 && remaining > fadeMs && remaining <= 25000) {
                        int target = nextIndex();
                        if (target >= 0 && preparedTarget != target) prepareIncoming(target);
                    }
                }
                boolean atQueueEnd = inUserQueue && queueIndex >= queue.size() - 1;
                boolean external = externalQueue();
                if (crossfadeMode() && !crossfading && queue.size() > 1
                        && !atQueueEnd && !external) {
                    long position = active().getCurrentPosition();
                    long duration = active().getDuration();
                    boolean activePlayback = active().isPlaying()
                            || (carMode && active().getPlayWhenReady());
                    long fadeMs = crossfadeSeconds * 1000L;
                    // Never fade a track that reports a suspiciously short length:
                    // some files expose a wrong duration while they start.
                    boolean plausible = duration > 60000;
                    long guard = Math.min(fadeMs, duration / 2);
                    if (plausible && duration - position <= fadeMs
                            && position >= guard && activePlayback) {
                        autoAdvance();
                    }
                }
            } catch (Exception ignored) { }
            handler.postDelayed(this, 300);
        }
    };

    // ---- inspection ----------------------------------------------------

    TrackItem current() {
        if (engines == null) return null;
        try {
            MediaItem playing = active().getCurrentMediaItem();
            if (playing != null && playing.mediaId != null) {
                TrackItem external = ITEMS.get(playing.mediaId);
                if (external != null) return external;
            }
        } catch (Exception ignored) { }
        if (queue.isEmpty()) return null;
        int at = playlistMode() ? active().getCurrentMediaItemIndex() : queueIndex;
        return at >= 0 && at < queue.size() ? queue.get(at) : null;
    }

    String getLastError() { return lastError; }

    TrackItem peek(int offset) {
        if (engines == null) return null;
        if (externalQueue()) {
            try {
                int count = active().getMediaItemCount();
                if (count == 0) return null;
                int at = active().getCurrentMediaItemIndex() + offset;
                int index = ((at % count) + count) % count;
                MediaItem item = active().getMediaItemAt(index);
                TrackItem track = item.mediaId == null ? null : ITEMS.get(item.mediaId);
                if (track != null) return track;
            } catch (Exception ignored) { }
            return current();
        }
        if (queue.isEmpty()) return null;
        if (offset == 1) {
            int target = nextIndex();
            return target >= 0 && target < queue.size() ? queue.get(target) : null;
        }
        int base = playlistMode() ? active().getCurrentMediaItemIndex() : queueIndex;
        int size = queue.size();
        int i = ((base + offset) % size + size) % size;
        return queue.get(i);
    }

    androidx.media3.common.Player engine() {
        return engines == null ? null : active();
    }

    boolean isPlaying() {
        return engines != null && (crossfading || active().isPlaying());
    }

    /** During a crossfade the incoming engine is the one the UI should follow. */
    private ExoPlayer displayEngine() {
        if (engines == null) return null;
        return crossfading && !externalQueue() ? standby() : active();
    }

    int getPosition() {
        ExoPlayer engine = displayEngine();
        return engine == null ? 0 : (int) engine.getCurrentPosition();
    }

    int getDuration() {
        try {
            ExoPlayer engine = displayEngine();
            long d = engine == null ? 0 : engine.getDuration();
            return d > 0 ? (int) d : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- settings ------------------------------------------------------

    void setCrossfade(int seconds) {
        int next = CrossfadePolicy.clamp(seconds);
        if (next == crossfadeSeconds) return;
        boolean wasPlaying = isPlaying();
        long position = getPosition();
        TrackItem item = current();
        int index = item == null ? 0 : queue.indexOf(item);
        crossfadeSeconds = next;
        if (item == null) return;

        stopEngine(standby());
        if (!playlistMode()) {
            queueIndex = index;
            loadSingle(active(), index, position);
        } else {
            List<MediaSource> sources = new ArrayList<>(queue.size());
            for (TrackItem entry : queue) sources.add(mediaSourceFor(entry));
            active().setMediaSources(sources, index, position);
            active().prepare();
        }
        if (wasPlaying) active().play();
        else active().pause();
        active().setVolume(1f);
        notifyChanged();
    }

    int getCrossfade() { return crossfadeSeconds; }

    // ---- helpers -------------------------------------------------------

    private int clamp(int pos) {
        return Math.max(0, Math.min(pos, queue.size() - 1));
    }

    private void prefetch(int index) {
        if (queue.isEmpty()) return;
        TrackItem next = queue.get(((index % queue.size()) + queue.size()) % queue.size());
        PREFETCH.execute(() -> {
            try {
                android.media.MediaDataSource src = next.nas.source(next.entry);
                byte[] buffer = new byte[8192];
                src.readAt(0, buffer, 0, buffer.length);
                src.close();
            } catch (Exception ignored) { }
        });
    }

    private void note(String message) {
        try {
            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            synchronized (journal) {
                journal.addLast(time + "  " + message);
                while (journal.size() > 120) journal.removeFirst();
            }
        } catch (Exception ignored) { }
    }

    void noteEvent(String message) {
        note(message);
    }

    List<String> journalSnapshot() {
        synchronized (journal) {
            return new ArrayList<>(journal);
        }
    }

    /** Re-publishes session state (used after a favorite change). */
    void refresh() {
        notifyChanged();
    }

    private void notifyChanged() {
        for (Listener listener : new ArrayList<>(listeners)) listener.onChanged();
    }

    void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
