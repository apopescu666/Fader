package com.fader.tv;

import android.graphics.*;
import android.media.*;
import android.util.LruCache;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Background metadata/cover reader with an in-memory and on-disk folder index. */
final class ArtworkStore {
    private static final int COMPLETED_LIMIT = 4000;
    /** One visited folder index is enough to re-open it instantly; more is heap risk. */
    private static final int FOLDER_INDEX_LIMIT = 1;
    /** Hard cap for a single folder index, so one huge folder cannot fill the heap. */
    private static final int FOLDER_ENTRY_LIMIT = 8000;
    private static final int EMBEDDED_PICTURE_LIMIT = 8 * 1024 * 1024;
    private static final long FOLDER_INDEX_FILE_LIMIT = 6L * 1024 * 1024;
    /**
     * MediaMetadataRetriever is not thread-safe on several TV chipsets and can
     * take the whole process down in native code. One extraction at a time is
     * slower only on the first visit of a folder; cached folders stay instant.
     */
    private static final Object RETRIEVER_LOCK = new Object();
    private final File directory;
    private final File folderDirectory;
    // Several visible covers can be read at once; a single worker made the list feel slow on a NAS.
    private final ExecutorService worker=Executors.newFixedThreadPool(4,r->{
        Thread t=new Thread(r,"fader-artwork");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> requested=ConcurrentHashMap.newKeySet();
    private final Set<String> coverLoading=ConcurrentHashMap.newKeySet();
    private final Set<String> noCover=ConcurrentHashMap.newKeySet();
    private final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(32*1024*1024) {
        protected int sizeOf(String key,Bitmap b){return b.getAllocationByteCount();}
    };
    private final Map<String,TrackItem> completed=new ConcurrentHashMap<>();
    private final Map<String,Integer> attempts=new ConcurrentHashMap<>();
    // Folder URL -> track key -> [title, artist, album, duration]. One local read
    // restores a whole previously visited folder without touching the NAS again.
    private final Map<String,Map<String,String[]>> folderMeta=new ConcurrentHashMap<>();
    private final Set<String> dirtyFolders=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean saveScheduled=new AtomicBoolean();
    private final ScheduledExecutorService saver=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"fader-meta-save");
        t.setDaemon(true);
        return t;
    });
    private volatile Bitmap folderArt;
    private volatile boolean stopped;
    ArtworkStore(File cache) {
        directory=new File(cache,"nas-artwork");directory.mkdirs();
        folderDirectory=new File(cache,"nas-meta");folderDirectory.mkdirs();
    }
    Bitmap get(TrackItem item){return images.get(item.key());}
    Bitmap getCover(){return folderArt;}
    void clearCover(){folderArt=null;}

    /**
     * Copies already-loaded metadata onto freshly built rows. Re-entering a folder
     * must not show filename-only rows while the same metadata sits in memory or in
     * the folder index written by an earlier visit.
     */
    void applyCached(List<TrackItem> items){
        if(items==null)return;
        // Search results and favorites can span many folders. Loading one folder
        // index per row would thrash the single in-memory index and the disk, so
        // use the folder index only when the whole list belongs to one folder.
        String onlyFolder=null;
        boolean mixed=false;
        for(TrackItem item:items){
            if(item.folderUrl==null)continue;
            if(onlyFolder==null)onlyFolder=item.folderUrl;
            else if(!onlyFolder.equals(item.folderUrl)){mixed=true;break;}
        }
        Map<String,String[]> index=(mixed||onlyFolder==null)?null:folderIndex(onlyFolder);
        for(TrackItem item:items){
            if(index!=null&&item.folderUrl!=null){
                String[] meta=index.get(item.key());
                if(meta!=null)applyMeta(item,meta);
            }
            TrackItem cached=completed.get(item.key());
            if(cached!=null){
                item.title=cached.title;item.artist=cached.artist;item.duration=cached.duration;item.hasCover=cached.hasCover;
                if(cached.album!=null&&!cached.album.isEmpty())item.album=cached.album;
            }
        }
    }
    private static void applyMeta(TrackItem item,String[] meta){
        if(meta.length<4)return;
        if(meta[0]!=null&&!meta[0].isEmpty())item.title=meta[0];
        if(meta[1]!=null&&!meta[1].isEmpty())item.artist=meta[1];
        if(meta[2]!=null&&!meta[2].isEmpty())item.album=meta[2];
        try{item.duration=Integer.parseInt(meta[3]);}catch(Exception ignored){}
        if(meta.length>4)item.hasCover="1".equals(meta[4]);
    }
    private Map<String,String[]> folderIndex(String folderUrl){
        Map<String,String[]> index=folderMeta.get(folderUrl);
        if(index!=null)return index;
        synchronized(folderMeta){
            index=folderMeta.get(folderUrl);
            if(index!=null)return index;
            while(folderMeta.size()>=FOLDER_INDEX_LIMIT){
                String victim=null;
                for(String key:folderMeta.keySet()) if(!key.equals(folderUrl)){victim=key;break;}
                if(victim==null)break;
                final Map<String,String[]> evicted=folderMeta.remove(victim);
                if(evicted!=null&&dirtyFolders.remove(victim)){
                    final String url=victim;
                    try{saver.execute(()->writeFolder(url,evicted));}catch(Exception ignored) { }
                }
            }
            index=loadFolderIndex(folderUrl);
            folderMeta.put(folderUrl,index);
            return index;
        }
    }
    private Map<String,String[]> loadFolderIndex(String folderUrl){
        Map<String,String[]> index=new ConcurrentHashMap<>();
        try{
            File file=new File(folderDirectory,hex(folderUrl)+".json");
            if(!file.isFile()||file.length()>FOLDER_INDEX_FILE_LIMIT)return index;
            JSONObject o;
            try(FileInputStream in=new FileInputStream(file)){
                o=new JSONObject(new String(readBytes(in),java.nio.charset.StandardCharsets.UTF_8));
            }
            Iterator<String> keys=o.keys();
            while(keys.hasNext()&&index.size()<FOLDER_ENTRY_LIMIT){
                String key=keys.next();
                JSONArray a=o.optJSONArray(key);
                if(a==null||a.length()<4)continue;
                index.put(key,new String[]{a.optString(0),a.optString(1),a.optString(2),String.valueOf(a.optInt(3,0)),a.optBoolean(4,false)?"1":"0"});
            }
        }catch(OutOfMemoryError e){
            trimMemory();
            index.clear();
        }catch(Exception ignored) { }
        return index;
    }
    /** Stores metadata locally as soon as it is read, so a later visit is instant. */
    private void remember(TrackItem item){
        if(item.folderUrl==null||item.title==null)return;
        try{
            Map<String,String[]> index=folderIndex(item.folderUrl);
            String key=item.key();
            if(index.size()>=FOLDER_ENTRY_LIMIT&&!index.containsKey(key))return;
            index.put(key,new String[]{item.title,item.artist,item.album,String.valueOf(item.duration),item.hasCover?"1":"0"});
            dirtyFolders.add(item.folderUrl);
            scheduleSave();
        }catch(OutOfMemoryError e){
            trimMemory();
        }catch(Exception ignored) { }
    }
    private void scheduleSave(){
        if(!saveScheduled.compareAndSet(false,true))return;
        saver.schedule(()->{
            saveScheduled.set(false);
            saveDirty();
        },2,TimeUnit.SECONDS);
    }
    private synchronized void saveDirty(){
        for(String url:new ArrayList<>(dirtyFolders)){
            dirtyFolders.remove(url);
            Map<String,String[]> index=folderMeta.get(url);
            if(index!=null)writeFolder(url,index);
        }
    }
    private void saveFolder(String url){
        Map<String,String[]> index=folderMeta.get(url);
        if(index==null)return;
        writeFolder(url,index);
    }
    private void writeFolder(String url,Map<String,String[]> index){
        File file=null,tmp=null;
        try{
            file=new File(folderDirectory,hex(url)+".json");
            tmp=new File(file.getPath()+".tmp");
            JSONObject o=new JSONObject();
            for(Map.Entry<String,String[]> e:index.entrySet()){
                String[] m=e.getValue();
                if(m==null||m.length<4)continue;
                JSONArray a=new JSONArray();
                a.put(m[0]);a.put(m[1]);a.put(m[2]);a.put(m[3]);a.put(m.length>4&&"1".equals(m[4]));
                o.put(e.getKey(),a);
            }
            try(FileOutputStream out=new FileOutputStream(tmp)){
                out.write(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if(!tmp.renameTo(file)){
                file.delete();
                if(!tmp.renameTo(file))tmp.delete();
            }
        }catch(Exception ignored) {
            if(tmp!=null)tmp.delete();
        }catch(OutOfMemoryError ignored) {
            if(tmp!=null)tmp.delete();
        }
    }

    /** Decode folder artwork (folder.jpg / cover.*) once, down-scaled, with a 4 MB read budget. */
    void requestCover(LibraryClient client, NasClient.Entry cover, Runnable changed) {
        if(stopped || client==null || cover==null) return;
        worker.execute(()->{
            try {
                String id="folder-"+hex(cover.url);
                File out=new File(directory,id+".jpg");
                if(out.isFile()) {Bitmap b=BitmapFactory.decodeFile(out.getPath());if(b!=null){folderArt=b;changed.run();}return;}
                try(MediaDataSource raw=client.source(cover);MediaDataSource bounded=new MediaDataSource(){long read;
                    public int readAt(long p,byte[] b,int off,int len)throws java.io.IOException {if(stopped||read>4*1024*1024)throw new java.io.IOException("Cover budget exceeded");int n=raw.readAt(p,b,off,len);if(n>0)read+=n;return n;}
                    public long getSize()throws java.io.IOException{return raw.getSize();}
                    public void close()throws java.io.IOException{try{if(raw!=null)raw.close();}catch(java.io.IOException ignored){}}}) {
                    byte[] bytes=new byte[(int)Math.min(bounded.getSize(),4*1024*1024)];
                    int pos=0;int n;while((n=bounded.readAt(pos,bytes,pos,bytes.length))>0)pos+=n;
                    BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
                    opt.inSampleSize=1;while(Math.max(opt.outWidth,opt.outHeight)/opt.inSampleSize>512)opt.inSampleSize*=2;
                    opt.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
                    if(b!=null){folderArt=b;try(FileOutputStream fo=new FileOutputStream(out)){b.compress(Bitmap.CompressFormat.JPEG,85,fo);}}
                    if(b!=null)changed.run();
                }
            } catch(OutOfMemoryError e) {
                trimMemory();
            } catch(Exception ignored) { /* Filename stays available; the on-disc cover is retried next session. */ }
        });
    }
    void request(TrackItem item,Runnable changed) {
        if(stopped)return;
        TrackItem cached=completed.get(item.key());
        if(cached!=null){
            item.title=cached.title;item.artist=cached.artist;item.duration=cached.duration;
            if(cached.album!=null&&!cached.album.isEmpty())item.album=cached.album;
            // The LRU may have evicted the bitmap while the metadata stayed in memory. Reload the
            // already-cached cover from disk instead of leaving the row with the fallback artwork.
            if(images.get(item.key())==null&&!noCover.contains(item.key()))loadCachedCover(item,changed);
            return;
        }
        if(!requested.add(item.key()))return;
        worker.execute(()->{
            boolean success=false,retry=false;
            File meta=null,cover=null;
            try {
                String id=hex(item.key());meta=new File(directory,id+".json");cover=new File(directory,id+".jpg");
                if(meta.isFile()) {
                    try(FileInputStream in=new FileInputStream(meta)) {
                        JSONObject o=new JSONObject(new String(readBytes(in),java.nio.charset.StandardCharsets.UTF_8));
                        String cachedTitle=o.optString("title",""),cachedArtist=o.optString("artist","");
                        if(!cachedTitle.isEmpty())item.title=cachedTitle;
                        if(!cachedArtist.isEmpty())item.artist=cachedArtist;
                        String cachedAlbum=o.optString("album","");
                        if(!cachedAlbum.isEmpty()&&!isSchemeArtifact(cachedAlbum))item.album=cachedAlbum;
                        item.duration=o.optInt("duration",0);
                        item.hasCover=o.optBoolean("cover",cover.isFile());
                    }
                    if(cover.isFile()) {
                        Bitmap b=BitmapFactory.decodeFile(cover.getPath());
                        if(b!=null){images.put(item.key(),b);noCover.remove(item.key());item.hasCover=true;}
                    } else if(!item.hasCover) {
                        noCover.add(item.key());
                    }
                    success=true;
                } else {
                    synchronized(RETRIEVER_LOCK) {
                        try(MediaMetadataRetriever r=new MediaMetadataRetriever();MediaDataSource raw=item.nas.source(item.entry);
                            MediaDataSource bounded=new MediaDataSource(){long read;
                                public int readAt(long p,byte[] b,int off,int len)throws IOException { if(stopped || read>32*1024*1024)throw new IOException("Metadata read budget exceeded");int n=raw.readAt(p,b,off,len);if(n>0)read+=n;return n;}
                                public long getSize()throws IOException{return raw.getSize();} public void close(){}
                            }) {
                            r.setDataSource(bounded);
                            String title=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),artist=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),album=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),duration=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                            if(title!=null&&!title.trim().isEmpty())item.title=title;
                            if(artist!=null&&!artist.trim().isEmpty())item.artist=artist;
                            if(album!=null&&!album.trim().isEmpty())item.album=album;
                            if(duration!=null)item.duration=Integer.parseInt(duration);
                            byte[] bytes=r.getEmbeddedPicture();
                            item.hasCover=false;
                            if(bytes!=null&&bytes.length<=EMBEDDED_PICTURE_LIMIT){
                                BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
                                opt.inSampleSize=1;while(Math.max(opt.outWidth,opt.outHeight)/opt.inSampleSize>512)opt.inSampleSize*=2;
                                opt.inJustDecodeBounds=false;Bitmap b=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
                                if(b!=null){images.put(item.key(),b);noCover.remove(item.key());item.hasCover=true;try(FileOutputStream out=new FileOutputStream(cover)){b.compress(Bitmap.CompressFormat.JPEG,85,out);}}
                            }
                            if(images.get(item.key())==null&&!item.hasCover)noCover.add(item.key());
                        }
                    }
                    JSONObject o=new JSONObject();o.put("title",item.title);o.put("artist",item.artist);o.put("album",item.album);o.put("duration",item.duration);o.put("cover",item.hasCover);
                    try(FileOutputStream out=new FileOutputStream(meta)){out.write(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                    prune();
                    success=true;
                }
            } catch(OutOfMemoryError e) {
                // Never let a huge embedded picture or a big folder index kill the
                // process. Free what we can and keep the row with its filename.
                trimMemory();
                noCover.add(item.key());
                retry=false;
            } catch(Exception ignored) {
                // A missing/unsupported tag is not a network failure. Retry a real file a
                // few times so a transient SMB timeout does not leave a random blank cover.
                if(meta!=null&&meta.isFile())meta.delete();
                retry=item.entry.size>0&&attempts.merge(item.key(),1,Integer::sum)<3;
                if(!retry)noCover.add(item.key());
            }
            finally { requested.remove(item.key()); }
            if(success){
                attempts.remove(item.key());
                completed.put(item.key(),item);
                trimCompleted();
                remember(item);
            } else if(!retry){
                // Zero-byte/unsupported files stay cached so redraws do not retry forever.
                completed.put(item.key(),item);
                trimCompleted();
            }
            if(success&&!stopped&&changed!=null)changed.run();
            if(retry&&!stopped){
                try{saver.schedule(()->{if(!stopped)request(item,changed);},1500,TimeUnit.MILLISECONDS);}
                catch(Exception ignored) { }
            }
        });
    }
    private void loadCachedCover(TrackItem item,Runnable changed){
        if(stopped||noCover.contains(item.key())||!coverLoading.add(item.key()))return;
        worker.execute(()->{
            boolean loaded=false,reload=false;
            try {
                File cover=new File(directory,hex(item.key())+".jpg");
                if(cover.isFile()){
                    Bitmap b=BitmapFactory.decodeFile(cover.getPath());
                    if(b!=null){images.put(item.key(),b);loaded=true;}
                }
                if(!loaded){
                    // The disk cache may have pruned this cover. Re-extract it from the
                    // audio file instead of leaving a permanently blank row.
                    if(item.hasCover){
                        completed.remove(item.key());noCover.remove(item.key());
                        try{new File(directory,hex(item.key())+".json").delete();}catch(Exception ignored) { }
                        reload=true;
                    }
                    else noCover.add(item.key());
                }
            } catch(OutOfMemoryError e) {
                trimMemory();
                noCover.add(item.key());
                reload=false;
            } catch(Exception ignored) {
                if(item.hasCover){
                    completed.remove(item.key());
                    try{new File(directory,hex(item.key())+".json").delete();}catch(Exception ignored2) { }
                    reload=true;
                }
                else noCover.add(item.key());
            }
            finally { coverLoading.remove(item.key()); }
            if(loaded&&!stopped&&changed!=null)changed.run();
            if(reload&&!stopped)request(item,changed);
        });
    }
    private static byte[] readBytes(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}
    /** Bytes used by the cover and folder-index caches, for the settings screen. */
    long diskUsage(){
        long total=0;
        File[] covers=directory.listFiles();
        if(covers!=null)for(File f:covers)total+=f.length();
        File[] metas=folderDirectory.listFiles();
        if(metas!=null)for(File f:metas)total+=f.length();
        return total;
    }
    /** Frees memory under pressure; the on-disk folder index keeps re-opens instant. */
    void trimMemory(){
        images.evictAll();
        completed.clear();
        requested.clear();
        coverLoading.clear();
        noCover.clear();
        attempts.clear();
        folderArt=null;
    }
    /**
     * Drops the in-memory folder metadata after an out-of-memory event. The
     * index is already on disk (the periodic saver writes it), so re-opening
     * the folder still restores title/artist/album/duration without a NAS read.
     */
    void trimFolderIndex(){
        synchronized(folderMeta){
            folderMeta.clear();
            dirtyFolders.clear();
        }
    }
    private void trimCompleted(){
        if(completed.size()<=COMPLETED_LIMIT)return;
        completed.clear();
        noCover.clear();
        attempts.clear();
    }
    /** Drops every in-memory and on-disk cover/metadata entry without touching playback. */
    void clearCaches(){
        images.evictAll();
        completed.clear();
        requested.clear();
        noCover.clear();
        coverLoading.clear();
        synchronized(folderMeta){
            folderMeta.clear();
            dirtyFolders.clear();
        }
        folderArt=null;
        deleteChildren(directory);
        deleteChildren(folderDirectory);
    }
    private static void deleteChildren(File dir){
        File[] files=dir.listFiles();
        if(files==null)return;
        for(File f:files){
            if(f.isDirectory())deleteChildren(f);
            f.delete();
        }
    }
    /** Average cover colour, brightened a little, used to tint the ambient screen. */
    static int dominant(Bitmap bitmap){
        if(bitmap==null)return 0xff2c4764;
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        if(w<=0||h<=0)return 0xff2c4764;
        long r=0,g=0,b=0;int n=0;
        int stepX=Math.max(1,w/16),stepY=Math.max(1,h/16);
        for(int y=0;y<h;y+=stepY)for(int x=0;x<w;x+=stepX){
            int c=bitmap.getPixel(x,y);
            r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);n++;
        }
        if(n==0)return 0xff2c4764;
        float[] hsv=new float[3];
        Color.RGBToHSV((int)(r/n),(int)(g/n),(int)(b/n),hsv);
        hsv[1]=Math.max(0.32f,Math.min(0.72f,hsv[1]));
        hsv[2]=Math.max(0.42f,Math.min(0.62f,hsv[2]));
        return Color.HSVToColor(hsv);
    }
    /** Old caches stored a scheme prefix such as "smb:" as the album; ignore those values. */
    private static boolean isSchemeArtifact(String s){
        if(s==null)return false;
        String t=s.trim();
        if(t.length()<2||t.length()>12||t.charAt(t.length()-1)!=':')return false;
        for(int i=0;i<t.length()-1;i++){
            char c=Character.toLowerCase(t.charAt(i));
            if(!(c>='a'&&c<='z')&&!(c>='0'&&c<='9')&&c!='+'&&c!='-'&&c!='.')return false;
        }
        return true;
    }
    private void prune(){File[] files=directory.listFiles();if(files==null)return;long total=0;for(File f:files)total+=f.length();if(total<128L*1024*1024)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(File f:files){long size=f.length();if(f.delete())total-=size;if(total<96L*1024*1024)break;}}
    private String hex(String s)throws Exception {byte[] hash=MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:hash)b.append(String.format(Locale.ROOT,"%02x",v));return b.toString();}
    void close(){stopped=true;worker.shutdownNow();saveDirty();saver.shutdownNow();images.evictAll();}
}
