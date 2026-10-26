package com.simplehometv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Lets the system installer read an APK that {@link Installer} downloaded into the cache. */
public class ApkProvider extends ContentProvider {

    static final String AUTHORITY = "com.simplehometv.apk";

    static Uri uri(File apk) {
        return Uri.parse("content://" + AUTHORITY + "/" + apk.getName());
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(Installer.dir(getContext()), new File(uri.getPath()).getName());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override public Uri insert(Uri u, ContentValues v) {
        return null;
    }

    @Override public int delete(Uri u, String s, String[] a) {
        return 0;
    }

    @Override public int update(Uri u, ContentValues v, String s, String[] a) {
        return 0;
    }
}
