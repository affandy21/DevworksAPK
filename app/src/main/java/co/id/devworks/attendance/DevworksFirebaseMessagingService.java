package co.id.devworks.attendance;

import com.google.firebase.messaging.FirebaseMessagingService;

public final class DevworksFirebaseMessagingService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        DevworksFcmTokenSender.send(token);
    }
}
