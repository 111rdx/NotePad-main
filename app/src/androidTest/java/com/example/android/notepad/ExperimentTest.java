package com.example.android.notepad;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.widget.EditText;
import android.widget.PopupMenu;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Behavior checks against the actual provider, activities, loaders and export writer. */
public class ExperimentTest extends ActivityInstrumentationTestCase2<NotesList> {
    private final List<Uri> records = new ArrayList<>();
    private final String prefix = "experiment_" + System.nanoTime();
    private NoteEditor editor;

    public ExperimentTest() { super(NotesList.class); }
    private Uri insert(String title, String body) {
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, body);
        Uri uri = getActivity().getContentResolver().insert(NotePad.Notes.CONTENT_URI, values);
        records.add(uri);
        return uri;
    }
    private String read(Uri uri, String column) {
        try (Cursor cursor = getActivity().getContentResolver().query(uri,
                new String[]{column}, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        }
    }
    private void search(String value, int expected) throws Throwable {
        runTestOnUiThread(() -> ((EditText) getActivity().findViewById(R.id.search)).setText(value));
        long deadline = SystemClock.uptimeMillis() + 5000;
        final int[] count = {-1};
        do {
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> count[0] = getActivity().getListAdapter().getCount());
            if (count[0] == expected) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals("Search: " + value, expected, count[0]);
    }
    private void open(Uri uri, String action) {
        Intent intent = new Intent(getActivity(), NoteEditor.class).setAction(action).setData(uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        editor = (NoteEditor) getInstrumentation().startActivitySync(intent);
        getInstrumentation().waitForIdleSync();
    }
    private void choose(int id) {
        PopupMenu menu = new PopupMenu(editor, editor.findViewById(R.id.note));
        editor.onOptionsItemSelected(menu.getMenu().add(0, id, 0, "test"));
    }
    public void testSearchTitleBodyLiteralWildcardsAndEmpty() throws Throwable {
        insert(prefix + " title", "normal body");
        insert("body match", prefix + " body");
        insert("literal", prefix + "%_\\中文");
        search(prefix, 3);
        search(prefix + "%_\\中文", 1);
        search(prefix + "missing", 0);
        search(prefix, 3);
    }
    public void testSavingAndReopeningDoesNotChangeTimestamp() throws Throwable {
        Uri uri = insert(prefix, "before");
        String created = read(uri, NotePad.Notes.COLUMN_NAME_CREATE_DATE);
        open(uri, Intent.ACTION_EDIT);
        runTestOnUiThread(() -> {
            ((EditText) editor.findViewById(R.id.note_title)).setText(prefix + " edited");
            ((EditText) editor.findViewById(R.id.note)).setText("中文正文\nsecond line");
            choose(R.id.menu_save);
        });
        getInstrumentation().waitForIdleSync();
        assertEquals("中文正文\nsecond line", read(uri, NotePad.Notes.COLUMN_NAME_NOTE));
        assertEquals(created, read(uri, NotePad.Notes.COLUMN_NAME_CREATE_DATE));
        String modified = read(uri, NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE);
        open(uri, Intent.ACTION_EDIT);
        runTestOnUiThread(() -> editor.finish());
        getInstrumentation().waitForIdleSync();
        assertEquals(modified, read(uri, NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE));
    }
    public void testNewNoteRecreationKeepsSingleRecord() throws Throwable {
        open(NotePad.Notes.CONTENT_URI, Intent.ACTION_INSERT);
        runTestOnUiThread(() -> {
            ((EditText) editor.findViewById(R.id.note_title)).setText(prefix);
            ((EditText) editor.findViewById(R.id.note)).setText("rotation content");
        });
        Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(
                NoteEditor.class.getName(), null, false);
        runTestOnUiThread(() -> editor.recreate());
        NoteEditor recreated = (NoteEditor) getInstrumentation().waitForMonitorWithTimeout(monitor, 5000);
        getInstrumentation().removeMonitor(monitor);
        assertNotNull(recreated);
        editor = recreated;
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            assertEquals(prefix, ((EditText) editor.findViewById(R.id.note_title)).getText().toString());
            assertEquals("rotation content", ((EditText) editor.findViewById(R.id.note)).getText().toString());
            editor.finish();
        });
        getInstrumentation().waitForIdleSync();
        try (Cursor cursor = getActivity().getContentResolver().query(NotePad.Notes.CONTENT_URI,
                new String[]{NotePad.Notes._ID}, "title = ?", new String[]{prefix}, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            records.add(android.content.ContentUris.withAppendedId(NotePad.Notes.CONTENT_URI, cursor.getLong(0)));
        }
    }
    public void testExportWritesChineseAndLineBreaksAsUtf8() throws Throwable {
        Uri uri = insert(prefix + " 中文", "第一行\n第二行 %_\\");
        open(uri, Intent.ACTION_EDIT);
        File output = new File(getActivity().getCacheDir(), "experiment-export.txt");
        Intent result = new Intent().setData(Uri.fromFile(output));
        IntentFilter filter = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        filter.addDataType("text/plain");
        Instrumentation.ActivityMonitor picker = getInstrumentation().addMonitor(
                filter,
                new Instrumentation.ActivityResult(Activity.RESULT_OK, result), true);
        try {
            runTestOnUiThread(() -> choose(R.id.menu_export));
            long deadline = SystemClock.uptimeMillis() + 5000;
            while ((!output.exists() || output.length() == 0) && SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(50);
            assertTrue("TXT must be written", output.exists());
            try (FileInputStream input = new FileInputStream(output)) {
                byte[] bytes = new byte[(int) output.length()];
                assertEquals(bytes.length, input.read(bytes));
                String text = new String(bytes, StandardCharsets.UTF_8);
                assertTrue(text.startsWith(prefix + " 中文\n更新于 "));
                assertTrue(text.endsWith("\n\n第一行\n第二行 %_\\"));
            }
        } finally {
            getInstrumentation().removeMonitor(picker);
            output.delete();
        }
    }
    public void testClipboardNotePasteKeepsTitleAndBody() throws Throwable {
        Uri source = insert(prefix, "粘贴内容\nsecond line");
        runTestOnUiThread(() -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getActivity().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newUri(
                    getActivity().getContentResolver(), "Note", source));
        });
        open(NotePad.Notes.CONTENT_URI, Intent.ACTION_PASTE);
        runTestOnUiThread(() -> {
            assertEquals(prefix, ((EditText) editor.findViewById(R.id.note_title)).getText().toString());
            assertEquals("粘贴内容\nsecond line", ((EditText) editor.findViewById(R.id.note)).getText().toString());
            choose(R.id.menu_save);
        });
        getInstrumentation().waitForIdleSync();
        try (Cursor cursor = getActivity().getContentResolver().query(NotePad.Notes.CONTENT_URI,
                new String[]{NotePad.Notes._ID}, "title = ?", new String[]{prefix}, null)) {
            assertEquals(2, cursor.getCount());
            while (cursor.moveToNext()) records.add(android.content.ContentUris.withAppendedId(
                    NotePad.Notes.CONTENT_URI, cursor.getLong(0)));
        }
    }
    public void testBlankNewNoteIsDiscardedOnExit() throws Throwable {
        int before;
        try (Cursor cursor = getActivity().getContentResolver().query(NotePad.Notes.CONTENT_URI,
                new String[]{NotePad.Notes._ID}, null, null, null)) { before = cursor.getCount(); }
        open(NotePad.Notes.CONTENT_URI, Intent.ACTION_INSERT);
        runTestOnUiThread(() -> editor.finish());
        getInstrumentation().waitForIdleSync();
        try (Cursor cursor = getActivity().getContentResolver().query(NotePad.Notes.CONTENT_URI,
                new String[]{NotePad.Notes._ID}, null, null, null)) { assertEquals(before, cursor.getCount()); }
    }
    public void testBodyOnlyNoteUsesFirstLineAsTitle() throws Throwable {
        open(NotePad.Notes.CONTENT_URI, Intent.ACTION_INSERT);
        runTestOnUiThread(() -> {
            ((EditText) editor.findViewById(R.id.note)).setText(prefix + "\n正文第二行");
            choose(R.id.menu_save);
        });
        getInstrumentation().waitForIdleSync();
        try (Cursor cursor = getActivity().getContentResolver().query(NotePad.Notes.CONTENT_URI,
                new String[]{NotePad.Notes._ID, NotePad.Notes.COLUMN_NAME_TITLE}, "note = ?",
                new String[]{prefix + "\n正文第二行"}, null)) {
            assertEquals(1, cursor.getCount());
            cursor.moveToFirst();
            records.add(android.content.ContentUris.withAppendedId(NotePad.Notes.CONTENT_URI, cursor.getLong(0)));
            assertEquals(prefix.substring(0, Math.min(prefix.length(), 30)), cursor.getString(1));
        }
    }
    @Override protected void tearDown() throws Exception {
        try {
            if (editor != null) {
                getInstrumentation().runOnMainSync(() -> { if (!editor.isFinishing()) editor.finish(); });
                getInstrumentation().waitForIdleSync();
            }
            for (Uri uri : records) getActivity().getContentResolver().delete(uri, null, null);
        } finally { super.tearDown(); }
    }
}


