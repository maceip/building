package dev.ide.workbench.webapp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** No Gradle wrapper, npm, AndroidX, remote repository, SDK path or signing secret is generated. */
internal object NativeWebAppProject {
    fun files(review: WebAppPackageReview): Map<String, String> {
        val request = review.request
        val javaRoot = "app/src/main/java/${request.applicationId.replace('.', '/')}"
        return linkedMapOf(
            ".platform/workspace.json" to workspace(request),
            ".platform/libraries.json" to "{\"version\":1,\"libraries\":[]}\n",
            ".platform/sdks.json" to "{\"version\":1,\"sdks\":[]}\n",
            "app/module.toml" to module(request),
            "app/proguard-rules.pro" to "# No reflection or JavaScript bridges. Android framework entry points use the default rules.\n",
            "app/src/main/AndroidManifest.xml" to manifest(request),
            "app/src/main/res/values/strings.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name" translatable="false" formatted="false">"${label(request.appLabel)}"</string>
                </resources>
            """.trimIndent() + "\n",
            "app/src/main/res/values/styles.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar">
                        <item name="android:windowLightStatusBar">true</item>
                        <item name="android:colorAccent">#315DA8</item>
                    </style>
                </resources>
            """.trimIndent() + "\n",
            "app/src/main/res/drawable/ic_launcher.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <vector xmlns:android="http://schemas.android.com/apk/res/android"
                    android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48">
                    <path android:fillColor="#315DA8" android:pathData="M0,0h48v48h-48z"/>
                    <path android:fillColor="#FFFFFF" android:pathData="M8,10h32v28h-32z"/>
                    <path android:fillColor="#315DA8" android:pathData="M11,17h26v18h-26z"/>
                    <path android:fillColor="#FFFFFF" android:pathData="M14,22h20v3h-20zM14,28h14v3h-14z"/>
                </vector>
            """.trimIndent() + "\n",
            "$javaRoot/BundlePath.java" to pathPolicy(request.applicationId),
            "$javaRoot/MainActivity.java" to activity(request),
        )
    }

    private fun workspace(request: WebAppPackageRequest): String = buildJsonObject {
        put("version", 1)
        put("projects", JsonArray(listOf(buildJsonObject {
            put("id", "webapp-${request.projectName}")
            put("name", request.projectName)
            put("root", ".")
            put("buildSystem", "native")
            put("settings", JsonObject(emptyMap()))
            put("modules", JsonArray(listOf(buildJsonObject { put("id", "app"); put("name", "app"); put("dir", "app") })))
            put("libraries", JsonArray(emptyList()))
        })))
    }.toString() + "\n"

    private fun module(request: WebAppPackageRequest): String = buildString {
        append("""
            version = 2

            [module]
            type = "android-app"
            name = "app"
            languageLevel = "JAVA_8"
            output = "build/classes"

        """.trimIndent())
        for (set in listOf("main", "debug", "release")) {
            append("""

                [sourceSets.$set]
                scope = "IMPLEMENTATION"
                java = ["src/$set/java", "src/$set/kotlin"]
                res = ["src/$set/res"]
                assets = ["src/$set/assets"]
                resources = ["src/$set/resources"]
                jniLibs = ["src/$set/jniLibs"]
                aidl = ["src/$set/aidl"]

            """.trimIndent())
        }
        // AndroidApiLevels.LATEST is 36 (also the IDE's bundled platform). Keep this contract tested.
        append("""

            [android]
            namespace = "${request.applicationId}"
            compileSdk = 36
            minSdk = 26
            targetSdk = 36
            manifest = "src/main/AndroidManifest.xml"
            versionCode = ${request.versionCode}
            versionName = "${request.versionName}"
            isApplication = true
            manifestPlaceholders = []
            buildTypes = [{ name = "debug", debuggable = true, minifyEnabled = false, shrinkResources = false }, { name = "release", debuggable = false, minifyEnabled = false, shrinkResources = false, proguardFiles = ["proguard-android-optimize.txt", "proguard-rules.pro"] }]
        """.trimIndent())
        append('\n')
    }

    private fun manifest(request: WebAppPackageRequest): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <!-- Deliberately no INTERNET or storage permission. Only bundled static content can be loaded. -->
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${request.applicationId}"
            android:versionCode="${request.versionCode}" android:versionName="${request.versionName}">
            <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
            <application android:allowBackup="false" android:usesCleartextTraffic="false"
                android:icon="@drawable/ic_launcher" android:label="@string/app_name"
                android:supportsRtl="true" android:theme="@style/AppTheme">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent() + "\n"

    private fun label(value: String): String = value
        .replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Pure-Java policy, compiled/exercised in JVM tests as well as used by the generated WebView. */
    internal fun pathPolicy(pkg: String): String = """
        package $pkg;

        import java.net.URI;
        import java.net.URLDecoder;
        import java.util.Locale;

        /** Exact HTTPS origin plus one decoded, non-ambiguous relative asset path. */
        public final class BundlePath {
            public static final String ORIGIN = "${StaticWebAppPackager.ORIGIN}";
            private BundlePath() {}

            public static boolean ownOrigin(String value) {
                try {
                    URI uri = new URI(value);
                    return "https".equalsIgnoreCase(uri.getScheme())
                        && "appassets.androidplatform.net".equalsIgnoreCase(uri.getHost())
                        && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
                } catch (Exception ignored) { return false; }
            }

            public static String assetPath(String value) {
                if (!ownOrigin(value)) return null;
                try {
                    String raw = new URI(value).getRawPath();
                    if (raw == null || raw.length() == 0 || "/".equals(raw)) return "index.html";
                    if (!raw.startsWith("/") || raw.length() > 4096) return null;
                    String[] segments = raw.substring(1).split("/", -1);
                    StringBuilder result = new StringBuilder();
                    for (int i = 0; i < segments.length; i++) {
                        if (i == segments.length - 1 && segments[i].length() == 0) break;
                        String part = URLDecoder.decode(segments[i].replace("+", "%2B"), "UTF-8");
                        if (part.length() == 0 || part.startsWith(".")) return null;
                        for (int c = 0; c < part.length(); c++) {
                            char ch = part.charAt(c);
                            if (ch < 32 || ch == 127 || ch == '/' || ch == '\\' || ch == '%' || ch == ':' || ch == '?' || ch == '#') return null;
                        }
                        if (result.length() > 0) result.append('/');
                        result.append(part);
                    }
                    if (raw.endsWith("/")) result.append('/');
                    return result.toString();
                } catch (Exception ignored) { return null; }
            }

            public static boolean spaDocument(String path) {
                if (path == null) return false;
                String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                String last = trimmed.substring(trimmed.lastIndexOf('/') + 1);
                String first = trimmed.split("/", 2)[0].toLowerCase(Locale.ROOT);
                return last.length() > 0 && last.indexOf('.') < 0
                    && !"api".equals(first) && !"assets".equals(first) && !"_next".equals(first) && !"static".equals(first);
            }

            public static boolean externalHttps(String value) {
                try {
                    URI uri = new URI(value);
                    return value.length() <= 4096 && "https".equalsIgnoreCase(uri.getScheme())
                        && uri.getHost() != null && uri.getRawUserInfo() == null && !ownOrigin(value)
                        && (uri.getPort() == -1 || uri.getPort() == 443);
                } catch (Exception ignored) { return false; }
            }
        }
    """.trimIndent() + "\n"

    private fun activity(request: WebAppPackageRequest): String = """
        package ${request.applicationId};

        import android.app.Activity;
        import android.app.AlertDialog;
        import android.content.ActivityNotFoundException;
        import android.content.Intent;
        import android.net.Uri;
        import android.net.http.SslError;
        import android.os.Build;
        import android.os.Bundle;
        import android.webkit.CookieManager;
        import android.webkit.GeolocationPermissions;
        import android.webkit.PermissionRequest;
        import android.webkit.ServiceWorkerClient;
        import android.webkit.ServiceWorkerController;
        import android.webkit.ServiceWorkerWebSettings;
        import android.webkit.SslErrorHandler;
        import android.webkit.ValueCallback;
        import android.webkit.WebChromeClient;
        import android.webkit.WebResourceRequest;
        import android.webkit.WebResourceResponse;
        import android.webkit.WebSettings;
        import android.webkit.WebView;
        import android.webkit.WebViewClient;
        import android.widget.TextView;
        import android.widget.Toast;
        import java.io.ByteArrayInputStream;
        import java.io.ByteArrayOutputStream;
        import java.io.IOException;
        import java.io.InputStream;
        import java.util.Collections;
        import java.util.HashMap;
        import java.util.HashSet;
        import java.util.Locale;
        import java.util.Map;
        import java.util.Set;
        import org.json.JSONArray;
        import org.json.JSONObject;

        /** Offline static bundle; no JavaScript/native bridge and no network or file permissions. */
        public final class MainActivity extends Activity {
            private static final boolean SPA_FALLBACK = ${request.spaFallback};
            private static final boolean ALLOW_EXTERNAL_HTTPS = ${request.allowExternalHttps};
            private static final String CSP = "default-src 'self'; base-uri 'self'; object-src 'none'; "
                + "frame-src 'none'; frame-ancestors 'none'; form-action 'none'; connect-src 'self'; "
                + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' blob:; "
                + "worker-src 'self'; manifest-src 'self'";
            private WebView web;
            private Set<String> assets = Collections.emptySet();
            private AlertDialog externalDialog;

            @Override public void onCreate(Bundle state) {
                super.onCreate(state);
                try { assets = readAssetIndex(); }
                catch (Exception invalid) {
                    TextView message = new TextView(this);
                    message.setText("The bundled site is incomplete. Re-package a reviewed static build.");
                    message.setPadding(32, 32, 32, 32);
                    setContentView(message);
                    return;
                }
                WebView.setWebContentsDebuggingEnabled(false);
                web = new WebView(this);
                WebSettings settings = web.getSettings();
                settings.setJavaScriptEnabled(true); // Only the app's own reviewed bundle.
                settings.setDomStorageEnabled(true);
                settings.setAllowFileAccess(false);
                settings.setAllowContentAccess(false);
                settings.setAllowFileAccessFromFileURLs(false);
                settings.setAllowUniversalAccessFromFileURLs(false);
                settings.setBlockNetworkLoads(true);
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                settings.setJavaScriptCanOpenWindowsAutomatically(false);
                settings.setSupportMultipleWindows(false);
                settings.setGeolocationEnabled(false);
                settings.setMediaPlaybackRequiresUserGesture(true);
                settings.setSaveFormData(false);
                CookieManager.getInstance().setAcceptCookie(false);
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
                web.setWebChromeClient(new WebChromeClient() {
                    @Override public void onPermissionRequest(PermissionRequest permission) { permission.deny(); }
                    @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                        callback.invoke(origin, false, false);
                    }
                    @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                        callback.onReceiveValue(null);
                        return true;
                    }
                });
                web.setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        return respond(request.getUrl().toString(), request.getMethod(), request.isForMainFrame());
                    }
                    @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                        return respond(url, "GET", false);
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        String url = request.getUrl().toString();
                        if (BundlePath.assetPath(url) != null) return false;
                        if (ALLOW_EXTERNAL_HTTPS && request.isForMainFrame() && request.hasGesture()
                            && BundlePath.externalHttps(url)) confirmExternal(url);
                        return true;
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        // Old callback has no trustworthy gesture information: never open an external app.
                        return BundlePath.assetPath(url) == null;
                    }
                    @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                        handler.cancel();
                    }
                });
                ServiceWorkerController workers = ServiceWorkerController.getInstance();
                ServiceWorkerWebSettings workerSettings = workers.getServiceWorkerWebSettings();
                workerSettings.setAllowContentAccess(false);
                workerSettings.setAllowFileAccess(false);
                workerSettings.setBlockNetworkLoads(true);
                workers.setServiceWorkerClient(new ServiceWorkerClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                        return respond(request.getUrl().toString(), request.getMethod(), false);
                    }
                });
                setContentView(web);
                if (Build.VERSION.SDK_INT >= 33) {
                    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        new android.window.OnBackInvokedCallback() {
                            @Override public void onBackInvoked() { navigateBack(); }
                        });
                }
                // Intents/deep links never select a URL. All launches enter the fixed local origin.
                web.loadUrl(BundlePath.ORIGIN + "/index.html");
            }

            private Set<String> readAssetIndex() throws Exception {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = getAssets().open("webapp-manifest.json")) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (bytes.size() + n > 8 * 1024 * 1024) throw new IOException("Index too large");
                        bytes.write(buffer, 0, n);
                    }
                }
                JSONObject index = new JSONObject(bytes.toString("UTF-8"));
                if (index.getInt("schema") != 1 || !BundlePath.ORIGIN.equals(index.getString("origin"))) throw new IOException("Invalid index");
                JSONArray files = index.getJSONArray("files");
                if (files.length() < 1 || files.length() > 10000) throw new IOException("Invalid asset count");
                Set<String> result = new HashSet<String>();
                for (int i = 0; i < files.length(); i++) {
                    String path = files.getJSONObject(i).getString("path");
                    String encoded = new java.net.URI("https", "appassets.androidplatform.net", "/" + path, null).toASCIIString();
                    if (!path.equals(BundlePath.assetPath(encoded)) || !result.add(path)) throw new IOException("Invalid asset path");
                }
                if (!result.contains("index.html")) throw new IOException("Missing entry point");
                return Collections.unmodifiableSet(result);
            }

            private WebResourceResponse respond(String url, String method, boolean document) {
                String path = BundlePath.assetPath(url);
                if (path == null) return error(403, "Forbidden");
                if (!"GET".equals(method) && !"HEAD".equals(method)) return error(405, "Method Not Allowed");
                if (!assets.contains(path)) {
                    String directoryIndex = path + (path.endsWith("/") ? "" : "/") + "index.html";
                    if (assets.contains(directoryIndex)) path = directoryIndex;
                    else if (SPA_FALLBACK && document && BundlePath.spaDocument(path)) path = "index.html";
                    else return error(404, "Not Found");
                }
                try {
                    InputStream body = "HEAD".equals(method) ? new ByteArrayInputStream(new byte[0]) : getAssets().open("web/" + path);
                    return new WebResourceResponse(mime(path), text(path) ? "UTF-8" : null, 200, "OK", headers(), body);
                } catch (IOException missing) { return error(404, "Not Found"); }
            }

            private static Map<String, String> headers() {
                Map<String, String> headers = new HashMap<String, String>();
                headers.put("Content-Security-Policy", CSP);
                headers.put("X-Content-Type-Options", "nosniff");
                headers.put("Referrer-Policy", "no-referrer");
                headers.put("Cache-Control", "no-store");
                headers.put("Cross-Origin-Resource-Policy", "same-origin");
                headers.put("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
                return headers;
            }

            private static WebResourceResponse error(int code, String reason) {
                return new WebResourceResponse("text/plain", "UTF-8", code, reason, headers(), new ByteArrayInputStream(new byte[0]));
            }

            private static boolean text(String path) {
                String type = mime(path);
                return type.startsWith("text/") || type.equals("application/javascript") || type.endsWith("json") || type.endsWith("xml");
            }

            private static String mime(String path) {
                String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                if (ext.equals("html") || ext.equals("htm")) return "text/html";
                if (ext.equals("js") || ext.equals("mjs")) return "application/javascript";
                if (ext.equals("css")) return "text/css";
                if (ext.equals("json") || ext.equals("map")) return "application/json";
                if (ext.equals("webmanifest")) return "application/manifest+json";
                if (ext.equals("wasm")) return "application/wasm";
                if (ext.equals("svg")) return "image/svg+xml";
                if (ext.equals("png")) return "image/png";
                if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
                if (ext.equals("webp")) return "image/webp";
                if (ext.equals("gif")) return "image/gif";
                if (ext.equals("avif")) return "image/avif";
                if (ext.equals("ico")) return "image/x-icon";
                if (ext.equals("woff")) return "font/woff";
                if (ext.equals("woff2")) return "font/woff2";
                if (ext.equals("ttf")) return "font/ttf";
                if (ext.equals("otf")) return "font/otf";
                if (ext.equals("mp4")) return "video/mp4";
                if (ext.equals("webm")) return "video/webm";
                if (ext.equals("mp3")) return "audio/mpeg";
                if (ext.equals("ogg")) return "audio/ogg";
                if (ext.equals("wav")) return "audio/wav";
                if (ext.equals("txt")) return "text/plain";
                if (ext.equals("xml")) return "application/xml";
                if (ext.equals("pdf")) return "application/pdf";
                return "application/octet-stream";
            }

            private void confirmExternal(final String url) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || externalDialog != null) return;
                        externalDialog = new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Open in your browser?")
                            .setMessage(url + "\n\nThis leaves the offline app. Your browser will connect to this website.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Open browser", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                    if (!BundlePath.externalHttps(url)) return;
                                    try {
                                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                                        intent.addCategory(Intent.CATEGORY_BROWSABLE);
                                        startActivity(intent);
                                    } catch (ActivityNotFoundException missing) {
                                        Toast.makeText(MainActivity.this, "No browser is available", Toast.LENGTH_SHORT).show();
                                    }
                                }
                            }).create();
                        externalDialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                            @Override public void onDismiss(android.content.DialogInterface dialog) { externalDialog = null; }
                        });
                        externalDialog.show();
                    }
                });
            }

            private void navigateBack() { if (web != null && web.canGoBack()) web.goBack(); else finish(); }
            @Override public void onBackPressed() { navigateBack(); }
            @Override protected void onDestroy() {
                if (externalDialog != null) externalDialog.dismiss();
                ServiceWorkerController.getInstance().setServiceWorkerClient(null);
                if (web != null) { web.stopLoading(); web.destroy(); web = null; }
                super.onDestroy();
            }
        }
    """.trimIndent() + "\n"
}
