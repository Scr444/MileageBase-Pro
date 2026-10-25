package uk.mileagebase.app;

import android.app.Activity;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.IntentSender;
import androidx.annotation.RequiresApi;
import java.util.List;

/** Links the car's Bluetooth through Android's companion-device chooser, which also lets logging start in the background. */
@RequiresApi(26)
class CarLink {
    interface Done { void ok(); void fail(String why); }

    static CompanionDeviceManager cdm(Context c) { return (CompanionDeviceManager) c.getSystemService(Context.COMPANION_DEVICE_SERVICE); }

    static void choose(Activity act, int requestCode, Done done) {
        CompanionDeviceManager m = cdm(act);
        if (m == null) { done.fail("This phone can't link Bluetooth devices to apps."); return; }
        AssociationRequest req = new AssociationRequest.Builder()
                .addDeviceFilter(new BluetoothDeviceFilter.Builder().build())
                .setSingleDevice(false)
                .build();
        m.associate(req, new CompanionDeviceManager.Callback() {
            @Override public void onDeviceFound(IntentSender chooser) {
                try { act.startIntentSenderForResult(chooser, requestCode, null, 0, 0, 0); done.ok(); }
                catch (IntentSender.SendIntentException e) { done.fail("Could not open the device chooser."); }
            }
            @Override public void onFailure(CharSequence error) { done.fail(error == null ? "No devices found." : error.toString()); }
        }, null);
    }

    @SuppressWarnings("deprecation")
    static String cars(Context c) {
        CompanionDeviceManager m = cdm(c);
        if (m == null) return "";
        List<String> all = m.getAssociations();
        return android.text.TextUtils.join(",", all);
    }

    @SuppressWarnings("deprecation")
    static void forget(Context c) {
        CompanionDeviceManager m = cdm(c);
        if (m == null) return;
        for (String mac : m.getAssociations()) { try { m.disassociate(mac); } catch (Exception e) { /* ignore */ } }
    }
}
