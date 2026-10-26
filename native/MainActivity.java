package uk.mileagebase.app;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TrackerPlugin.class);
        super.onCreate(savedInstanceState);
        keepAction(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        keepAction(intent);
    }

    /** A tap on the widget or a reminder: the app picks it up when it next checks. */
    static String incoming = null; // a backup file opened from Downloads or a share

    private void keepAction(Intent i) {
        if (i == null) return;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            try (java.io.InputStream in = getContentResolver().openInputStream(i.getData())) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536]; int n;
                while (in != null && (n = in.read(buf)) > 0 && out.size() < 30_000_000) out.write(buf, 0, n);
                incoming = out.toString("UTF-8");
            } catch (Exception e) { incoming = null; }
        }
        String a = i.getStringExtra("mbp_action");
        if (a != null) TrackingService.prefs(this).edit().putString("pendingAction", a).apply();
    }
}
