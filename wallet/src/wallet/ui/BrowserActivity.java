package wallet.ui;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import wallet.R;

public class BrowserActivity extends AbstractWalletActivity {

    private EditText urlBar;
    private WebView webView;
    private ImageView btnBackWeb;
    private ImageView btnForwardWeb;
    private ImageView btnRefreshWeb;
    private LinearLayout toolbarContainer;
    private View rootLayout;

    private static final String PREFS_NAME = "BrowserPrefs";
    private static final String KEY_HOME_URL = "home_url";
    private static final String KEY_HISTORY = "history_list_json";
    private static final String KEY_LAST_URL = "last_url";

    private static class HistoryEntry {
        String url;
        long time;
        HistoryEntry(String url, long time) {
            this.url = url;
            this.time = time;
        }
    }

    private final List<HistoryEntry> historyList = new ArrayList<>();
    private final Map<String, Bitmap> faviconCache = new HashMap<>();

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int originalSystemUiVisibility;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        rootLayout = findViewById(android.R.id.content);
        toolbarContainer = findViewById(R.id.toolbar_container);

        if (getActionBar()!= null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        urlBar = findViewById(R.id.url_bar);
        btnBackWeb = findViewById(R.id.btn_back_web);
        btnForwardWeb = findViewById(R.id.btn_forward_web);
        btnRefreshWeb = findViewById(R.id.btn_refresh_web);
        webView = findViewById(R.id.webview);

        applySystemRipple(btnBackWeb);
        applySystemRipple(btnForwardWeb);
        applySystemRipple(btnRefreshWeb);

        updateAllColors();
        loadHistoryFromPrefs();

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setBlockNetworkImage(false);
        webSettings.setBlockNetworkLoads(false);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setGeolocationEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String ua = webSettings.getUserAgentString();
        webSettings.setUserAgentString(ua + " Chrome/120.0.0.0 Mobile");
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String scheme = request.getUrl().getScheme();

                if (scheme!= null &&!scheme.equalsIgnoreCase("http") &&!scheme.equalsIgnoreCase("https")) {
                    if (scheme.equalsIgnoreCase("blob") || scheme.equalsIgnoreCase("data")) {
                        return false;
                    }
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        view.getContext().startActivity(intent);
                        urlBar.setText(url);
                    } catch (Exception ignored) {
                    }
                    return true;
                }

                stopAllMediaPlayback();
                view.loadUrl(url);
                urlBar.setText(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if (urlBar!= null) {
                    urlBar.setText(url);
                }

                saveHistory(url);

                if (url!= null &&!url.equals("about:blank") &&!url.isEmpty()) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_LAST_URL, url).apply();
                }
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);

                if (urlBar!= null && url!= null &&!url.equals("about:blank")) {
                    urlBar.setText(url);
                }

                saveHistory(url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView!= null) {
                    customViewCallback.onCustomViewHidden();
                    return;
                }

                customView = view;
                customViewCallback = callback;
                originalSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();

                if (getActionBar()!= null) {
                    getActionBar().hide();
                }

                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );

                ((FrameLayout) rootLayout).addView(view);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) {
                    return;
                }

                ((FrameLayout) rootLayout).removeView(customView);
                customView = null;

                if (getActionBar()!= null) {
                    getActionBar().show();
                }

                getWindow().getDecorView().setSystemUiVisibility(originalSystemUiVisibility);
                customViewCallback.onCustomViewHidden();
            }
        });

        webView.addJavascriptInterface(new DownloadJavascriptInterface(), "AndroidDownload");

        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                if (url == null) {
                    return;
                }
                if (url.startsWith("blob:")) {
                    handleBlobUrl(url, contentDisposition, mimetype);
                } else if (url.startsWith("data:")) {
                    handleDataUrl(url, mimetype);
                } else {
                    handleHttpDownload(url, userAgent, contentDisposition, mimetype);
                }
            }
        });

        btnBackWeb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoBack()) {
                    stopAllMediaPlayback();
                    webView.goBack();
                }
            }
        });

        btnForwardWeb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView.canGoForward()) {
                    webView.goForward();
                }
            }
        });

        btnRefreshWeb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                webView.reload();
            }
        });

        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setSingleLine(true);

        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean isEnterKey = event!= null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN;

                if (actionId == EditorInfo.IME_ACTION_GO
                        || actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_SEND
                        || actionId == EditorInfo.IME_NULL
                        || isEnterKey) {
                    handleUrlInput();
                    return true;
                }
                return false;
            }
        });

        urlBar.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleUrlInput();
                    return true;
                }
                return false;
            }
        });

        Intent intent = getIntent();
        Uri intentData = intent.getData();

        if (intentData!= null) {
            String newUrl = intentData.toString();
            urlBar.setText(newUrl);
            webView.loadUrl(newUrl);
        } else if (savedInstanceState!= null) {
            webView.restoreState(savedInstanceState);
            String restoredUrl = webView.getUrl();
            if (restoredUrl!= null) {
                urlBar.setText(restoredUrl);
            }
        } else {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String last = prefs.getString(KEY_LAST_URL, null);
            if (last!= null &&!last.isEmpty() &&!last.equals("about:blank")) {
                urlBar.setText(last);
                webView.loadUrl(last);
            }
        }
    }

    private void applySystemRipple(ImageView imageView) {
        if (imageView == null) {
            return;
        }
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        imageView.setBackgroundResource(outValue.resourceId);
        imageView.setClickable(true);
        imageView.setFocusable(true);
    }

    private void stopAllMediaPlayback() {
        if (webView == null) {
            return;
        }
        try {
            webView.loadUrl("javascript:(function(){try{var ms=document.querySelectorAll('audio,video');for(var i=0;i<ms.length;i++){try{ms[i].pause();ms[i].currentTime=0;try{ms[i].src='';}catch(e){}try{ms[i].removeAttribute('src');}catch(e){}try{ms[i].load();}catch(e){}}catch(e){}} }catch(e){}})()");
        } catch (Exception ignored) {
        }
        try {
            webView.evaluateJavascript(
                    "(function(){try{var ms=document.querySelectorAll('audio,video');for(var i=0;i<ms.length;i++){try{ms[i].pause();ms[i].src='';ms[i].load();}catch(e){}}}catch(e){}})();",
                    null
            );
        } catch (Exception ignored) {
        }
    }

    private void handleHttpDownload(String url, String userAgent, String contentDisposition, String mimetype) {
        try {
            String fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimetype);
            request.addRequestHeader("User-Agent", userAgent);
            String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
            if (cookies!= null) {
                request.addRequestHeader("Cookie", cookies);
            }
            request.setDescription(getString(R.string.browser_downloading_file));
            request.setTitle(fileName);
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm!= null) {
                dm.enqueue(request);
                Toast.makeText(getApplicationContext(), getString(R.string.browser_downloading, fileName), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.browser_download_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception ignored) {
            }
        }
    }

    private void handleBlobUrl(String blobUrl, String contentDisposition, String mimetype) {
        String fileName = android.webkit.URLUtil.guessFileName(blobUrl, contentDisposition, mimetype);
        String js = "(function(){" +
                "var xhr = new XMLHttpRequest();" +
                "xhr.open('GET', '" + blobUrl + "', true);" +
                "xhr.responseType = 'blob';" +
                "xhr.onload = function(){" +
                " if(this.status == 200){" +
                " var blob = this.response;" +
                " var reader = new FileReader();" +
                " reader.onloadend = function(){" +
                " var base64data = reader.result;" +
                " AndroidDownload.processBlobData(base64data, '" + fileName + "', '" + mimetype + "');" +
                " };" +
                " reader.readAsDataURL(blob);" +
                " }" +
                "};" +
                "xhr.send();" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void handleDataUrl(String dataUrl, String mimetype) {
        try {
            String fileName = "download_" + System.currentTimeMillis();
            String extension = ".bin";
            if (mimetype!= null) {
                if (mimetype.contains("png")) extension = ".png";
                else if (mimetype.contains("jpeg") || mimetype.contains("jpg")) extension = ".jpg";
                else if (mimetype.contains("pdf")) extension = ".pdf";
                else if (mimetype.contains("mp4")) extension = ".mp4";
            }
            fileName = fileName + extension;

            int commaIndex = dataUrl.indexOf(",");
            if (commaIndex == -1) {
                return;
            }
            String base64Data = dataUrl.substring(commaIndex + 1);
            boolean isBase64 = dataUrl.substring(0, commaIndex).contains("base64");

            byte[] fileData;
            if (isBase64) {
                fileData = Base64.decode(base64Data, Base64.DEFAULT);
            } else {
                fileData = java.net.URLDecoder.decode(base64Data, "UTF-8").getBytes();
            }

            saveBase64File(fileData, fileName, mimetype);

        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.browser_download_data_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private class DownloadJavascriptInterface {
        @JavascriptInterface
        public void processBlobData(String base64Data, String fileName, String mimetype) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (base64Data == null) {
                            return;
                        }
                        int commaIndex = base64Data.indexOf(",");
                        String pureBase64 = base64Data;
                        if (commaIndex!= -1) {
                            pureBase64 = base64Data.substring(commaIndex + 1);
                        }
                        byte[] fileData = Base64.decode(pureBase64, Base64.DEFAULT);
                        saveBase64File(fileData, fileName, mimetype);
                    } catch (Exception e) {
                        Toast.makeText(BrowserActivity.this, getString(R.string.browser_download_blob_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    private void saveBase64File(byte[] fileData, String fileName, String mimetype) {
        try {
            if (fileName == null || fileName.isEmpty()) {
                fileName = "download_" + System.currentTimeMillis();
                if (mimetype!= null && mimetype.contains("/")) {
                    String ext = mimetype.split("/")[1];
                    fileName = fileName + "." + ext;
                }
            }
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
            File file = new File(downloadDir, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(fileData);
            fos.flush();
            fos.close();

            Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            intent.setData(Uri.fromFile(file));
            sendBroadcast(intent);

            Toast.makeText(this, getString(R.string.browser_download_complete, fileName), Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.browser_download_save_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadHistoryFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY, null);

        if (json!= null &&!json.isEmpty()) {
            try {
                if (json.trim().startsWith("[")) {
                    JSONArray arr = new JSONArray(json);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String url = o.getString("url");
                        long time = o.getLong("time");
                        historyList.add(new HistoryEntry(url, time));
                    }
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        String old = prefs.getString("history_list", null);
        if (old!= null &&!old.isEmpty()) {
            String[] arr = old.split("\\|\\|");
            long now = System.currentTimeMillis();
            for (String s : arr) {
                if (!s.isEmpty()) {
                    historyList.add(new HistoryEntry(s, now));
                    now = now - 1000;
                }
            }
            try {
                JSONArray newArr = new JSONArray();
                for (HistoryEntry e : historyList) {
                    JSONObject o = new JSONObject();
                    o.put("url", e.url);
                    o.put("time", e.time);
                    newArr.put(o);
                }
                prefs.edit().putString(KEY_HISTORY, newArr.toString()).remove("history_list").apply();
            } catch (Exception ignored) {
            }
        }
    }

    private void saveHistory(String url) {
        if (url == null) {
            return;
        }
        if (url.equals("about:blank")) {
            return;
        }
        if (url.isEmpty()) {
            return;
        }

        for (int i = 0; i < historyList.size(); i++) {
            HistoryEntry e = historyList.get(i);
            if (e.url.equals(url)) {
                historyList.remove(i);
                break;
            }
        }

        HistoryEntry entry = new HistoryEntry(url, System.currentTimeMillis());
        historyList.add(0, entry);

        if (historyList.size() > 200) {
            historyList.remove(historyList.size() - 1);
        }

        try {
            JSONArray arr = new JSONArray();
            for (HistoryEntry e : historyList) {
                JSONObject o = new JSONObject();
                o.put("url", e.url);
                o.put("time", e.time);
                arr.put(o);
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_HISTORY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.browser_options, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        final int networkSignificantColor = getResources().getColor(R.color.fg_on_dark_bg_network_significant);
        final View decor = getWindow().getDecorView();

        decor.post(new Runnable() {
            @Override
            public void run() {
                ArrayList<View> actionMenuViews = new ArrayList<>();
                findViewsByClass(decor, "ActionMenuView", actionMenuViews);

                for (View amv : actionMenuViews) {
                    if (!(amv instanceof ViewGroup)) {
                        continue;
                    }

                    ViewGroup vg = (ViewGroup) amv;

                    for (int i = 0; i < vg.getChildCount(); i++) {
                        View itemView = vg.getChildAt(i);
                        if (itemView.getClass().getSimpleName().contains("ActionMenuItemView")) {
                            findAndWhiteText(itemView, networkSignificantColor);
                        }
                    }
                }
            }
        });

        return super.onPrepareOptionsMenu(menu);
    }

    private void findAndWhiteText(View root, int color) {
        if (root instanceof TextView) {
            ((TextView) root).setTextColor(color);
            return;
        }

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                findAndWhiteText(vg.getChildAt(i), color);
            }
        }
    }

    private void findViewsByClass(View root, String className, ArrayList<View> out) {
        if (root.getClass().getSimpleName().contains(className)) {
            out.add(root);
        }

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                findViewsByClass(vg.getChildAt(i), className, out);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(final android.view.MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.menu_browser_home) {
            loadHomeUrl();
            return true;
        } else if (id == R.id.menu_browser_history) {
            showHistoryDialog();
            return true;
        } else if (id == R.id.menu_browser_set_home) {
            showSetHomeDialog();
            return true;
        } else if (id == R.id.menu_browser_clear_history) {
            historyList.clear();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();
            Toast.makeText(this, R.string.browser_clear_history, Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.menu_browser_settings_root) {
            return true;
        } else if (id == android.R.id.home) {
            if (customView!= null && customViewCallback!= null) {
                customViewCallback.onCustomViewHidden();
            }
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView!= null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView!= null) {
            webView.onResume();
        }
        updateAllColors();
        invalidateOptionsMenu();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        Uri data = intent.getData();
        if (data!= null) {
            String newUrl = data.toString();
            urlBar.setText(newUrl);
            stopAllMediaPlayback();
            webView.loadUrl(newUrl);
        }
    }

    @Override
    public void finish() {
        if (webView!= null) {
            try {
                stopAllMediaPlayback();
            } catch (Exception ignored) {
            }
        }
        super.finish();
    }

    @Override
    protected void onDestroy() {
        if (webView!= null) {
            try {
                webView.destroy();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView!= null && customViewCallback!= null) {
            customViewCallback.onCustomViewHidden();
            return;
        }

        if (webView.canGoBack()) {
            stopAllMediaPlayback();
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void updateAllColors() {
        int bgActionBarColor = getResources().getColor(R.color.bg_action_bar);
        int fgIconColor = getResources().getColor(R.color.fg_on_dark_bg_network_significant);

        if (toolbarContainer!= null) {
            toolbarContainer.setBackgroundColor(bgActionBarColor);
        }

        if (btnBackWeb!= null) {
            btnBackWeb.setColorFilter(fgIconColor);
        }

        if (btnForwardWeb!= null) {
            btnForwardWeb.setColorFilter(fgIconColor);
        }

        if (btnRefreshWeb!= null) {
            btnRefreshWeb.setColorFilter(fgIconColor);
        }

        if (urlBar!= null) {
            int[] textColorAttr = {android.R.attr.textColorPrimary};
            TypedArray taText = obtainStyledAttributes(textColorAttr);
            int textColor = taText.getColor(0, 0);
            taText.recycle();
            urlBar.setTextColor(textColor);

            int[] hintColorAttr = {android.R.attr.textColorHint};
            TypedArray taHint = obtainStyledAttributes(hintColorAttr);
            int hintColor = taHint.getColor(0, 0);
            taHint.recycle();
            urlBar.setHintTextColor(hintColor);

            Drawable urlBg = getResources().getDrawable(R.drawable.edittext_background);
            urlBar.setBackground(urlBg);
        }

        int[] windowBgAttr = {android.R.attr.windowBackground};
        TypedArray taBg = obtainStyledAttributes(windowBgAttr);
        int windowBg = taBg.getColor(0, 0);
        taBg.recycle();

        if (webView!= null) {
            webView.setBackgroundColor(windowBg);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (toolbarContainer!= null) {
            ViewGroup.LayoutParams params = toolbarContainer.getLayoutParams();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            toolbarContainer.setLayoutParams(params);
            toolbarContainer.requestLayout();
        }

        if (urlBar!= null) {
            urlBar.requestLayout();
        }

        if (rootLayout!= null) {
            rootLayout.requestLayout();
        }

        updateAllColors();
        invalidateOptionsMenu();

        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                getWindow().getDecorView().requestLayout();
            }
        });
    }

    private void handleUrlInput() {
        String input = urlBar.getText().toString().trim();
        hideKeyboard();

        if (input.isEmpty()) {
            return;
        }

        String finalUrl;

        if (isValidUrl(input)) {
            if (input.startsWith("http")) {
                finalUrl = input;
            } else {
                finalUrl = "https://" + input;
            }
        } else {
            finalUrl = "https://www.google.com/search?q=" + Uri.encode(input);
        }

        stopAllMediaPlayback();
        webView.loadUrl(finalUrl);
        urlBar.setText(finalUrl);
    }

    private boolean isValidUrl(String input) {
        if (!input.contains(".")) {
            return false;
        }

        if (input.contains(" ")) {
            return false;
        }

        try {
            String checkUrl;
            if (input.startsWith("http")) {
                checkUrl = input;
            } else {
                checkUrl = "https://" + input;
            }

            URI uri = new URI(checkUrl);
            return uri.getHost()!= null && uri.getHost().contains(".");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm!= null && urlBar!= null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }

        if (urlBar!= null) {
            urlBar.clearFocus();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView!= null) {
            webView.saveState(outState);
        }
    }

    private void loadHomeUrl() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String homeUrl = prefs.getString(KEY_HOME_URL, null);

        if (homeUrl!= null) {
            stopAllMediaPlayback();
            webView.loadUrl(homeUrl);
            urlBar.setText(homeUrl);
        } else {
            stopAllMediaPlayback();
            webView.loadUrl("about:blank");
            urlBar.setText("");
        }
    }

    private void showHistoryDialog() {
        if (historyList.isEmpty()) {
            new AlertDialog.Builder(this)
               .setMessage(R.string.browser_no_history)
               .setPositiveButton(R.string.browser_close, null)
               .show();
            return;
        }

        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        listView.setVerticalScrollBarEnabled(true);

        final HistoryAdapter adapter = new HistoryAdapter();
        listView.setAdapter(adapter);

        int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.8);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                height
        );
        listView.setLayoutParams(params);

        final AlertDialog dialog = new AlertDialog.Builder(this)
           .setTitle(R.string.browser_history_title)
           .setView(listView)
           .setPositiveButton(R.string.browser_close, null)
           .setNegativeButton(R.string.browser_clear_history, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        historyList.clear();
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();
                        Toast.makeText(BrowserActivity.this, R.string.browser_clear_history, Toast.LENGTH_SHORT).show();
                    }
                })
           .create();

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String url = historyList.get(position).url;
                stopAllMediaPlayback();
                webView.loadUrl(url);
                urlBar.setText(url);
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    private class HistoryAdapter extends BaseAdapter {

        private SimpleDateFormat dateFormat;

        HistoryAdapter() {
            dateFormat = new SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault());
        }

        @Override
        public int getCount() {
            return historyList.size();
        }

        @Override
        public Object getItem(int position) {
            return historyList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            ViewHolder holder;

            if (convertView == null) {
                LinearLayout row = new LinearLayout(BrowserActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(24, 24, 16, 24);
                row.setGravity(Gravity.CENTER_VERTICAL);

                ImageView iconFavicon = new ImageView(BrowserActivity.this);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(96, 96);
                iconParams.setMargins(0, 0, 24, 0);
                iconFavicon.setLayoutParams(iconParams);
                iconFavicon.setScaleType(ImageView.ScaleType.FIT_CENTER);

                LinearLayout textColumn = new LinearLayout(BrowserActivity.this);
                textColumn.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textColumnParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                textColumn.setLayoutParams(textColumnParams);

                TextView textTitle = new TextView(BrowserActivity.this);
                textTitle.setTextSize(14);
                textTitle.setMaxLines(1);
                textTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);

                TextView textDomain = new TextView(BrowserActivity.this);
                textDomain.setTextSize(12);
                textDomain.setAlpha(0.7f);
                textDomain.setMaxLines(1);
                textDomain.setEllipsize(android.text.TextUtils.TruncateAt.END);

                textColumn.addView(textTitle);
                textColumn.addView(textDomain);

                ImageView buttonClose = new ImageView(BrowserActivity.this);
                LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(96, 96);
                buttonParams.setMargins(16, 0, 0, 0);
                buttonClose.setLayoutParams(buttonParams);
                buttonClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
                buttonClose.setScaleType(ImageView.ScaleType.CENTER);
                buttonClose.setPadding(24, 24, 24, 24);

                row.addView(iconFavicon);
                row.addView(textColumn);
                row.addView(buttonClose);

                holder = new ViewHolder();
                holder.icon = iconFavicon;
                holder.title = textTitle;
                holder.domain = textDomain;
                holder.buttonClose = buttonClose;
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            HistoryEntry entry = historyList.get(position);

            String host = "";
            try {
                URI uri = new URI(entry.url);
                host = uri.getHost();
                if (host == null) {
                    host = "";
                }
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
            } catch (Exception e) {
                host = "";
            }

            String displayTitle = entry.url;
            displayTitle = displayTitle.replace("https://", "");
            displayTitle = displayTitle.replace("http://", "");
            if (displayTitle.length() > 50) {
                displayTitle = displayTitle.substring(0, 50) + "...";
            }

            holder.title.setText(displayTitle);
            String date = dateFormat.format(new Date(entry.time));
            holder.domain.setText(date + " • " + host);

            holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);

            if (host!= null &&!host.isEmpty()) {
                loadFaviconForDomain(host, holder.icon);
            }

            holder.buttonClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    historyList.remove(position);
                    try {
                        JSONArray arr = new JSONArray();
                        for (HistoryEntry e : historyList) {
                            JSONObject o = new JSONObject();
                            o.put("url", e.url);
                            o.put("time", e.time);
                            arr.put(o);
                        }
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_HISTORY, arr.toString()).apply();
                    } catch (Exception ignored) {
                    }
                    notifyDataSetChanged();
                }
            });

            return convertView;
        }

        class ViewHolder {
            ImageView icon;
            TextView title;
            TextView domain;
            ImageView buttonClose;
        }

        private void loadFaviconForDomain(final String host, final ImageView imageView) {
            if (host == null) {
                return;
            }
            if (host.isEmpty()) {
                return;
            }

            if (faviconCache.containsKey(host)) {
                Bitmap cached = faviconCache.get(host);
                if (cached!= null) {
                    imageView.setImageBitmap(cached);
                }
                return;
            }

            final String domainForUrl = host;

            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String faviconUrl = "https://www.google.com/s2/favicons?domain=" + domainForUrl + "&sz=64";
                        URL url = new URL(faviconUrl);
                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                        connection.setConnectTimeout(5000);
                        connection.setReadTimeout(5000);
                        connection.setDoInput(true);
                        connection.connect();
                        InputStream inputStream = connection.getInputStream();
                        final Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                        inputStream.close();
                        connection.disconnect();

                        if (bitmap!= null) {
                            faviconCache.put(domainForUrl, bitmap);

                            BrowserActivity.this.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    imageView.setImageBitmap(bitmap);
                                }
                            });
                        }
                    } catch (Exception e) {
                    }
                }
            });

            thread.start();
        }
    }

    private void showSetHomeDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.browser_home_hint);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String currentHome = prefs.getString(KEY_HOME_URL, null);

        if (currentHome!= null) {
            input.setText(currentHome);
        }

        new AlertDialog.Builder(this)
           .setTitle(R.string.browser_set_home_title)
           .setView(input)
           .setPositiveButton(R.string.browser_save, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String url = input.getText().toString().trim();
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        SharedPreferences.Editor edit = prefs.edit();

                        if (url.isEmpty()) {
                            edit.remove(KEY_HOME_URL);
                            Toast.makeText(BrowserActivity.this, R.string.browser_home_cleared, Toast.LENGTH_SHORT).show();
                        } else {
                            if (!url.startsWith("http")) {
                                url = "https://" + url;
                            }
                            edit.putString(KEY_HOME_URL, url);
                            Toast.makeText(BrowserActivity.this, R.string.browser_home_saved, Toast.LENGTH_SHORT).show();
                        }

                        edit.apply();
                    }
                })
           .setNegativeButton(R.string.browser_cancel, null)
           .show();
    }
}
