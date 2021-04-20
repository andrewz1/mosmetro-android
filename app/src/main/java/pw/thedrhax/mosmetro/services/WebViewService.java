/**
 * Wi-Fi в метро (pw.thedrhax.mosmetro, Moscow Wi-Fi autologin)
 * Copyright © 2015 Dmitry Karikh <the.dr.hax@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package pw.thedrhax.mosmetro.services;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.authenticator.InterceptorTask;
import pw.thedrhax.mosmetro.httpclient.Client;
import pw.thedrhax.mosmetro.httpclient.ParsedResponse;
import pw.thedrhax.mosmetro.httpclient.clients.OkHttp;
import pw.thedrhax.util.Listener;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.Randomizer;
import pw.thedrhax.util.Util;

public class WebViewService extends Service {
    private final Listener<Boolean> running = new Listener<Boolean>(true) {
        @Override
        public void onChange(Boolean new_value) {
            if (!new_value) {
                stopSelf();
            }
        }
    };

    private SharedPreferences settings;
    private ViewGroup view;
    private WindowManager wm;
    private WebView webview;
    private InterceptedClient webviewclient;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void onCreate() {
        super.onCreate();
        setContentView(R.layout.webview_activity);
        webview = (WebView)view.findViewById(R.id.webview);
        webviewclient = new InterceptedClient();

        Randomizer random = new Randomizer(this);

        webview.setWebViewClient(webviewclient);
        webview.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Logger.log(WebViewService.this, "Chrome | " + consoleMessage.message());
                return super.onConsoleMessage(consoleMessage);
            }
        });

        clear();

        WebSettings settings = webview.getSettings();
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptEnabled(true);
        settings.setUserAgentString(random.cached_useragent());
        settings.setDomStorageEnabled(true);

        this.settings = PreferenceManager.getDefaultSharedPreferences(this);
    }

    /**
     * Clear user data: Cookies, History, Cache
     * Source: https://stackoverflow.com/a/31950789
     */
    @SuppressWarnings("deprecation")
    private void clear() {
        webview.clearCache(true);
        webview.clearHistory();

        CookieManager manager = CookieManager.getInstance();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            manager.removeAllCookies(null);
            manager.flush();
        } else {
            CookieSyncManager syncmanager = CookieSyncManager.createInstance(this);
            syncmanager.startSync();
            manager.removeAllCookie();
            manager.removeSessionCookie();
            syncmanager.stopSync();
            syncmanager.sync();
        }
    }

    private void setContentView(@LayoutRes int layoutResID) {
        view = new LinearLayout(this);

        LayoutInflater inflater = (LayoutInflater)getSystemService(LAYOUT_INFLATER_SERVICE);
        if (inflater != null) {
            inflater.inflate(layoutResID, view);
        } else {
            return;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_TOAST,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );

        wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            wm.addView(view, params);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        webview.stopLoading();

        // Avoid WebView leaks
        // Source: https://stackoverflow.com/a/48596543
        ((ViewGroup) webview.getParent()).removeView(webview);
        webview.removeAllViews();
        webview.destroy();
        webview = null;

        if (view != null && wm != null) {
            wm.removeView(view);
        }

        destroyed.set(true);
    }

    private final Listener<Boolean> destroyed = new Listener<Boolean>(false);

    /**
     * @return Read-only Listener that will be updated as soon as service is
     *         destroyed.
     */
    public Listener<Boolean> onDestroyListener() {
        Listener<Boolean> result = new Listener<Boolean>(false);
        result.subscribe(destroyed);
        return result;
    }

    public void get(final String url) {
        webview.getHandler().post(new Runnable() {
            @Override
            public void run() {
                webview.loadUrl(url);
            }
        });
    }

    public String getURL() {
        return webviewclient.current_url.get();
    }

    public void setCookies(String url, Map<String, String> cookies) {
        CookieManager manager = CookieManager.getInstance();

        CookieSyncManager syncmanager = null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            syncmanager = CookieSyncManager.createInstance(this);
            syncmanager.startSync();
        }

        for (String name : cookies.keySet()) {
            manager.setCookie(url, name + "=" + cookies.get(name));
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            syncmanager.stopSync();
            syncmanager.sync();
        } else {
            manager.flush();
        }
    }

    public Map<String,String> getCookies(String url) {
        Map<String,String> result = new HashMap<>();

        String cookie_string = CookieManager.getInstance().getCookie(url);
        if (cookie_string != null) {
            String[] cookies = cookie_string.split("; ");
            for (String cookie : cookies) {
                String[] name_value = cookie.split("=");
                result.put(name_value[0], name_value.length > 1 ? name_value[1] : "");
            }
        }

        return result;
    }

    public void setClient(Client client) {
        webviewclient.setClient(client);
    }

    /**
     * Implementation of WebViewClient that ignores redirects in onPageFinished()
     * Inspired by https://stackoverflow.com/a/25547544
     */
    private class InterceptedClient extends WebViewClient {
        private final Listener<String> current_url = new Listener<String>("") {
            @Override
            public void onChange(String new_value) {
                Logger.log(InterceptedClient.this, "Current URL | " + new_value);
            }
        };

        private Client client = null;
        private String next_referer;
        private String referer;
        private final String key;
        private final List<InterceptorTask> interceptors = new LinkedList<>();

        public InterceptedClient() {
            setClient(new OkHttp(WebViewService.this).setRunningListener(running));
            key = new Randomizer(WebViewService.this).string(25).toLowerCase();

            // Serve interceptor script
            this.interceptors.add(new InterceptorTask("^https?://" + key + "/webview-proxy\\.js$") {
                @Nullable @Override
                public ParsedResponse request(Client client, Client.METHOD method, String url, Map<String, String> params) throws IOException {
                    String script =
                            Util.readAsset(WebViewService.this, "xhook.min.js") +
                            Util.readAsset(WebViewService.this, "webview-proxy.js")
                                    .replaceAll("INTERCEPT_KEY", key);

                    return new ParsedResponse(script, "text/javascript");
                }
            });
        }

        public InterceptedClient setClient(Client client) {
            if (this.client != null) {
                for (InterceptorTask task : this.interceptors) {
                    this.client.interceptors.remove(task);
                }
            }

            this.client = client;

            for (InterceptorTask task : this.interceptors) {
                this.client.interceptors.add(0, task);
            }

            return this;
        }

        private WebResourceResponse webresponse(@NonNull ParsedResponse response) {
            if (response.getMimeType().contains("text/html") && !response.getURL().isEmpty()) {
                Logger.log(this, response.toString());
            }

            if (response.isHtml()) {
                Document doc = response.getPageContent();

                doc.head().insertChildren(0, new LinkedList<Element>() {{
                    Element xhook = doc.createElement("script");
                    xhook.attr("src", "https://" + key + "/webview-proxy.js");
                    add(xhook);
                }});
            }

            WebResourceResponse result = new WebResourceResponse(
                    response.getMimeType(),
                    response.getEncoding(),
                    response.getInputStream()
            );

            if (Build.VERSION.SDK_INT >= 21) {
                result.setResponseHeaders(new HashMap<String, String>() {{
                    Map<String,List<String>> headers = response.getHeaders();
                    for (String name : headers.keySet()) {
                        if (headers.get(name) != null && headers.get(name).size() == 1) {
                            put(name, headers.get(name).get(0));
                        }
                    }
                }});

                if (!response.getReason().isEmpty()) {
                    result.setStatusCodeAndReasonPhrase(
                            response.getResponseCode(),
                            response.getReason()
                    );
                }
            }

            return result;
        }

        private ParsedResponse getToPost(String url) throws IOException {
            if (url.matches("https?://[^/]+/" + key + "\\?.*")) {
                Uri uri = Uri.parse(url);
                url = uri.getQueryParameter("url");

                HashMap<String, List<String>> headers = new HashMap<>();

                try {
                    JSONParser parser = new JSONParser();
                    JSONObject rawHeaders = (JSONObject) parser.parse(uri.getQueryParameter("headers"));

                    if (rawHeaders != null) {
                        for (Object key : rawHeaders.keySet()) {
                            headers.put((String) key, new LinkedList<String>() {{
                                add((String) rawHeaders.get(key));
                            }});
                        }
                    }
                } catch (ParseException|ClassCastException ex) {
                    Logger.log(this, Log.getStackTraceString(ex));
                    Logger.log(this, "Unable to parse headers");
                }

                String type = "text/plain";

                if (headers.containsKey(Client.HEADER_CONTENT_TYPE)) {
                    type = headers.get(Client.HEADER_CONTENT_TYPE).get(0);
                }

                String body = uri.getQueryParameter("body");

                Logger.log(this, "POST " + url);
                Logger.log(this, body);

                return client.post(url, type, body);
            } else {
                Logger.log(this, "GET " + url);
                return client.get(url, null, 1);
            }
        }

        @Override
        public synchronized WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            WebResourceResponse result = new WebResourceResponse(
                    "text/html",
                    "utf-8",
                    new ByteArrayInputStream("".getBytes())
            );

            if (referer != null) {
                client.setHeader(Client.HEADER_REFERER, referer);
            }

            if ("about:blank".equals(url)) return null;

            try {
                client.setCookies(url, getCookies(url));
                result = webresponse(getToPost(url));
                setCookies(url, client.getCookies(url));
            } catch (UnknownHostException ex) {
                onReceivedError(view, ERROR_HOST_LOOKUP, ex.toString(), url);
                return result;
            } catch (IOException ex) {
                Logger.log(this, ex.toString());
            }

            // Apply scheduled referer update
            if (next_referer != null && next_referer.equals(url)) {
                Logger.log(this, "Referer | Scheduled: " + next_referer);
                referer = next_referer;
                next_referer = null;
            }

            // First request sets referer for others
            if (referer == null) {
                Logger.log(this, "Referer | First: " + url);
                referer = url;
            }

            return result;
        }

        @Nullable @Override @TargetApi(21)
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if ("POST".equals(request.getMethod())) {
                Logger.log(this, "WARNING: Cannot intercept POST request to " + request.getUrl());
            }

            return super.shouldInterceptRequest(view, request);
        }

        @Override @TargetApi(21)
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return shouldOverrideUrlLoading(view, request.getUrl().toString());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            next_referer = url; // Schedule referer update
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Logger.log(this, "onPageStarted(" + url + ")");
            current_url.set(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Logger.log(this, "onPageFinished(" + url + ")");
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            if (Build.VERSION.SDK_INT < 23) {
                Logger.log(this, "Error: " + description);
            }
        }

        @Override @TargetApi(23)
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            Logger.log(this, "Error: " + error.getDescription());
        }
    }

    /*
     * Listener interface
     */

    public Listener<Boolean> getRunningListener() {
        return running;
    }

    /*
     * Binding interface
     */

    public class WebViewBinder extends Binder {
        public WebViewService getService() {
            return WebViewService.this;
        }
    }

    private final IBinder binder = new WebViewBinder();

    @Nullable @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running.unsubscribe();
        running.set(false);
        return false;
    }
}
