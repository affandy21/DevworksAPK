package co.id.devworks.attendance;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public final class DevworksFirebaseMessagingService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        DevworksFcmTokenSender.send(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        RemoteMessage.Notification notification = message.getNotification();
        Map<String, String> data = message.getData();
        String title = notification != null ? notification.getTitle() : data.get("title");
        String body = notification != null ? notification.getBody() : data.get("body");
        showAttendanceNotification(
            title == null || title.isBlank() ? "Devworks" : title,
            body == null || body.isBlank() ? "Ada notifikasi absensi baru." : body,
            data.get("linkUrl")
        );
    }

    private void showAttendanceNotification(String title, String body, String linkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent intent = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String link = normalizeLink(linkUrl);
        if (!link.isBlank()) intent.setData(Uri.parse(link));
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            (int) System.currentTimeMillis(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, MainActivity.ATTENDANCE_NOTIFICATION_CHANNEL)
            : new Notification.Builder(this);
        Notification notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify((int) System.currentTimeMillis(), notification);
    }

    private String normalizeLink(String linkUrl) {
        if (linkUrl == null || linkUrl.isBlank()) return "https://devworks.co.id/client/dashboard/attendance";
        String value = linkUrl.trim();
        if (value.startsWith("https://devworks.co.id/")) return value;
        if (value.startsWith("/")) return "https://devworks.co.id" + value;
        return "https://devworks.co.id/client/dashboard/attendance";
    }
}
