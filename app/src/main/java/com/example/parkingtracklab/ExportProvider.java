package com.example.parkingtracklab;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only, explicitly exported files in the app-owned sessions directory. */
public class ExportProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File resolve(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(name==null || !name.matches("track_[0-9]{8}_[0-9]{6}_(walk|drive)\\.csv")) throw new FileNotFoundException("非法文件名");
        File dir = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "sessions");
        File file=new File(dir,name);
        if(!file.isFile()) throw new FileNotFoundException(name);
        return file;
    }
    @Override public String getType(Uri uri) { return "text/csv"; }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("只读");
        return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri,String[] projection,String s,String[] sa,String order) {
        try {
            File f=resolve(uri);
            MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(),f.length()});
            return c;
        } catch(Exception ignored) { return null; }
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String s,String[] sa) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues v,String s,String[] sa) { throw new UnsupportedOperationException(); }
}
