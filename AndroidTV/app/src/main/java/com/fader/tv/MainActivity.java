package com.fader.tv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.*;
import android.media.audiofx.Visualizer;
import android.os.*;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android TV music player with direct, read-only SMB network-share browsing and streaming. */
public class MainActivity extends Activity {
    private enum Mode { FOLDER, FAVORITES }
    private enum PlayFrom { FOLDER, FAVORITES, SEARCH }
    private enum Overlay { NONE, SETTINGS, SEARCH }

    private static final int REQ_AUDIO = 41;
    private static final int REQ_STORAGE = 42;
    private static final int LIBRARY_CACHE_SIZE = 8;
    /** Keeps the in-memory folder cache bounded on very large libraries. */
    private static final int LIBRARY_CACHE_MAX_TRACKS = 24000;
    /** Folder-list handling for browse(): keep the list, replace with subfolders, or decide by content. */
    private static final int FOLDER_LIST_KEEP = 0;
    private static final int FOLDER_LIST_REPLACE = 1;
    private static final int FOLDER_LIST_AUTO = 2;

    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Handler debounce = new Handler(Looper.getMainLooper());
    private final int[] colors = {0xff087af4,0xff16b7a6,0xff865cf1};
    private final ArrayList<TrackItem> shown = new ArrayList<>(), playback = new ArrayList<>();
    private final ArrayList<TrackItem> folderSongs = new ArrayList<>();
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final ExecutorService connector = Executors.newSingleThreadExecutor();
    private final Map<MediaPlayer,MediaDataSource> sources = new IdentityHashMap<>();
    private final LinkedHashMap<String,NasClient.Library> libraryCache = new LinkedHashMap<>(8,0.75f,true);

    private SharedPreferences prefs;
    private ArtworkStore artwork;
    private FavoritesStore favorites;
    private LibraryClient nas;
    private NasClient activeNetwork;
    private LocalClient activeLocal;
    private final List<NasClient> nasClients = new ArrayList<>();
    private String browseUrl = "", folderName = "";
    private final ArrayList<NasClient.Entry> browsedFolders = new ArrayList<>();
    private volatile int browseGeneration, connectGeneration;
    private volatile boolean destroyed, autoConnectTried, connecting;
    private boolean suppressPause;
    private AlertDialog errorDialog;

    private LinearLayout root, columns, shareBox, sourceRow, folderBox;
    private ListView trackList;
    private TrackAdapter trackAdapter;
    private TextView listTitle, status, crumb, folderInfo, emptyState;
    private Button previous, next, refreshButton, connectButton, settingsButton, playAllButton, stopButton, upButton;
    private LinearLayout favoritesButton;
    private final Random rng = new Random();

    private MediaPlayer active, standby;
    private int index = 0, standbyIndex = -1, generation = 0, fadeSeconds = 3, plannedNext = -1, fadeTargetIndex = -1;
    private boolean playing, ready, standbyReady, fading, shuffle, dark = true, dragging, foreground;
    private long fadeStart, fadeDuration, lastInput;
    private String lastCenteredKey;
    private String pendingFolderFocus;
    private int pendingResumeIndex = -1;
    private long pendingResumePos = -1, pendingSeek = 0;

    private FrameLayout ambient;
    private BlurredBackground ambientBackground;
    private Art ambientArt;
    private TextView ambientTitle, ambientArtist, ambientTime, ambientNextUp;
    private Button ambientPrevious, ambientPlay, ambientNext, ambientList, ambientHeart;
    private SeekBar ambientSeek;
    private boolean ambientMode;
    private TextView song, artist, time, nextUp;
    private Button play, heart;
    private SeekBar seek;
    private FrameLayout stage;
    private Art art;
    private SpectrumView spectrumMain, spectrumAmbient;
    private Button shuffleChip, crossfadeChip;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private final int BLUE = 0xff63adff;
    private boolean resumeAfterFocus;

    private Mode viewMode = Mode.FOLDER;
    private PlayFrom playFrom = PlayFrom.FOLDER;
    private Overlay overlay = Overlay.NONE;
    private String playbackUrl = "", playbackName = "", playbackQuery = "";
    private int selectedPosition = -1;
    private boolean listFocused;
    private boolean focusListAfterBrowse;
    private final Map<String,Integer> folderCounts = new ConcurrentHashMap<>();
    private final Map<String,TextView> folderCountViews = new HashMap<>();

    private LinearLayout nowCard;
    private Art nowArt;
    private TextView nowTitle, nowArtist, nowSource;

    private FrameLayout settingsOverlay;
    private LinearLayout settingsContent;
    private Button settingsClose;
    private boolean spectrumOn = true, audioSync = false, audioSyncAsked = false;
    private long sleepUntil = 0;

    private Visualizer visualizer;
    private volatile byte[] lastFft;
    private volatile long lastFftAt;
    private volatile int lastFftRate = 44100;
    private float liveGainDb;
    private float[] liveMags;
    private float[] liveDb = new float[64];
    private long lastGainAt;

    private FrameLayout searchOverlay;
    private EditText searchField;
    private TextView searchStatus;
    private ListView searchList;
    private Button searchScope, searchClose;
    private final ArrayList<TrackItem> searchResults = new ArrayList<>();
    private SearchAdapter searchAdapter;
    private boolean searchWhole = true, indexing;
    private String lastQuery = "";
    private volatile List<NasClient.Entry> globalIndex;
    private int searchGeneration;
    private int searchSelected = -1;
    private boolean searchFocused;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        CrashLog.install(this);
        prefs = getPreferences(0);
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(change -> {
                if(change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    resumeAfterFocus = playing && change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                    pauseAudio();
                } else if(change == AudioManager.AUDIOFOCUS_GAIN && resumeAfterFocus) {
                    resumeAfterFocus = false; startAudio();
                } else if(change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    pauseAudio();
                }
            }).build();
        index = 0;
        shuffle = prefs.getBoolean("shuffle", false);
        fadeSeconds = CrossfadePolicy.clamp(prefs.getInt("fade", 3));
        spectrumOn = prefs.getBoolean("spectrum", true);
        audioSync = prefs.getBoolean("audioSync", true);
        audioSyncAsked = prefs.getBoolean("audioSyncAsked", false);
        dark = resolveDark();
        lastInput = SystemClock.uptimeMillis();
        artwork = new ArtworkStore(getCacheDir());
        favorites = new FavoritesStore(getSharedPreferences("favorites", 0));
        loadFolderCounts();
        buildUI();
        ensureAudioPermission();
    }

    /**
     * Real spectrum levels need RECORD_AUDIO. Ask once, after the browser has
     * settled, and only when the user still has real sync selected.
     */
    private void ensureAudioPermission() {
        if(!audioSync || audioSyncAsked) return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return;
        clock.postDelayed(() -> {
            if(destroyed || isFinishing() || !foreground || audioSyncAsked) return;
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return;
            audioSyncAsked = true;
            prefs.edit().putBoolean("audioSyncAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }, 1500);
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private int fg() { return dark ? 0xffedf2fa : 0xff202833; }
    private int muted() { return dark ? 0xffa3b2c5 : 0xff5d6b7c; }
    private int panel() { return dark ? 0xff192534 : 0xffe8edf3; }
    private int panel2() { return dark ? 0xff142234 : 0xffdde6f1; }
    private int focusFill() { return dark ? 0xff28496a : 0xffc9ddf9; }

    private TextView text(String s, int size) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(fg());
        v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }
    private TextView section(String s) {
        TextView v = text(s, 10); v.setTextColor(muted()); v.setTypeface(null, Typeface.BOLD);
        v.setLetterSpacing(0.10f); v.setPadding(dp(4), 0, 0, 0); return v;
    }
    private LinearLayout vertical() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout horizontal() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int color, boolean focused) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(12));
        if(focused) d.setStroke(dp(3), BLUE); return d;
    }
    private StateListDrawable buttonBackground(boolean ambient) {
        int normal = ambient ? 0xff142234 : panel();
        int focused = ambient ? 0xff28496a : focusFill();
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, shape(focused, true));
        d.addState(new int[]{android.R.attr.state_focused}, shape(focused, true));
        d.addState(new int[]{}, shape(normal, false));
        return d;
    }
    private void keepFocus(View v) {
        if(v == null) return;
        v.post(() -> {
            if(destroyed || !v.isAttachedToWindow() || !v.isShown()) return;
            if(v.requestFocus()) return;
            v.postDelayed(() -> {
                if(!destroyed && v.isAttachedToWindow() && v.isShown()) v.requestFocus();
            }, 90);
            v.postDelayed(() -> {
                if(!destroyed && v.isAttachedToWindow() && v.isShown()) v.requestFocus();
            }, 240);
        });
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(fg());
        b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(12), dp(4), dp(12), dp(4));
        b.setBackground(buttonBackground(false)); b.setFocusable(true); b.setFocusableInTouchMode(true);
        b.setId(View.generateViewId()); b.setOnClickListener(v -> action.run()); return b;
    }
    private Button ambientButton(String label, Runnable action) {
        Button b = button(label, action); b.setTextColor(0xffedf2fa); b.setBackground(buttonBackground(true)); return b;
    }
    private Button settingsRow(String label, Runnable action) {
        Button b = button(label, action); b.setTextSize(14); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), 0, dp(16), 0); return b;
    }
    private TextView settingsInfo(String label) {
        TextView v = text(label, 13); v.setTextColor(muted()); v.setPadding(dp(16), dp(2), dp(16), dp(6)); return v;
    }
    private void spaced(LinearLayout parent, View child, int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4)); parent.addView(child, lp);
    }
    private LinearLayout.LayoutParams lp(int w, int h, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(dp(margin), dp(margin), dp(margin), dp(margin)); return p;
    }
    private static String formatBytes(long bytes) {
        if(bytes < 1024) return bytes + " B";
        if(bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
    private static int mix(int a, int b, float t) {
        return Color.rgb((int)(Color.red(a) * (1 - t) + Color.red(b) * t),
            (int)(Color.green(a) * (1 - t) + Color.green(b) * t),
            (int)(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }
    private boolean resolveDark() {
        String theme = prefs.getString("theme", dark ? "dark" : "light");
        if("auto".equals(theme)) {
            int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            return hour < 7 || hour >= 19;
        }
        return "dark".equals(theme);
    }

    private void buildUI() {
        overlay = Overlay.NONE;
        ambientMode = false;
        root = vertical();
        root.setPadding(dp(22), dp(10), dp(22), dp(8));
        root.setBackgroundColor(dark ? 0xff101820 : 0xfff5f7fa);
        root.addView(buildHeader());
        columns = horizontal();
        root.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1f));
        columns.addView(buildLeftPanel(), new LinearLayout.LayoutParams(dp(252), -1));
        LinearLayout.LayoutParams centreLp = new LinearLayout.LayoutParams(0, -1, 1.08f);
        centreLp.setMargins(dp(12), 0, dp(12), 0);
        columns.addView(buildCenterPanel(), centreLp);
        columns.addView(buildRightPanel(), new LinearLayout.LayoutParams(0, -1, 1f));
        TextView hint = text("↑ ↓ ← →  Navigate     •     OK  Select     •     OK lung  Acțiuni     •     Back  Return", 11);
        hint.setGravity(Gravity.CENTER); hint.setTextColor(muted());
        spaced(root, hint, -1, 22);
        FrameLayout shell = new FrameLayout(this);
        shell.addView(root);
        ambient = buildAmbient();
        shell.addView(ambient, new FrameLayout.LayoutParams(-1, -1));
        settingsOverlay = buildSettingsOverlay();
        shell.addView(settingsOverlay, new FrameLayout.LayoutParams(-1, -1));
        searchOverlay = buildSearchOverlay();
        shell.addView(searchOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(shell);
        shell.post(() -> {
            WindowInsetsController controller = shell.getWindowInsetsController();
            if(controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        });
        renderSources();
        restoreBrowser();
        updateCrumb();
        applySpectrumVisibility();
        if(playback.isEmpty()) connectButton.requestFocus(); else play.requestFocus();
        updateUI();
        if(!autoConnectTried) { autoConnectTried = true; restoreLastSource(); }
    }

    private LinearLayout buildHeader() {
        LinearLayout head = horizontal();
        head.setPadding(0, 0, 0, dp(4));
        TextView brand = text("Fader TV", 24); brand.setTypeface(null, Typeface.BOLD);
        head.addView(brand, new LinearLayout.LayoutParams(dp(118), dp(48)));
        Button up = button("↑", () -> goUp()); up.setTextSize(18);
        upButton = up;
        head.addView(up, new LinearLayout.LayoutParams(dp(42), dp(40)));
        LinearLayout crumbBox = vertical();
        crumb = text("Not connected", 15); crumb.setTypeface(null, Typeface.BOLD);
        crumb.setSingleLine(true); crumb.setEllipsize(android.text.TextUtils.TruncateAt.END);
        crumbBox.addView(crumb, new LinearLayout.LayoutParams(-1, dp(23)));
        status = text("", 11); status.setTextColor(muted());
        status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        crumbBox.addView(status, new LinearLayout.LayoutParams(-1, dp(17)));
        LinearLayout.LayoutParams crumbLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        crumbLp.setMargins(dp(8), 0, dp(12), 0);
        head.addView(crumbBox, crumbLp);
        settingsButton = button("⚙  Settings", () -> openSettings());
        head.addView(settingsButton, new LinearLayout.LayoutParams(dp(124), dp(40)));
        return head;
    }

    private LinearLayout buildLeftPanel() {
        LinearLayout left = vertical();
        left.setPadding(dp(12), dp(10), dp(12), dp(8));
        left.setBackground(shape(panel(), false));
        left.addView(section("SOURCES"), new LinearLayout.LayoutParams(-1, dp(18)));
        shareBox = vertical();
        left.addView(shareBox, new LinearLayout.LayoutParams(-1, -2));
        sourceRow = horizontal();
        left.addView(sourceRow, lp(-1, 36, 3));
        LinearLayout actions = horizontal();
        refreshButton = button("Refresh", () -> {
            if(nas != null && !browseUrl.isEmpty()) {
                libraryCache.clear(); globalIndex = null; dismissError(); browse(browseUrl, folderName, false, null, FOLDER_LIST_KEEP);
                keepFocus(refreshButton);
            }
        });
        refreshButton.setTextSize(11);
        actions.addView(refreshButton, new LinearLayout.LayoutParams(0, dp(32), 1f));
        Button searchButton = button("Search", () -> openSearch(""));
        searchButton.setTextSize(11);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, dp(32), 1f);
        searchLp.setMargins(dp(3), 0, 0, 0);
        actions.addView(searchButton, searchLp);
        left.addView(actions, lp(-1, 32, 2));
        left.addView(section("LIBRARY"), new LinearLayout.LayoutParams(-1, dp(18)));
        favoritesButton = navRow("♥  Favorites", "0", () -> openFavorites());
        left.addView(favoritesButton, lp(-1, 34, 2));
        left.addView(section("FOLDERS"), new LinearLayout.LayoutParams(-1, dp(18)));
        ScrollView folderScroll = new ScrollView(this);
        folderScroll.setFocusable(false);
        folderScroll.setVerticalScrollBarEnabled(true);
        folderScroll.setScrollbarFadingEnabled(false);
        folderBox = vertical();
        folderScroll.addView(folderBox, new FrameLayout.LayoutParams(-1, -2));
        left.addView(folderScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        stopButton = button("■  Stop scan", () -> stopScan());
        stopButton.setTextSize(10);
        stopButton.setVisibility(View.GONE);
        left.addView(stopButton, lp(-1, 30, 1));
        folderInfo = text("SMB 2/3 • read only", 9);
        folderInfo.setTextColor(muted()); folderInfo.setSingleLine(true);
        left.addView(folderInfo, new LinearLayout.LayoutParams(-1, dp(16)));
        return left;
    }

    private LinearLayout navRow(String label, String count, Runnable action) {
        LinearLayout row = horizontal();
        row.setPadding(dp(10), 0, dp(10), 0);
        row.setBackground(buttonBackground(false));
        row.setFocusable(true); row.setFocusableInTouchMode(true);
        TextView name = text(label, 13); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView badge = text(count, 10); badge.setTextColor(muted());
        row.addView(badge, new LinearLayout.LayoutParams(-2, dp(34)));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private LinearLayout buildNowCard() {
        LinearLayout card = vertical();
        card.setPadding(dp(8), dp(6), dp(8), dp(5));
        card.setBackground(buttonBackground(false));
        card.setFocusable(true); card.setFocusableInTouchMode(true); card.setId(View.generateViewId());
        LinearLayout top = horizontal();
        nowArt = new Art(true);
        top.addView(nowArt, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout info = vertical();
        info.setPadding(dp(8), 0, 0, 0);
        nowTitle = text("Nothing playing", 12); nowTitle.setTypeface(null, Typeface.BOLD);
        nowTitle.setSingleLine(true); nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowArtist = text("Open a folder to start", 10); nowArtist.setTextColor(muted());
        nowArtist.setSingleLine(true); nowArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(nowTitle, new LinearLayout.LayoutParams(-1, dp(17)));
        info.addView(nowArtist, new LinearLayout.LayoutParams(-1, dp(15)));
        top.addView(info, new LinearLayout.LayoutParams(0, dp(34), 1f));
        card.addView(top, new LinearLayout.LayoutParams(-1, dp(34)));
        nowSource = text("", 8); nowSource.setTextColor(BLUE); nowSource.setLetterSpacing(0.08f);
        nowSource.setSingleLine(true); nowSource.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(nowSource, new LinearLayout.LayoutParams(-1, dp(13)));
        card.setOnClickListener(v -> showPlayingFolder());
        return card;
    }

    private LinearLayout buildCenterPanel() {
        LinearLayout centre = vertical();
        centre.setGravity(Gravity.CENTER);
        centre.setPadding(dp(16), 0, dp(16), 0);
        FrameLayout artHolder = new FrameLayout(this);
        centre.addView(artHolder, new LinearLayout.LayoutParams(-1, 0, 1f));
        art = new Art(true);
        artHolder.addView(art, new FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER));
        artHolder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            int size = Math.max(0, Math.min(dp(158), Math.min(r - l, b - t) - dp(8)));
            if(size > 0 && art.getLayoutParams().width != size) {
                art.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            }
        });
        TrackItem now = current();
        LinearLayout songRow = horizontal();
        song = text(now == null ? "Fader TV" : now.title, 22);
        song.setGravity(Gravity.CENTER); song.setTypeface(null, Typeface.BOLD);
        song.setSingleLine(true); song.setEllipsize(android.text.TextUtils.TruncateAt.END);
        songRow.addView(song, new LinearLayout.LayoutParams(0, dp(30), 1f));
        heart = button("♡", () -> { toggleFavorite(current()); keepFocus(heart); });
        heart.setTextSize(18); heart.setGravity(Gravity.CENTER);
        songRow.addView(heart, new LinearLayout.LayoutParams(dp(38), dp(30)));
        spaced(centre, songRow, -1, 30);
        artist = text(now == null ? "Connect to a network share" : now.artist, 14);
        artist.setGravity(Gravity.CENTER); artist.setSingleLine(true);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        spaced(centre, artist, -1, 20);
        seek = new SeekBar(this); seek.setMax(0);
        seek.setProgressTintList(ColorStateList.valueOf(BLUE));
        seek.setThumbTintList(ColorStateList.valueOf(BLUE));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(dark ? 0xff33465c : 0xffc3cfdd));
        bindSeek(seek);
        spaced(centre, seek, -1, 26);
        time = text("0:00 / 0:00", 11); time.setGravity(Gravity.CENTER); time.setTextColor(muted());
        spaced(centre, time, -1, 16);
        LinearLayout controls = horizontal();
        controls.setGravity(Gravity.CENTER);
        previous = button("|◀", () -> { if(!playback.isEmpty()) { prevWithFade(); keepFocus(previous); } });
        spaced(controls, previous, 62, 42);
        play = button("Play", () -> { if(playing) pauseAudio(); else startAudio(); keepFocus(play); });
        spaced(controls, play, 92, 42);
        next = button("▶|", () -> { if(!playback.isEmpty()) { nextWithFade(); keepFocus(next); } });
        spaced(controls, next, 62, 42);
        centre.addView(controls);
        nextUp = text("NEXT UP · —", 10);
        nextUp.setTextColor(muted()); nextUp.setGravity(Gravity.CENTER);
        nextUp.setSingleLine(true); nextUp.setEllipsize(android.text.TextUtils.TruncateAt.END);
        spaced(centre, nextUp, -1, 26);
        LinearLayout optionsRow = horizontal();
        optionsRow.setGravity(Gravity.CENTER);
        shuffleChip = button("Shuffle: " + (shuffle ? "On" : "Off"), () -> {
            shuffle = !shuffle; preload(); updateUI(); saveSettings(); keepFocus(shuffleChip);
        });
        shuffleChip.setTextSize(10);
        spaced(optionsRow, shuffleChip, -2, 28);
        crossfadeChip = button("Crossfade: " + fadeLabel(), () -> {
            cancelFade(); fadeSeconds = CrossfadePolicy.next(fadeSeconds); updateUI(); saveSettings(); keepFocus(crossfadeChip);
        });
        crossfadeChip.setTextSize(10);
        spaced(optionsRow, crossfadeChip, -2, 28);
        centre.addView(optionsRow);
        spectrumMain = new SpectrumView(this);
        spectrumMain.setBars(48);
        spectrumMain.setSource(this::readLive);
        centre.addView(spectrumMain, new LinearLayout.LayoutParams(-1, dp(52)));
        previous.setNextFocusRightId(play.getId()); play.setNextFocusLeftId(previous.getId());
        play.setNextFocusRightId(next.getId()); next.setNextFocusLeftId(play.getId());
        seek.setId(View.generateViewId());
        play.setNextFocusUpId(seek.getId()); previous.setNextFocusUpId(seek.getId()); next.setNextFocusUpId(seek.getId());
        seek.setNextFocusDownId(play.getId());
        heart.setNextFocusUpId(seek.getId()); heart.setNextFocusDownId(play.getId());
        play.setNextFocusDownId(shuffleChip.getId());
        shuffleChip.setNextFocusUpId(play.getId());
        shuffleChip.setNextFocusRightId(crossfadeChip.getId());
        crossfadeChip.setNextFocusLeftId(shuffleChip.getId());
        crossfadeChip.setNextFocusUpId(play.getId());
        return centre;
    }

    private LinearLayout buildRightPanel() {
        LinearLayout right = vertical();
        right.setPadding(dp(12), dp(10), dp(12), dp(8));
        right.setBackground(shape(panel(), false));
        LinearLayout head = horizontal();
        listTitle = text("SONGS", 14); listTitle.setTypeface(null, Typeface.BOLD);
        listTitle.setSingleLine(true); listTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(listTitle, new LinearLayout.LayoutParams(0, dp(34), 1f));
        playAllButton = button("▶  Play all", () -> playAllVisible());
        playAllButton.setTextSize(11);
        head.addView(playAllButton, new LinearLayout.LayoutParams(dp(96), dp(32)));
        right.addView(head, new LinearLayout.LayoutParams(-1, dp(38)));
        FrameLayout wrap = new FrameLayout(this);
        trackList = new ListView(this);
        trackList.setId(View.generateViewId());
        trackList.setDivider(new ColorDrawable(dark ? 0x18ffffff : 0x18000000));
        trackList.setDividerHeight(dp(1));
        trackList.setSelector(new ColorDrawable(0x00000000));
        trackList.setVerticalScrollBarEnabled(true);
        trackList.setScrollbarFadingEnabled(false);
        trackList.setFocusable(true);
        trackList.setFocusableInTouchMode(true);
        trackAdapter = new TrackAdapter();
        trackList.setAdapter(trackAdapter);
        trackList.setFocusable(true);
        trackList.setOnItemClickListener((parent, view, position, id) -> playTrackAt(position));
        trackList.setOnItemLongClickListener((parent, view, position, id) -> { showTrackMenu(position); return true; });
        trackList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPosition = position;
                trackAdapter.notifyDataSetChanged();
            }
            public void onNothingSelected(AdapterView<?> parent) {
                selectedPosition = -1;
                trackAdapter.notifyDataSetChanged();
            }
        });
        trackList.setOnFocusChangeListener((v, has) -> {
            listFocused = has;
            wrap.setBackground(has && trackAdapter.getCount() == 0 ? shape(0x00000000, true) : null);
            if(has) {
                int position = trackList.getSelectedItemPosition();
                if(position == ListView.INVALID_POSITION || position < 0) {
                    int target = indexOfCurrent();
                    if(target < 0) target = 0;
                    if(target >= 0 && trackList.getCount() > 0) trackList.setSelection(target);
                    selectedPosition = target;
                }
            }
            trackAdapter.notifyDataSetChanged();
        });
        wrap.addView(trackList, new FrameLayout.LayoutParams(-1, -1));
        emptyState = text("", 13); emptyState.setGravity(Gravity.CENTER); emptyState.setTextColor(muted());
        wrap.addView(emptyState, new FrameLayout.LayoutParams(-1, -1));
        right.addView(wrap, new LinearLayout.LayoutParams(-1, 0, 1f));
        nowCard = buildNowCard();
        right.addView(nowCard, lp(-1, 58, 3));
        trackList.setNextFocusDownId(nowCard.getId());
        nowCard.setNextFocusUpId(trackList.getId());
        return right;
    }

    private FrameLayout buildAmbient() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0xff080e16);
        ambientBackground = new BlurredBackground();
        layer.addView(ambientBackground, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout centre = vertical();
        centre.setGravity(Gravity.CENTER);
        centre.setPadding(dp(20), dp(6), dp(20), dp(6));
        FrameLayout coverWrap = new FrameLayout(this);
        ambientArt = new Art(true, true);
        ambientArt.setShadow(true);
        coverWrap.addView(ambientArt, new FrameLayout.LayoutParams(dp(224), dp(224), Gravity.CENTER));
        centre.addView(coverWrap, new LinearLayout.LayoutParams(-1, dp(230)));
        TrackItem now = current();
        ambientTitle = text(now == null ? "Fader TV" : now.title, 27);
        ambientTitle.setTextColor(0xffedf2fa); ambientTitle.setGravity(Gravity.CENTER);
        ambientTitle.setTypeface(null, Typeface.BOLD); ambientTitle.setSingleLine(true);
        ambientTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        centre.addView(ambientTitle, new LinearLayout.LayoutParams(-1, dp(34)));
        ambientArtist = text(now == null ? "" : now.artist, 14);
        ambientArtist.setTextColor(0xffc2cede); ambientArtist.setGravity(Gravity.CENTER);
        ambientArtist.setSingleLine(true); ambientArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        centre.addView(ambientArtist, new LinearLayout.LayoutParams(-1, dp(22)));
        LinearLayout seekBox = vertical();
        seekBox.setGravity(Gravity.CENTER);
        ambientSeek = new SeekBar(this); ambientSeek.setMax(0);
        ambientSeek.setProgressTintList(ColorStateList.valueOf(BLUE));
        ambientSeek.setThumbTintList(ColorStateList.valueOf(BLUE));
        ambientSeek.setProgressBackgroundTintList(ColorStateList.valueOf(0xff33465c));
        bindSeek(ambientSeek);
        seekBox.addView(ambientSeek, new LinearLayout.LayoutParams(dp(520), dp(24)));
        ambientTime = text("0:00 / 0:00", 11);
        ambientTime.setTextColor(0xff8fa1b6); ambientTime.setGravity(Gravity.CENTER);
        seekBox.addView(ambientTime, new LinearLayout.LayoutParams(dp(520), dp(14)));
        centre.addView(seekBox, new LinearLayout.LayoutParams(-1, dp(40)));
        LinearLayout controls = horizontal();
        controls.setGravity(Gravity.CENTER);
        ambientPrevious = ambientButton("|◀", () -> { if(!playback.isEmpty()) { prevWithFade(); keepFocus(ambientPrevious); } });
        spaced(controls, ambientPrevious, 62, 42);
        ambientPlay = ambientButton("Play", () -> { if(playing) pauseAudio(); else startAudio(); keepFocus(ambientPlay); });
        spaced(controls, ambientPlay, 92, 42);
        ambientNext = ambientButton("▶|", () -> { if(!playback.isEmpty()) { nextWithFade(); keepFocus(ambientNext); } });
        spaced(controls, ambientNext, 62, 42);
        ambientHeart = ambientButton("♡", () -> { toggleFavoriteFromAmbient(); keepFocus(ambientHeart); });
        spaced(controls, ambientHeart, 48, 42);
        ambientList = ambientButton("♪  List", () -> showPlayingFolder());
        spaced(controls, ambientList, 92, 42);
        centre.addView(controls);
        ambientNextUp = text("NEXT UP · —", 10);
        ambientNextUp.setTextColor(0xffa3b2c5); ambientNextUp.setGravity(Gravity.CENTER);
        ambientNextUp.setSingleLine(true); ambientNextUp.setEllipsize(android.text.TextUtils.TruncateAt.END);
        centre.addView(ambientNextUp, new LinearLayout.LayoutParams(-1, dp(26)));
        spectrumAmbient = new SpectrumView(this);
        spectrumAmbient.setBars(64);
        spectrumAmbient.setFadeEdges(true);
        spectrumAmbient.setSource(this::readLive);
        centre.addView(spectrumAmbient, new LinearLayout.LayoutParams(-1, dp(64)));
        TextView wake = text("◀ ▶  Select     •     OK  Activate     •     Back  Return", 11);
        wake.setTextColor(0xff8fa1b6); wake.setGravity(Gravity.CENTER);
        centre.addView(wake, new LinearLayout.LayoutParams(-1, dp(22)));
        ambientPrevious.setNextFocusRightId(ambientPlay.getId());
        ambientPlay.setNextFocusLeftId(ambientPrevious.getId());
        ambientPlay.setNextFocusRightId(ambientNext.getId());
        ambientNext.setNextFocusLeftId(ambientPlay.getId());
        ambientNext.setNextFocusRightId(ambientHeart.getId());
        ambientHeart.setNextFocusLeftId(ambientNext.getId());
        ambientHeart.setNextFocusRightId(ambientList.getId());
        ambientList.setNextFocusLeftId(ambientHeart.getId());
        ambientSeek.setId(View.generateViewId());
        ambientSeek.setNextFocusDownId(ambientPlay.getId());
        ambientPlay.setNextFocusUpId(ambientSeek.getId());
        ambientPrevious.setNextFocusUpId(ambientSeek.getId());
        ambientNext.setNextFocusUpId(ambientSeek.getId());
        ambientList.setNextFocusUpId(ambientSeek.getId());
        ambientHeart.setNextFocusUpId(ambientSeek.getId());
        layer.addView(centre, new FrameLayout.LayoutParams(-1, -1));
        layer.setVisibility(View.GONE);
        return layer;
    }

    private FrameLayout buildSettingsOverlay() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(dark ? 0xff101820 : 0xfff5f7fa);
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        LinearLayout col = vertical();
        col.setPadding(dp(90), dp(20), dp(90), dp(20));
        settingsContent = vertical();
        col.addView(settingsContent, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(col);
        layer.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        layer.setVisibility(View.GONE);
        return layer;
    }

    private FrameLayout buildSearchOverlay() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(dark ? 0xff101820 : 0xfff5f7fa);
        LinearLayout col = vertical();
        col.setPadding(dp(60), dp(22), dp(60), dp(10));
        LinearLayout top = horizontal();
        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextSize(18);
        searchField.setTextColor(fg());
        searchField.setHintTextColor(muted());
        searchField.setHint("Search song name, artist folder or album");
        searchField.setBackground(shape(panel(), false));
        searchField.setPadding(dp(16), 0, dp(16), 0);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        top.addView(searchField, new LinearLayout.LayoutParams(0, dp(46), 1f));
        searchScope = button("Whole share", () -> toggleSearchScope());
        searchScope.setTextSize(12);
        LinearLayout.LayoutParams scopeLp = new LinearLayout.LayoutParams(dp(128), dp(46));
        scopeLp.setMargins(dp(8), 0, 0, 0);
        top.addView(searchScope, scopeLp);
        searchClose = button("Close", () -> closeSearch());
        searchClose.setTextSize(12);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(88), dp(46));
        closeLp.setMargins(dp(8), 0, 0, 0);
        top.addView(searchClose, closeLp);
        col.addView(top, new LinearLayout.LayoutParams(-1, dp(50)));
        searchStatus = text("", 12); searchStatus.setTextColor(muted());
        searchStatus.setSingleLine(true); searchStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(searchStatus, new LinearLayout.LayoutParams(-1, dp(22)));
        searchList = new ListView(this);
        searchList.setId(View.generateViewId());
        searchList.setDivider(new ColorDrawable(dark ? 0x18ffffff : 0x18000000));
        searchList.setDividerHeight(dp(1));
        searchList.setSelector(new ColorDrawable(0x00000000));
        searchList.setVerticalScrollBarEnabled(true);
        searchList.setScrollbarFadingEnabled(false);
        searchList.setFocusable(true);
        searchList.setFocusableInTouchMode(true);
        searchAdapter = new SearchAdapter();
        searchList.setAdapter(searchAdapter);
        searchList.setOnItemClickListener((parent, view, position, id) -> playSearchResult(position));
        searchList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                searchSelected = position;
                searchAdapter.notifyDataSetChanged();
            }
            public void onNothingSelected(AdapterView<?> parent) {
                searchSelected = -1;
                searchAdapter.notifyDataSetChanged();
            }
        });
        searchList.setOnFocusChangeListener((v, has) -> {
            searchFocused = has;
            searchList.setBackground(has && searchAdapter.getCount() == 0 ? shape(0x00000000, true) : null);
            if(has && searchAdapter.getCount() > 0 && searchList.getSelectedItemPosition() < 0) {
                searchList.setSelection(0);
                searchSelected = 0;
            }
            searchAdapter.notifyDataSetChanged();
        });
        // Explicit focus flow: after typing, DOWN moves to the results; pressing
        // OK/ENTER in the field also jumps to the results so a track can be played.
        searchField.setNextFocusDownId(searchList.getId());
        searchScope.setNextFocusDownId(searchList.getId());
        searchClose.setNextFocusDownId(searchList.getId());
        searchList.setNextFocusUpId(searchField.getId());
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = actionId == EditorInfo.IME_ACTION_SEARCH
                || (event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER));
            if(enter && searchList.getCount() > 0) {
                searchList.requestFocus();
                searchList.setSelection(0);
                return true;
            }
            return false;
        });
        col.addView(searchList, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = text("OK  Play     •     Back  Close     •     Type to filter the share", 11);
        hint.setTextColor(muted()); hint.setGravity(Gravity.CENTER);
        col.addView(hint, new LinearLayout.LayoutParams(-1, dp(22)));
        layer.addView(col, new FrameLayout.LayoutParams(-1, -1));
        layer.setVisibility(View.GONE);
        searchField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                debounce.removeCallbacks(searchTask);
                debounce.postDelayed(searchTask, 260);
            }
            public void afterTextChanged(Editable s) { }
        });
        return layer;
    }

    private final Runnable searchTask = () -> runSearch(searchField == null ? "" : searchField.getText().toString());

    /** Rebuilds the current folder after a theme change without hitting the network. */
    private void restoreBrowser() {
        if(nas == null || browseUrl.isEmpty() || viewMode != Mode.FOLDER) {
            if(viewMode == Mode.FAVORITES) {
                listTitle.setText("FAVORITES (" + shown.size() + ")");
                folderInfo.setText(plural(shown.size(), "favorite", "favorites"));
                showEmptyState(shown.isEmpty() ? "No favorites yet.\nPress the heart on the playing track\nor long-press a song." : null);
                updateFavoritesBadge();
            }
            return;
        }
        NasClient.Library cached = libraryCache.get(browseUrl);
        if(cached != null) {
            ArrayList<TrackItem> items = buildTrackItems(nas, cached.audio, browseUrl);
            renderLibrary(nas, browseUrl, folderName, cached, items, false, FOLDER_LIST_KEEP);
        } else {
            browse(browseUrl, folderName, false, null, FOLDER_LIST_KEEP);
        }
    }

    private void renderSources() {
        if(shareBox == null || sourceRow == null) return;
        boolean local = nas instanceof LocalClient;
        String saved = prefs.getString("nas", "");

        // Top wide button = the active source.
        shareBox.removeAllViews();
        if(local) {
            Button card = button("▣  USB / Local", () -> openUsb());
            card.setSingleLine(true); card.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(34));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            shareBox.addView(card, lp);
        } else if(!saved.isEmpty()) {
            Button card = button("▣  " + shareLabel(saved), () -> {
                SavedCredentials stored = loadCredentials();
                if(stored != null && saved.equals(stored.address)) connectShare(saved, stored.domain, stored.user, stored.password, true, false);
                else connectDialog(saved);
            });
            card.setSingleLine(true); card.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.setOnLongClickListener(v -> { connectDialog(saved); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(34));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            shareBox.addView(card, lp);
        }

        // Second row = "+ Add share" + the other (inactive) source.
        sourceRow.removeAllViews();
        connectButton = button("+  Add share", () -> connectDialog(""));
        connectButton.setTextSize(12);
        sourceRow.addView(connectButton, new LinearLayout.LayoutParams(0, dp(36), 1f));
        Button other;
        if(local) {
            if(saved.isEmpty()) {
                other = button("▣  Share", () -> connectDialog(""));
            } else {
                other = button("▣  " + shareLabel(saved), () -> {
                    SavedCredentials stored = loadCredentials();
                    if(stored != null && saved.equals(stored.address)) connectShare(saved, stored.domain, stored.user, stored.password, true, false);
                    else connectDialog(saved);
                });
            }
        } else {
            other = button("📁 USB", () -> openUsb());
        }
        other.setTextSize(12);
        other.setSingleLine(true); other.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lp2.setMargins(dp(3), 0, 0, 0);
        sourceRow.addView(other, lp2);
    }

    private void renderFolders() {
        if(folderBox == null) return;
        String focusedUrl = null;
        View focused = getCurrentFocus();
        if(focused != null && focused.getParent() == folderBox && focused.getTag() instanceof String) focusedUrl = (String) focused.getTag();
        String wanted = pendingFolderFocus != null ? pendingFolderFocus : focusedUrl;
        pendingFolderFocus = null;
        folderBox.removeAllViews();
        folderCountViews.clear();
        for(NasClient.Entry e : browsedFolders) {
            Integer known = folderCounts.get(e.url);
            String count = known == null ? "…" : String.valueOf(known);
            boolean here = playFrom == PlayFrom.FOLDER && e.url.equals(playbackUrl);
            boolean opened = e.url.equals(browseUrl);
            String label = (here ? "♪  " : opened ? "▶  " : "▸  ") + e.name;
            LinearLayout row = horizontal();
            row.setId(View.generateViewId());
            row.setPadding(dp(10), 0, dp(8), 0);
            row.setBackground(buttonBackground(false));
            row.setFocusable(true); row.setFocusableInTouchMode(true);
            TextView name = text(label, 13); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if(opened) name.setTypeface(null, Typeface.BOLD);
            row.addView(name, new LinearLayout.LayoutParams(0, dp(34), 1f));
            TextView badge = text(count, 10); badge.setTextColor(muted());
            row.addView(badge, new LinearLayout.LayoutParams(-2, dp(34)));
            row.setTag(e.url);
            final NasClient.Entry entry = e;
            row.setOnClickListener(v -> openFolder(entry));
            row.setOnLongClickListener(v -> { playAllFolder(entry); return true; });
            folderCountViews.put(e.url, badge);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(34));
            rowLp.setMargins(0, 0, 0, dp(3));
            folderBox.addView(row, rowLp);
        }
        if(trackList != null && folderBox.getChildCount() > 0) trackList.setNextFocusLeftId(folderBox.getChildAt(0).getId());
        if(focusListAfterBrowse) return;
        if(wanted != null) focusFolder(wanted, true);
        else if(focusedUrl == null && folderBox.getChildCount() > 0) {
            View now = getCurrentFocus();
            if(now == null || now.getParent() != folderBox) focusFolder("", true);
        }
    }

    /** Opens a folder: shows its songs and marks it, keeping the folder list intact. */
    private void openFolder(NasClient.Entry entry) {
        focusListAfterBrowse = true;
        browse(entry.url, entry.name, false, null, FOLDER_LIST_AUTO);
    }

    private void focusFolder(String wanted, boolean force) {
        if(folderBox == null) return;
        folderBox.post(() -> {
            if(destroyed || folderBox == null) return;
            if(!force && getCurrentFocus() != null) return;
            View target = null;
            if(wanted != null && !wanted.isEmpty()) {
                for(int i = 0; i < folderBox.getChildCount(); i++) {
                    View v = folderBox.getChildAt(i);
                    if(wanted.equals(v.getTag())) { target = v; break; }
                }
            }
            if(target == null && folderBox.getChildCount() > 0) target = folderBox.getChildAt(0);
            if(target == null) return;
            final View focusTarget = target;
            if(!focusTarget.requestFocus()) {
                focusTarget.postDelayed(() -> {
                    if(destroyed) return;
                    View now = getCurrentFocus();
                    if(now == null || now.getParent() != folderBox) focusTarget.requestFocus();
                }, 120);
            }
        });
    }

    private void browse(String url, String name) { browse(url, name, false, null, FOLDER_LIST_REPLACE); }

    private void browse(String url, String name, boolean focusFolders, String focusUrl) { browse(url, name, focusFolders, focusUrl, FOLDER_LIST_REPLACE); }

    private void browse(String url, String name, boolean focusFolders, String focusUrl, int folderListMode) {
        final int token = ++browseGeneration;
        final LibraryClient client = nas;
        final boolean resumeHere = pendingResumeIndex >= 0;
        pendingFolderFocus = focusFolders ? (focusUrl == null ? "" : focusUrl) : null;
        browseUrl = url;
        viewMode = Mode.FOLDER;
        updateCrumb();
        dismissError();
        if(client == null) { status.setText("Connect to a network share first"); return; }
        stopButton.setVisibility(View.GONE);
        boolean quick = isServerRoot(url) || url.equals(client.rootUrl());
        if(quick) {
            status.setText("Loading folder…");
            network.execute(() -> {
                try {
                    NasClient.Library lib = client.quickLibrary(url, name);
                    ArrayList<TrackItem> items = buildTrackItems(client, lib.audio, url);
                    clock.post(() -> {
                        if(destroyed || token != browseGeneration) return;
                        cacheLibrary(url, lib);
                        renderLibrary(client, url, name, lib, items, resumeHere, folderListMode);
                    });
                } catch(OutOfMemoryError e) {
                    freeMemory();
                    clock.post(() -> {
                        if(destroyed || token != browseGeneration) return;
                        pendingResumeIndex = -1; pendingResumePos = -1;
                        status.setText("Not enough memory for this folder. Try a smaller folder or clear the cover cache in Settings.");
                    });
                } catch(Exception e) {
                    final String details = describe(e), message = browseMessage(e);
                    clock.post(() -> {
                        if(destroyed || token != browseGeneration) return;
                        pendingResumeIndex = -1; pendingResumePos = -1;
                        showError(message, details);
                    });
                }
            });
            return;
        }
        NasClient.Library cached = libraryCache.get(url);
        if(cached != null) {
            if(cached.audio.size() <= 3000) {
                renderLibrary(client, url, name, cached, buildTrackItems(client, cached.audio, url), resumeHere, folderListMode);
            } else {
                status.setText("Loading folder…");
                network.execute(() -> {
                    ArrayList<TrackItem> items = buildTrackItems(client, cached.audio, url);
                    clock.post(() -> { if(destroyed || token != browseGeneration) return; renderLibrary(client, url, name, cached, items, resumeHere, folderListMode); });
                });
            }
            return;
        }
        status.setText("Loading folder…");
        stopButton.setVisibility(View.VISIBLE);
        network.execute(() -> {
            final boolean[] partialShown = {false};
            try {
                final long[] lastProgress = {0};
                NasClient.Library lib = client.indexLibrary(url, name,
                    () -> token != browseGeneration,
                    (folders, songs) -> {
                        long now = System.currentTimeMillis();
                        if(now - lastProgress[0] < 750) return;
                        lastProgress[0] = now;
                        clock.post(() -> { if(!destroyed && token == browseGeneration) status.setText("Loading folder… " + folders + " folders • " + songs + " songs"); });
                    },
                    partialLib -> {
                        ArrayList<TrackItem> partialItems = buildTrackItems(client, partialLib.audio, url);
                        clock.post(() -> {
                            if(destroyed || token != browseGeneration) return;
                            partialShown[0] = true;
                            renderPartial(client, url, name, partialLib, partialItems, folderListMode);
                        });
                    });
                ArrayList<TrackItem> items = buildTrackItems(client, lib.audio, url);
                clock.post(() -> {
                    if(destroyed || token != browseGeneration) return;
                    stopButton.setVisibility(View.GONE);
                    cacheLibrary(url, lib);
                    renderLibrary(client, url, name, lib, items, resumeHere, folderListMode);
                });
            } catch(OutOfMemoryError e) {
                freeMemory();
                clock.post(() -> {
                    if(destroyed || token != browseGeneration) return;
                    stopButton.setVisibility(View.GONE);
                    pendingResumeIndex = -1; pendingResumePos = -1;
                    status.setText("Not enough memory for this folder. Try a smaller folder or clear the cover cache in Settings.");
                });
            } catch(Exception e) {
                final String details = describe(e), message = browseMessage(e);
                clock.post(() -> {
                    if(destroyed || token != browseGeneration) return;
                    stopButton.setVisibility(View.GONE);
                    pendingResumeIndex = -1; pendingResumePos = -1;
                    if(partialShown[0]) { status.setText("Folder partially loaded • " + message); return; }
                    if(client != null && !url.equals(client.rootUrl())) { status.setText("Folder unavailable • opening share root…"); browse(client.rootUrl(), shareLabel(client.rootUrl())); }
                    else showError(message, details);
                });
            }
        });
    }

    private ArrayList<TrackItem> buildTrackItems(LibraryClient client, List<NasClient.Entry> entries, String folderUrl) {
        ArrayList<TrackItem> items = new ArrayList<>(entries.size());
        for(NasClient.Entry e : entries) items.add(new TrackItem(client, e, folderUrl));
        artwork.applyCached(items);
        return items;
    }

    private static String plural(int n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    private void renderPartial(LibraryClient client, String url, String name, NasClient.Library lib, ArrayList<TrackItem> items, int folderListMode) {
        if(destroyed) return;
        dismissError();
        folderName = name;
        // If the opened folder has only subfolders, the recursive scan has no
        // immediate songs yet. Keep the previous playlist visible instead of
        // blanking the right panel until the scan finds the first tracks.
        boolean keepList = items.isEmpty() && !shown.isEmpty();
        if(!keepList) {
            folderSongs.clear(); folderSongs.addAll(items);
            shown.clear(); shown.addAll(items);
            lastCenteredKey = null;
        }
        boolean keepFolders = folderListMode == FOLDER_LIST_KEEP
            || (folderListMode == FOLDER_LIST_AUTO && lib.folders.isEmpty());
        if(!keepFolders) {
            browsedFolders.clear();
            for(NasClient.Entry e : lib.folders) browsedFolders.add(e);
        }
        renderFolders();
        int count = keepList ? shown.size() : items.size();
        listTitle.setText(keepList ? name + " • LOADING…" : name + " • SONGS (" + count + ")");
        folderInfo.setText(plural(lib.folders.size(), "folder", "folders") + " • " + plural(count, "song", "songs") + " • loading… • " + client.modeLabel());
        status.setText(folderStatusName() + " • loading… " + plural(lib.folders.size(), "folder", "folders") + " • " + plural(count, "song", "songs"));
        showEmptyState(keepList ? null : (shown.isEmpty() ? "No songs in this folder." : null));
        if(lib.cover != null) artwork.requestCover(client, lib.cover, () -> clock.post(() -> { if(!destroyed) updateUI(); }));
        else artwork.clearCover();
        if(!keepList) {
            trackAdapter.notifyDataSetChanged();
            centerCurrentTrack();
        }
        updateCrumb();
        updateFavoritesBadge();
        updateUI();
        persistBrowse();
    }

    private void renderLibrary(LibraryClient client, String url, String name, NasClient.Library lib, ArrayList<TrackItem> items, boolean resumeHere, int folderListMode) {
        if(destroyed) return;
        dismissError();
        boolean libraryRoot = client != null && url.equals(client.rootUrl());
        folderName = name;
        // When the newly opened folder has no songs, keep the previous playlist
        // visible so browsing up/down the tree does not blank the right panel
        // until a folder with songs is actually chosen.
        boolean keepList = items.isEmpty() && !shown.isEmpty();
        if(!keepList) {
            folderSongs.clear(); folderSongs.addAll(items);
            shown.clear(); shown.addAll(items);
            lastCenteredKey = null;
        }
        boolean keepFolders = folderListMode == FOLDER_LIST_KEEP
            || (folderListMode == FOLDER_LIST_AUTO && lib.folders.isEmpty());
        if(!keepFolders) {
            browsedFolders.clear();
            for(NasClient.Entry e : lib.folders) browsedFolders.add(e);
        }
        renderFolders();
        int count = keepList ? shown.size() : items.size();
        listTitle.setText(libraryRoot ? "LIBRARY" : name + " • SONGS (" + count + ")");
        folderInfo.setText(plural(lib.folders.size(), "folder", "folders") + " • " + plural(count, "song", "songs") + (lib.truncated ? " • partial" : "") + " • " + client.modeLabel());
        status.setText(libraryRoot ? (keepList ? "No songs here • showing previous playlist" : summary())
            : folderStatusName() + " • " + (keepList ? "no songs • showing previous playlist" : plural(shown.size(), "song", "songs") + (lib.truncated ? " • partial" : "")));
        showEmptyState(keepList ? null : (shown.isEmpty() ? (libraryRoot ? "No songs at the share root.\nChoose a folder on the left." : "No songs in this folder.") : null));
        if(lib.cover != null) artwork.requestCover(client, lib.cover, () -> clock.post(() -> { if(!destroyed) updateUI(); }));
        else artwork.clearCover();
        if(!keepList) {
            trackAdapter.notifyDataSetChanged();
            centerCurrentTrack();
        }
        updateCrumb();
        updateFavoritesBadge();
        updateUI();
        if(libraryRoot && !lib.folders.isEmpty()) startFolderCounts(client, browseGeneration, lib.folders);
        if(focusListAfterBrowse) {
            focusListAfterBrowse = false;
            if(!shown.isEmpty() && trackList != null) trackList.requestFocus();
            else focusFolder("", true);
        }
        if(!keepList && resumeHere && shown.size() > 0) {
            playback.clear(); playback.addAll(shown);
            playbackUrl = url; playbackName = name; playFrom = PlayFrom.FOLDER;
            int resumeIndex = LibraryPolicy.clampIndex(pendingResumeIndex, shown.size());
            pendingSeek = pendingResumePos; pendingResumeIndex = -1; pendingResumePos = -1;
            load(resumeIndex, true);
        } else { pendingResumeIndex = -1; pendingResumePos = -1; }
        persistBrowse();
    }

    private void cacheLibrary(String url, NasClient.Library lib) {
        if(lib == null) return;
        // A single 50k-track folder can be larger than the whole heap budget for
        // cached libraries. Keep it out of the cache instead of risking an OOM
        // when the next folder is opened; the rows still stay alive via shown.
        if(lib.audio.size() > LIBRARY_CACHE_MAX_TRACKS) {
            libraryCache.remove(url);
            return;
        }
        libraryCache.put(url, lib);
        int total = 0;
        for(NasClient.Library cached : libraryCache.values()) total += cached.audio.size();
        while(total > LIBRARY_CACHE_MAX_TRACKS && libraryCache.size() > 1) {
            Iterator<String> it = libraryCache.keySet().iterator();
            String oldest = it.next();
            NasClient.Library removed = libraryCache.remove(oldest);
            if(removed == null) break;
            total -= removed.audio.size();
        }
        while(libraryCache.size() > LIBRARY_CACHE_SIZE) {
            Iterator<String> it = libraryCache.keySet().iterator();
            it.next(); it.remove();
        }
    }

    /** Frees the big caches after an OOM without touching the current playback. */
    private void freeMemory() {
        libraryCache.clear();
        if(!indexing) globalIndex = null;
        if(artwork != null) {
            artwork.trimMemory();
            artwork.trimFolderIndex();
        }
    }

    private void startFolderCounts(LibraryClient client, int token, List<NasClient.Entry> folders) {
        if(folders.isEmpty()) return;
        final List<NasClient.Entry> list = new ArrayList<>(folders);
        stopButton.setVisibility(View.VISIBLE);
        network.execute(() -> {
            int done = 0;
            for(NasClient.Entry folder : list) {
                if(destroyed || token != browseGeneration) return;
                if(folderCounts.get(folder.url) == null) {
                    try {
                        int count = client.countAudio(folder.url,
                            () -> destroyed || token != browseGeneration,
                            (dirs, songs) -> clock.post(() -> {
                                if(!destroyed && token == browseGeneration) status.setText("Counting " + folder.name + "… " + songs + " songs");
                            }));
                        folderCounts.put(folder.url, count);
                        clock.post(() -> {
                            if(destroyed || token != browseGeneration) return;
                            TextView view = folderCountViews.get(folder.url);
                            if(view != null) view.setText(String.valueOf(count));
                        });
                    } catch(Exception ignored) { }
                }
                done++;
                final int progress = done;
                clock.post(() -> { if(!destroyed && token == browseGeneration) status.setText("Counting folders… " + progress + "/" + list.size()); });
            }
            clock.post(() -> {
                if(destroyed || token != browseGeneration) return;
                stopButton.setVisibility(View.GONE);
                saveFolderCounts();
                status.setText(summary());
            });
        });
    }

    private void loadFolderCounts() {
        try {
            String raw = prefs.getString("folder_counts", "");
            if(raw == null || raw.isEmpty()) return;
            JSONObject object = new JSONObject(raw);
            Iterator<String> keys = object.keys();
            while(keys.hasNext()) {
                String key = keys.next();
                folderCounts.put(key, object.optInt(key));
            }
        } catch(Exception ignored) { }
    }

    private void saveFolderCounts() {
        try {
            JSONObject object = new JSONObject();
            for(Map.Entry<String,Integer> e : folderCounts.entrySet()) object.put(e.getKey(), e.getValue());
            prefs.edit().putString("folder_counts", object.toString()).apply();
        } catch(Exception ignored) { }
    }

    private void stopScan() {
        browseGeneration++;
        searchGeneration++;
        indexing = false;
        if(stopButton != null) stopButton.setVisibility(View.GONE);
        status.setText("Scan stopped");
    }

    private String summary() {
        if(viewMode == Mode.FAVORITES) return plural(shown.size(), "favorite", "favorites");
        return plural(browsedFolders.size(), "folder", "folders") + " • " + plural(shown.size(), "song", "songs");
    }

    private String folderStatusName() { return folderName == null || folderName.isEmpty() ? "SOURCES" : folderName; }

    private void updateCrumb() {
        if(crumb == null) return;
        boolean atRoot = nas == null || browseUrl.isEmpty() || browseUrl.equals(nas.rootUrl()) || isServerRoot(browseUrl);
        if(upButton != null) { upButton.setEnabled(!atRoot); upButton.setAlpha(atRoot ? 0.35f : 1f); }
        if(viewMode == Mode.FAVORITES) { crumb.setText("Library / Favorites"); return; }
        if(browseUrl.isEmpty()) { crumb.setText("Not connected"); return; }
        String base = nas == null ? shareLabel(browseUrl) : shareLabel(nas.rootUrl());
        String rel = "";
        if(nas != null && browseUrl.startsWith(nas.rootUrl())) {
            rel = browseUrl.substring(nas.rootUrl().length());
            if(rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
            rel = rel.replace("/", " / ");
        }
        crumb.setText(rel.isEmpty() ? base : base + " / " + rel);
    }

    private String folderOf(String url) {
        if(url == null || url.isEmpty()) return "";
        return folderLabel(parentUrl(url));
    }

    private void updateFavoritesBadge() {
        if(favoritesButton == null || favoritesButton.getChildCount() < 2) return;
        ((TextView) favoritesButton.getChildAt(1)).setText(String.valueOf(favorites.size()));
    }

    private void playAllFolder(NasClient.Entry folder) {
        if(nas == null) return;
        final LibraryClient client = nas;
        final int token = ++browseGeneration;
        status.setText("Preparing " + folder.name + "…");
        stopButton.setVisibility(View.VISIBLE);
        network.execute(() -> {
            try {
                NasClient.Library lib = client.indexLibrary(folder.url, folder.name,
                    () -> destroyed || token != browseGeneration,
                    (folders, songs) -> clock.post(() -> {
                        if(!destroyed && token == browseGeneration) status.setText("Scanning " + folder.name + "… " + songs + " songs");
                    }), null);
                ArrayList<TrackItem> items = buildTrackItems(client, lib.audio, folder.url);
                clock.post(() -> {
                    if(destroyed || token != browseGeneration) return;
                    stopButton.setVisibility(View.GONE);
                    cacheLibrary(folder.url, lib);
                    renderLibrary(client, folder.url, folder.name, lib, items, false, FOLDER_LIST_REPLACE);
                    if(items.isEmpty()) { status.setText("No songs in " + folder.name); return; }
                    playback.clear(); playback.addAll(items);
                    playbackUrl = folder.url; playbackName = folder.name; playFrom = PlayFrom.FOLDER;
                    planNext(); load(0, true);
                    if(trackList != null) trackList.requestFocus();
                });
            } catch(OutOfMemoryError e) {
                freeMemory();
                clock.post(() -> {
                    if(destroyed || token != browseGeneration) return;
                    stopButton.setVisibility(View.GONE);
                    status.setText("Not enough memory for this folder. Try a smaller folder or clear the cover cache in Settings.");
                });
            } catch(Exception e) {
                final String message = browseMessage(e);
                clock.post(() -> {
                    if(destroyed || token != browseGeneration) return;
                    stopButton.setVisibility(View.GONE);
                    status.setText(message);
                });
            }
        });
    }

    private void playAllVisible() {
        if(shown.isEmpty()) { status.setText("No songs to play"); return; }
        playback.clear(); playback.addAll(shown);
        if(viewMode == Mode.FAVORITES) {
            playFrom = PlayFrom.FAVORITES; playbackUrl = ""; playbackName = "Favorites";
        } else {
            playFrom = PlayFrom.FOLDER; playbackUrl = browseUrl; playbackName = folderName;
        }
        planNext();
        load(0, true);
    }

    private void openFavorites() {
        if(nas == null) { status.setText("Connect to a network share first"); return; }
        if(ambientMode) hideAmbient();
        viewMode = Mode.FAVORITES;
        folderSongs.clear(); folderSongs.addAll(favorites.tracks(activeNetwork, activeLocal));
        shown.clear(); shown.addAll(folderSongs);
        listTitle.setText("FAVORITES (" + shown.size() + ")");
        folderInfo.setText(plural(shown.size(), "favorite", "favorites"));
        status.setText(plural(shown.size(), "favorite", "favorites"));
        updateCrumb();
        showEmptyState(shown.isEmpty() ? "No favorites yet.\nPress the heart on the playing track\nor long-press a song." : null);
        trackAdapter.notifyDataSetChanged();
        updateFavoritesBadge();
        if(trackList != null) trackList.requestFocus();
    }

    private void showPlayingFolder() {
        if(playFrom == PlayFrom.SEARCH) { openSearch(playbackQuery); return; }
        if(playFrom == PlayFrom.FAVORITES) { openFavorites(); return; }
        TrackItem now = current();
        if(now == null) { status.setText("Nothing is playing"); return; }
        if(nas == null) { status.setText("Connect to a network share first"); return; }
        String url = playbackUrl, name = playbackName;
        if(url == null || url.isEmpty()) { url = parentUrl(now.entry.url); name = folderLabel(url); }
        if(!url.startsWith(nas.rootUrl())) { status.setText("The playing folder is on another share"); return; }
        if(ambientMode) hideAmbient();
        focusListAfterBrowse = true;
        browse(url, name, false, null, FOLDER_LIST_KEEP);
    }

    private void goUp() {
        if(browseUrl.isEmpty()) return;
        if(nas != null && browseUrl.equals(nas.rootUrl())) { status.setText("Already at the share root"); return; }
        String from = browseUrl;
        String target = parentUrl(browseUrl);
        browse(target, folderLabel(target), true, from);
    }

    private void openSearch(String query) {
        if(nas == null) { status.setText("Connect to a network share first"); return; }
        if(ambientMode) hideAmbient();
        searchWhole = viewMode == Mode.FAVORITES || browseUrl.isEmpty() || browseUrl.equals(nas.rootUrl());
        updateSearchScope();
        searchOverlay.setVisibility(View.VISIBLE);
        overlay = Overlay.SEARCH;
        searchField.setText(query == null ? "" : query);
        searchField.requestFocus();
        searchField.setSelection(searchField.getText().length());
        searchResults.clear();
        searchAdapter.notifyDataSetChanged();
        searchStatus.setText("Type at least 2 characters");
        if(query != null && query.trim().length() >= 2) runSearch(query);
    }

    private void closeSearch() {
        searchGeneration++;
        indexing = false;
        if(searchOverlay != null) searchOverlay.setVisibility(View.GONE);
        if(overlay == Overlay.SEARCH) overlay = Overlay.NONE;
        if(play != null) play.requestFocus();
    }

    private void toggleSearchScope() {
        searchWhole = !searchWhole;
        updateSearchScope();
        runSearch(searchField.getText().toString());
    }

    private void updateSearchScope() {
        if(searchScope == null) return;
        searchScope.setText(searchWhole ? "Whole share" : (folderName.isEmpty() ? "This folder" : "Folder: " + folderName));
    }

    private void runSearch(String query) {
        if(searchStatus == null) return;
        lastQuery = query == null ? "" : query;
        String needle = lastQuery.trim().toLowerCase(Locale.ROOT);
        searchResults.clear();
        if(needle.length() < 2) {
            searchStatus.setText("Type at least 2 characters");
            searchAdapter.notifyDataSetChanged();
            return;
        }
        if(searchWhole) {
            List<NasClient.Entry> index = globalIndex;
            if(index == null) {
                startIndexing();
                searchStatus.setText("Indexing the share…");
                searchAdapter.notifyDataSetChanged();
                return;
            }
            for(NasClient.Entry e : index) {
                if(searchResults.size() >= 800) break;
                String hay = (e.name + " " + e.url).toLowerCase(Locale.ROOT);
                if(hay.contains(needle)) searchResults.add(new TrackItem(nas, e, null));
            }
            artwork.applyCached(searchResults);
        } else {
            for(TrackItem t : folderSongs) {
                String hay = (t.title + " " + t.artist + " " + (t.album == null ? "" : t.album) + " " + t.entry.name).toLowerCase(Locale.ROOT);
                if(hay.contains(needle)) searchResults.add(t);
            }
        }
        searchStatus.setText(plural(searchResults.size(), "result", "results") + (searchWhole ? " in the share" : " in " + (folderName.isEmpty() ? "this folder" : folderName)));
        searchAdapter.notifyDataSetChanged();
        if(!searchResults.isEmpty()) searchList.setSelection(0);
    }

    private void startIndexing() {
        if(indexing || nas == null) return;
        indexing = true;
        final int token = ++searchGeneration;
        final LibraryClient client = nas;
        final String root = client.rootUrl();
        network.execute(() -> {
            try {
                final long[] last = {0};
                NasClient.Library lib = client.indexLibrary(root, shareLabel(root),
                    () -> destroyed || token != searchGeneration,
                    (folders, songs) -> {
                        long now = System.currentTimeMillis();
                        if(now - last[0] < 700) return;
                        last[0] = now;
                        clock.post(() -> { if(!destroyed && token == searchGeneration) searchStatus.setText("Indexing the share… " + songs + " songs"); });
                    }, null);
                clock.post(() -> {
                    if(destroyed || token != searchGeneration) return;
                    globalIndex = lib.audio;
                    indexing = false;
                    if(overlay == Overlay.SEARCH) runSearch(lastQuery);
                });
            } catch(Exception e) {
                clock.post(() -> {
                    if(destroyed || token != searchGeneration) return;
                    indexing = false;
                    searchStatus.setText("Index interrupted • search inside a folder");
                });
            }
        });
    }

    private void playSearchResult(int position) {
        if(position < 0 || position >= searchResults.size()) return;
        playback.clear(); playback.addAll(searchResults);
        playFrom = PlayFrom.SEARCH;
        playbackName = "Search";
        playbackQuery = lastQuery;
        playbackUrl = "";
        planNext();
        load(position, true);
        closeSearch();
    }

    private void bindSeek(SeekBar bar) {
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.setOnFocusChangeListener((v, has) -> bar.setBackground(has ? shape(0x00000000, true) : null));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) { dragging = true; cancelFade(); }
            public void onProgressChanged(SeekBar s, int value, boolean user) {
                if(!user) return;
                if(bar == seek) time.setText(format(value) + " / " + format(seek.getMax()));
                else if(ambientTime != null) ambientTime.setText(format(value) + " / " + format(ambientSeek.getMax()));
                if(!dragging && ready && active != null) { cancelFade(); active.seekTo(value); }
            }
            public void onStopTrackingTouch(SeekBar s) { if(ready && active != null) active.seekTo(s.getProgress()); dragging = false; }
        });
    }

    private String format(int ms) { int s = ms / 1000; return s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60); }
    private TrackItem current() { return playback.isEmpty() ? null : playback.get(Math.max(0, Math.min(fading && fadeTargetIndex >= 0 ? fadeTargetIndex : index, playback.size() - 1))); }
    private int nextIndex() { return playback.isEmpty() ? 0 : QueuePolicy.next(Math.max(0, Math.min(index, playback.size() - 1)), playback.size(), shuffle, rng); }
    private void planNext() { plannedNext = playback.isEmpty() ? -1 : nextIndex(); }

    private void updateUI() {
        if(song == null) return;
        TrackItem item = current();
        if(item == null) {
            song.setText("Fader TV");
            artist.setText(nas == null ? "Connect to a network share to start listening"
                : (viewMode == Mode.FAVORITES ? "No favorites yet" : "Choose a song to start listening"));
            art.item = null; art.invalidate();
            ambientArt.item = null; ambientArt.invalidate();
            ambientTitle.setText("Fader TV");
            ambientArtist.setText("");
            ambientTime.setText("0:00 / 0:00");
            ambientNextUp.setText("NEXT UP · —");
            if(!dragging) { seek.setMax(0); seek.setProgress(0); ambientSeek.setMax(0); ambientSeek.setProgress(0); }
            time.setText("0:00 / 0:00");
            heart.setText("♡"); heart.setTextColor(fg());
            if(ambientHeart != null) { ambientHeart.setText("♡"); ambientHeart.setTextColor(0xffedf2fa); }
            spectrumMain.setSeed(1); spectrumAmbient.setSeed(1);
        } else {
            song.setText(item.title);
            artist.setText(item.artist + (item.album != null && !item.album.isEmpty() ? " · " + item.album : ""));
            art.item = item; art.invalidate();
            ambientArt.item = item; ambientArt.invalidate();
            ambientTitle.setText(item.title);
            ambientArtist.setText(item.artist + (item.album != null && !item.album.isEmpty() ? " · " + item.album : ""));
            boolean favourite = favorites.has(item);
            heart.setText(favourite ? "♥" : "♡");
            heart.setTextColor(favourite ? 0xffff7b9c : fg());
            if(ambientHeart != null) { ambientHeart.setText(favourite ? "♥" : "♡"); ambientHeart.setTextColor(favourite ? 0xffff7b9c : 0xffedf2fa); }
            int seed = item.key().hashCode();
            spectrumMain.setSeed(seed); spectrumAmbient.setSeed(seed);
            Bitmap cover = artwork.get(item);
            if(cover != null) { currentTint = ArtworkStore.dominant(cover); applyAmbientTint(cover); }
            else applyAmbientTint(null);
            artwork.request(item, () -> clock.post(() -> { if(!destroyed) { trackAdapter.notifyDataSetChanged(); updateUI(); } }));
        }
        play.setText(playing ? "Pause" : "Play");
        ambientPlay.setText(playing ? "Pause" : "Play");
        updateNextUp();
        if(shuffleChip != null) shuffleChip.setText("Shuffle: " + (shuffle ? "On" : "Off"));
        if(crossfadeChip != null) crossfadeChip.setText("Crossfade: " + fadeLabel());
        updateNowCard();
        if(trackAdapter != null) trackAdapter.notifyDataSetChanged();
        centerCurrentTrack();
        if(playing && foreground) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private String fadeLabel() { return fadeSeconds == 0 ? "Off" : fadeSeconds + " s"; }

    private void updateNextUp() {
        TrackItem now = current();
        TrackItem nextItem = playback.size() > 1 && plannedNext >= 0 && plannedNext < playback.size() ? playback.get(plannedNext) : null;
        if(nextItem != null && now != null && nextItem.key().equals(now.key())) nextItem = null;
        String label = nextItem == null ? "NEXT UP · —"
            : "NEXT UP · " + nextItem.title + " — " + nextItem.artist + (nextItem.duration > 0 ? " · " + format(nextItem.duration) : "");
        if(nextUp != null) nextUp.setText(label);
        if(ambientNextUp != null) ambientNextUp.setText(label);
    }

    private void updateNowCard() {
        if(nowCard == null) return;
        TrackItem item = current();
        if(item == null) {
            nowTitle.setText("Nothing playing");
            nowArtist.setText("Open a folder to start");
            nowSource.setText("");
            nowArt.item = null; nowArt.invalidate();
            return;
        }
        nowTitle.setText(item.title);
        nowArtist.setText(item.artist);
        String from = playFrom == PlayFrom.FAVORITES ? "Favorites"
            : playFrom == PlayFrom.SEARCH ? "Search results"
            : (playbackName == null || playbackName.isEmpty() ? "Network share" : playbackName);
        nowSource.setText("PLAYING FROM · " + from);
        nowArt.item = item; nowArt.invalidate();
    }

    private void applyAmbientTint(Bitmap cover) {
        if(ambient == null || ambientBackground == null) return;
        if(cover != null) ambientBackground.setCover(cover);
        else ambientBackground.clear();
    }

    private void toggleFavorite(TrackItem item) {
        if(item == null) { status.setText("Nothing is playing"); return; }
        boolean added = favorites.toggle(item);
        status.setText(added ? "Added to favorites" : "Removed from favorites");
        updateFavoritesBadge();
        if(viewMode == Mode.FAVORITES) openFavorites();
        else updateUI();
    }

    /** Favourite toggle for the ambient screen, which must not reopen the list. */
    private void toggleFavoriteFromAmbient() {
        TrackItem item = current();
        if(item == null) { status.setText("Nothing is playing"); return; }
        boolean added = favorites.toggle(item);
        status.setText(added ? "Added to favorites" : "Removed from favorites");
        updateFavoritesBadge();
        updateUI();
    }

    /** Favourites the highlighted row in the list without touching playback or the queue. */
    private void toggleFavoriteSelected() {
        int position = trackList != null ? trackList.getSelectedItemPosition() : -1;
        if(position < 0) position = selectedPosition;
        if(position < 0 || position >= shown.size()) { status.setText("Highlight a track in the list first"); return; }
        toggleFavoriteForItem(shown.get(position));
        if(viewMode == Mode.FAVORITES) openFavorites();
        else trackAdapter.notifyDataSetChanged();
    }

    /** Toggles a specific track's favourite state; returns true when it was added. */
    private boolean toggleFavoriteForItem(TrackItem item) {
        if(item == null) return false;
        boolean added = favorites.toggle(item);
        status.setText(added ? "♥ Added to favorites" : "♡ Removed from favorites");
        updateFavoritesBadge();
        return added;
    }

    private int indexOfCurrent() {
        TrackItem now = current();
        if(now == null) return -1;
        String key = now.key();
        for(int i = 0; i < shown.size(); i++) if(key.equals(shown.get(i).key())) return i;
        return -1;
    }

    private void centerCurrentTrack() {
        TrackItem now = current();
        if(now == null || trackList == null || shown.isEmpty()) { if(now == null) lastCenteredKey = null; return; }
        String key = now.key();
        if(key.equals(lastCenteredKey)) return;
        int position = -1;
        for(int i = 0; i < shown.size(); i++) if(key.equals(shown.get(i).key())) { position = i; break; }
        lastCenteredKey = key;
        if(position < 0) return;
        final int at = position;
        trackList.post(() -> {
            if(destroyed || at >= shown.size() || !key.equals(shown.get(at).key())) return;
            int itemHeight = dp(60);
            int offset = shown.size() * itemHeight <= trackList.getHeight() ? 0 : Math.max(0, (trackList.getHeight() - itemHeight) / 2);
            trackList.setSelectionFromTop(at, offset);
        });
    }

    private void playTrackAt(int position) {
        if(position < 0 || position >= shown.size()) return;
        playback.clear(); playback.addAll(shown);
        if(viewMode == Mode.FAVORITES) {
            playFrom = PlayFrom.FAVORITES; playbackUrl = ""; playbackName = "Favorites";
        } else {
            playFrom = PlayFrom.FOLDER; playbackUrl = browseUrl; playbackName = folderName;
        }
        planNext();
        load(position, true);
    }

    private void showTrackMenu(int position) {
        if(position < 0 || position >= shown.size()) return;
        final TrackItem item = shown.get(position);
        final boolean favourite = favorites.has(item);
        String[] actions = {"Play now", favourite ? "Remove from favorites" : "Add to favorites", "Details"};
        new AlertDialog.Builder(this).setTitle(item.title).setItems(actions, (dialog, which) -> {
            if(which == 0) playTrackAt(position);
            else if(which == 1) {
                favorites.toggle(item);
                updateFavoritesBadge();
                if(viewMode == Mode.FAVORITES) openFavorites();
                else { trackAdapter.notifyDataSetChanged(); updateUI(); }
            } else showDetails(item);
        }).show();
    }

    private void showDetails(TrackItem item) {
        String name = item.entry.name;
        int dot = name.lastIndexOf('.');
        String format = dot > 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "—";
        String details = "Title: " + item.title + "\nArtist: " + item.artist + "\nAlbum: " + (item.album == null || item.album.isEmpty() ? "—" : item.album)
            + "\nDuration: " + (item.duration > 0 ? format(item.duration) : "—") + "\nFormat: " + format
            + "\nSize: " + formatBytes(item.entry.size) + "\nFolder: " + folderOf(item.entry.url)
            + "\nPath: " + item.entry.url;
        TextView body = new TextView(this);
        body.setText(details); body.setTextSize(14); body.setTextColor(fg());
        body.setTextIsSelectable(true); body.setPadding(dp(20), dp(10), dp(20), dp(10));
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Details").setView(scroll).setPositiveButton("OK", null).show();
    }

    private MediaPlayer create(int track) throws IOException {
        if(playback.isEmpty()) throw new IOException("No track selected");
        MediaPlayer player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        TrackItem item = playback.get(Math.max(0, Math.min(track, playback.size() - 1)));
        try { MediaDataSource source = item.nas.source(item.entry); sources.put(player, source); player.setDataSource(source); }
        catch(IOException|RuntimeException e) { release(player); throw e; }
        player.setOnErrorListener((mp, what, extra) -> {
            if(mp == active) {
                ready = false;
                playing = false;
                releaseVisualizer();
                artist.setText("Audio error — select a track to retry");
                status.setText("Playback error • " + what + "/" + extra);
                clock.post(() -> {
                    if(destroyed || mp != active) return;
                    active = null;
                    release(mp);
                    updateUI();
                });
            }
            return true;
        });
        return player;
    }

    private void release(MediaPlayer player) {
        if(player == null) return;
        // Some TV MediaPlayer implementations crash if release() is called while
        // prepareAsync() is still running. reset() first brings it to idle safely.
        try { player.reset(); } catch(Throwable ignored) { }
        try { player.release(); } catch(Throwable ignored) { }
        MediaDataSource source = sources.remove(player);
        if(source != null) network.execute(() -> { try { source.close(); } catch(IOException ignored) { } });
    }

    private void load(int selected, boolean auto) {
        if(playback.isEmpty()) { updateUI(); return; }
        selected = LibraryPolicy.clampIndex(selected, playback.size());
        generation++;
        int token = generation;
        MediaPlayer oldActive = active, oldStandby = standby;
        active = null; standby = null;
        ready = false; standbyReady = false; fading = false; playing = false; standbyIndex = -1; fadeTargetIndex = -1;
        // The Visualizer is attached to the player's audio session: detach it
        // before the player is released, otherwise some TV audio HALs crash.
        releaseVisualizer();
        release(oldActive);
        if(oldStandby != oldActive) release(oldStandby);
        index = selected;
        planNext();
        try {
            active = create(index);
            MediaPlayer pending = active;
            active.setOnPreparedListener(m -> {
                if(destroyed || token != generation || m != active) return;
                try {
                    ready = true;
                    m.setOnCompletionListener(done -> {
                        if(done == active && !fading) load(plannedNext >= 0 ? plannedNext : nextIndex(), playing);
                    });
                    preload();
                    if(pendingSeek > 0) { try { m.seekTo((int) pendingSeek); } catch(Exception ignored) { } pendingSeek = 0; }
                    if(auto && foreground) startAudio();
                    updateUI();
                    if(getCurrentFocus() == null && play != null) play.requestFocus();
                } catch(IllegalStateException e) {
                    ready = false; playing = false;
                    artist.setText("Cannot start audio — select the track again");
                }
            });
            pending.prepareAsync();
        } catch(IOException|RuntimeException e) {
            ready = false; playing = false;
            artist.setText("Cannot open audio — check the share connection");
        }
        updateUI();
    }

    private void preload() {
        if(fading) return;
        MediaPlayer old = standby;
        standby = null; standbyReady = false;
        release(old);
        if(playback.isEmpty()) return;
        standbyIndex = plannedNext >= 0 ? plannedNext : nextIndex();
        final int token = generation;
        try {
            standby = create(standbyIndex);
            standby.setOnPreparedListener(m -> {
                if(destroyed || token != generation || m != standby) return;
                try { standbyReady = true; m.setVolume(0, 0); }
                catch(IllegalStateException ignored) { standbyReady = false; }
            });
            standby.prepareAsync();
        } catch(IOException|RuntimeException e) { standbyReady = false; }
    }

    private void startAudio() {
        if(!ready || !foreground || active == null) return;
        if(audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
        try {
            active.start();
            if(fading && standbyReady && standby != null) standby.start();
        } catch(IllegalStateException e) {
            ready = false; playing = false;
            releaseVisualizer();
            status.setText("Audio stopped • select a track to retry");
            updateUI();
            return;
        }
        playing = true;
        attachVisualizer();
        updateUI();
    }

    private void pauseAudio() {
        try { if(ready && active != null && active.isPlaying()) active.pause(); }
        catch(IllegalStateException ignored) { ready = false; }
        try { if(standbyReady && standby != null && standby.isPlaying()) standby.pause(); }
        catch(IllegalStateException ignored) { standbyReady = false; }
        if(fading) cancelFade();
        playing = false;
        releaseVisualizer();
        updateUI();
    }

    private void cancelFade() {
        if(!fading) return;
        fading = false;
        fadeTargetIndex = -1;
        if(active != null) active.setVolume(1, 1);
        preload();
    }

    /** Starts a crossfade into the preloaded standby player. */
    private void startFade() {
        if(fading || standby == null || !standbyReady || active == null || !ready) return;
        fading = true;
        fadeTargetIndex = standbyIndex;
        fadeStart = SystemClock.uptimeMillis();
        fadeDuration = fadeSeconds * 1000L;
        try { standby.start(); }
        catch(IllegalStateException e) { fading = false; standbyReady = false; fadeTargetIndex = -1; return; }
        // Reflect the incoming track immediately (title/cover) while the audio crossfades.
        updateUI();
    }

    /** Switches to {@code target}, crossfading when possible and falling back to a hard cut. */
    private void crossfadeTo(int target) {
        if(playback.isEmpty()) { updateUI(); return; }
        if(fading) return; // already crossfading; ignore repeated presses
        target = LibraryPolicy.clampIndex(target, playback.size());
        if(target == index || fadeSeconds <= 0 || !playing || !ready || active == null) { load(target, true); return; }

        // The next track is already preloaded as standby: fade straight into it.
        if(standbyReady && standby != null && standbyIndex == target) {
            startFade();
            return;
        }

        // Prepare the target as a fresh standby, then fade once it is ready.
        final int t = target;
        final int token = generation;
        MediaPlayer oldStandby = standby;
        standby = null; standbyReady = false;
        release(oldStandby);
        standbyIndex = t;
        try {
            standby = create(t);
            standby.setOnPreparedListener(m -> {
                if(destroyed || token != generation || m != standby) return;
                try {
                    standbyReady = true;
                    m.setVolume(0, 0);
                    if(playing && ready && active != null) startFade();
                    else clock.post(() -> { if(!destroyed && token == generation) load(t, false); });
                } catch(IllegalStateException e) {
                    standbyReady = false;
                    clock.post(() -> { if(!destroyed && token == generation) load(t, false); });
                }
            });
            standby.prepareAsync();
        } catch(IOException|RuntimeException e) {
            load(t, true);
        }
    }

    private void nextWithFade() {
        if(playback.isEmpty()) return;
        int target = (plannedNext >= 0 && plannedNext != index) ? plannedNext : nextIndex();
        crossfadeTo(target);
    }

    private void prevWithFade() {
        if(playback.isEmpty()) return;
        crossfadeTo((index + playback.size() - 1) % playback.size());
    }

    private int currentTint = 0xff2c4764;
    private Button themeRow, ambientRow, sleepRow, spectrumRow, audioRow, cacheRow;

    private final Runnable tick = new Runnable() { public void run() {
        if(!foreground) return;
        if(ready && active != null) {
            try {
                int position = active.getCurrentPosition(), duration = active.getDuration();
                if(!dragging) {
                    seek.setMax(duration); seek.setProgress(position);
                    time.setText(format(position) + " / " + format(duration));
                    ambientSeek.setMax(duration); ambientSeek.setProgress(position);
                }
                ambientTime.setText(format(position) + " / " + format(duration));
                if(playing && !dragging && fadeSeconds > 0 && standbyReady && standby != null && !fading && duration - position <= fadeSeconds * 1000) {
                    startFade();
                }
                if(fading && playing && standby != null) {
                    float f = QueuePolicy.fade(SystemClock.uptimeMillis() - fadeStart, fadeDuration);
                    active.setVolume((float) Math.cos(f * Math.PI / 2), (float) Math.cos(f * Math.PI / 2));
                    standby.setVolume((float) Math.sin(f * Math.PI / 2), (float) Math.sin(f * Math.PI / 2));
                    if(f >= 1f) {
                        MediaPlayer old = active;
                        active = standby;
                        index = standbyIndex;
                        standby = null; standbyReady = false; fading = false; fadeTargetIndex = -1;
                        active.setVolume(1, 1);
                        active.setOnCompletionListener(m -> { if(m == active && !fading) load(plannedNext >= 0 ? plannedNext : nextIndex(), playing); });
                        release(old);
                        planNext();
                        preload();
                        attachVisualizer();
                        updateUI();
                    }
                }
            } catch(IllegalStateException e) {
                ready = false; playing = false;
                releaseVisualizer();
                status.setText("Audio stopped • select a track to retry");
                artist.setText("Audio stopped — select a track to retry");
            }
        }
        if(spectrumMain != null) { spectrumMain.setPlaying(playing && foreground); spectrumAmbient.setPlaying(playing && foreground); }
        if(sleepUntil > 0 && SystemClock.uptimeMillis() >= sleepUntil) {
            sleepUntil = 0;
            pauseAudio();
            status.setText("Sleep timer • playback paused");
            Toast.makeText(MainActivity.this, "Sleep timer • playback paused", Toast.LENGTH_SHORT).show();
        }
        int timeout = prefs.getInt("ambient", 45);
        boolean showAmbient = overlay == Overlay.NONE && (ambientMode || (playing && timeout > 0 && SystemClock.uptimeMillis() - lastInput > timeout * 1000L));
        if(showAmbient && !ambientMode) ambientMode = true;
        if(ambient != null) ambient.setVisibility(showAmbient ? View.VISIBLE : View.GONE);
        if(showAmbient) focusAmbient();
        clock.postDelayed(this, playing ? 50 : 250);
    } };

    private void hideAmbient() {
        ambientMode = false;
        lastInput = SystemClock.uptimeMillis();
        if(ambient != null) ambient.setVisibility(View.GONE);
        if(play != null) play.requestFocus();
    }

    /** Keeps focus inside the ambient screen; restores it when focus is lost or strayed. */
    private void focusAmbient() {
        if(ambientPlay == null || ambient == null) return;
        View f = getCurrentFocus();
        if(f != null) {
            for(ViewParent p = f.getParent(); p != null; p = p.getParent()) {
                if(p == ambient) return;
            }
        }
        ambientPlay.requestFocus();
    }

    private void openSettings() {
        if(ambientMode) hideAmbient();
        buildSettingsContent();
        settingsOverlay.setVisibility(View.VISIBLE);
        overlay = Overlay.SETTINGS;
        if(themeRow != null) themeRow.requestFocus();
    }

    private void closeOverlay() {
        if(overlay == Overlay.SETTINGS) {
            if(settingsOverlay != null) settingsOverlay.setVisibility(View.GONE);
            overlay = Overlay.NONE;
            if(play != null) play.requestFocus();
        } else if(overlay == Overlay.SEARCH) closeSearch();
    }

    private void buildSettingsContent() {
        if(settingsContent == null) return;
        settingsContent.removeAllViews();
        TextView title = text("Settings", 22);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(4), 0, 0, dp(8));
        settingsContent.addView(title);
        themeRow = settingsRow("Theme: " + themeLabel(), () -> cycleTheme());
        settingsContent.addView(themeRow, lp(-1, 44, 3));
        ambientRow = settingsRow("Ambient screen: " + ambientLabel(), () -> cycleAmbient());
        settingsContent.addView(ambientRow, lp(-1, 44, 3));
        sleepRow = settingsRow("Sleep timer: " + sleepLabel(), () -> cycleSleep());
        settingsContent.addView(sleepRow, lp(-1, 44, 3));
        spectrumRow = settingsRow("Spectrum: " + (spectrumOn ? "On" : "Off"), () -> toggleSpectrum());
        settingsContent.addView(spectrumRow, lp(-1, 44, 3));
        audioRow = settingsRow("Audio sync: " + (audioSync ? "On — real levels" : "Off — rhythmic effect"), () -> toggleAudioSync());
        settingsContent.addView(audioRow, lp(-1, 44, 3));
        settingsContent.addView(settingsInfo("Audio sync uses Android's output audio tap. It asks for the microphone permission, but nothing is recorded or sent anywhere. Without it the spectrum uses the rhythmic effect."));
        cacheRow = settingsRow("Cover cache: " + formatBytes(artwork.diskUsage()) + "   •   Clear", () -> clearCaches());
        settingsContent.addView(cacheRow, lp(-1, 44, 3));
        settingsContent.addView(settingsInfo(memoryLabel()));
        if(CrashLog.has(this)) {
            settingsContent.addView(settingsRow("Last crash: view", () -> showCrashLog()), lp(-1, 44, 3));
        }
        settingsContent.addView(section("SHARE"), new LinearLayout.LayoutParams(-1, dp(26)));
        String address = prefs.getString("nas", "");
        settingsContent.addView(settingsInfo(address.isEmpty() ? "No saved share." : "Saved share: " + address));
        if(!address.isEmpty()) {
            settingsContent.addView(settingsRow("Change login", () -> { closeOverlay(); connectDialog(address); }), lp(-1, 44, 3));
            settingsContent.addView(settingsRow("Forget saved password", () -> { clearCredentials(); status.setText("Saved password removed"); refreshSettingsLabels(); }), lp(-1, 44, 3));
            settingsContent.addView(settingsRow("Remove share", () -> disconnectShare()), lp(-1, 44, 3));
        }
        settingsClose = settingsRow("Close", () -> closeOverlay());
        settingsContent.addView(settingsClose, lp(-1, 44, 6));
    }

    private void refreshSettingsLabels() {
        if(themeRow != null) themeRow.setText("Theme: " + themeLabel());
        if(ambientRow != null) ambientRow.setText("Ambient screen: " + ambientLabel());
        if(sleepRow != null) sleepRow.setText("Sleep timer: " + sleepLabel());
        if(spectrumRow != null) spectrumRow.setText("Spectrum: " + (spectrumOn ? "On" : "Off"));
        if(audioRow != null) audioRow.setText("Audio sync: " + (audioSync ? "On — real levels" : "Off — rhythmic effect"));
        if(cacheRow != null) cacheRow.setText("Cover cache: " + formatBytes(artwork.diskUsage()) + "   •   Clear");
    }

    private String themeLabel() {
        String theme = prefs.getString("theme", dark ? "dark" : "light");
        return "dark".equals(theme) ? "Dark" : "light".equals(theme) ? "Light" : "Auto";
    }

    private String ambientLabel() {
        int seconds = prefs.getInt("ambient", 45);
        return seconds <= 0 ? "Never" : seconds + " s";
    }

    private String sleepLabel() {
        if(sleepUntil <= 0) return "Off";
        long left = Math.max(1, (sleepUntil - SystemClock.uptimeMillis() + 59999) / 60000);
        return left + " min left";
    }

    private void cycleTheme() {
        String theme = prefs.getString("theme", dark ? "dark" : "light");
        String next = "dark".equals(theme) ? "light" : "light".equals(theme) ? "auto" : "dark";
        prefs.edit().putString("theme", next).apply();
        dark = resolveDark();
        overlay = Overlay.NONE;
        buildUI();
        openSettings();
    }

    private void cycleAmbient() {
        int[] steps = {30, 45, 60, 90, 0};
        int current = prefs.getInt("ambient", 45);
        int at = 0;
        for(int i = 0; i < steps.length; i++) if(steps[i] == current) { at = i; break; }
        prefs.edit().putInt("ambient", steps[(at + 1) % steps.length]).apply();
        refreshSettingsLabels();
    }

    private void cycleSleep() {
        int[] steps = {0, 15, 30, 60, 90};
        int minutes = sleepUntil <= 0 ? 0 : (int) Math.max(1, (sleepUntil - SystemClock.uptimeMillis() + 59999) / 60000);
        int at = 0;
        for(int i = 0; i < steps.length; i++) if(steps[i] == minutes) { at = i; break; }
        int next = steps[(at + 1) % steps.length];
        sleepUntil = next <= 0 ? 0 : SystemClock.uptimeMillis() + next * 60000L;
        refreshSettingsLabels();
    }

    private void toggleSpectrum() {
        spectrumOn = !spectrumOn;
        prefs.edit().putBoolean("spectrum", spectrumOn).apply();
        applySpectrumVisibility();
        refreshSettingsLabels();
    }

    private void applySpectrumVisibility() {
        int visibility = spectrumOn ? View.VISIBLE : View.GONE;
        if(spectrumMain != null) spectrumMain.setVisibility(visibility);
        if(spectrumAmbient != null) spectrumAmbient.setVisibility(visibility);
    }

    private void toggleAudioSync() {
        if(audioSync) {
            audioSync = false;
            prefs.edit().putBoolean("audioSync", false).apply();
            releaseVisualizer();
            refreshSettingsLabels();
            status.setText("Audio sync off • rhythmic effect");
            return;
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioSync = true;
            prefs.edit().putBoolean("audioSync", true).apply();
            attachVisualizer();
            refreshSettingsLabels();
            status.setText("Audio sync on • real levels");
            return;
        }
        audioSyncAsked = true;
        prefs.edit().putBoolean("audioSyncAsked", true).apply();
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQ_STORAGE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if(granted) switchToLocal();
            else status.setText("Media access denied");
            return;
        }
        if(requestCode != REQ_AUDIO) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        audioSyncAsked = true;
        prefs.edit().putBoolean("audioSyncAsked", true).apply();
        audioSync = granted;
        prefs.edit().putBoolean("audioSync", granted).apply();
        if(granted) {
            attachVisualizer();
            Toast.makeText(this, "Audio sync enabled", Toast.LENGTH_SHORT).show();
        } else {
            releaseVisualizer();
            Toast.makeText(this, "Permission denied — using the rhythmic effect", Toast.LENGTH_LONG).show();
        }
        refreshSettingsLabels();
    }

    private void clearCaches() {
        status.setText("Clearing cover cache…");
        network.execute(() -> {
            artwork.clearCaches();
            clock.post(() -> { status.setText("Cover cache cleared"); refreshSettingsLabels(); });
        });
    }

    private String memoryLabel() {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long max = runtime.maxMemory() / (1024L * 1024L);
        return "Memory: " + used + " MB used of " + max + " MB. largeHeap is enabled for big libraries.";
    }

    private void showCrashLog() {
        String text = CrashLog.read(this);
        if(text.isEmpty()) text = "No crash was recorded.";
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(12);
        body.setTextColor(fg());
        body.setTextIsSelectable(true);
        body.setPadding(dp(20), dp(10), dp(20), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
            .setTitle("Last crash")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear", (dialog, which) -> { CrashLog.clear(this); buildSettingsContent(); })
            .show();
    }

    private void disconnectShare() {
        prefs.edit().remove("nas").remove("res_url").remove("res_name").remove("res_index").remove("res_pos").remove("res_shuffle").remove("last_source").remove("browse_url").remove("browse_name").remove("browse_folders").apply();
        clearCredentials();
        nas = null;
        activeNetwork = null;
        browseUrl = ""; folderName = "";
        viewMode = Mode.FOLDER;
        shown.clear(); folderSongs.clear(); browsedFolders.clear();
        libraryCache.clear(); globalIndex = null;
        renderSources(); renderFolders(); updateCrumb(); updateUI();
        showEmptyState("Connect to a network share to start.");
        buildSettingsContent();
        status.setText("Share removed");
    }

    /** Switches the active source to local/USB (MediaStore), requesting read-media permission first. */
    private void openUsb() {
        String perm = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if(checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            suppressPause = true;
            requestPermissions(new String[]{perm}, REQ_STORAGE);
            return;
        }
        switchToLocal();
    }

    private void switchToLocal() {
        dismissError();
        libraryCache.clear();
        globalIndex = null;
        activeLocal = new LocalClient(this);
        nas = activeLocal;
        renderSources();
        browse(nas.rootUrl(), "USB / Local", true, "");
    }

    /**
     * Maps the real FFT onto log-spaced bars. Android returns DC at 0, Nyquist
     * at 1 and interleaved Rf/If pairs from index 2, so only bins 1..n/2-1 are
     * usable. A slow auto-gain keeps quiet tracks visible without turning
     * silence into noise; the bars still rise and fall with the actual beats.
     */
    private boolean readLive(float[] out) {
        byte[] fft = lastFft;
        if(fft == null || SystemClock.uptimeMillis() - lastFftAt > 1200) return false;
        int n = fft.length;
        if(n < 64 || out.length < 8) return false;
        int bins = n / 2;
        int rate = lastFftRate > 8000 ? lastFftRate : 44100;
        float binHz = rate / (float) n;
        if(liveMags == null || liveMags.length != bins) liveMags = new float[bins];
        if(liveDb.length < out.length) liveDb = new float[out.length];
        float[] mags = liveMags;
        for(int k = 1; k < bins; k++) {
            float re = fft[2 * k], im = fft[2 * k + 1];
            mags[k] = (float) Math.hypot(re, im);
        }
        float fMin = 42f;
        float fMax = Math.min(16000f, rate * 0.5f);
        float ratio = fMax / fMin;
        float framePeak = -120f;
        for(int i = 0; i < out.length; i++) {
            float loF = fMin * (float) Math.pow(ratio, i / (double) out.length);
            float hiF = fMin * (float) Math.pow(ratio, (i + 1) / (double) out.length);
            int lo = (int) Math.floor(loF / binHz);
            int hi = (int) Math.ceil(hiF / binHz);
            if(lo < 1) lo = 1;
            if(hi <= lo) hi = lo + 1;
            if(hi > bins) hi = bins;
            float peak = 0f;
            for(int k = lo; k < hi; k++) if(mags[k] > peak) peak = mags[k];
            if(peak <= 0f && lo < bins) peak = mags[lo];
            float db = peak <= 0.0001f ? -120f : 20f * (float) Math.log10(peak / 127f);
            if(db < -72f) db = -72f;
            if(db > 0f) db = 0f;
            liveDb[i] = db;
            if(db > framePeak) framePeak = db;
        }
        if(framePeak < -58f) {
            for(int i = 0; i < out.length; i++) out[i] = 0f;
            return true;
        }
        long now = SystemClock.uptimeMillis();
        if(now != lastGainAt) {
            lastGainAt = now;
            float targetGain = -framePeak;
            if(targetGain > liveGainDb) liveGainDb += (targetGain - liveGainDb) * 0.55f;
            else liveGainDb += (targetGain - liveGainDb) * 0.035f;
            if(liveGainDb < 0f) liveGainDb = 0f;
            if(liveGainDb > 38f) liveGainDb = 38f;
        }
        for(int i = 0; i < out.length; i++) {
            float norm = (liveDb[i] + liveGainDb + 42f) / 42f;
            if(norm < 0f) norm = 0f;
            if(norm > 1f) norm = 1f;
            out[i] = (float) Math.pow(norm, 0.78f);
        }
        return true;
    }

    private void attachVisualizer() {
        releaseVisualizer();
        if(!audioSync || active == null) return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            int session = active.getAudioSessionId();
            Visualizer v = new Visualizer(session);
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int rate) { }
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int rate) {
                    if(fft != null && fft.length > 4) {
                        // Visualizer documents milliHertz (48000000 for 48 kHz), but
                        // a few devices report plain Hz. Accept both.
                        int hz = (rate < 8000 || rate > 192000) ? Math.round(rate / 1000f) : rate;
                        lastFftRate = hz >= 8000 ? hz : 44100;
                        lastFft = fft.clone();
                        lastFftAt = SystemClock.uptimeMillis();
                    }
                }
            }, Visualizer.getMaxCaptureRate(), false, true);
            if(v.setEnabled(true) != Visualizer.SUCCESS) { try { v.release(); } catch(Throwable ignored) { } return; }
            visualizer = v;
        } catch(Throwable ignored) { visualizer = null; lastFft = null; }
    }

    private void releaseVisualizer() {
        Visualizer v = visualizer;
        visualizer = null;
        lastFft = null;
        liveGainDb = 0f;
        if(v != null) {
            try { v.setEnabled(false); } catch(Throwable ignored) { }
            try { v.release(); } catch(Throwable ignored) { }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        lastInput = SystemClock.uptimeMillis();
        if(dark != resolveDark()) { dark = resolveDark(); buildUI(); }
        clock.removeCallbacks(tick);
        clock.post(tick);
    }

    @Override protected void onPause() {
        boolean keepPlaying = suppressPause;
        suppressPause = false;
        foreground = false;
        ambientMode = false;
        if(ambient != null) ambient.setVisibility(View.GONE);
        if(!keepPlaying) {
            pauseAudio();
            audioManager.abandonAudioFocusRequest(focus);
        }
        clock.removeCallbacks(tick);
        saveResume();
        saveSettings();
        releaseVisualizer();
        super.onPause();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if(artwork == null) return;
        // 10 = RUNNING_LOW, 15 = RUNNING_CRITICAL (values kept literal because
        // the named constants are deprecated on newer Android releases).
        if(level >= 10) artwork.trimMemory();
        if(level >= 15) {
            libraryCache.clear();
            if(!indexing) globalIndex = null;
        }
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if(artwork != null) artwork.trimMemory();
        libraryCache.clear();
        if(!indexing) globalIndex = null;
    }

    @Override protected void onDestroy() {
        generation++;
        clock.removeCallbacksAndMessages(null);
        destroyed = true;
        browseGeneration++; connectGeneration++; connecting = false;
        libraryCache.clear();
        dismissError();
        artwork.close();
        releaseVisualizer();
        release(active); release(standby);
        network.execute(() -> { synchronized(nasClients) { for(NasClient client : nasClients) { try { client.close(); } catch(IOException ignored) { } } nasClients.clear(); } });
        network.shutdown();
        connector.shutdown();
        super.onDestroy();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        lastInput = SystemClock.uptimeMillis();
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if(event.getAction() == KeyEvent.ACTION_DOWN) {
            if(key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
                if(overlay == Overlay.SETTINGS) { closeOverlay(); return true; }
                if(overlay == Overlay.SEARCH) { closeSearch(); return true; }
                if(ambientMode) { hideAmbient(); return true; }
                if(back()) return true;
            }
            if(key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { if(playing) pauseAudio(); else startAudio(); return true; }
            if(key == KeyEvent.KEYCODE_MEDIA_NEXT) { if(!playback.isEmpty()) nextWithFade(); return true; }
            if(key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { if(!playback.isEmpty()) prevWithFade(); return true; }
            // RIGHT arrow while the song list is focused toggles the favourite on the
            // highlighted track, without moving focus or touching playback.
            if(key == KeyEvent.KEYCODE_DPAD_RIGHT && overlay == Overlay.NONE && !ambientMode
                && trackList != null && trackList.isFocused()) {
                toggleFavoriteSelected();
                return true;
            }
        }
        if(overlay == Overlay.NONE && !ambientMode) lastInput = SystemClock.uptimeMillis();
        return super.dispatchKeyEvent(event);
    }

    private boolean back() {
        if(viewMode == Mode.FAVORITES) {
            if(browseUrl.isEmpty() && nas != null) browse(nas.rootUrl(), shareLabel(nas.rootUrl()));
            else browse(browseUrl, folderName, false, null, FOLDER_LIST_KEEP);
            return true;
        }
        if(browseUrl.isEmpty() || nas == null) return false;
        if(browseUrl.equals(nas.rootUrl()) || isServerRoot(browseUrl)) return false;
        // Do not navigate up on BACK: the folder list stays at its last display
        // until the user opens another folder or presses the ↑ button.
        return true;
    }

    private void saveSettings() {
        prefs.edit().putInt("index", index).putInt("fade", fadeSeconds).putBoolean("shuffle", shuffle).putBoolean("dark", dark).apply();
    }

    private void showEmptyState(String message) {
        if(emptyState == null) return;
        if(message == null) { emptyState.setVisibility(View.GONE); return; }
        emptyState.setText(message);
        emptyState.setVisibility(View.VISIBLE);
    }

    private static final String CRED_PREF = "share_credentials";
    private static final String CRED_KEY_ALIAS = "fader_share_credentials";
    private static final class SavedCredentials {
        final String address, domain, user, password;
        SavedCredentials(String address, String domain, String user, String password) {
            this.address = address; this.domain = domain; this.user = user; this.password = password;
        }
    }

    private SecretKey credentialsKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias(CRED_KEY_ALIAS)) {
            KeyStore.Entry entry = store.getEntry(CRED_KEY_ALIAS, null);
            if(entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(CRED_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }

    private boolean saveCredentials(String address, String domain, String user, String password) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("address", address); payload.put("domain", domain); payload.put("user", user); payload.put("password", password);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, credentialsKey());
            byte[] iv = cipher.getIV(), data = cipher.doFinal(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + data.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(data, 0, packed, iv.length, data.length);
            prefs.edit().putString(CRED_PREF, Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
            return true;
        } catch(Exception e) {
            android.util.Log.w("FaderTV", "Could not store encrypted share credentials");
            return false;
        }
    }

    private SavedCredentials loadCredentials() {
        String stored = prefs.getString(CRED_PREF, "");
        if(stored.isEmpty()) return null;
        try {
            byte[] packed = Base64.decode(stored, Base64.NO_WRAP);
            if(packed.length <= 12) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, credentialsKey(), new GCMParameterSpec(128, packed, 0, 12));
            JSONObject payload = new JSONObject(new String(cipher.doFinal(packed, 12, packed.length - 12), java.nio.charset.StandardCharsets.UTF_8));
            return new SavedCredentials(payload.optString("address"), payload.optString("domain"), payload.optString("user"), payload.optString("password"));
        } catch(Exception e) { clearCredentials(); return null; }
    }

    private void clearCredentials() { prefs.edit().remove(CRED_PREF).apply(); }

    private void tryAutoConnect() {
        String address = prefs.getString("nas", "");
        if(address.isEmpty()) return;
        SavedCredentials saved = loadCredentials();
        if(saved == null || !address.equals(saved.address)) return;
        connectShare(address, saved.domain, saved.user, saved.password, true, true);
    }

    /** Restores the last active source (local/USB or network) after a restart. */
    private void restoreLastSource() {
        if("local".equals(prefs.getString("last_source", null))) {
            activeLocal = new LocalClient(this);
            nas = activeLocal;
            libraryCache.clear();
            globalIndex = null;
            renderSources();
            restoreSavedFolders();
            String url = prefs.getString("browse_url", null);
            String name = prefs.getString("browse_name", "USB / Local");
            if(url != null && !url.isEmpty() && url.startsWith("/local/")) {
                browse(url, name, true, "", FOLDER_LIST_KEEP);
            } else {
                browse(nas.rootUrl(), "USB / Local", true, "", FOLDER_LIST_KEEP);
            }
        } else {
            tryAutoConnect();
        }
    }

    private void connectShare(String url, String domain, String user, String password, boolean remember, boolean quiet) {
        if(connecting) { if(!quiet) status.setText("Still connecting… please wait"); return; }
        connecting = true;
        final int token = ++connectGeneration;
        dismissError();
        status.setText("Connecting to share…");
        connector.execute(() -> {
            try {
                NasClient client = NasClient.open(url, domain, user, password, mode -> clock.post(() -> {
                    if(!destroyed && token == connectGeneration) status.setText("Connecting… " + mode.label);
                }));
                if(destroyed || token != connectGeneration) { try { client.close(); } catch(IOException ignored) { } return; }
                synchronized(nasClients) { nasClients.add(client); }
                boolean stored = remember && saveCredentials(client.root, domain, user, password);
                if(!remember) clearCredentials();
                final boolean saved = stored;
                final NasClient ready = client;
                clock.post(() -> {
                    if(destroyed || token != connectGeneration) return;
                    connecting = false;
                    dismissError();
                    libraryCache.clear();
                    globalIndex = null;
                    activeNetwork = ready;
                    nas = ready;
                    prefs.edit().putString("nas", ready.root).apply();
                    renderSources();
                    if(remember && !saved) Toast.makeText(this, "Could not save the password on this TV", Toast.LENGTH_SHORT).show();
                    if(quiet) {
                        String resumeUrl = prefs.getString("res_url", null);
                        if(resumeUrl != null && !resumeUrl.isEmpty() && resumeUrl.startsWith(ready.root)) {
                            pendingResumeIndex = prefs.getInt("res_index", 0);
                            pendingResumePos = prefs.getLong("res_pos", -1);
                            shuffle = prefs.getBoolean("res_shuffle", shuffle);
                            restoreSavedFolders();
                            browse(resumeUrl, prefs.getString("res_name", "Music"), false, null, FOLDER_LIST_KEEP);
                        } else {
                            restoreSavedFolders();
                            String browseUrlSaved = prefs.getString("browse_url", null);
                            if(browseUrlSaved != null && !browseUrlSaved.isEmpty() && browseUrlSaved.startsWith(ready.root)) {
                                browse(browseUrlSaved, prefs.getString("browse_name", "Music"), true, "", FOLDER_LIST_KEEP);
                            } else {
                                browse(ready.root, shareLabel(ready.root), true, "", FOLDER_LIST_KEEP);
                            }
                        }
                    } else {
                        browse(ready.root, shareLabel(ready.root), true, "");
                    }
                });
            } catch(IllegalArgumentException e) {
                clock.post(() -> {
                    if(destroyed || token != connectGeneration) return;
                    connecting = false;
                    showError("Invalid share address. Use smb://server/share, without a username or password in the address.", describe(e));
                });
            } catch(Exception e) {
                if(remember && e instanceof jcifs.smb.SmbAuthException) clearCredentials();
                final String summary = e instanceof NasClient.ConnectFailure ? ((NasClient.ConnectFailure) e).summary : "Cannot connect to the network share.";
                final String details = e instanceof NasClient.ConnectFailure ? ((NasClient.ConnectFailure) e).details : describe(e);
                clock.post(() -> {
                    if(destroyed || token != connectGeneration) return;
                    connecting = false;
                    if(quiet) status.setText("Saved share unavailable • tap the share card");
                    else showConnectError(summary, details, url);
                });
            }
        });
    }

    private void connectDialog(String prefill) {
        SavedCredentials stored = loadCredentials();
        String savedAddress = prefs.getString("nas", "");
        boolean hasStored = stored != null && !stored.address.isEmpty();
        LinearLayout form = vertical();
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText address = new EditText(this); address.setSingleLine();
        address.setText(prefill != null && !prefill.isEmpty() ? prefill : savedAddress);
        address.setHint("smb://server/share"); form.addView(address);
        EditText user = new EditText(this); user.setSingleLine(); user.setHint("Username (empty for guest)"); form.addView(user);
        EditText domain = new EditText(this); domain.setSingleLine(); domain.setHint("Domain (optional)"); form.addView(domain);
        EditText password = new EditText(this); password.setSingleLine(); password.setHint("Password"); password.setInputType(129); form.addView(password);
        boolean prefillCredentials = hasStored && (prefill == null || prefill.isEmpty() || prefill.equals(stored.address));
        if(prefillCredentials) { user.setText(stored.user); domain.setText(stored.domain); password.setText(stored.password); }
        CheckBox remember = new CheckBox(this);
        remember.setText("Remember username & password (encrypted on this TV)");
        remember.setChecked(hasStored); form.addView(remember);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle("Connect to network share (read only)").setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect", (d, w) -> {
                String url = address.getText().toString().trim(), u = user.getText().toString(), pw = password.getText().toString(), dom = domain.getText().toString();
                connectShare(url, dom, u, pw, remember.isChecked(), false);
            });
        if(hasStored) builder.setNeutralButton("Forget saved", (d, w) -> { clearCredentials(); status.setText("Saved password removed"); });
        builder.show();
    }

    private void dismissError() {
        if(errorDialog != null) { if(errorDialog.isShowing()) errorDialog.dismiss(); errorDialog = null; }
    }

    private void showError(String message) { showError(message, null); }

    private void showError(String message, String details) {
        if(destroyed) return;
        dismissError();
        status.setText("Share unavailable • Connect again");
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Network share").setMessage(message).setPositiveButton("OK", null);
        if(details != null && !details.isEmpty()) builder.setNeutralButton("Details", (d, w) -> showDetails(details));
        errorDialog = builder.show();
    }

    private void showConnectError(String message, String details, String address) {
        if(destroyed) return;
        dismissError();
        status.setText("Share unavailable • Connect again");
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Network share").setMessage(message).setPositiveButton("OK", null);
        if(details != null && !details.isEmpty()) builder.setNeutralButton("Details", (d, w) -> showDetails(details));
        if(address != null && !address.isEmpty() && loadCredentials() != null) builder.setNegativeButton("Change login", (d, w) -> connectDialog(address));
        errorDialog = builder.show();
    }

    private void showDetails(String details) {
        TextView body = new TextView(this);
        body.setText(details); body.setTextIsSelectable(true);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Connection details").setView(scroll).setPositiveButton("OK", null).show();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : " • " + message);
    }

    private static String browseMessage(Exception e) {
        if(e instanceof jcifs.smb.SmbAuthException)
            return "Authentication failed while reading the shared folder. Check the username, password and read permission.";
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if(m.contains("timed out") || m.contains("timeout"))
            return "The server took too long to answer. Check that the TV and the server are on the same network, then press Refresh.";
        if(m.contains("access is denied") || m.contains("access_denied"))
            return "Access denied. The account needs read permission on this folder.";
        if(m.contains("network name cannot be found") || m.contains("bad_network_name"))
            return "The share is no longer available. Check the address and the server.";
        return "Cannot read the shared folder. Check the username, password and read permission. Use the full address, for example smb://server/music.";
    }

    /** Saves the browse state (current folder + the left folder panel) so it
     *  survives a restart, even when the app is killed abruptly. */
    private void persistBrowse() {
        if(nas == null) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putString("last_source", nas instanceof LocalClient ? "local" : "network");
        if(browseUrl != null && !browseUrl.isEmpty()) {
            e.putString("browse_url", browseUrl).putString("browse_name", folderName);
        } else {
            e.remove("browse_url").remove("browse_name");
        }
        try {
            JSONArray folders = new JSONArray();
            for(NasClient.Entry f : browsedFolders) {
                JSONObject o = new JSONObject();
                o.put("url", f.url);
                o.put("name", f.name);
                folders.put(o);
            }
            e.putString("browse_folders", folders.toString());
        } catch(Exception ignored) { }
        e.apply();
    }

    private void restoreSavedFolders() {
        String raw = prefs.getString("browse_folders", null);
        if(raw == null || raw.isEmpty()) return;
        browsedFolders.clear();
        try {
            JSONArray arr = new JSONArray(raw);
            for(int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if(o == null) continue;
                String url = o.optString("url");
                String name = o.optString("name");
                if(!url.isEmpty()) browsedFolders.add(new NasClient.Entry(name, url, true, 0, 0));
            }
        } catch(Exception ignored) { }
        renderFolders();
    }

    private void saveResume() {
        if(nas == null) return;
        persistBrowse();
        SharedPreferences.Editor e = prefs.edit();
        // Playback: remember the playing folder + position for a full resume.
        if(playFrom == PlayFrom.FOLDER && playbackUrl != null && !playbackUrl.isEmpty()) {
            long resumePos = -1L;
            try { if(ready && active != null) resumePos = active.getCurrentPosition(); }
            catch(IllegalStateException ignored) { ready = false; }
            e.putString("res_url", playbackUrl).putString("res_name", playbackName)
                .putInt("res_index", index).putLong("res_pos", resumePos)
                .putBoolean("res_shuffle", shuffle);
        } else {
            e.remove("res_url").remove("res_name").remove("res_index").remove("res_pos");
        }
        e.apply();
    }

    private static String parentUrl(String url) {
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int slash = u.lastIndexOf('/');
        return slash < 0 ? url : u.substring(0, slash + 1);
    }

    private static String folderLabel(String url) {
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int slash = u.lastIndexOf('/');
        String name = slash < 0 ? "" : u.substring(slash + 1);
        return name.isEmpty() ? "Network share" : name;
    }

    private static boolean isServerRoot(String url) {
        // SMB names may contain spaces and other characters that jcifs returns
        // raw (not percent-encoded), e.g. "smb://server/music/Like by me/".
        // java.net.URI.create() rejects those, so extract the path manually.
        if (url == null || url.isEmpty()) return true;
        String u = url;
        int scheme = u.indexOf("://");
        if (scheme >= 0) u = u.substring(scheme + 3);
        int slash = u.indexOf('/');
        if (slash < 0) return true;            // smb://server  (no path)
        String path = u.substring(slash);      // "/" or "/share" or "/share/Like by me/"
        return path.isEmpty() || "/".equals(path);
    }

    private String shareLabel(String address) {
        try {
            String a = address.endsWith("/") ? address : address + "/";
            java.net.URI u = java.net.URI.create(a);
            String path = u.getPath() == null ? "" : u.getPath();
            String[] parts = path.split("/");
            for(int i = parts.length - 1; i >= 0; i--) if(!parts[i].isEmpty()) return parts[i];
            return u.getHost() != null ? u.getHost() : address;
        } catch(Exception e) { return address; }
    }

    private String sourceLabel(TrackItem item) {
        if(viewMode == Mode.FAVORITES) return "";
        if(playFrom == PlayFrom.SEARCH) return "Search";
        String folder = folderOf(item.entry.url);
        String current = folderLabel(browseUrl);
        if(folder.equals(current)) return "";
        return folder;
    }

    private class TrackAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int position) { return shown.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            TrackItem item = shown.get(position);
            LinearLayout row;
            Art thumb;
            TextView label, duration, heartView;
            View accent;
            if(recycled == null) {
                row = horizontal();
                row.setPadding(0, 0, dp(8), 0);
                row.setMinimumHeight(dp(58));
                accent = new View(MainActivity.this);
                row.addView(accent, new LinearLayout.LayoutParams(dp(3), -1));
                thumb = new Art(true);
                LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(42), dp(42));
                thumbLp.setMargins(dp(8), dp(7), dp(8), dp(7));
                row.addView(thumb, thumbLp);
                label = text("", 13); label.setMaxLines(2);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(label, new LinearLayout.LayoutParams(0, dp(56), 1f));
                heartView = text("", 15); heartView.setGravity(Gravity.CENTER);
                row.addView(heartView, new LinearLayout.LayoutParams(dp(26), -1));
                duration = text("", 11); duration.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                row.addView(duration, new LinearLayout.LayoutParams(dp(42), -1));
                row.setTag(new Object[]{thumb, label, duration, heartView, accent});
            } else {
                row = (LinearLayout) recycled;
                Object[] views = (Object[]) row.getTag();
                thumb = (Art) views[0]; label = (TextView) views[1]; duration = (TextView) views[2];
                heartView = (TextView) views[3]; accent = (View) views[4];
            }
            artwork.request(item, () -> clock.post(() -> { if(!destroyed) { notifyDataSetChanged(); updateUI(); } }));
            thumb.item = item; thumb.invalidate();
            String source = sourceLabel(item);
            label.setText(item.title + "\n" + item.artist + (source.isEmpty() ? "" : " · " + source));
            duration.setText(item.duration > 0 ? format(item.duration) : "—");
            boolean favourite = favorites.has(item);
            heartView.setText(favourite ? "♥" : "♡");
            heartView.setTextColor(favourite ? 0xffff7b9c : muted());
            TrackItem now = current();
            boolean isNow = now != null && now.key().equals(item.key());
            accent.setBackgroundColor(isNow ? BLUE : 0x00000000);
            boolean selected = listFocused && position == selectedPosition;
            row.setBackground(selected ? shape(focusFill(), true) : null);
            label.setTypeface(null, isNow ? Typeface.BOLD : Typeface.NORMAL);
            return row;
        }
    }

    private class SearchAdapter extends BaseAdapter {
        public int getCount() { return searchResults.size(); }
        public Object getItem(int position) { return searchResults.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            TrackItem item = searchResults.get(position);
            LinearLayout row;
            TextView title, sub;
            if(recycled == null) {
                row = vertical();
                row.setPadding(dp(10), dp(5), dp(10), dp(5));
                row.setMinimumHeight(dp(54));
                title = text("", 14); title.setTypeface(null, Typeface.BOLD);
                title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                sub = text("", 11); sub.setTextColor(muted());
                sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(title, new LinearLayout.LayoutParams(-1, dp(22)));
                row.addView(sub, new LinearLayout.LayoutParams(-1, dp(18)));
                row.setTag(new Object[]{title, sub});
            } else {
                row = (LinearLayout) recycled;
                Object[] views = (Object[]) row.getTag();
                title = (TextView) views[0]; sub = (TextView) views[1];
            }
            title.setText(item.title);
            sub.setText(item.artist + " · " + folderOf(item.entry.url) + " · " + item.entry.name);
            boolean selected = searchFocused && position == searchSelected;
            row.setBackground(selected ? shape(focusFill(), true) : null);
            return row;
        }
    }

    private class Art extends View {
        TrackItem item;
        private final boolean folderFallback;
        private final boolean darkPlaceholder;
        private boolean shadow;
        private Bitmap shown;
        private final Paint paint = new Paint(3);
        private final Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Art(boolean fallback) { this(fallback, false); }
        Art(boolean fallback, boolean forceDark) {
            super(MainActivity.this);
            folderFallback = fallback;
            darkPlaceholder = forceDark;
        }

        void setShadow(boolean enabled) { shadow = enabled; invalidate(); }

        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if(w <= 0 || h <= 0) return;
            Bitmap bitmap = item == null ? null : artwork.get(item);
            if(bitmap == null && folderFallback) bitmap = artwork.getCover();
            if(bitmap != null) {
                if(bitmap != shown) { shown = bitmap; setAlpha(0f); animate().alpha(1f).setDuration(180).start(); }
                if(shadow) {
                    float inset = dp(14);
                    paint.setShadowLayer(dp(10), 0, dp(3), 0x80000000);
                    canvas.drawBitmap(bitmap, null, new RectF(inset, inset, w - inset, h - inset), paint);
                    paint.clearShadowLayer();
                } else {
                    canvas.drawBitmap(bitmap, null, new RectF(0, 0, w, h), paint);
                }
                return;
            }
            shown = null;
            if(getAlpha() != 1f) setAlpha(1f);
            boolean darkArt = dark || darkPlaceholder;
            paint.setShader(new LinearGradient(0, 0, w, h, darkArt ? 0xff1c2836 : 0xffe6edf5, darkArt ? 0xff121a24 : 0xffd4dfeb, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(0, 0, w, h, dp(10), dp(10), paint);
            paint.setShader(null);
            notePaint.setColor(darkArt ? 0x33ffffff : 0x2e33414f);
            notePaint.setTextSize(Math.min(w, h) * 0.38f);
            notePaint.setTextAlign(Paint.Align.CENTER);
            notePaint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("♪", w / 2f, h / 2f - (notePaint.descent() + notePaint.ascent()) / 2f, notePaint);
        }
    }

    private static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static Bitmap boxBlur(Bitmap src, int radius) {
        int w = src.getWidth(), h = src.getHeight();
        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);
        int[] out = new int[w * h];
        int r = Math.max(1, radius);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int rs = 0, gs = 0, bs = 0;
            for (int x = -r; x <= r; x++) {
                int c = pix[row + clampInt(x, 0, w - 1)];
                rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255;
            }
            for (int x = 0; x < w; x++) {
                int cnt = 2 * r + 1;
                out[row + x] = 0xff000000 | ((rs / cnt) << 16) | ((gs / cnt) << 8) | (bs / cnt);
                int add = pix[row + clampInt(x + r + 1, 0, w - 1)];
                int rem = pix[row + clampInt(x - r, 0, w - 1)];
                rs += ((add >> 16) & 255) - ((rem >> 16) & 255);
                gs += ((add >> 8) & 255) - ((rem >> 8) & 255);
                bs += (add & 255) - (rem & 255);
            }
        }
        for (int x = 0; x < w; x++) {
            int rs = 0, gs = 0, bs = 0;
            for (int y = -r; y <= r; y++) {
                int c = out[clampInt(y, 0, h - 1) * w + x];
                rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255;
            }
            for (int y = 0; y < h; y++) {
                int cnt = 2 * r + 1;
                pix[y * w + x] = 0xff000000 | ((rs / cnt) << 16) | ((gs / cnt) << 8) | (bs / cnt);
                int add = out[clampInt(y + r + 1, 0, h - 1) * w + x];
                int rem = out[clampInt(y - r, 0, h - 1) * w + x];
                rs += ((add >> 16) & 255) - ((rem >> 16) & 255);
                gs += ((add >> 8) & 255) - ((rem >> 8) & 255);
                bs += (add & 255) - (rem & 255);
            }
        }
        return Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap blur(Bitmap src) {
        if (src == null) return null;
        int maxDim = 160;
        int w = src.getWidth(), h = src.getHeight();
        Bitmap bmp = src;
        if (Math.max(w, h) > maxDim) {
            float s = (float) maxDim / Math.max(w, h);
            bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, (int)(w * s)), Math.max(1, (int)(h * s)), true);
        }
        return boxBlur(bmp, 1);
    }

    /** Full-screen blurred cover background behind the ambient screen, dimmed for readability. */
    private class BlurredBackground extends View {
        private Bitmap blurred;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        BlurredBackground() { super(MainActivity.this); }
        void setCover(Bitmap cover) { blurred = blur(cover); invalidate(); }
        void clear() { blurred = null; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (blurred != null) {
                float scale = Math.max((float) w / blurred.getWidth(), (float) h / blurred.getHeight());
                int dw = Math.round(blurred.getWidth() * scale);
                int dh = Math.round(blurred.getHeight() * scale);
                int dx = (w - dw) / 2, dy = (h - dh) / 2;
                canvas.drawBitmap(blurred, null, new RectF(dx, dy, dx + dw, dy + dh), paint);
            }
            canvas.drawColor(0xb3080e16);
        }
    }
}
