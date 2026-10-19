package com.copymore.android;

import android.app.Activity;
import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;

/** Test APK only; deliberately uses only platform APIs so it runs under its own UID. */
public class PasteProbeActivity extends Activity {
    private boolean checked;
    private TextView label;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); label = new TextView(this); label.setText("CopyMore 跨应用粘贴验证");
        label.setTextSize(22); label.setPadding(32, 80, 32, 32); setContentView(label);
    }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus); if (!focus || checked) return; checked = true;
        JSONObject result = new JSONObject();
        try {
            result.put("nonce", getIntent().getStringExtra("nonce"));
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            String mode = getIntent().getStringExtra("mode");
            if ("set_text".equals(mode)) clipboard.setPrimaryClip(ClipData.newPlainText("CopyMore test", getIntent().getStringExtra("expected")));
            else if ("set_uri".equals(mode)) {
                String name = getIntent().getStringExtra("file");
                check("fixture.png".equals(name) || "fixture.pdf".equals(name), "Invalid fixture");
                clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), name, Uri.parse("content://com.copymore.android.test.fixtures/" + name)));
            } else if ("share_uri".equals(mode)) {
                String name = getIntent().getStringExtra("file");
                check("fixture.png".equals(name) || "fixture.pdf".equals(name), "Invalid fixture");
                Uri uri = Uri.parse("content://com.copymore.android.test.fixtures/" + name);
                android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
                share.setPackage("com.copymore.android"); share.setType(getContentResolver().getType(uri));
                share.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                share.setClipData(ClipData.newUri(getContentResolver(), name, uri));
                share.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(share);
            } else if ("verify_text".equals(mode)) {
                ClipData clip = clipboard.getPrimaryClip();
                check(clip != null && getIntent().getStringExtra("expected").contentEquals(clip.getItemAt(0).getText()), "Text mismatch");
            } else if ("verify_uri".equals(mode)) {
                ClipData clip = clipboard.getPrimaryClip(); check(clip != null, "Clipboard empty");
                Uri uri = clip.getItemAt(0).getUri(); check(uri != null, "Clipboard has no URI");
                String mime = getContentResolver().getType(uri);
                check(getIntent().getStringExtra("mime").equals(mime), "MIME mismatch");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    check(input != null, "URI unreadable"); byte[] bytes = new byte[65536]; int n;
                    while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n);
                }
                StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format("%02x", b));
                check(hash.toString().equals(getIntent().getStringExtra("sha256")), "Content mismatch");
                result.put("mime", mime); result.put("crossAppUriReadable", true);
            } else throw new IllegalArgumentException("Unknown test");
            result.put("passed", true);
        } catch (Exception failure) { try { result.put("passed", false); result.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); } catch (Exception ignored) {} }
        try (FileOutputStream output = openFileOutput("paste-probe-result.json", MODE_PRIVATE)) { output.write(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        label.setText(result.optBoolean("passed") ? "验证通过" : "验证失败：" + result.optString("error"));
    }
}
