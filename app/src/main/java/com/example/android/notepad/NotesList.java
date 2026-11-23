/* Copyright (C) 2007 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0. */
package com.example.android.notepad;

import android.app.ListActivity;
import android.app.LoaderManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** CursorLoader owns cursors and runs searches off the UI thread. */
public class NotesList extends ListActivity implements LoaderManager.LoaderCallbacks<Cursor> {
    private static final String[] PROJECTION = {NotePad.Notes._ID,
            NotePad.Notes.COLUMN_NAME_TITLE, NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
            NotePad.Notes.COLUMN_NAME_NOTE};
    private SimpleCursorAdapter adapter;
    private EditText search;
    private TextView empty;
    private String keyword = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.notes_list);
        setTitle(R.string.title_notes_list);
        if (getIntent().getData() == null) getIntent().setData(NotePad.Notes.CONTENT_URI);
        search = findViewById(R.id.search);
        empty = findViewById(android.R.id.empty);
        if (state != null) keyword = state.getString("keyword", "");
        search.setText(keyword);
        adapter = new SimpleCursorAdapter(this, R.layout.noteslist_item, null,
                new String[]{NotePad.Notes.COLUMN_NAME_TITLE,
                        NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, NotePad.Notes.COLUMN_NAME_NOTE},
                new int[]{android.R.id.text1, R.id.note_time, R.id.note_preview}, 0);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        adapter.setViewBinder((view, cursor, column) -> {
            if (view.getId() == R.id.note_time) {
                ((TextView) view).setText(getString(R.string.modified_at,
                        format.format(new Date(cursor.getLong(column)))));
                return true;
            }
            return false;
        });
        setListAdapter(adapter);
        registerForContextMenu(getListView());
        findViewById(R.id.add_note).setOnClickListener(v -> openNew(Intent.ACTION_INSERT));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                keyword = s.toString();
                getLoaderManager().restartLoader(0, null, NotesList.this);
            }
            public void afterTextChanged(Editable s) {}
        });
        getLoaderManager().initLoader(0, null, this);
    }
    private void openNew(String action) {
        startActivity(new Intent(this, NoteEditor.class).setAction(action)
                .setData(getIntent().getData()));
    }
    @Override public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String selection = null;
        String[] values = null;
        if (!keyword.isEmpty()) {
            // Escape LIKE wildcards so %, _ and backslash are searched literally.
            String pattern = "%" + keyword.replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_") + "%";
            selection = "(" + NotePad.Notes.COLUMN_NAME_TITLE + " LIKE ? ESCAPE '\\' OR "
                    + NotePad.Notes.COLUMN_NAME_NOTE + " LIKE ? ESCAPE '\\')";
            values = new String[]{pattern, pattern};
        }
        return new CursorLoader(this, getIntent().getData(), PROJECTION, selection, values,
                NotePad.Notes.DEFAULT_SORT_ORDER + ", _id DESC");
    }
    @Override public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        adapter.swapCursor(cursor);
        empty.setText(keyword.isEmpty() ? R.string.empty_notes : R.string.empty_search);
        ((TextView) findViewById(R.id.note_count)).setText(
                getString(R.string.note_count, cursor == null ? 0 : cursor.getCount()));
    }
    @Override public void onLoaderReset(Loader<Cursor> loader) { adapter.swapCursor(null); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("keyword", keyword);
        super.onSaveInstanceState(out);
    }
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.list_options_menu, menu);
        return true;
    }
    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        menu.findItem(R.id.menu_paste).setEnabled(clipboard.hasPrimaryClip());
        return super.onPrepareOptionsMenu(menu);
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_add) { openNew(Intent.ACTION_INSERT); return true; }
        if (item.getItemId() == R.id.menu_paste) { openNew(Intent.ACTION_PASTE); return true; }
        return super.onOptionsItemSelected(item);
    }
    @Override protected void onListItemClick(ListView list, View view, int position, long id) {
        Uri uri = ContentUris.withAppendedId(getIntent().getData(), id);
        String action = getIntent().getAction();
        if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {
            setResult(RESULT_OK, new Intent().setData(uri));
            finish();
        } else {
            startActivity(new Intent(this, NoteEditor.class).setAction(Intent.ACTION_EDIT).setData(uri));
        }
    }
    @Override public void onCreateContextMenu(ContextMenu menu, View view,
            ContextMenu.ContextMenuInfo info) {
        super.onCreateContextMenu(menu, view, info);
        getMenuInflater().inflate(R.menu.list_context_menu, menu);
        Cursor cursor = (Cursor) adapter.getItem(((AdapterView.AdapterContextMenuInfo) info).position);
        menu.setHeaderTitle(cursor.getString(1));
    }
    @Override public boolean onContextItemSelected(MenuItem item) {
        if (!(item.getMenuInfo() instanceof AdapterView.AdapterContextMenuInfo))
            return super.onContextItemSelected(item);
        long id = ((AdapterView.AdapterContextMenuInfo) item.getMenuInfo()).id;
        Uri uri = ContentUris.withAppendedId(getIntent().getData(), id);
        if (item.getItemId() == R.id.context_open) {
            startActivity(new Intent(this, NoteEditor.class).setAction(Intent.ACTION_EDIT).setData(uri));
        } else if (item.getItemId() == R.id.context_copy) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), "Note", uri));
            Toast.makeText(this, R.string.note_copied, Toast.LENGTH_SHORT).show();
        } else if (item.getItemId() == R.id.context_delete) {
            new android.app.AlertDialog.Builder(this).setMessage(R.string.confirm_delete)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.menu_delete,
                            (dialog, which) -> getContentResolver().delete(uri, null, null)).show();
        } else return super.onContextItemSelected(item);
        return true;
    }
}
