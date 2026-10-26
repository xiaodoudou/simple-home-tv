package com.simplehometv;

import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

/**
 * Restarts Simple Home after the firmware force-stops it, to put the Home button service back. Android keeps a
 * notification listener's approval through a force-stop and binds it again shortly after, which an accessibility
 * service does not get. No notification is read. Allowed over adb with cmd notification allow_listener.
 */
public class RevivalListener extends NotificationListenerService {

    @Override public void onListenerConnected() {
        HomeButton.ensureOn(this);
        new Handler(Looper.getMainLooper()).postDelayed(() -> HomeButton.rebindIfStalled(this), 5_000);
    }
}
