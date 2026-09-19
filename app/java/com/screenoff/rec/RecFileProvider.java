package com.screenoff.rec;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** 轻量只读文件提供器：仅供系统播放器通过 content:// 读取 /sdcard/Rec/ 下的本应用媒体文件 */
public class RecFileProvider extends ContentProvider {

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/")) throw new FileNotFoundException();
        if (!(name.startsWith("VID_") || name.startsWith("REC_"))) throw new FileNotFoundException();
        File dir = new File(Environment.getExternalStorageDirectory(), "Rec");
        File target = new File(dir, name);
        if (!target.exists()) throw new FileNotFoundException();
        try {
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            throw new FileNotFoundException();
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        return n != null && n.endsWith(".mp4") ? "video/mp4" : "audio/mp4";
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
