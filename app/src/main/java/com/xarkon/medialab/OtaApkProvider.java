package com.xarkon.medialab;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Exposes exactly one verified update APK to the system package installer. */
public final class OtaApkProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    private File candidate(Uri uri) throws FileNotFoundException {
        if (uri == null || !"/apk".equals(uri.getPath())) throw new FileNotFoundException("Unsupported URI");
        File f = OtaUpdater.file(getContext());
        if (!f.isFile()) throw new FileNotFoundException("Update absent");
        return f;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        return ParcelFileDescriptor.open(candidate(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try {
            File file=candidate(uri);
            String[] columns=projection!=null?projection:new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
            MatrixCursor result=new MatrixCursor(columns);
            Object[] values=new Object[columns.length];
            for(int i=0;i<columns.length;i++){
                if(OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i]="MediaLAB-Update.apk";
                else if(OpenableColumns.SIZE.equals(columns[i])) values[i]=file.length();
            }
            result.addRow(values);
            return result;
        } catch(Exception ignored){ return null; }
    }
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
    @Override public int delete(Uri u,String s,String[] a){return 0;}
}
