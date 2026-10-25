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
    private void keepAction(Intent i) {
        if (i == null) return;
        String a = i.getStringExtra("mbp_action");
        if (a != null) TrackingService.prefs(this).edit().putString("pendingAction", a).apply();
    }
}
