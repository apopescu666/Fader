package com.fader.mobil.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * Local database of NAS folders (Kodi/Navidrome-style): the folder is read once
 * over the network, stored in SQLite, and from then on it opens instantly, also
 * after a restart. Refreshes update the rows in place.
 */
object NasStore {

    private const val DB_NAME = "nas-index.db"
    private const val VERSION = 1

    @Volatile
    private var helper: Helper? = null

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE folder (" +
                    "url TEXT PRIMARY KEY, name TEXT NOT NULL, parent TEXT NOT NULL, " +
                    "scanned_at INTEGER NOT NULL, cover_url TEXT)",
            )
            db.execSQL(
                "CREATE TABLE child_folder (" +
                    "url TEXT PRIMARY KEY, parent TEXT NOT NULL, name TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX idx_child_parent ON child_folder(parent)")
            db.execSQL(
                "CREATE TABLE track (" +
                    "url TEXT PRIMARY KEY, folder TEXT NOT NULL, name TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX idx_track_folder ON track(folder)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun db(context: Context): SQLiteDatabase {
        val current = helper ?: synchronized(this) {
            helper ?: Helper(context.applicationContext).also {
                helper = it
                // Old per-folder JSON indexes are replaced by this database.
                runCatching {
                    File(context.applicationContext.filesDir, "nas-index").deleteRecursively()
                }
            }
        }
        return current.writableDatabase
    }

    /** When this folder was last read from the NAS, or null if never. */
    fun scannedAt(context: Context, url: String): Long? = runCatching {
        db(context).rawQuery("SELECT scanned_at FROM folder WHERE url=?", arrayOf(url)).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }.getOrNull()

    /** Marks a folder as checked just now, without rewriting its rows. */
    fun touch(context: Context, url: String) {
        runCatching {
            db(context).execSQL(
                "UPDATE folder SET scanned_at=? WHERE url=?",
                arrayOf(System.currentTimeMillis(), url),
            )
        }
    }

    /** Replaces the stored contents of one folder with a fresh listing. */
    fun saveListing(context: Context, listing: Listing) {
        if (!listing.remote || listing.url.isBlank()) return
        val database = db(context)
        database.beginTransaction()
        try {
            database.delete("track", "folder=?", arrayOf(listing.url))
            database.delete("child_folder", "parent=?", arrayOf(listing.url))

            val trackStatement = database.compileStatement(
                "INSERT OR REPLACE INTO track (url, folder, name) VALUES (?, ?, ?)",
            )
            listing.tracks.forEach { track ->
                trackStatement.clearBindings()
                trackStatement.bindString(1, track.url)
                trackStatement.bindString(2, listing.url)
                trackStatement.bindString(3, track.name)
                trackStatement.executeInsert()
            }

            val folderStatement = database.compileStatement(
                "INSERT OR REPLACE INTO child_folder (url, parent, name) VALUES (?, ?, ?)",
            )
            listing.folders.forEach { folder ->
                folderStatement.clearBindings()
                folderStatement.bindString(1, folder.url)
                folderStatement.bindString(2, listing.url)
                folderStatement.bindString(3, folder.name)
                folderStatement.executeInsert()
            }

            val values = ContentValues().apply {
                put("url", listing.url)
                put("name", listing.name)
                put("parent", listing.url.substringBeforeLast('/', ""))
                put("scanned_at", System.currentTimeMillis())
                put("cover_url", listing.coverUrl ?: "")
            }
            database.insertWithOnConflict("folder", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Stored folder contents, or null when this folder was never read. */
    fun loadListing(context: Context, url: String): Listing? = runCatching {
        val database = db(context)
        val name: String
        val coverUrl: String
        database.rawQuery("SELECT name, cover_url FROM folder WHERE url=?", arrayOf(url)).use {
            if (!it.moveToFirst()) return null
            name = it.getString(0).orEmpty()
            coverUrl = it.getString(1).orEmpty()
        }

        val folders = ArrayList<FolderEntry>()
        database.rawQuery(
            "SELECT name, url FROM child_folder WHERE parent=? ORDER BY name",
            arrayOf(url),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                folders.add(
                    FolderEntry(
                        RemoteLibrary.decodeName(cursor.getString(0).orEmpty()),
                        cursor.getString(1).orEmpty(),
                    ),
                )
            }
        }

        val tracks = ArrayList<TrackEntry>()
        database.rawQuery(
            "SELECT name, url FROM track WHERE folder=? ORDER BY name",
            arrayOf(url),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val trackName = cursor.getString(0).orEmpty()
                val trackUrl = cursor.getString(1).orEmpty()
                if (trackUrl.isNotBlank()) tracks.add(remoteEntry(trackName, trackUrl, name))
            }
        }

        Listing(
            url = url,
            name = name,
            folders = folders,
            tracks = tracks,
            remote = true,
            coverUrl = coverUrl.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** Same mapping as the live WebDAV listing, rebuilt from the stored names. */
    private fun remoteEntry(rawName: String, url: String, folderName: String): TrackEntry {
        val name = RemoteLibrary.decodeName(rawName)
        val parent = url.substringBeforeLast('/', "")
        val parsed = MetaParser.fromName(name, parent.ifEmpty { url })
        return TrackEntry(
            name = name,
            title = if (parsed.nonEmptyTitle()) parsed.title else name.substringBeforeLast('.'),
            artist = if (parsed.nonEmptyArtist()) parsed.artist else "Unknown artist",
            album = if (parsed.nonEmptyAlbum()) parsed.album else folderName,
            durationMs = 0,
            url = url,
            artKey = "track:" + url,
            artUri = "remote:" + url,
        )
    }
}
