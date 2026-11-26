package org.atree.survey;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;

final class Visits extends SQLiteOpenHelper {
  Visits(Context c) {
    super(c, "fieldbook.db", null, 1);
  }

  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE visits(id TEXT PRIMARY KEY, at INTEGER NOT NULL, meta TEXT NOT NULL, photo"
            + " TEXT NOT NULL, mask TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0)");
  }

  public void onUpgrade(SQLiteDatabase db, int from, int to) {
    throw new IllegalStateException("Unexpected database version");
  }

  void save(JSONObject meta, String photo, String mask) throws JSONException {
    ContentValues row = new ContentValues();
    row.put("id", meta.getString("id"));
    row.put("at", meta.getLong("at"));
    row.put("meta", meta.toString());
    row.put("photo", photo);
    row.put("mask", mask);
    getWritableDatabase().insertOrThrow("visits", null, row);
  }

  JSONArray list() throws JSONException {
    JSONArray out = new JSONArray();
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT meta,synced FROM visits ORDER BY at DESC", null)) {
      while (c.moveToNext())
        out.put(new JSONObject(c.getString(0)).put("synced", c.getInt(1) == 1));
    }
    return out;
  }

  JSONObject read(String id) throws JSONException {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT meta,photo,mask FROM visits WHERE id=?", new String[] {id})) {
      return c.moveToFirst()
          ? new JSONObject(c.getString(0)).put("photo", c.getString(1)).put("mask", c.getString(2))
          : null;
    }
  }

  String pending() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT id FROM visits WHERE synced=0 ORDER BY at LIMIT 1", null)) {
      return c.moveToFirst() ? c.getString(0) : null;
    }
  }

  void acknowledged(String id) {
    getWritableDatabase().execSQL("UPDATE visits SET synced=1 WHERE id=?", new Object[] {id});
  }
}
