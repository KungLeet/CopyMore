package com.copymore.android;
import android.content.*;
import android.net.Uri;
import android.database.*;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
public class PasteProbeProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (!"fixture.png".equals(name) && !"fixture.pdf".equals(name)) throw new IllegalArgumentException("Invalid fixture");
        return new File(getContext().getFilesDir(), name);
    }
    public String getType(Uri uri) { return file(uri).getName().endsWith("png") ? "image/png" : "application/pdf"; }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f=file(uri); String[] columns=projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor=new MatrixCursor(columns); Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++) row[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? f.getName() : OpenableColumns.SIZE.equals(columns[i]) ? f.length() : null;
        cursor.addRow(row); return cursor;
    }
    public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    public int delete(Uri uri,String selection,String[] args){return 0;}
    public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
}
