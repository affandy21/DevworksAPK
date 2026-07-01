package co.id.devworks.attendance;

import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class DevworksFcmTokenSender {
    private static final String TOKEN_URL = "https://devworks.co.id/api/attendance/fcm-token";

    private DevworksFcmTokenSender() {
    }

    static void send(String token) {
        if (token == null || token.isBlank()) return;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String cookies = CookieManager.getInstance().getCookie("https://devworks.co.id");
                if (cookies == null || cookies.isBlank()) return;
                JSONObject payload = new JSONObject();
                payload.put("token", token);
                payload.put("platform", "android");
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                connection = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Cookie", cookies);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                connection.getResponseCode();
            } catch (Exception ignored) {
                // Token registration is retried on the next app/page load.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "devworks-fcm-token").start();
    }
}
