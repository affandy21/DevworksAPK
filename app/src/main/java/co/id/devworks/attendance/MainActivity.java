package co.id.devworks.attendance;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.window.OnBackInvokedDispatcher;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.browser.customtabs.CustomTabColorSchemeParams;
import androidx.browser.customtabs.CustomTabsIntent;

import com.google.firebase.messaging.FirebaseMessaging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity implements LocationListener {
    private static final String LOGIN_URL = "https://devworks.co.id/login";
    private static final String OAUTH_START_PATH = "/api/auth/oauth/";
    private static final String OAUTH_APP_CALLBACK_PATH = "/app/oauth-complete";
    private static final String OAUTH_MOBILE_COMPLETE_URL = "https://devworks.co.id/api/auth/oauth/mobile/complete";
    private static final String STATE_PAGE_LOAD_FAILED = "page_load_failed";
    private static final int LOCATION_REQUEST = 1101;
    private static final int CAMERA_REQUEST = 1102;
    private static final int FILE_CHOOSER_REQUEST = 1103;
    private static final int NOTIFICATION_REQUEST = 1104;
    static final String ATTENDANCE_NOTIFICATION_CHANNEL = "attendance";
    private static final long EXIT_CONFIRM_WINDOW_MS = 2_000L;
    private static final long MOCK_CLEAR_DELAY_MS = 10_000L;
    private static final int REAL_FIXES_TO_CLEAR = 3;
    private static final long MAX_PDF_BYTES = 50L * 1024L * 1024L;
    private static final Pattern PDF_FILENAME_PATTERN = Pattern.compile("filename\\*?=(?:UTF-8''|\\\")?([^\\\";]+)", Pattern.CASE_INSENSITIVE);

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout messagePanel;
    private TextView messageTitle;
    private TextView messageBody;
    private Button messageButton;
    private LocationManager locationManager;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private PermissionRequest pendingCameraRequest;
    private ValueCallback<Uri[]> pendingFileChooser;
    private FrameLayout pdfOverlay;
    private FrameLayout pdfContent;
    private TextView pdfTitle;
    private TextView pdfPageLabel;
    private Button pdfSaveButton;
    private Button pdfPreviousButton;
    private Button pdfNextButton;
    private ImageView pdfImage;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfDescriptor;
    private Bitmap pdfBitmap;
    private File pdfFile;
    private String pdfFileName;
    private int pdfPageIndex;
    private int pdfRequestId;
    private boolean pageLoadFailed;
    private boolean mockLocationBlocked;
    private long lastBackPressedAt;
    private long lastMockAt;
    private int consecutiveRealFixes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        buildInterface();
        configureWebView();
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                this::handleBackNavigation
            );
        }
        if (handleOAuthCallback(getIntent())) return;
        if (handleNotificationLink(getIntent())) return;
        if (handleNotificationExtras(getIntent())) return;
        boolean restoredFailure = savedInstanceState != null
            && savedInstanceState.getBoolean(STATE_PAGE_LOAD_FAILED, false);
        if (restoredFailure || !isOnline()) {
            pageLoadFailed = true;
            showConnectionError();
        } else if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(LOGIN_URL);
        }
    }

    private void buildInterface() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        // Keep Chromium's built-in network error page out of sight on cold start.
        webView.setVisibility(View.INVISIBLE);
        root.addView(webView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(3)
        );
        progressParams.gravity = Gravity.TOP;
        root.addView(progressBar, progressParams);

        messagePanel = new LinearLayout(this);
        messagePanel.setOrientation(LinearLayout.VERTICAL);
        messagePanel.setGravity(Gravity.CENTER);
        messagePanel.setPadding(dp(28), dp(28), dp(28), dp(28));
        messagePanel.setBackgroundColor(Color.rgb(242, 246, 244));
        messagePanel.setVisibility(View.GONE);

        messageTitle = new TextView(this);
        messageTitle.setGravity(Gravity.CENTER);
        messageTitle.setTextColor(Color.rgb(23, 35, 30));
        messageTitle.setTextSize(21);
        messageTitle.setTypeface(messageTitle.getTypeface(), android.graphics.Typeface.BOLD);
        messagePanel.addView(messageTitle, matchWrap());

        messageBody = new TextView(this);
        messageBody.setGravity(Gravity.CENTER);
        messageBody.setTextColor(Color.rgb(91, 108, 101));
        messageBody.setTextSize(14);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(10);
        messagePanel.addView(messageBody, bodyParams);

        messageButton = new Button(this);
        messageButton.setAllCaps(false);
        messageButton.setTextColor(Color.WHITE);
        messageButton.setTextSize(14);
        messageButton.setBackgroundColor(Color.rgb(8, 122, 91));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(210), dp(48));
        buttonParams.topMargin = dp(22);
        messagePanel.addView(messageButton, buttonParams);

        root.addView(messagePanel, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUserAgentString(settings.getUserAgentString() + " DevworksAttendance/1.0 Android");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new SecureWebViewClient());
        webView.setWebChromeClient(new AttendanceChromeClient());
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            boolean pdf = "application/pdf".equalsIgnoreCase(mimeType)
                || (contentDisposition != null && contentDisposition.toLowerCase(Locale.ROOT).contains(".pdf"))
                || isPdfUrl(Uri.parse(url));
            if (pdf) openPdfInApp(url, contentDisposition);
            else openExternal(url);
        });
    }

    private boolean isAllowed(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("devworks.co.id") || host.endsWith(".devworks.co.id");
    }

    private boolean isNavigationBoundaryUrl(String url) {
        if (url == null || url.isBlank() || url.startsWith("about:")) return true;
        Uri uri = Uri.parse(url);
        if (!isAllowed(uri)) return false;
        String path = uri.getPath();
        return path == null
            || "/login".equals(path)
            || OAUTH_APP_CALLBACK_PATH.equals(path)
            || "/api/auth/oauth/mobile/complete".equals(path)
            || path.startsWith(OAUTH_START_PATH);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, NOTIFICATION_REQUEST);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
            ATTENDANCE_NOTIFICATION_CHANNEL,
            "Absensi",
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Notifikasi pengajuan dan approval absensi.");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void registerFcmToken() {
        FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(DevworksFcmTokenSender::send);
    }

    private boolean isAttendancePage() {
        Uri uri = Uri.parse(webView.getUrl() == null ? "" : webView.getUrl());
        return isAllowed(uri) && uri.getPath() != null && uri.getPath().startsWith("/attendance");
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        requestPermissions(new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        }, LOCATION_REQUEST);
    }

    @SuppressLint("MissingPermission")
    private void startLocationMonitoring() {
        if (!hasLocationPermission() || locationManager == null) return;
        stopLocationMonitoring();
        List<String> providers = locationManager.getProviders(true);
        for (String provider : providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    Location lastKnown = locationManager.getLastKnownLocation(provider);
                    if (lastKnown != null && isMockLocation(lastKnown)) onLocationChanged(lastKnown);
                    locationManager.requestLocationUpdates(provider, 1_000L, 0f, this, Looper.getMainLooper());
                }
            } catch (IllegalArgumentException | SecurityException ignored) {
                // A provider may disappear while Android changes location settings.
            }
        }
    }

    private void stopLocationMonitoring() {
        if (locationManager == null) return;
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
            // Permission can be revoked while the app is open.
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (isMockLocation(location)) {
            mockLocationBlocked = true;
            lastMockAt = android.os.SystemClock.elapsedRealtime();
            consecutiveRealFixes = 0;
            if (isAttendancePage()) showMockLocationBlock();
            return;
        }

        if (!mockLocationBlocked) return;
        consecutiveRealFixes += 1;
        long elapsed = android.os.SystemClock.elapsedRealtime() - lastMockAt;
        if (elapsed >= MOCK_CLEAR_DELAY_MS && consecutiveRealFixes >= REAL_FIXES_TO_CLEAR) {
            mockLocationBlocked = false;
            consecutiveRealFixes = 0;
            if (!pageLoadFailed) hideMessage();
            webView.reload();
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isMockLocation(Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return location.isMock();
        return location.isFromMockProvider();
    }

    private void showMockLocationBlock() {
        showMessage(
            getString(R.string.mock_title),
            getString(R.string.mock_message),
            getString(R.string.retry),
            ignored -> {
                consecutiveRealFixes = 0;
                messageBody.setText(R.string.checking_location);
                messageButton.setEnabled(false);
                messageButton.postDelayed(() -> messageButton.setEnabled(true), 4_000L);
                startLocationMonitoring();
            }
        );
    }

    private void showConnectionError() {
        showMessage(
            getString(R.string.connection_title),
            getString(R.string.connection_message),
            getString(R.string.retry),
            ignored -> {
                if (!isOnline()) return;
                pageLoadFailed = false;
                progressBar.setVisibility(View.VISIBLE);
                webView.setVisibility(View.INVISIBLE);
                String currentUrl = webView.getUrl();
                if (currentUrl == null || currentUrl.isBlank() || currentUrl.startsWith("about:")) {
                    webView.loadUrl(LOGIN_URL);
                } else {
                    webView.reload();
                }
            }
        );
    }

    private void showPermissionError() {
        showMessage(
            getString(R.string.permission_title),
            getString(R.string.permission_message),
            getString(R.string.open_settings),
            ignored -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        );
    }

    private void showMessage(String title, String body, String action, View.OnClickListener listener) {
        messageTitle.setText(title);
        messageBody.setText(body);
        messageButton.setText(action);
        messageButton.setEnabled(true);
        messageButton.setOnClickListener(listener);
        messagePanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.INVISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void hideMessage() {
        messagePanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private boolean isOnline() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
            showConnectionError();
        }
    }

    private boolean isOAuthStart(Uri uri) {
        if (!isAllowed(uri) || uri.getPath() == null || !uri.getPath().startsWith(OAUTH_START_PATH)) return false;
        String path = uri.getPath();
        return path.endsWith("/google/start") || path.endsWith("/meta/start") || path.endsWith("/x/start");
    }

    private void openOAuthInApp(Uri uri) {
        Uri oauthUri = uri.buildUpon().appendQueryParameter("app", "android").build();
        CustomTabColorSchemeParams colors = new CustomTabColorSchemeParams.Builder()
            .setToolbarColor(Color.WHITE)
            .setNavigationBarColor(Color.WHITE)
            .build();
        CustomTabsIntent intent = new CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colors)
            .setShowTitle(false)
            .setUrlBarHidingEnabled(true)
            .build();
        try {
            intent.launchUrl(this, oauthUri);
        } catch (ActivityNotFoundException ignored) {
            openExternal(oauthUri.toString());
        }
    }

    private boolean handleOAuthCallback(Intent intent) {
        Uri uri = intent == null ? null : intent.getData();
        boolean verifiedAppLink = uri != null && isAllowed(uri) && OAUTH_APP_CALLBACK_PATH.equals(uri.getPath());
        boolean customAppLink = uri != null
            && "devworksattendance".equalsIgnoreCase(uri.getScheme())
            && "oauth-complete".equalsIgnoreCase(uri.getHost());
        if (!verifiedAppLink && !customAppLink) return false;
        String code = uri.getQueryParameter("code");
        if (code == null || !code.matches("^[A-Za-z0-9_-]{40,80}$")) {
            webView.loadUrl(LOGIN_URL + "?oauthError=Kode%20login%20aplikasi%20tidak%20valid");
            return true;
        }
        Uri completeUri = Uri.parse(OAUTH_MOBILE_COMPLETE_URL).buildUpon()
            .appendQueryParameter("code", code)
            .build();
        webView.loadUrl(completeUri.toString());
        return true;
    }

    private boolean handleNotificationLink(Intent intent) {
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null || !isAllowed(uri)) return false;
        webView.loadUrl(uri.toString());
        return true;
    }

    private boolean handleNotificationExtras(Intent intent) {
        if (intent == null || intent.getExtras() == null) return false;
        String link = intent.getStringExtra("linkUrl");
        if (link == null || link.isBlank()) return false;
        String normalized = normalizeNotificationLink(link);
        Uri uri = Uri.parse(normalized);
        if (!isAllowed(uri)) return false;
        webView.loadUrl(normalized);
        return true;
    }

    private String normalizeNotificationLink(String link) {
        String value = link == null ? "" : link.trim();
        if (value.startsWith("https://devworks.co.id/")) return value;
        if (value.startsWith("/")) return "https://devworks.co.id" + value;
        return "https://devworks.co.id/client/dashboard/attendance";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleOAuthCallback(intent) && !handleNotificationLink(intent)) handleNotificationExtras(intent);
    }

    private boolean isPdfUrl(Uri uri) {
        if (!isAllowed(uri) || uri.getPath() == null) return false;
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".pdf")
            || path.endsWith("/pdf")
            || path.contains("/pdf/")
            || path.endsWith("/agreement/final");
    }

    private void openPdfInApp(String url, String contentDisposition) {
        Uri uri = Uri.parse(url);
        if (!isAllowed(uri)) {
            Toast.makeText(this, "PDF dari domain ini tidak diizinkan.", Toast.LENGTH_LONG).show();
            return;
        }
        showPdfLoading();
        int requestId = ++pdfRequestId;
        String userAgent = webView.getSettings().getUserAgentString();
        new Thread(() -> {
            try {
                DownloadedPdf downloaded = downloadPdf(url, contentDisposition, userAgent);
                runOnUiThread(() -> {
                    if (requestId != pdfRequestId || isFinishing()) {
                        downloaded.file.delete();
                        return;
                    }
                    showDownloadedPdf(downloaded);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (requestId != pdfRequestId || isFinishing()) return;
                    showPdfError(error.getMessage() == null ? "PDF tidak dapat dibuka." : error.getMessage());
                });
            }
        }, "devworks-pdf-download").start();
    }

    private void showPdfLoading() {
        closePdfViewer();
        pdfOverlay = new FrameLayout(this);
        pdfOverlay.setBackgroundColor(Color.rgb(245, 247, 250));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.rgb(245, 247, 250));
        pdfOverlay.addView(shell, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(8), dp(8));
        header.setBackgroundColor(Color.WHITE);
        shell.addView(header, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64)
        ));

        pdfTitle = new TextView(this);
        pdfTitle.setText("Membuka PDF…");
        pdfTitle.setTextColor(Color.rgb(15, 23, 42));
        pdfTitle.setTextSize(15);
        pdfTitle.setTypeface(pdfTitle.getTypeface(), android.graphics.Typeface.BOLD);
        pdfTitle.setSingleLine(true);
        pdfTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(pdfTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        pdfSaveButton = new Button(this);
        pdfSaveButton.setAllCaps(false);
        pdfSaveButton.setText("Simpan");
        pdfSaveButton.setTextSize(12);
        pdfSaveButton.setVisibility(View.GONE);
        pdfSaveButton.setOnClickListener(ignored -> savePdfToDownloads());
        header.addView(pdfSaveButton, new LinearLayout.LayoutParams(dp(82), dp(44)));

        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(24);
        close.setContentDescription("Tutup PDF");
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(ignored -> closePdfViewer());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        pdfContent = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1
        );
        shell.addView(pdfContent, contentParams);

        ProgressBar loading = new ProgressBar(this);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(48), dp(48));
        loadingParams.gravity = Gravity.CENTER;
        pdfContent.addView(loading, loadingParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(10), dp(8), dp(10), dp(8));
        footer.setBackgroundColor(Color.WHITE);
        shell.addView(footer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64)
        ));

        pdfPreviousButton = new Button(this);
        pdfPreviousButton.setAllCaps(false);
        pdfPreviousButton.setText("Sebelumnya");
        pdfPreviousButton.setEnabled(false);
        pdfPreviousButton.setOnClickListener(ignored -> renderPdfPage(pdfPageIndex - 1));
        footer.addView(pdfPreviousButton, new LinearLayout.LayoutParams(dp(120), dp(46)));

        pdfPageLabel = new TextView(this);
        pdfPageLabel.setGravity(Gravity.CENTER);
        pdfPageLabel.setTextColor(Color.rgb(71, 85, 105));
        pdfPageLabel.setTextSize(13);
        footer.addView(pdfPageLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        pdfNextButton = new Button(this);
        pdfNextButton.setAllCaps(false);
        pdfNextButton.setText("Berikutnya");
        pdfNextButton.setEnabled(false);
        pdfNextButton.setOnClickListener(ignored -> renderPdfPage(pdfPageIndex + 1));
        footer.addView(pdfNextButton, new LinearLayout.LayoutParams(dp(120), dp(46)));

        root.addView(pdfOverlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private DownloadedPdf downloadPdf(String initialUrl, String dispositionHint, String userAgent) throws Exception {
        String currentUrl = initialUrl;
        String disposition = dispositionHint;
        for (int redirects = 0; redirects <= 5; redirects += 1) {
            if (!isAllowed(Uri.parse(currentUrl))) throw new IllegalStateException("Redirect PDF tidak diizinkan.");
            HttpURLConnection connection = (HttpURLConnection) new URL(currentUrl).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("Accept", "application/pdf");
            connection.setRequestProperty("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(currentUrl);
            if (cookie != null && !cookie.isBlank()) connection.setRequestProperty("Cookie", cookie);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IllegalStateException("Redirect PDF tidak valid.");
                currentUrl = new URL(new URL(currentUrl), location).toString();
                continue;
            }
            if (status == 401 || status == 403) {
                connection.disconnect();
                throw new IllegalStateException("Sesi login tidak diizinkan membuka PDF ini.");
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IllegalStateException("PDF gagal dimuat (HTTP " + status + ").");
            }
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("application/pdf")) {
                connection.disconnect();
                throw new IllegalStateException("Respons server bukan dokumen PDF.");
            }
            if (connection.getHeaderField("Content-Disposition") != null) {
                disposition = connection.getHeaderField("Content-Disposition");
            }
            File target = File.createTempFile("devworks-pdf-", ".pdf", getCacheDir());
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[16 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_PDF_BYTES) throw new IllegalStateException("Ukuran PDF melebihi 50 MB.");
                    output.write(buffer, 0, read);
                }
            } catch (Exception error) {
                target.delete();
                throw error;
            } finally {
                connection.disconnect();
            }
            try (InputStream signature = new FileInputStream(target)) {
                byte[] header = new byte[5];
                if (signature.read(header) != 5 || !"%PDF-".equals(new String(header, java.nio.charset.StandardCharsets.US_ASCII))) {
                    target.delete();
                    throw new IllegalStateException("File yang diterima bukan PDF yang valid.");
                }
            }
            return new DownloadedPdf(target, pdfFileName(disposition, currentUrl));
        }
        throw new IllegalStateException("Terlalu banyak redirect saat membuka PDF.");
    }

    private String pdfFileName(String contentDisposition, String url) {
        String candidate = "Dokumen-Devworks.pdf";
        if (contentDisposition != null) {
            Matcher matcher = PDF_FILENAME_PATTERN.matcher(contentDisposition);
            if (matcher.find()) candidate = matcher.group(1).trim();
        } else {
            String segment = Uri.parse(url).getLastPathSegment();
            if (segment != null && segment.toLowerCase(Locale.ROOT).endsWith(".pdf")) candidate = segment;
        }
        try {
            candidate = URLDecoder.decode(candidate, "UTF-8");
        } catch (Exception ignored) { }
        candidate = candidate.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (!candidate.toLowerCase(Locale.ROOT).endsWith(".pdf")) candidate += ".pdf";
        return candidate;
    }

    private void showDownloadedPdf(DownloadedPdf downloaded) {
        try {
            pdfFile = downloaded.file;
            pdfFileName = downloaded.name;
            pdfDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(pdfDescriptor);
            if (pdfRenderer.getPageCount() < 1) throw new IllegalStateException("PDF tidak memiliki halaman.");
            pdfTitle.setText(pdfFileName);
            pdfSaveButton.setVisibility(View.VISIBLE);
            pdfContent.removeAllViews();
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.setBackgroundColor(Color.rgb(226, 232, 240));
            pdfImage = new ImageView(this);
            pdfImage.setAdjustViewBounds(true);
            pdfImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pdfImage.setPadding(dp(8), dp(10), dp(8), dp(10));
            scroll.addView(pdfImage, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            pdfContent.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            renderPdfPage(0);
        } catch (Exception error) {
            showPdfError("PDF tidak dapat dirender pada perangkat ini.");
        }
    }

    private void renderPdfPage(int index) {
        if (pdfRenderer == null || index < 0 || index >= pdfRenderer.getPageCount()) return;
        PdfRenderer.Page page = pdfRenderer.openPage(index);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int targetWidth = Math.min(1800, Math.max(720, screenWidth * 2));
        int targetHeight = Math.max(1, Math.round(targetWidth * (page.getHeight() / (float) page.getWidth())));
        Bitmap nextBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        nextBitmap.eraseColor(Color.WHITE);
        page.render(nextBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        page.close();
        if (pdfBitmap != null && !pdfBitmap.isRecycled()) pdfBitmap.recycle();
        pdfBitmap = nextBitmap;
        pdfPageIndex = index;
        pdfImage.setImageBitmap(pdfBitmap);
        pdfPageLabel.setText((index + 1) + " / " + pdfRenderer.getPageCount());
        pdfPreviousButton.setEnabled(index > 0);
        pdfNextButton.setEnabled(index + 1 < pdfRenderer.getPageCount());
    }

    private void showPdfError(String message) {
        releasePdfDocument();
        pdfTitle.setText("PDF tidak dapat dibuka");
        pdfSaveButton.setVisibility(View.GONE);
        pdfPreviousButton.setEnabled(false);
        pdfNextButton.setEnabled(false);
        pdfPageLabel.setText("");
        pdfContent.removeAllViews();
        TextView error = new TextView(this);
        error.setText(message);
        error.setTextColor(Color.rgb(153, 27, 27));
        error.setTextSize(14);
        error.setGravity(Gravity.CENTER);
        error.setPadding(dp(28), dp(28), dp(28), dp(28));
        pdfContent.addView(error, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private void savePdfToDownloads() {
        File source = pdfFile;
        String fileName = pdfFileName;
        if (source == null || fileName == null) return;
        pdfSaveButton.setEnabled(false);
        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Devworks");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri destination = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (destination == null) throw new IllegalStateException("Folder Downloads tidak tersedia.");
                    try (InputStream input = new FileInputStream(source); OutputStream output = getContentResolver().openOutputStream(destination)) {
                        if (output == null) throw new IllegalStateException("File tujuan tidak dapat dibuat.");
                        copyStream(input, output);
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(destination, values, null, null);
                } else {
                    File directory = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Devworks");
                    if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Folder download tidak dapat dibuat.");
                    try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(new File(directory, fileName))) {
                        copyStream(input, output);
                    }
                }
                runOnUiThread(() -> Toast.makeText(this, "PDF tersimpan di Downloads/Devworks.", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, "PDF gagal disimpan.", Toast.LENGTH_LONG).show());
            } finally {
                runOnUiThread(() -> { if (pdfSaveButton != null) pdfSaveButton.setEnabled(true); });
            }
        }, "devworks-pdf-save").start();
    }

    private void copyStream(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private void closePdfViewer() {
        pdfRequestId += 1;
        releasePdfDocument();
        if (pdfOverlay != null) root.removeView(pdfOverlay);
        pdfOverlay = null;
        pdfContent = null;
        pdfTitle = null;
        pdfPageLabel = null;
        pdfSaveButton = null;
        pdfPreviousButton = null;
        pdfNextButton = null;
        pdfImage = null;
    }

    private void releasePdfDocument() {
        if (pdfBitmap != null && !pdfBitmap.isRecycled()) pdfBitmap.recycle();
        pdfBitmap = null;
        if (pdfRenderer != null) pdfRenderer.close();
        pdfRenderer = null;
        if (pdfDescriptor != null) {
            try { pdfDescriptor.close(); } catch (Exception ignored) { }
        }
        pdfDescriptor = null;
        if (pdfFile != null) pdfFile.delete();
        pdfFile = null;
        pdfFileName = null;
    }

    private static final class DownloadedPdf {
        final File file;
        final String name;

        DownloadedPdf(File file, String name) {
            this.file = file;
            this.name = name;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST) {
            boolean granted = hasLocationPermission();
            if (pendingGeoCallback != null) {
                pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
                pendingGeoCallback = null;
                pendingGeoOrigin = null;
            }
            if (granted) {
                hideMessage();
                startLocationMonitoring();
            } else if (isAttendancePage()) {
                showPermissionError();
            }
        } else if (requestCode == CAMERA_REQUEST) {
            if (pendingCameraRequest != null) {
                if (hasCameraPermission()) pendingCameraRequest.grant(new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE });
                else pendingCameraRequest.deny();
                pendingCameraRequest = null;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || pendingFileChooser == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                result = new Uri[count];
                for (int index = 0; index < count; index += 1) result[index] = data.getClipData().getItemAt(index).getUri();
            } else if (data.getData() != null) {
                result = new Uri[] { data.getData() };
            }
        }
        pendingFileChooser.onReceiveValue(result);
        pendingFileChooser = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasLocationPermission() && isAttendancePage()) startLocationMonitoring();
    }

    @Override
    protected void onPause() {
        stopLocationMonitoring();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_PAGE_LOAD_FAILED, pageLoadFailed);
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBackNavigation();
    }

    private void handleBackNavigation() {
        if (pdfOverlay != null) {
            closePdfViewer();
            return;
        }
        if (messagePanel.getVisibility() == View.VISIBLE && !mockLocationBlocked) {
            if (pageLoadFailed) {
                if (webView.canGoBack()) {
                    pageLoadFailed = false;
                    hideMessage();
                    lastBackPressedAt = 0L;
                    webView.goBack();
                } else {
                    confirmExitOrFinish();
                }
                return;
            }
            hideMessage();
            lastBackPressedAt = 0L;
            return;
        }
        if (webView.canGoBack() && !isBackNavigationAtExitBoundary()) {
            lastBackPressedAt = 0L;
            webView.goBack();
            return;
        }
        confirmExitOrFinish();
    }

    private boolean isBackNavigationAtExitBoundary() {
        android.webkit.WebBackForwardList history = webView.copyBackForwardList();
        int currentIndex = history.getCurrentIndex();
        if (currentIndex <= 0) return true;
        android.webkit.WebHistoryItem previous = history.getItemAtIndex(currentIndex - 1);
        android.webkit.WebHistoryItem current = history.getCurrentItem();
        String previousUrl = previous == null ? null : previous.getUrl();
        String currentUrl = current == null ? webView.getUrl() : current.getUrl();
        return isNavigationBoundaryUrl(previousUrl) && !isNavigationBoundaryUrl(currentUrl);
    }

    private void confirmExitOrFinish() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBackPressedAt <= EXIT_CONFIRM_WINDOW_MS) {
            finish();
            return;
        }
        lastBackPressedAt = now;
        Toast.makeText(this, R.string.press_back_to_exit, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        stopLocationMonitoring();
        closePdfViewer();
        if (pendingCameraRequest != null) pendingCameraRequest.deny();
        if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.destroy();
        super.onDestroy();
    }

    @Override public void onProviderEnabled(String provider) { startLocationMonitoring(); }
    @Override public void onProviderDisabled(String provider) { }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class SecureWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isOAuthStart(uri)) {
                openOAuthInApp(uri);
                return true;
            }
            if (isPdfUrl(uri)) {
                openPdfInApp(uri.toString(), null);
                return true;
            }
            if (isAllowed(uri)) return false;
            openExternal(uri.toString());
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            pageLoadFailed = false;
            view.setVisibility(View.INVISIBLE);
            progressBar.setVisibility(View.VISIBLE);
            if (!isOnline()) {
                pageLoadFailed = true;
                showConnectionError();
                view.stopLoading();
                return;
            }
            Uri uri = Uri.parse(url);
            if (isAllowed(uri) && uri.getPath() != null && uri.getPath().startsWith("/attendance")) {
                if (!hasLocationPermission()) requestLocationPermission();
                else startLocationMonitoring();
                if (mockLocationBlocked) showMockLocationBlock();
            } else {
                stopLocationMonitoring();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progressBar.setVisibility(View.GONE);
            if (!pageLoadFailed && !(mockLocationBlocked && isAttendancePage())) hideMessage();
            if (isAllowed(Uri.parse(url)) && !isNavigationBoundaryUrl(url)) {
                requestNotificationPermission();
                registerFcmToken();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            pageLoadFailed = true;
            view.stopLoading();
            showConnectionError();
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (!request.isForMainFrame() || errorResponse.getStatusCode() < 500) return;
            pageLoadFailed = true;
            view.stopLoading();
            showConnectionError();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
            handler.cancel();
            pageLoadFailed = true;
            showConnectionError();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            pageLoadFailed = true;
            view.post(MainActivity.this::recreate);
            return true;
        }
    }

    private final class AttendanceChromeClient extends WebChromeClient {
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (!isAllowed(Uri.parse(origin))) {
                callback.invoke(origin, false, false);
                return;
            }
            if (mockLocationBlocked) {
                callback.invoke(origin, false, false);
                showMockLocationBlock();
                return;
            }
            if (hasLocationPermission()) {
                callback.invoke(origin, true, false);
                startLocationMonitoring();
                return;
            }
            pendingGeoCallback = callback;
            pendingGeoOrigin = origin;
            requestLocationPermission();
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> {
                if (!isAllowed(request.getOrigin())) {
                    request.deny();
                    return;
                }
                boolean asksForVideo = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) asksForVideo = true;
                }
                if (!asksForVideo) {
                    request.deny();
                    return;
                }
                if (hasCameraPermission()) {
                    request.grant(new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE });
                } else {
                    pendingCameraRequest = request;
                    requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_REQUEST);
                }
            });
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingCameraRequest == request) pendingCameraRequest = null;
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
            pendingFileChooser = filePathCallback;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "image/jpeg", "image/png", "image/webp", "application/pdf"
            });
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            try {
                startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                return true;
            } catch (ActivityNotFoundException error) {
                pendingFileChooser = null;
                return false;
            }
        }
    }
}
