/* Copyright (C) 2007 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. */
package com.example.android.notepad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/** Autosaves changed notes and exports UTF-8 snapshots through the system picker. */
public class NoteEditor extends Activity {
    private static final int EXPORT_TEXT = 10;
    private Uri uri;
    private EditText title;
    private EditText body;
    private String savedTitle = "";
    private String savedBody = "";
    private String exportText;
    private boolean deleted;
    private boolean newNote;
    private boolean pendingPaste;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.note_editor);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        title = findViewById(R.id.note_title);
        body = findViewById(R.id.note);
        if (state != null) {
            uri = Uri.parse(state.getString("uri"));
            newNote = state.getBoolean("newNote");
            exportText = state.getString("exportText");
            pendingPaste = state.getBoolean("pendingPaste");
        } else {
            String action = getIntent().getAction();
            newNote = Intent.ACTION_INSERT.equals(action) || Intent.ACTION_PASTE.equals(action);
            uri = newNote ? getContentResolver().insert(NotePad.Notes.CONTENT_URI, null)
                    : getIntent().getData();
        }
        if (uri == null || !loadNote()) { finish(); return; }
        if (state == null) pendingPaste = Intent.ACTION_PASTE.equals(getIntent().getAction());
        setTitle(newNote ? R.string.title_create : R.string.title_edit_title);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Android 10+ only allows foreground, focused apps to read the clipboard.
        if (hasFocus && pendingPaste) {
            pendingPaste = false;
            pasteNote();
        }
    }
    private boolean loadNote() {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{
                NotePad.Notes.COLUMN_NAME_TITLE, NotePad.Notes.COLUMN_NAME_NOTE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            savedTitle = cursor.getString(0);
            savedBody = cursor.getString(1);
            title.setText(newNote && savedBody.isEmpty() ? "" : savedTitle);
            body.setText(savedBody);
            return true;
        }
    }
    private void pasteNote() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        ClipData.Item item = clip.getItemAt(0);
        Uri source = item.getUri();
        if (source != null && NotePad.Notes.CONTENT_ITEM_TYPE.equals(getContentResolver().getType(source))) {
            try (Cursor cursor = getContentResolver().query(source, new String[]{
                    NotePad.Notes.COLUMN_NAME_TITLE, NotePad.Notes.COLUMN_NAME_NOTE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    title.setText(cursor.getString(0));
                    body.setText(cursor.getString(1));
                    return;
                }
            }
        }
        body.setText(item.coerceToText(this));
    }
    private boolean saveNote() {
        if (uri == null || deleted) return false;
        String text = body.getText().toString();
        String heading = title.getText().toString().trim();
        if (heading.isEmpty() && text.trim().isEmpty() && newNote) return false;
        if (heading.isEmpty()) {
            String firstLine = text.trim().split("\\r?\\n", 2)[0];
            heading = firstLine.isEmpty() ? getString(R.string.untitled_note)
                    : firstLine.substring(0, Math.min(firstLine.length(), 30));
            title.setText(heading);
        }
        if (!Objects.equals(heading, savedTitle) || !Objects.equals(text, savedBody)) {
            ContentValues values = new ContentValues();
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, heading);
            values.put(NotePad.Notes.COLUMN_NAME_NOTE, text);
            values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());
            if (getContentResolver().update(uri, values, null, null) != 1) return false;
            savedTitle = heading;
            savedBody = text;
        }
        newNote = false;
        return true;
    }
    @Override protected void onPause() {
        if (!deleted && uri != null) {
            saveNote();
            // Only discard untouched new placeholders, never existing notes.
            if (isFinishing() && newNote && title.getText().toString().trim().isEmpty()
                    && body.getText().toString().trim().isEmpty()) {
                getContentResolver().delete(uri, null, null);
                deleted = true;
            }
        }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (uri != null) out.putString("uri", uri.toString());
        out.putBoolean("newNote", newNote);
        out.putString("exportText", exportText);
        out.putBoolean("pendingPaste", pendingPaste);
        super.onSaveInstanceState(out);
    }
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.editor_options_menu, menu);
        return true;
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) { finish(); return true; }
        if (id == R.id.menu_save) {
            if (saveNote()) finish();
            else Toast.makeText(this, R.string.nothing_to_save, Toast.LENGTH_SHORT).show();
        } else if (id == R.id.menu_delete) {
            new AlertDialog.Builder(this).setMessage(R.string.confirm_delete)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.menu_delete, (dialog, which) -> {
                        getContentResolver().delete(uri, null, null);
                        deleted = true;
                        finish();
                    }).show();
        } else if (id == R.id.menu_revert) {
            title.setText(newNote ? "" : savedTitle);
            body.setText(savedBody);
        } else if (id == R.id.menu_export) {
            exportNote();
        } else return super.onOptionsItemSelected(item);
        return true;
    }
    private void exportNote() {
        if (!saveNote()) {
            Toast.makeText(this, R.string.nothing_to_save, Toast.LENGTH_SHORT).show();
            return;
        }
        long modified;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            modified = cursor.getLong(0);
        }
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(modified));
        exportText = savedTitle + "\n" + getString(R.string.modified_at, time) + "\n\n" + savedBody;
        String filename = savedTitle.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        filename = filename.substring(0, Math.min(filename.length(), 60)) + ".txt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, filename);
        try { startActivityForResult(intent, EXPORT_TEXT); }
        catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.export_unavailable, Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_TEXT) return;
        if (result == RESULT_OK && data != null && data.getData() != null && exportText != null) {
            final Uri destination = data.getData();
            final String snapshot = exportText;
            // A document provider may be remote, so write off the UI thread.
            new Thread(() -> {
                int message = R.string.export_success;
                try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
                    if (stream == null) throw new IOException("No output stream");
                    try (OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
                        writer.write(snapshot);
                    }
                } catch (IOException | SecurityException e) { message = R.string.export_failed; }
                final int feedback = message;
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), feedback, Toast.LENGTH_LONG).show());
            }, "note-export").start();
        }
        exportText = null;
    }
}

