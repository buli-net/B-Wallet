/*
 * Copyright (c) 2024
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package wallet.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.SeekBar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import wallet.R;

/**
 * MarketChartView - A custom Android View that renders an interactive financial chart.
 * 
 * <p>This view displays candlestick price data, moving averages, volume bars, and
 * volume moving averages. It supports touch gestures (pinch to zoom, pan to scroll)
 * and real-time price updates from Binance API.</p>
 * 
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Candlestick rendering with customizable colors and widths</li>
 *   <li>Multiple moving average lines (configurable periods and colors)</li>
 *   <li>Volume chart with optional moving average overlay</li>
 *   <li>Real-time price updates with WebSocket-like polling</li>
 *   <li>Countdown timer until next candle close</li>
 *   <li>Interactive candle selection (tap to show details)</li>
 *   <li>Last price line with configurable appearance</li>
 *   <li>Fiat currency conversion support</li>
 * </ul>
 * </p>
 * 
 * <p><b>Architecture:</b> All visual dimensions and default colors are defined in
 * XML resources (dimens.xml, colors.xml). The view does not hardcode any pixel
 * values, ensuring consistent theming (dark/light mode support).</p>
 * 
 * <p><b>Usage:</b> Instantiate in XML or code, then call {@link #loadChart(String, String)}
 * with a symbol and interval to start fetching data.</p>
 */
public class MarketChartView extends View {

    // ===== INNER CLASSES =====

    /**
     * Represents a single candlestick data point.
     * Contains open, high, low, close prices, volume, and timestamps.
     */
    public static class Candle {
        public final float open;
        public final float high;
        public final float low;
        public final float close;
        public final float volume;
        public final long openTime;
        public final long closeTime;

        public Candle(float open, float high, float low, float close, float volume,
                      long openTime, long closeTime) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
            this.openTime = openTime;
            this.closeTime = closeTime;
        }
    }

    /**
     * Represents a Moving Average line with a period and color.
     */
    public static class MaLine {
        public int period;
        public int color;

        public MaLine(int period, int color) {
            this.period = period;
            this.color = color;
        }
    }

    /**
     * Listener interface for chart update events.
     * Implement this to receive real-time price, ticker, MA, and selection updates.
     */
    public interface OnChartUpdateListener {
        void onPriceUpdate(float price, float high24h, float low24h);
        void onTickerUpdate(float high24h, float low24h, float volBtc, float volUsdt, float changePercent);
        void onMaUpdate(List<Float> maValues);
        void onCountdownUpdate(String countdown);
        void onCandleSelected(Candle candle);
        void onNothingSelected();
    }

    /**
     * Listener interface for volume bar click events.
     */
    public interface OnVolumeClickListener {
        void onVolumeClick(Candle candle);
    }

    // ===== LISTENERS =====
    private OnChartUpdateListener updateListener;
    private OnVolumeClickListener volumeClickListener;

    /**
     * Sets the chart update listener.
     */
    public void setOnChartUpdateListener(OnChartUpdateListener listener) {
        this.updateListener = listener;
    }

    /**
     * Sets the volume click listener.
     */
    public void setOnVolumeClickListener(OnVolumeClickListener listener) {
        this.volumeClickListener = listener;
    }

    // ===== DATA =====
    private List<Candle> data = new ArrayList<>();
    private List<MaLine> maLines = new ArrayList<>();

    // ===== PAINTS =====
    private Paint bullishPaint;
    private Paint bearishPaint;
    private Paint wickBullishPaint;
    private Paint wickBearishPaint;
    private Paint gridPaint;
    private Paint textPaint;
    private Paint lastPriceLinePaint;
    private Paint lastPriceBgPaint;
    private Paint lastPriceTextPaint;
    private Paint movingAverage5Paint;
    private Paint movingAverage10Paint;
    private Paint movingAverage20Paint;
    private Paint volumeBullishPaint;
    private Paint volumeBearishPaint;
    private Paint selectedLinePaint;
    private List<Paint> maExtraPaints = new ArrayList<>();

    // Volume MA paints
    private Paint volMa5Paint;
    private Paint volMa10Paint;
    private Paint volHeaderTextPaint;
    private Paint volHeaderMa5Paint;
    private Paint volHeaderMa10Paint;

    // ===== DIMENSIONS AND CONSTANTS =====
    private int DEFAULT_VISIBLE_CANDLE_COUNT;
    private int MIN_VISIBLE_CANDLE_COUNT;
    private int MAX_VISIBLE_CANDLE_COUNT;
    private int TOP_PADDING_PX;
    private int BOTTOM_PADDING_PX;
    private int VOLUME_CHART_HEIGHT_DP;
    private int VOLUME_TOP_MARGIN_PX;
    private int PRICE_AXIS_WIDTH_DP;
    private int FETCH_LIMIT;
    private long LIVE_REFRESH_INTERVAL_MS;
    private long COUNTDOWN_INTERVAL_MS;
    private int VOLUME_ALPHA;
    private int SELECTED_ALPHA;
    private int BIG_FIAT_THRESHOLD;
    private int NETWORK_TIMEOUT;
    private float GRID_WIDTH;
    private float DASH_ON;
    private float DASH_OFF;
    private float SELECTED_WIDTH;
    private float MIN_SCROLL_FRACTION;
    private float PRICE_PADDING_FRACTION;
    private int TIME_AXIS_HEIGHT;
    private int BODY_MIN_WIDTH;
    private int BODY_MAX_WIDTH;
    private int CANDLE_MIN_HEIGHT;
    private int PRICE_TEXT_OFFSET;
    private int TEXT_SIZE;
    private int PRICE_TEXT_MARGIN;
    private int TIME_TEXT_OFFSET;
    private int LOADING_TEXT_OFFSET;

    // ===== HARDCODED TECHNICAL STRINGS =====
    private static final String PREF_CHART = "chart_options_prefs";
    private static final String PREF_CANDLE = "candle_prefs";
    private static final String PREF_MA = "ma_prefs";
    private static final String KEY_BULL = "bull_color";
    private static final String KEY_BEAR = "bear_color";
    private static final String KEY_BODY_FRACTION = "body_fraction";
    private static final String KEY_WICK_WIDTH = "wick_width";
    private static final String KEY_MA_WIDTH = "ma_width";
    private static final String KEY_SHOW_GRID = "show_grid";
    private static final String KEY_SHOW_VOLUME = "show_volume";
    private static final String KEY_VISIBLE_COUNT = "visible_count";
    private static final String KEY_SHOW_LAST_PRICE = "show_last_price";
    private static final String KEY_LAST_PRICE_LINE_COLOR = "last_price_line_color";
    private static final String KEY_LAST_PRICE_BG_COLOR = "last_price_bg_color";
    private static final String KEY_PRICE_TEXT_SIZE = "price_text_size";
    private static final String KEY_PRICE_TEXT_COLOR = "price_text_color";
    private static final String KEY_GRID_COLOR = "grid_color";
    private static final String KEY_BG_COLOR = "bg_color";
    private static final String KEY_LAST_LINE_WIDTH = "last_line_width";
    private static final String KEY_LAST_LINE_DASH = "last_line_dash";
    private static final String KEY_LAST_LABEL_TEXT_SIZE = "last_label_text_size";
    private static final String KEY_LAST_LABEL_TEXT_COLOR = "last_label_text_color";
    private static final String KEY_SELECTED_LINE_COLOR = "selected_line_color";
    private static final String KEY_SELECTED_LINE_WIDTH = "selected_line_width";
    private static final String KEY_SELECTED_LINE_ALPHA = "selected_line_alpha";
    private static final String KEY_SELECTED_LINE_DASH = "selected_line_dash";
    private static final String KEY_VOL_SHOW_MA = "vol_show_ma";
    private static final String KEY_VOL_MA1_PERIOD = "vol_ma1_period";
    private static final String KEY_VOL_MA2_PERIOD = "vol_ma2_period";
    private static final String KEY_VOL_MA1_COLOR = "vol_ma1_color";
    private static final String KEY_VOL_MA2_COLOR = "vol_ma2_color";
    private static final String KEY_VOL_MA_WIDTH = "vol_ma_width";
    private static final String KEY_MA = "ma_lines";

    private static final String FMT_HMS = "%02d:%02d:%02d";
    private static final String FMT_DHMS = "%02d:%02d:%02d:%02d";
    private static final String FMT_PRICE_0 = "%,.0f";
    private static final String FMT_PRICE_2 = "%,.2f";
    private static final String SEP_SEMICOLON = ";";
    private static final String SEP_COMMA = ",";
    private static final String SEP_SEMICOLON_REGEX = ";";
    private static final String SEP_COMMA_REGEX = ",";

    private static final String JSON_LAST_PRICE = "lastPrice";
    private static final String JSON_HIGH_PRICE = "highPrice";
    private static final String JSON_LOW_PRICE = "lowPrice";
    private static final String JSON_VOLUME = "volume";
    private static final String JSON_QUOTE_VOLUME = "quoteVolume";
    private static final String JSON_PRICE_CHANGE_PERCENT = "priceChangePercent";

    private static final String SUFFIX_USDT = "USDT";
    private static final String SUFFIX_BUSD = "BUSD";
    private static final String TIME_FORMAT = "MM-dd HH:mm";

    // ===== DEFAULT VALUES FROM LAYOUT =====
    private boolean defaultsLoadedFromLayout = false;
    private float defBodyFraction;
    private float defWickWidthPx;
    private float defMaWidthPx;
    private int defVisibleCount;
    private boolean defShowGrid;
    private boolean defShowVolume;
    private boolean defShowLastPrice;
    private boolean defLastDashed;
    private float defPriceTextSizePx;
    private float defLastLineWidthPx;
    private float defLabelTextSizePx;
    private int defBullColor;
    private int defBearColor;
    private List<MaLine> defMaLines = new ArrayList<>();
    private int defLastPriceLineColor;
    private int defLastPriceBgColor;
    private int defPriceTextColor;
    private int defGridColor;
    private int defLabelTextColor;
    private int defSelectedLineColor;
    private float defSelectedLineWidthPx;
    private int defSelectedAlpha;
    private boolean defSelectedDashed;

    // ===== CHART STATE =====
    private int visibleCandleCount;
    private float translationX = 0f;
    private float minPrice = 0f;
    private float maxPrice = 0f;
    private float lastPrice = 0f;
    private float maxVolume = 0f;
    private int selectedIndex = -1;
    private int startIndexCache = 0;
    private float extraOffsetX = 0f;

    // ===== VOLUME MA STATE =====
    private boolean showVolMa = true;
    private int volMa1Period = 5;
    private int volMa2Period = 10;
    private int volMa1Color = Color.parseColor("#FFC107");
    private int volMa2Color = Color.parseColor("#9C27B0");
    private float volMaWidthPx = 2f;
    private List<Float> volMa1Values = new ArrayList<>();
    private List<Float> volMa2Values = new ArrayList<>();
    private float lastVolValue = 0f;
    private float lastVolMa1Value = 0f;
    private float lastVolMa2Value = 0f;

    // ===== GESTURE DETECTORS =====
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;

    // ===== HANDLERS =====
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Handler liveHandler = new Handler(Looper.getMainLooper());
    private Handler countdownHandler = new Handler(Looper.getMainLooper());
    private Runnable liveRunnable;
    private Runnable countdownRunnable;

    // ===== SYMBOL / INTERVAL =====
    private String currentSymbol;
    private String currentInterval;
    private SimpleDateFormat timeFormat;
    private long currentCandleCloseTime = 0L;
    private String fiatCode;
    private float fiatMultiplier = 1f;

    // ===== CURRENT APPEARANCE =====
    private int bullishColor;
    private int bearishColor;
    private float bodyWidthFraction;
    private float wickWidthPx;
    private float maLineWidthPx;
    private boolean showGrid;
    private boolean showVolume;
    private boolean showLastPriceLine;
    private int lastPriceLineColor;
    private int lastPriceBgColor;
    private float priceTextSizePx;
    private int priceTextColor;
    private int gridColor;
    private int bgColor;
    private float lastLineWidthPx;
    private boolean lastLineDashed;
    private float lastPriceLabelTextSizePx;
    private int lastPriceLabelTextColor;
    private int selectedLineColor;
    private float selectedLineWidthPx;
    private int selectedLineAlpha;
    private boolean selectedLineDashed;

    // ===== PERSISTENCE FLAGS =====
    private static final String KEY_SELECTED_COLOR_USER_SET = "selected_line_color_user_set";
    private static final String KEY_SELECTED_WIDTH_USER_SET = "selected_width_user_set";
    private static final String KEY_SELECTED_ALPHA_USER_SET = "selected_alpha_user_set";
    private static final String KEY_SELECTED_DASHED_USER_SET = "selected_dashed_user_set";

    // ===== CONSTRUCTOR =====

    public MarketChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fiatCode = context.getString(R.string.fiat_usd);
        timeFormat = new SimpleDateFormat(TIME_FORMAT, Locale.US);
        loadViewDimensions(context);
        visibleCandleCount = DEFAULT_VISIBLE_CANDLE_COUNT;
        initGestures(context);
        initPaints(context);
    }

    // ===== DIMENSION LOADING =====

    /**
     * Loads all view dimensions and constants from resources.
     * This method reads from dimens.xml and integers.xml.
     */
    private void loadViewDimensions(Context context) {
        TOP_PADDING_PX = (int) context.getResources()
               .getDimension(R.dimen.default_top_padding);
        BOTTOM_PADDING_PX = (int) context.getResources()
               .getDimension(R.dimen.default_bottom_padding);
        VOLUME_CHART_HEIGHT_DP = (int) context.getResources()
               .getDimension(R.dimen.default_volume_height);
        VOLUME_TOP_MARGIN_PX = (int) context.getResources()
               .getDimension(R.dimen.default_volume_top_margin);
        PRICE_AXIS_WIDTH_DP = (int) context.getResources()
               .getDimension(R.dimen.default_price_axis_width);
        TIME_AXIS_HEIGHT = (int) context.getResources()
               .getDimension(R.dimen.default_time_axis_height);
        PRICE_TEXT_MARGIN = (int) context.getResources()
               .getDimension(R.dimen.default_price_text_margin);
        PRICE_TEXT_OFFSET = (int) context.getResources()
               .getDimension(R.dimen.default_price_text_offset);
        GRID_WIDTH = context.getResources()
               .getDimension(R.dimen.default_grid_width);
        BODY_MIN_WIDTH = (int) context.getResources()
               .getDimension(R.dimen.default_body_min_width);
        BODY_MAX_WIDTH = (int) context.getResources()
               .getDimension(R.dimen.default_body_max_width);
        TEXT_SIZE = (int) context.getResources()
               .getDimension(R.dimen.default_text_size);
        SELECTED_WIDTH = context.getResources()
               .getDimension(R.dimen.default_selected_width);
        DASH_ON = context.getResources()
               .getDimension(R.dimen.dash_on);
        DASH_OFF = context.getResources()
               .getDimension(R.dimen.dash_off);
        TIME_TEXT_OFFSET = (int) context.getResources()
               .getDimension(R.dimen.time_text_offset);
        LOADING_TEXT_OFFSET = (int) context.getResources()
               .getDimension(R.dimen.loading_text_offset);
        CANDLE_MIN_HEIGHT = (int) context.getResources()
               .getDimension(R.dimen.default_candle_min_height);
        FETCH_LIMIT = context.getResources()
               .getInteger(R.integer.default_fetch_limit);
        MIN_VISIBLE_CANDLE_COUNT = context.getResources()
               .getInteger(R.integer.min_visible_candle_count);
        MAX_VISIBLE_CANDLE_COUNT = context.getResources()
               .getInteger(R.integer.max_visible_candle_count);
        DEFAULT_VISIBLE_CANDLE_COUNT = context.getResources()
               .getInteger(R.integer.default_visible_candle_count);
        VOLUME_ALPHA = context.getResources()
               .getInteger(R.integer.volume_alpha);
        SELECTED_ALPHA = context.getResources()
               .getInteger(R.integer.selected_alpha);
        BIG_FIAT_THRESHOLD = context.getResources()
               .getInteger(R.integer.big_fiat_threshold);
        NETWORK_TIMEOUT = context.getResources()
               .getInteger(R.integer.network_timeout);
        LIVE_REFRESH_INTERVAL_MS = context.getResources()
               .getInteger(R.integer.live_refresh_interval);
        COUNTDOWN_INTERVAL_MS = context.getResources()
               .getInteger(R.integer.countdown_interval);
        MIN_SCROLL_FRACTION = context.getResources()
               .getFraction(R.fraction.min_scroll_fraction, 1, 1);
        PRICE_PADDING_FRACTION = context.getResources()
               .getFraction(R.fraction.price_padding_fraction, 1, 1);

        volMa1Period = context.getResources().getInteger(R.integer.default_vol_ma1_period);
        volMa2Period = context.getResources().getInteger(R.integer.default_vol_ma2_period);
        volMaWidthPx = context.getResources().getDimension(R.dimen.default_vol_ma_width);
    }

    /**
     * Updates view dimensions from layout XML.
     * Called by MarketChartActivity with values extracted from chart_settings_*.xml.
     */
    public void setViewDimensionsFromLayout(int topPad, int bottomPad, int volumeHeight,
                                            int volumeTopMargin, int priceAxisWidth,
                                            int timeAxisHeight, int priceTextMargin,
                                            int priceTextOffset, int gridWidth,
                                            int bodyMinWidth, int bodyMaxWidth,
                                            int candleMinWidth, int candleMinHeight,
                                            float dashOn, float dashOff,
                                            float timeTextOffset, float loadingTextOffset,
                                            float defaultTextSize, float selectedWidth,
                                            float popupTimeSize, float popupSize) {
        TOP_PADDING_PX = topPad;
        BOTTOM_PADDING_PX = bottomPad;
        VOLUME_CHART_HEIGHT_DP = volumeHeight;
        VOLUME_TOP_MARGIN_PX = volumeTopMargin;
        PRICE_AXIS_WIDTH_DP = priceAxisWidth;
        TIME_AXIS_HEIGHT = timeAxisHeight;
        PRICE_TEXT_MARGIN = priceTextMargin;
        PRICE_TEXT_OFFSET = priceTextOffset;
        GRID_WIDTH = gridWidth;
        BODY_MIN_WIDTH = bodyMinWidth;
        BODY_MAX_WIDTH = bodyMaxWidth;
        DASH_ON = dashOn;
        DASH_OFF = dashOff;
        TIME_TEXT_OFFSET = (int) timeTextOffset;
        LOADING_TEXT_OFFSET = (int) loadingTextOffset;
        TEXT_SIZE = (int) defaultTextSize;
        SELECTED_WIDTH = selectedWidth;
        visibleCandleCount = DEFAULT_VISIBLE_CANDLE_COUNT;
    }

    // ===== DEFAULT SETTINGS =====

    /**
     * Sets default chart settings from layout XML.
     * This is the primary entry point for applying default styles.
     * 
     * @param bodyFrac Candle body width fraction
     * @param wickW Wick width in pixels
     * @param maW Moving average line width
     * @param visCount Initial visible candle count
     * @param showG Show grid toggle
     * @param showV Show volume toggle
     * @param showLast Show last price line toggle
     * @param dashed Last line dashed toggle
     * @param txtSize Price text size
     * @param lastW Last line width
     * @param labelSize Label text size
     * @param bullColor Bullish candle color
     * @param bearColor Bearish candle color
     * @param lastColor Last price line color
     * @param gridColor Grid color
     * @param txtColor Price text color
     * @param labelBg Label background color
     * @param labelTextColor Label text color
     * @param maDefaults List of default moving averages
     * @param selectedColor Selected line color
     * @param selectedWidth Selected line width
     * @param selectedAlpha Selected line alpha
     * @param selectedDashed Selected line dashed toggle
     */
    public void setDefaultsFromLayout(float bodyFrac, float wickW, float maW, int visCount,
                                      boolean showG, boolean showV, boolean showLast, boolean dashed,
                                      float txtSize, float lastW, float labelSize,
                                      int bullColor, int bearColor, int lastColor, int gridColor,
                                      int txtColor, int labelBg, int labelTextColor,
                                      List<MaLine> maDefaults,
                                      int selectedColor, float selectedWidth,
                                      int selectedAlpha, boolean selectedDashed) {
        if (defaultsLoadedFromLayout) {
            cleanupAutoIfNotUserSet();
        }

        this.defBodyFraction = bodyFrac;
        this.defWickWidthPx = wickW;
        this.defMaWidthPx = maW;
        this.defVisibleCount = visCount;
        this.defShowGrid = showG;
        this.defShowVolume = showV;
        this.defShowLastPrice = showLast;
        this.defLastDashed = dashed;
        this.defPriceTextSizePx = txtSize;
        this.defLastLineWidthPx = lastW;
        this.defLabelTextSizePx = labelSize;
        this.defBullColor = bullColor;
        this.defBearColor = bearColor;
        this.defLastPriceLineColor = lastColor;
        this.defLastPriceBgColor = labelBg;
        this.defGridColor = gridColor;
        this.defPriceTextColor = txtColor;
        this.defLabelTextColor = labelTextColor;
        this.defSelectedLineColor = selectedColor;
        this.defSelectedLineWidthPx = selectedWidth;
        this.defSelectedAlpha = selectedAlpha;
        this.defSelectedDashed = selectedDashed;

        this.defMaLines = new ArrayList<>();
        for (MaLine m : maDefaults) {
            this.defMaLines.add(
                    new MaLine(m.period, m.color)
            );
        }

        DEFAULT_VISIBLE_CANDLE_COUNT = visCount;
        visibleCandleCount = defVisibleCount;
        defaultsLoadedFromLayout = true;

        if (maDefaults.size() >= 2) {
            volMa1Color = maDefaults.get(0).color;
            volMa2Color = maDefaults.get(1).color;
        }

        initCandleColors(getContext());
        initMaLines(getContext());
        loadChartOptions(getContext());
        initPaints(getContext());
        invalidate();
    }

    /**
     * Overloaded version for backward compatibility with older code.
     */
    public void setDefaultsFromLayout(float bodyFrac, float wickW, float maW, int visCount,
                                      boolean showG, boolean showV, boolean showLast, boolean dashed,
                                      float txtSize, float lastW, float labelSize,
                                      int bullColor, int bearColor, int lastColor, int gridColor,
                                      int txtColor, int labelBg, int labelTextColor,
                                      List<MaLine> maDefaults) {
        int fallbackSelectedColor = getContext().getResources()
               .getColor(R.color.chart_selected_line, getContext().getTheme());
        float fallbackSelectedWidth = getContext().getResources()
               .getDimension(R.dimen.default_selected_width);
        int fallbackSelectedAlpha = getContext().getResources()
               .getInteger(R.integer.selected_alpha);
        boolean fallbackSelectedDashed = false;

        setDefaultsFromLayout(
                bodyFrac,
                wickW,
                maW,
                visCount,
                showG,
                showV,
                showLast,
                dashed,
                txtSize,
                lastW,
                labelSize,
                bullColor,
                bearColor,
                lastColor,
                gridColor,
                txtColor,
                labelBg,
                labelTextColor,
                maDefaults,
                fallbackSelectedColor,
                fallbackSelectedWidth,
                fallbackSelectedAlpha,
                fallbackSelectedDashed
        );
    }

    /**
     * Loads default settings from a settings view root.
     * Used when the chart settings popup is reset.
     */
    public void loadDefaultsFromSettingsView(View root) {
        View viewBull = root.findViewById(R.id.viewBull);
        View viewBear = root.findViewById(R.id.viewBear);
        View viewSelectedLine = root.findViewById(R.id.viewSelectedLine);
        SeekBar sbWick = root.findViewById(R.id.sbWick);
        SeekBar sbBody = root.findViewById(R.id.sbBody);
        SeekBar sbSelectedWidth = root.findViewById(R.id.sbSelectedWidth);
        SeekBar sbSelectedAlpha = root.findViewById(R.id.sbSelectedAlpha);

        int bullColor = defBullColor;
        int bearColor = defBearColor;
        int selectedColor = defSelectedLineColor;

        if (viewBull != null && viewBull.getTag() != null) {
            String bullTag = viewBull.getTag().toString();
            String bullName = bullTag.replace("@color/", "").replace("@", "");
            int bullResId = getContext().getResources().getIdentifier(bullName, "color", getContext().getPackageName());
            if (bullResId != 0) {
                bullColor = getContext().getResources().getColor(bullResId, getContext().getTheme());
            }
        }

        if (viewBear != null && viewBear.getTag() != null) {
            String bearTag = viewBear.getTag().toString();
            String bearName = bearTag.replace("@color/", "").replace("@", "");
            int bearResId = getContext().getResources().getIdentifier(bearName, "color", getContext().getPackageName());
            if (bearResId != 0) {
                bearColor = getContext().getResources().getColor(bearResId, getContext().getTheme());
            }
        }

        if (viewSelectedLine != null && viewSelectedLine.getTag() != null) {
            String selectedTag = viewSelectedLine.getTag().toString();
            String selectedName = selectedTag.replace("@color/", "").replace("@", "");
            int selectedResId = getContext().getResources().getIdentifier(selectedName, "color", getContext().getPackageName());
            if (selectedResId != 0) {
                selectedColor = getContext().getResources().getColor(selectedResId, getContext().getTheme());
            }
        }

        float wickWidth = defWickWidthPx;
        float bodyFraction = defBodyFraction;
        float selectedWidth = defSelectedLineWidthPx;
        int selectedAlpha = defSelectedAlpha;

        if (sbWick != null) {
            wickWidth = sbWick.getProgress() + 1f;
        }

        if (sbBody != null) {
            bodyFraction = sbBody.getProgress() / 100f;
        }

        if (sbSelectedWidth != null) {
            selectedWidth = sbSelectedWidth.getProgress();
        }

        if (sbSelectedAlpha != null) {
            selectedAlpha = sbSelectedAlpha.getProgress();
            if (selectedAlpha > 255) {
                selectedAlpha = 255;
            }
        }

        setDefaultsFromLayout(
                bodyFraction,
                wickWidth,
                defMaWidthPx,
                defVisibleCount,
                defShowGrid,
                defShowVolume,
                defShowLastPrice,
                defLastDashed,
                defPriceTextSizePx,
                defLastLineWidthPx,
                defLabelTextSizePx,
                bullColor,
                bearColor,
                defLastPriceLineColor,
                defGridColor,
                defPriceTextColor,
                defLastPriceBgColor,
                defLabelTextColor,
                defMaLines,
                selectedColor,
                selectedWidth,
                selectedAlpha,
                defSelectedDashed
        );
    }

    // ===== THEME RELOAD =====

    /**
     * Reloads default colors from the current theme.
     * Called when the system theme changes (dark/light mode).
     */
    private void reloadDefaultColorsFromCurrentTheme() {
        Context ctx = getContext();
        defGridColor = ctx.getResources()
               .getColor(R.color.chart_grid, ctx.getTheme());
        defPriceTextColor = ctx.getResources()
               .getColor(R.color.chart_text, ctx.getTheme());
        defSelectedLineColor = ctx.getResources()
               .getColor(R.color.chart_selected_line, ctx.getTheme());
        defLabelTextColor = ctx.getResources()
               .getColor(R.color.last_label_text, ctx.getTheme());
        defLastPriceLineColor = ctx.getResources()
               .getColor(R.color.chart_last_price_line, ctx.getTheme());
        defLastPriceBgColor = ctx.getResources()
               .getColor(R.color.chart_last_price_label_bg, ctx.getTheme());
        defBullColor = ctx.getResources()
               .getColor(R.color.chart_bull_default, ctx.getTheme());
        defBearColor = ctx.getResources()
               .getColor(R.color.chart_bear_default, ctx.getTheme());
    }

    /**
     * Cleans up saved preferences for selected line if the user has not explicitly set them.
     * This ensures auto-adaptation to theme changes for untouched properties.
     */
    private void cleanupAutoIfNotUserSet() {
        Context ctx = getContext();
        SharedPreferences sp = ctx.getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();

        if (!sp.getBoolean(KEY_SELECTED_COLOR_USER_SET, false)) {
            ed.remove(KEY_SELECTED_LINE_COLOR);
        }
        if (!sp.getBoolean(KEY_SELECTED_WIDTH_USER_SET, false)) {
            ed.remove(KEY_SELECTED_LINE_WIDTH);
        }
        if (!sp.getBoolean(KEY_SELECTED_ALPHA_USER_SET, false)) {
            ed.remove(KEY_SELECTED_LINE_ALPHA);
        }
        if (!sp.getBoolean(KEY_SELECTED_DASHED_USER_SET, false)) {
            ed.remove(KEY_SELECTED_LINE_DASH);
        }

        ed.commit();
    }

    // ===== COLOR INITIALIZATION =====

    /**
     * Initializes candle colors from SharedPreferences or fallback to defaults.
     */
    private void initCandleColors(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_CANDLE, Context.MODE_PRIVATE);
        bullishColor = sp.contains(KEY_BULL) ? sp.getInt(KEY_BULL, defBullColor) : defBullColor;
        bearishColor = sp.contains(KEY_BEAR) ? sp.getInt(KEY_BEAR, defBearColor) : defBearColor;
    }

    /**
     * Helper to read float from SharedPreferences with fallback.
     */
    private float getFloatCompat(SharedPreferences sp, String key, float defVal) {
        if (!sp.contains(key)) {
            return defVal;
        }
        try {
            return sp.getFloat(key, defVal);
        } catch (ClassCastException e) {
            return (float) sp.getInt(key, (int) defVal);
        }
    }

    /**
     * Helper to read int from SharedPreferences with fallback.
     */
    private int getIntCompat(SharedPreferences sp, String key, int defVal) {
        if (!sp.contains(key)) {
            return defVal;
        }
        return sp.getInt(key, defVal);
    }

    /**
     * Loads all chart appearance settings from SharedPreferences.
     * This includes all user-customizable options.
     */
    private void loadChartOptions(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);

        bodyWidthFraction = sp.contains(KEY_BODY_FRACTION) ?
                getFloatCompat(sp, KEY_BODY_FRACTION, defBodyFraction) :
                defBodyFraction;

        wickWidthPx = sp.contains(KEY_WICK_WIDTH) ?
                getFloatCompat(sp, KEY_WICK_WIDTH, defWickWidthPx) :
                defWickWidthPx;

        maLineWidthPx = sp.contains(KEY_MA_WIDTH) ?
                getFloatCompat(sp, KEY_MA_WIDTH, defMaWidthPx) :
                defMaWidthPx;

        showGrid = sp.contains(KEY_SHOW_GRID) ?
                sp.getBoolean(KEY_SHOW_GRID, defShowGrid) :
                defShowGrid;

        showVolume = sp.contains(KEY_SHOW_VOLUME) ?
                sp.getBoolean(KEY_SHOW_VOLUME, defShowVolume) :
                defShowVolume;

        visibleCandleCount = sp.contains(KEY_VISIBLE_COUNT) ?
                sp.getInt(KEY_VISIBLE_COUNT, defVisibleCount) :
                defVisibleCount;

        showLastPriceLine = sp.contains(KEY_SHOW_LAST_PRICE) ?
                sp.getBoolean(KEY_SHOW_LAST_PRICE, defShowLastPrice) :
                defShowLastPrice;

        if (sp.contains(KEY_LAST_PRICE_LINE_COLOR)) {
            lastPriceLineColor = sp.getInt(KEY_LAST_PRICE_LINE_COLOR, defLastPriceLineColor);
        } else {
            lastPriceLineColor = defLastPriceLineColor;
        }

        if (sp.contains(KEY_LAST_PRICE_BG_COLOR)) {
            lastPriceBgColor = sp.getInt(KEY_LAST_PRICE_BG_COLOR, defLastPriceBgColor);
        } else {
            lastPriceBgColor = defLastPriceBgColor;
        }

        priceTextSizePx = sp.contains(KEY_PRICE_TEXT_SIZE) ?
                getFloatCompat(sp, KEY_PRICE_TEXT_SIZE, defPriceTextSizePx) :
                defPriceTextSizePx;

        if (sp.contains(KEY_PRICE_TEXT_COLOR)) {
            priceTextColor = sp.getInt(KEY_PRICE_TEXT_COLOR, defPriceTextColor);
        } else {
            priceTextColor = defPriceTextColor;
        }

        if (sp.contains(KEY_GRID_COLOR)) {
            gridColor = sp.getInt(KEY_GRID_COLOR, defGridColor);
        } else {
            gridColor = defGridColor;
        }

        bgColor = sp.contains(KEY_BG_COLOR) ? sp.getInt(KEY_BG_COLOR, 0) : 0;

        lastLineWidthPx = sp.contains(KEY_LAST_LINE_WIDTH) ?
                getFloatCompat(sp, KEY_LAST_LINE_WIDTH, defLastLineWidthPx) :
                defLastLineWidthPx;

        lastLineDashed = sp.contains(KEY_LAST_LINE_DASH) ?
                sp.getBoolean(KEY_LAST_LINE_DASH, defLastDashed) :
                defLastDashed;

        lastPriceLabelTextSizePx = sp.contains(KEY_LAST_LABEL_TEXT_SIZE) ?
                getFloatCompat(sp, KEY_LAST_LABEL_TEXT_SIZE, defLabelTextSizePx) :
                defLabelTextSizePx;

        if (sp.contains(KEY_LAST_LABEL_TEXT_COLOR)) {
            lastPriceLabelTextColor = sp.getInt(KEY_LAST_LABEL_TEXT_COLOR, defLabelTextColor);
        } else {
            lastPriceLabelTextColor = defLabelTextColor;
        }

        if (sp.contains(KEY_SELECTED_LINE_COLOR)) {
            selectedLineColor = sp.getInt(KEY_SELECTED_LINE_COLOR, defSelectedLineColor);
        } else {
            selectedLineColor = defSelectedLineColor;
        }

        selectedLineWidthPx = sp.contains(KEY_SELECTED_LINE_WIDTH) ?
                getFloatCompat(sp, KEY_SELECTED_LINE_WIDTH, defSelectedLineWidthPx) :
                defSelectedLineWidthPx;

        selectedLineAlpha = sp.contains(KEY_SELECTED_LINE_ALPHA) ?
                getIntCompat(sp, KEY_SELECTED_LINE_ALPHA, defSelectedAlpha) :
                defSelectedAlpha;

        selectedLineDashed = sp.contains(KEY_SELECTED_LINE_DASH) ?
                sp.getBoolean(KEY_SELECTED_LINE_DASH, defSelectedDashed) :
                defSelectedDashed;

        showVolMa = sp.contains(KEY_VOL_SHOW_MA) ?
                sp.getBoolean(KEY_VOL_SHOW_MA, true) :
                true;

        volMa1Period = sp.contains(KEY_VOL_MA1_PERIOD) ?
                sp.getInt(KEY_VOL_MA1_PERIOD, volMa1Period) :
                volMa1Period;

        volMa2Period = sp.contains(KEY_VOL_MA2_PERIOD) ?
                sp.getInt(KEY_VOL_MA2_PERIOD, volMa2Period) :
                volMa2Period;

        if (sp.contains(KEY_VOL_MA1_COLOR)) {
            volMa1Color = sp.getInt(KEY_VOL_MA1_COLOR, volMa1Color);
        }

        if (sp.contains(KEY_VOL_MA2_COLOR)) {
            volMa2Color = sp.getInt(KEY_VOL_MA2_COLOR, volMa2Color);
        }

        volMaWidthPx = sp.contains(KEY_VOL_MA_WIDTH) ?
                getFloatCompat(sp, KEY_VOL_MA_WIDTH, volMaWidthPx) :
                volMaWidthPx;

        SharedPreferences sp2 = context.getSharedPreferences(
                "chart_settings",
                Context.MODE_PRIVATE
        );
        if (sp2.contains("label_bg")) {
            lastPriceBgColor = sp2.getInt("label_bg", lastPriceBgColor);
        } else if (sp2.contains("current_price_label_bg")) {
            lastPriceBgColor = sp2.getInt("current_price_label_bg", lastPriceBgColor);
        }

        if (sp2.contains("label_text_color")) {
            lastPriceLabelTextColor = sp2.getInt("label_text_color", lastPriceLabelTextColor);
        } else if (sp2.contains("current_price_label_text")) {
            lastPriceLabelTextColor = sp2.getInt("current_price_label_text", lastPriceLabelTextColor);
        }

        if (sp2.contains("grid_color")) {
            gridColor = sp2.getInt("grid_color", gridColor);
        }
        if (sp2.contains("price_text_color")) {
            priceTextColor = sp2.getInt("price_text_color", priceTextColor);
        }
    }

    // ===== GETTERS =====
    public int getBullishColor() { return bullishColor; }
    public int getBearishColor() { return bearishColor; }
    public float getBodyWidthFraction() { return bodyWidthFraction; }
    public float getWickWidthPx() { return wickWidthPx; }
    public float getMaLineWidthPx() { return maLineWidthPx; }
    public boolean isShowGrid() { return showGrid; }
    public boolean isShowVolume() { return showVolume; }
    public int getVisibleCandleCountValue() { return visibleCandleCount; }
    public boolean isShowLastPriceLine() { return showLastPriceLine; }
    public int getLastPriceLineColor() { return lastPriceLineColor; }
    public int getLastPriceBgColor() { return lastPriceBgColor; }
    public float getPriceTextSizePx() { return priceTextSizePx; }
    public int getPriceTextColor() { return priceTextColor; }
    public int getGridColor() { return gridColor; }
    public int getBgColor() { return bgColor; }
    public float getLastLineWidthPx() { return lastLineWidthPx; }
    public boolean isLastLineDashed() { return lastLineDashed; }
    public float getLastPriceLabelTextSizePx() { return lastPriceLabelTextSizePx; }
    public int getLastPriceLabelTextColor() { return lastPriceLabelTextColor; }
    public int getSelectedLineColor() { return selectedLineColor; }
    public float getSelectedLineWidthPx() { return selectedLineWidthPx; }
    public int getSelectedLineAlpha() { return selectedLineAlpha; }
    public boolean isSelectedLineDashed() { return selectedLineDashed; }
    public boolean isShowVolMa() { return showVolMa; }
    public int getVolMa1Period() { return volMa1Period; }
    public int getVolMa2Period() { return volMa2Period; }
    public int getVolMa1Color() { return volMa1Color; }
    public int getVolMa2Color() { return volMa2Color; }
    public float getVolMaWidthPx() { return volMaWidthPx; }

    // ===== SETTERS =====
    public void setBodyFraction(float fraction) {
        this.bodyWidthFraction = fraction;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_BODY_FRACTION, fraction).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setWickWidthPx(float widthPx) {
        this.wickWidthPx = widthPx;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_WICK_WIDTH, widthPx).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setMaLineWidthPx(float widthPx) {
        this.maLineWidthPx = widthPx;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_MA_WIDTH, widthPx).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setShowGrid(boolean show) {
        this.showGrid = show;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_SHOW_GRID, show).commit();
        invalidate();
    }

    public void setShowVolume(boolean show) {
        this.showVolume = show;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_SHOW_VOLUME, show).commit();
        invalidate();
    }

    public void setVisibleCandleCount(int count) {
        this.visibleCandleCount = count;
        clampVisibleCount();
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_VISIBLE_COUNT, this.visibleCandleCount).commit();
        clampTranslationX();
        invalidate();
    }

    public void setShowLastPriceLine(boolean show) {
        this.showLastPriceLine = show;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_SHOW_LAST_PRICE, show).commit();
        invalidate();
    }

    public void setLastPriceLineColor(int color) {
        this.lastPriceLineColor = color;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_LAST_PRICE_LINE_COLOR, color).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setBgColor(int color) {
        this.bgColor = color;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        if (color == 0) {
            sp.edit().remove(KEY_BG_COLOR).commit();
        } else {
            sp.edit().putInt(KEY_BG_COLOR, color).commit();
        }
        initPaints(getContext());
        invalidate();
    }

    public void setPriceTextSizePx(float sizePx) {
        this.priceTextSizePx = sizePx;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_PRICE_TEXT_SIZE, sizePx).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setLastLineWidthPx(float widthPx) {
        this.lastLineWidthPx = widthPx;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_LAST_LINE_WIDTH, widthPx).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setLastLineDashed(boolean dashed) {
        this.lastLineDashed = dashed;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_LAST_LINE_DASH, dashed).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setCurrentPriceLabelTextSizePx(float sizePx) {
        this.lastPriceLabelTextSizePx = sizePx;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_LAST_LABEL_TEXT_SIZE, sizePx).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setGridColor(int color) {
        this.gridColor = color;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_GRID_COLOR, color).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setPriceTextColor(int color) {
        this.priceTextColor = color;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_PRICE_TEXT_COLOR, color).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setCurrentPriceLabelBackground(int color) {
        this.lastPriceBgColor = color;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_LAST_PRICE_BG_COLOR, color).commit();

        SharedPreferences sp2 = getContext().getSharedPreferences(
                "chart_settings",
                Context.MODE_PRIVATE
        );
        sp2.edit()
                .putInt("label_bg", color)
                .putInt("current_price_label_bg", color)
                .commit();

        initPaints(getContext());
        invalidate();
    }

    public void setCurrentPriceLabelTextColor(int color) {
        this.lastPriceLabelTextColor = color;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_LAST_LABEL_TEXT_COLOR, color).commit();

        SharedPreferences sp2 = getContext().getSharedPreferences(
                "chart_settings",
                Context.MODE_PRIVATE
        );
        sp2.edit()
                .putInt("label_text_color", color)
                .putInt("current_price_label_text", color)
                .commit();

        initPaints(getContext());
        invalidate();
    }

    public void setLastPriceLabelAppearance(int bgColor, int textColor, float textSizePx) {
        setCurrentPriceLabelBackground(bgColor);
        setCurrentPriceLabelTextColor(textColor);
        setCurrentPriceLabelTextSizePx(textSizePx);
    }

    /**
     * Sets selected line color (auto mode, does not mark as user-set).
     */
    public void setSelectedLineColor(int color) {
        this.selectedLineColor = color;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_SELECTED_LINE_COLOR, color).commit();
        initPaints(getContext());
        invalidate();
    }

    /**
     * Sets selected line color and marks it as user-set.
     */
    public void setSelectedLineColorByUser(int color) {
        this.selectedLineColor = color;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit()
                .putInt(KEY_SELECTED_LINE_COLOR, color)
                .putBoolean(KEY_SELECTED_COLOR_USER_SET, true)
                .commit();
        initPaints(getContext());
        invalidate();
    }

    public void setSelectedLineWidthPx(float widthPx) {
        this.selectedLineWidthPx = widthPx;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_SELECTED_LINE_WIDTH, widthPx).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setSelectedLineWidthByUser(float widthPx) {
        this.selectedLineWidthPx = widthPx;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit()
                .putFloat(KEY_SELECTED_LINE_WIDTH, widthPx)
                .putBoolean(KEY_SELECTED_WIDTH_USER_SET, true)
                .commit();
        initPaints(getContext());
        invalidate();
    }

    public void setSelectedLineAlpha(int alpha) {
        this.selectedLineAlpha = alpha;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_SELECTED_LINE_ALPHA, alpha).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setSelectedLineAlphaByUser(int alpha) {
        this.selectedLineAlpha = alpha;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit()
                .putInt(KEY_SELECTED_LINE_ALPHA, alpha)
                .putBoolean(KEY_SELECTED_ALPHA_USER_SET, true)
                .commit();
        initPaints(getContext());
        invalidate();
    }

    public void setSelectedLineDashed(boolean dashed) {
        this.selectedLineDashed = dashed;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_SELECTED_LINE_DASH, dashed).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setSelectedLineDashedByUser(boolean dashed) {
        this.selectedLineDashed = dashed;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit()
                .putBoolean(KEY_SELECTED_LINE_DASH, dashed)
                .putBoolean(KEY_SELECTED_DASHED_USER_SET, true)
                .commit();
        initPaints(getContext());
        invalidate();
    }

    /**
     * Sets all selected line appearance properties (auto mode).
     */
    public void setSelectedLineAppearance(int color, float widthPx, int alpha, boolean dashed) {
        setSelectedLineColor(color);
        setSelectedLineWidthPx(widthPx);
        setSelectedLineAlpha(alpha);
        setSelectedLineDashed(dashed);
    }

    /**
     * Sets selected line appearance and marks only the touched properties as user-set.
     */
    public void setSelectedLineAppearanceByUser(int color, float widthPx, int alpha, boolean dashed,
                                                boolean colorTouched, boolean widthTouched,
                                                boolean alphaTouched, boolean dashedTouched) {
        if (colorTouched) {
            setSelectedLineColorByUser(color);
        }
        if (widthTouched) {
            setSelectedLineWidthByUser(widthPx);
        }
        if (alphaTouched) {
            setSelectedLineAlphaByUser(alpha);
        }
        if (dashedTouched) {
            setSelectedLineDashedByUser(dashed);
        }
    }

    // ===== VOLUME MA SETTERS =====

    public void setVolMaPeriods(int period1, int period2) {
        this.volMa1Period = period1;
        this.volMa2Period = period2;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit()
                .putInt(KEY_VOL_MA1_PERIOD, period1)
                .putInt(KEY_VOL_MA2_PERIOD, period2)
                .commit();
        calculateVolumeMas();
        invalidate();
    }

    public void setShowVolMa(boolean show) {
        this.showVolMa = show;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_VOL_SHOW_MA, show).commit();
        invalidate();
    }

    public void setVolMa1Color(int color) {
        if (color == 0) {
            return;
        }
        this.volMa1Color = color;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_VOL_MA1_COLOR, color).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setVolMa2Color(int color) {
        if (color == 0) {
            return;
        }
        this.volMa2Color = color;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_VOL_MA2_COLOR, color).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setVolMaWidthPx(float widthPx) {
        this.volMaWidthPx = widthPx;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_VOL_MA_WIDTH, widthPx).commit();
        initPaints(getContext());
        invalidate();
    }

    public void setVolMaAppearance(boolean show, int color1, int color2, float widthPx, int period1, int period2) {
        setShowVolMa(show);
        if (color1 != 0) {
            setVolMa1Color(color1);
        }
        if (color2 != 0) {
            setVolMa2Color(color2);
        }
        setVolMaWidthPx(widthPx);
        setVolMaPeriods(period1, period2);
    }

    // ===== CANDLE COLORS =====

    public void setCandleColors(int bull, int bear) {
        this.bullishColor = bull;
        this.bearishColor = bear;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_CANDLE, Context.MODE_PRIVATE);
        sp.edit()
                .putInt(KEY_BULL, bull)
                .putInt(KEY_BEAR, bear)
                .commit();
        initPaints(getContext());
        invalidate();
    }

    public void setChartOptions(float bodyFraction, float wickWidth, float maWidth,
                                boolean sGrid, boolean sVolume, int visCount) {
        setBodyFraction(bodyFraction);
        setWickWidthPx(wickWidth);
        setMaLineWidthPx(maWidth);
        setShowGrid(sGrid);
        setShowVolume(sVolume);
        setVisibleCandleCount(visCount);
    }

    public void setChartAppearance(boolean sLastPrice, int lastLineColor, int lastBgColor,
                                   float txtSize, int txtColor, int gColor, int bColor,
                                   float lastW, boolean lastDash) {
        setShowLastPriceLine(sLastPrice);
        if (lastLineColor != 0) {
            setLastPriceLineColor(lastLineColor);
        }
        if (lastBgColor != 0) {
            setCurrentPriceLabelBackground(lastBgColor);
        }
        if (txtSize > 0) {
            setPriceTextSizePx(txtSize);
        }
        if (lastW > 0) {
            setLastLineWidthPx(lastW);
        }
        setLastLineDashed(lastDash);
        if (gColor != 0) {
            setGridColor(gColor);
        }
        if (txtColor != 0) {
            setPriceTextColor(txtColor);
        }
        if (bColor != 0) {
            setBgColor(bColor);
        } else {
            SharedPreferences sp = getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
            sp.edit().remove(KEY_BG_COLOR).commit();
            bgColor = 0;
            initPaints(getContext());
            invalidate();
        }
    }

    public void setChartAppearance(boolean sLastPrice, int lastLineColor, int lastBgColor,
                                   float txtSize, int txtColor, int gColor) {
        setShowLastPriceLine(sLastPrice);
        if (lastLineColor != 0) {
            setLastPriceLineColor(lastLineColor);
        }
        if (lastBgColor != 0) {
            setCurrentPriceLabelBackground(lastBgColor);
        }
        if (txtSize > 0) {
            setPriceTextSizePx(txtSize);
        }
        if (gColor != 0) {
            setGridColor(gColor);
        }
        if (txtColor != 0) {
            setPriceTextColor(txtColor);
        }
    }

    // ===== CLAMP METHODS =====

    private void clampVisibleCount() {
        if (visibleCandleCount < MIN_VISIBLE_CANDLE_COUNT) {
            visibleCandleCount = MIN_VISIBLE_CANDLE_COUNT;
        }
        if (visibleCandleCount > MAX_VISIBLE_CANDLE_COUNT) {
            visibleCandleCount = MAX_VISIBLE_CANDLE_COUNT;
        }
    }

    // ===== PERSISTENCE =====

    public void clearSavedSettings() {
        getContext().getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE).edit().clear().commit();
        getContext().getSharedPreferences(PREF_CANDLE, Context.MODE_PRIVATE).edit().clear().commit();
        getContext().getSharedPreferences(PREF_MA, Context.MODE_PRIVATE).edit().clear().commit();
        getContext().getSharedPreferences(
                "chart_settings",
                Context.MODE_PRIVATE
        ).edit().clear().commit();
        getContext().getSharedPreferences(
                "chart_state_prefs",
                Context.MODE_PRIVATE
        ).edit().clear().commit();
    }

    public void resetToDefaultsFromLayout() {
        reloadDefaultColorsFromCurrentTheme();
        clearSavedSettings();
        bullishColor = defBullColor;
        bearishColor = defBearColor;
        bodyWidthFraction = defBodyFraction;
        wickWidthPx = defWickWidthPx;
        maLineWidthPx = defMaWidthPx;
        showGrid = defShowGrid;
        showVolume = defShowVolume;
        visibleCandleCount = defVisibleCount;
        showLastPriceLine = defShowLastPrice;
        lastPriceLineColor = defLastPriceLineColor;
        lastPriceBgColor = defLastPriceBgColor;
        priceTextSizePx = defPriceTextSizePx;
        priceTextColor = defPriceTextColor;
        gridColor = defGridColor;
        bgColor = 0;
        lastLineWidthPx = defLastLineWidthPx;
        lastLineDashed = defLastDashed;
        lastPriceLabelTextSizePx = defLabelTextSizePx;
        lastPriceLabelTextColor = defLabelTextColor;
        selectedLineColor = defSelectedLineColor;
        selectedLineWidthPx = defSelectedLineWidthPx;
        selectedLineAlpha = defSelectedAlpha;
        selectedLineDashed = defSelectedDashed;
        selectedIndex = -1;
        translationX = 0f;
        extraOffsetX = 0f;
        volMa1Period = getContext().getResources().getInteger(R.integer.default_vol_ma1_period);
        volMa2Period = getContext().getResources().getInteger(R.integer.default_vol_ma2_period);
        volMaWidthPx = getContext().getResources().getDimension(R.dimen.default_vol_ma_width);
        showVolMa = true;
        maLines.clear();
        for (MaLine m : defMaLines) {
            maLines.add(
                    new MaLine(m.period, m.color)
            );
        }
        if (defMaLines.size() >= 2) {
            volMa1Color = defMaLines.get(0).color;
            volMa2Color = defMaLines.get(1).color;
        }
        initPaints(getContext());
        clampVisibleCount();
        clampTranslationX();
        invalidate();
        notifyMa();
        if (updateListener != null) {
            updateListener.onNothingSelected();
        }
    }

    public void resetToDefaults() {
        resetToDefaultsFromLayout();
    }

    // ===== MA LINES PERSISTENCE =====

    private void saveMaLines(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_MA, Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maLines.size(); i++) {
            MaLine m = maLines.get(i);
            if (i > 0) {
                sb.append(SEP_SEMICOLON);
            }
            sb.append(m.period)
                    .append(SEP_COMMA)
                    .append(m.color);
        }
        sp.edit()
                .putString(KEY_MA, sb.toString())
                .commit();
    }

    private boolean loadMaLinesFromPrefs(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_MA, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_MA, null);
        if (s == null || s.isEmpty()) {
            return false;
        }
        String[] parts = s.split(SEP_SEMICOLON_REGEX);
        List<MaLine> list = new ArrayList<>();
        for (String p : parts) {
            String[] kv = p.split(SEP_COMMA_REGEX);
            if (kv.length != 2) {
                continue;
            }
            int period = Integer.parseInt(kv[0]);
            int color = Integer.parseInt(kv[1]);
            list.add(new MaLine(period, color));
        }
        if (!list.isEmpty()) {
            maLines = list;
            return true;
        }
        return false;
    }

    private void initMaLines(Context context) {
        if (loadMaLinesFromPrefs(context)) {
            return;
        }
        maLines.clear();
        for (MaLine m : defMaLines) {
            maLines.add(
                    new MaLine(m.period, m.color)
            );
        }
    }

    public List<MaLine> getMaLines() {
        return new ArrayList<>(maLines);
    }

    public void setMaLines(List<MaLine> list) {
        if (list == null) {
            return;
        }
        this.maLines = new ArrayList<>(list);
        saveMaLines(getContext());
        initPaints(getContext());
        invalidate();
        notifyMa();
    }

    // ===== THEME HELPER =====

    private int getThemeColor(int attr) {
        TypedValue tv = new TypedValue();
        getContext().getTheme().resolveAttribute(attr, tv, true);
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        } else {
            return getResources().getColor(tv.resourceId, getContext().getTheme());
        }
    }

    // ===== PAINT INITIALIZATION =====

    private void initPaints(Context context) {
        if (!defaultsLoadedFromLayout) {
            return;
        }

        int themeBg;
        try {
            themeBg = getThemeColor(android.R.attr.colorBackground);
        } catch (Exception ignored) {
            return;
        }

        if (bgColor != 0) {
            setBackgroundColor(bgColor);
        }

        if (bullishColor == 0) {
            bullishColor = defBullColor;
        }
        if (bearishColor == 0) {
            bearishColor = defBearColor;
        }
        if (gridColor == 0) {
            gridColor = defGridColor;
        }
        if (priceTextColor == 0) {
            priceTextColor = defPriceTextColor;
        }
        if (lastPriceLineColor == 0) {
            lastPriceLineColor = defLastPriceLineColor;
        }
        if (lastPriceBgColor == 0) {
            lastPriceBgColor = defLastPriceBgColor;
        }
        if (lastPriceLabelTextColor == 0) {
            lastPriceLabelTextColor = defLabelTextColor;
        }
        if (selectedLineColor == 0) {
            selectedLineColor = defSelectedLineColor;
        }
        if (volMa1Color == 0 && defMaLines.size() > 0) {
            volMa1Color = defMaLines.get(0).color;
        }
        if (volMa2Color == 0 && defMaLines.size() > 1) {
            volMa2Color = defMaLines.get(1).color;
        }

        bullishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bullishPaint.setColor(bullishColor);
        bullishPaint.setStyle(Paint.Style.FILL);

        bearishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bearishPaint.setColor(bearishColor);
        bearishPaint.setStyle(Paint.Style.FILL);

        volumeBullishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        volumeBullishPaint.setColor(bullishColor);
        volumeBullishPaint.setAlpha(VOLUME_ALPHA);
        volumeBullishPaint.setStyle(Paint.Style.FILL);

        volumeBearishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        volumeBearishPaint.setColor(bearishColor);
        volumeBearishPaint.setAlpha(VOLUME_ALPHA);
        volumeBearishPaint.setStyle(Paint.Style.FILL);

        wickBullishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wickBullishPaint.setColor(bullishColor);
        wickBullishPaint.setStrokeWidth(wickWidthPx);

        wickBearishPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wickBearishPaint.setColor(bearishColor);
        wickBearishPaint.setStrokeWidth(wickWidthPx);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(gridColor);
        gridPaint.setStrokeWidth(GRID_WIDTH);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(priceTextColor);
        textPaint.setTextSize(priceTextSizePx);

        lastPriceLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lastPriceLinePaint.setColor(lastPriceLineColor);
        lastPriceLinePaint.setStrokeWidth(lastLineWidthPx);
        lastPriceLinePaint.setStyle(Paint.Style.STROKE);
        if (lastLineDashed) {
            lastPriceLinePaint.setPathEffect(
                    new DashPathEffect(new float[]{DASH_ON, DASH_OFF}, 0f)
            );
        } else {
            lastPriceLinePaint.setPathEffect(null);
        }

        lastPriceBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lastPriceBgPaint.setColor(lastPriceBgColor);
        lastPriceBgPaint.setStyle(Paint.Style.FILL);

        lastPriceTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lastPriceTextPaint.setColor(lastPriceLabelTextColor);
        lastPriceTextPaint.setTextSize(lastPriceLabelTextSizePx);
        lastPriceTextPaint.setFakeBoldText(true);

        movingAverage5Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        movingAverage5Paint.setStyle(Paint.Style.STROKE);
        movingAverage5Paint.setStrokeWidth(maLineWidthPx);
        movingAverage5Paint.setStrokeCap(Paint.Cap.ROUND);
        movingAverage5Paint.setStrokeJoin(Paint.Join.ROUND);

        movingAverage10Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        movingAverage10Paint.setStyle(Paint.Style.STROKE);
        movingAverage10Paint.setStrokeWidth(maLineWidthPx);
        movingAverage10Paint.setStrokeCap(Paint.Cap.ROUND);
        movingAverage10Paint.setStrokeJoin(Paint.Join.ROUND);

        movingAverage20Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        movingAverage20Paint.setStyle(Paint.Style.STROKE);
        movingAverage20Paint.setStrokeWidth(maLineWidthPx);
        movingAverage20Paint.setStrokeCap(Paint.Cap.ROUND);
        movingAverage20Paint.setStrokeJoin(Paint.Join.ROUND);

        volMa5Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        volMa5Paint.setStyle(Paint.Style.STROKE);
        volMa5Paint.setStrokeWidth(volMaWidthPx);
        volMa5Paint.setStrokeCap(Paint.Cap.ROUND);
        volMa5Paint.setStrokeJoin(Paint.Join.ROUND);
        volMa5Paint.setColor(volMa1Color);

        volMa10Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        volMa10Paint.setStyle(Paint.Style.STROKE);
        volMa10Paint.setStrokeWidth(volMaWidthPx);
        volMa10Paint.setStrokeCap(Paint.Cap.ROUND);
        volMa10Paint.setStrokeJoin(Paint.Join.ROUND);
        volMa10Paint.setColor(volMa2Color);

        volHeaderTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        volHeaderTextPaint.setColor(priceTextColor);
        volHeaderTextPaint.setTextSize(priceTextSizePx * 0.85f);

        volHeaderMa5Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        volHeaderMa5Paint.setColor(volMa1Color);
        volHeaderMa5Paint.setTextSize(priceTextSizePx * 0.85f);

        volHeaderMa10Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        volHeaderMa10Paint.setColor(volMa2Color);
        volHeaderMa10Paint.setTextSize(priceTextSizePx * 0.85f);

        maExtraPaints.clear();
        if (maLines.isEmpty()) {
            initMaLines(context);
        }
        for (int i = 0; i < maLines.size(); i++) {
            Paint p;
            if (i == 0) {
                p = movingAverage5Paint;
            } else if (i == 1) {
                p = movingAverage10Paint;
            } else if (i == 2) {
                p = movingAverage20Paint;
            } else {
                p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(maLineWidthPx);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                maExtraPaints.add(p);
            }
            if (maLines.get(i).color == 0 && defMaLines.size() > 0) {
                maLines.get(i).color = defMaLines.get(
                        i % defMaLines.size()
                ).color;
            }
            p.setColor(maLines.get(i).color);
        }

        SharedPreferences sp = context.getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE);
        if (!sp.contains(KEY_VOL_MA1_COLOR) && maLines.size() > 0) {
            volMa1Color = maLines.get(0).color;
        }
        if (!sp.contains(KEY_VOL_MA2_COLOR) && maLines.size() > 1) {
            volMa2Color = maLines.get(1).color;
        }
        volMa5Paint.setColor(volMa1Color);
        volMa10Paint.setColor(volMa2Color);
        volHeaderMa5Paint.setColor(volMa1Color);
        volHeaderMa10Paint.setColor(volMa2Color);

        selectedLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedLinePaint.setColor(selectedLineColor);
        selectedLinePaint.setStrokeWidth(selectedLineWidthPx);
        selectedLinePaint.setAlpha(selectedLineAlpha);
        selectedLinePaint.setStyle(Paint.Style.STROKE);
        if (selectedLineDashed) {
            selectedLinePaint.setPathEffect(
                    new DashPathEffect(new float[]{DASH_ON, DASH_OFF}, 0f)
            );
        } else {
            selectedLinePaint.setPathEffect(null);
        }
    }

    // ===== THEME CHANGE HANDLING =====

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (defaultsLoadedFromLayout) {
            reloadDefaultColorsFromCurrentTheme();
            cleanupAutoIfNotUserSet();
            loadChartOptions(getContext());
            initCandleColors(getContext());
        }
        initPaints(getContext());
        invalidate();
    }

    public void refreshTheme() {
        if (defaultsLoadedFromLayout) {
            reloadDefaultColorsFromCurrentTheme();
            cleanupAutoIfNotUserSet();
            loadChartOptions(getContext());
            initCandleColors(getContext());
        }
        initPaints(getContext());
        invalidate();
    }

    // ===== GESTURE INITIALIZATION =====

    private void initGestures(Context context) {
        scaleGestureDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        visibleCandleCount = (int) (
                                visibleCandleCount / detector.getScaleFactor()
                        );
                        clampVisibleCount();
                        clampTranslationX();
                        invalidate();
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float distanceX, float distanceY) {
                        if (data.isEmpty()) {
                            return false;
                        }
                        int priceAxisW = PRICE_AXIS_WIDTH_DP;
                        int chartW = getWidth() - priceAxisW;
                        if (chartW <= 0) {
                            return false;
                        }
                        translationX -= distanceX;
                        clampTranslationX();
                        if (selectedIndex != -1) {
                            selectedIndex = -1;
                            if (updateListener != null) {
                                updateListener.onNothingSelected();
                            }
                        }
                        invalidate();
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        if (data.isEmpty()) {
                            return false;
                        }
                        int priceAxisW = PRICE_AXIS_WIDTH_DP;
                        int chartW = getWidth() - priceAxisW;
                        int count = Math.min(visibleCandleCount, data.size());
                        if (count == 0) {
                            return false;
                        }
                        if (e.getX() > chartW) {
                            if (selectedIndex != -1) {
                                selectedIndex = -1;
                                if (updateListener != null) {
                                    updateListener.onNothingSelected();
                                }
                                invalidate();
                            }
                            return false;
                        }
                        float candleWidth = chartW / (float) count;
                        float xWithOffset = e.getX() - extraOffsetX;
                        int index = (int) (xWithOffset / candleWidth) + startIndexCache;
                        if (index >= 0 && index < data.size()) {
                            selectedIndex = index;
                            if (updateListener != null) {
                                updateListener.onCandleSelected(data.get(index));
                            }
                            if (volumeClickListener != null) {
                                volumeClickListener.onVolumeClick(data.get(index));
                            }
                            invalidate();
                        } else {
                            selectedIndex = -1;
                            if (updateListener != null) {
                                updateListener.onNothingSelected();
                            }
                            invalidate();
                        }
                        return true;
                    }
                });
    }

    // ===== TRANSLATION CLAMP =====

    private void clampTranslationX() {
        if (data.isEmpty()) {
            translationX = 0f;
            extraOffsetX = 0f;
            return;
        }
        int priceAxisW = PRICE_AXIS_WIDTH_DP;
        int chartW = getWidth() - priceAxisW;
        if (chartW <= 0) {
            return;
        }
        int count = Math.min(visibleCandleCount, data.size());
        float candleWidth = chartW / (float) count;
        float maxScroll = (data.size() - count) * candleWidth;
        float minScroll = -chartW * MIN_SCROLL_FRACTION;

        if (translationX < minScroll) {
            translationX = minScroll;
        }
        if (translationX > maxScroll) {
            translationX = maxScroll;
        }

        if (translationX < 0f) {
            extraOffsetX = translationX;
        } else {
            extraOffsetX = 0f;
        }
    }

    // ===== DATA LOADING =====

    public void loadChart(String symbol, String interval) {
        this.currentSymbol = symbol;
        this.currentInterval = interval;
        this.selectedIndex = -1;
        this.translationX = 0f;
        this.extraOffsetX = 0f;
        stopLive();
        fetchCandles();
        startLive();
        startCountdown();
    }

    public void setFiatCode(String code) {
        this.fiatCode = code;
    }

    public float getFiatMultiplier() {
        return fiatMultiplier;
    }

    public void setFiatMultiplier(float mult) {
        if (mult <= 0f) {
            return;
        }
        this.fiatMultiplier = mult;
        invalidate();
    }

    public void setCountdown(String text) {
        invalidate();
    }

    private long getIntervalMillis(String interval) {
        if (interval == null) {
            return 15L * 60_000L;
        }
        switch (interval) {
            case "1m":
                return 60_000L;
            case "3m":
                return 3L * 60_000L;
            case "5m":
                return 5L * 60_000L;
            case "15m":
                return 15L * 60_000L;
            case "30m":
                return 30L * 60_000L;
            case "1h":
                return 60L * 60_000L;
            case "2h":
                return 2L * 60L * 60_000L;
            case "4h":
                return 4L * 60L * 60_000L;
            case "6h":
                return 6L * 60L * 60_000L;
            case "12h":
                return 12L * 60L * 60_000L;
            case "1d":
                return 24L * 60L * 60_000L;
            case "3d":
                return 3L * 24L * 60L * 60_000L;
            case "1w":
                return 7L * 24L * 60L * 60_000L;
            case "1M":
                return 30L * 24L * 60L * 60_000L;
            default:
                return 15L * 60_000L;
        }
    }

    // ===== LIVE UPDATES =====

    private void startCountdown() {
        if (countdownRunnable != null) {
            countdownHandler.removeCallbacks(countdownRunnable);
        }
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentCandleCloseTime > 0L) {
                    long now = System.currentTimeMillis();
                    long remain = currentCandleCloseTime - now;
                    if (remain < 0L) {
                        remain = 0L;
                    }
                    long seconds = (remain / 1000L) % 60L;
                    long minutes = (remain / 1000L / 60L) % 60L;
                    long hours = remain / 1000L / 60L / 60L;
                    String text;
                    if (getIntervalMillis(currentInterval) >= 24L * 60L * 60_000L) {
                        text = String.format(
                                Locale.US,
                                FMT_DHMS,
                                hours / 24L,
                                hours % 24L,
                                minutes,
                                seconds
                        );
                    } else {
                        text = String.format(
                                Locale.US,
                                FMT_HMS,
                                hours,
                                minutes,
                                seconds
                        );
                    }
                    if (updateListener != null) {
                        updateListener.onCountdownUpdate(text);
                    }
                }
                countdownHandler.postDelayed(this, COUNTDOWN_INTERVAL_MS);
            }
        };
        countdownHandler.post(countdownRunnable);
    }

    private void startLive() {
        if (liveRunnable != null) {
            liveHandler.removeCallbacks(liveRunnable);
        }
        liveRunnable = new Runnable() {
            @Override
            public void run() {
                fetchPriceAndCandle();
                liveHandler.postDelayed(this, LIVE_REFRESH_INTERVAL_MS);
            }
        };
        liveHandler.post(liveRunnable);
    }

    private void stopLive() {
        if (liveRunnable != null) {
            liveHandler.removeCallbacks(liveRunnable);
        }
        if (countdownRunnable != null) {
            countdownHandler.removeCallbacks(countdownRunnable);
        }
    }

    // ===== NOTIFICATIONS =====

    private void notifyMa() {
        if (data.isEmpty() || updateListener == null) {
            return;
        }
        int last = data.size() - 1;
        List<Float> values = new ArrayList<>();
        for (int i = 0; i < maLines.size(); i++) {
            values.add(
                    calculateMovingAverage(last, maLines.get(i).period)
            );
        }
        updateListener.onMaUpdate(values);
    }

    // ===== DATA FETCHING =====

    private void fetchCandles() {
        if (currentSymbol == null || currentInterval == null) {
            return;
        }
        new Thread(() -> {
            try {
                String urlString = getContext().getString(
                        R.string.url_klines,
                        currentSymbol,
                        currentInterval,
                        FETCH_LIMIT
                );
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(NETWORK_TIMEOUT);
                connection.setReadTimeout(NETWORK_TIMEOUT);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream())
                );
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
                reader.close();
                JSONArray jsonArray = new JSONArray(builder.toString());
                List<Candle> newData = new ArrayList<>(jsonArray.length());
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONArray kline = jsonArray.getJSONArray(i);
                    float open = (float) kline.getDouble(1);
                    float high = (float) kline.getDouble(2);
                    float low = (float) kline.getDouble(3);
                    float close = (float) kline.getDouble(4);
                    float volume = (float) kline.getDouble(5);
                    long openTime = kline.getLong(0);
                    long closeTime = kline.getLong(6);
                    newData.add(
                            new Candle(open, high, low, close, volume, openTime, closeTime)
                    );
                }
                mainHandler.post(() -> {
                    data = newData;
                    calculateVolumeMas();
                    clampTranslationX();
                    if (!data.isEmpty()) {
                        minPrice = Float.MAX_VALUE;
                        maxPrice = Float.MIN_VALUE;
                        maxVolume = 0f;
                        for (Candle candle : data) {
                            if (candle.low < minPrice) {
                                minPrice = candle.low;
                            }
                            if (candle.high > maxPrice) {
                                maxPrice = candle.high;
                            }
                            if (candle.volume > maxVolume) {
                                maxVolume = candle.volume;
                            }
                        }
                        lastPrice = data.get(data.size() - 1).close;
                        currentCandleCloseTime = data.get(data.size() - 1).closeTime;
                        float padding = (maxPrice - minPrice) * PRICE_PADDING_FRACTION;
                        minPrice -= padding;
                        maxPrice += padding;
                        if (updateListener != null) {
                            updateListener.onPriceUpdate(lastPrice, maxPrice, minPrice);
                        }
                        notifyMa();
                    }
                    invalidate();
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void fetchPriceAndCandle() {
        if (currentSymbol == null) {
            return;
        }
        new Thread(() -> {
            try {
                String tickerUrl = getContext().getString(
                        R.string.url_ticker,
                        currentSymbol
                );
                HttpURLConnection connection = (HttpURLConnection)
                        new URL(tickerUrl).openConnection();
                connection.setConnectTimeout(NETWORK_TIMEOUT);
                connection.setReadTimeout(NETWORK_TIMEOUT);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream())
                );
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
                reader.close();
                JSONObject jsonObject = new JSONObject(builder.toString());
                float price = (float) jsonObject.getDouble(JSON_LAST_PRICE);
                float high = (float) jsonObject.getDouble(JSON_HIGH_PRICE);
                float low = (float) jsonObject.getDouble(JSON_LOW_PRICE);
                float volBtc = (float) jsonObject.getDouble(JSON_VOLUME);
                float volUsdt = (float) jsonObject.getDouble(JSON_QUOTE_VOLUME);
                float changePercent = (float) jsonObject.getDouble(JSON_PRICE_CHANGE_PERCENT);
                mainHandler.post(() -> {
                    if (!data.isEmpty()) {
                        Candle lastCandle = data.get(data.size() - 1);
                        Candle updatedCandle = new Candle(
                                lastCandle.open,
                                Math.max(lastCandle.high, price),
                                Math.min(lastCandle.low, price),
                                price,
                                lastCandle.volume,
                                lastCandle.openTime,
                                lastCandle.closeTime
                        );
                        data.set(data.size() - 1, updatedCandle);
                        lastPrice = price;
                        currentCandleCloseTime = updatedCandle.closeTime;
                        calculateVolumeMas();
                        if (System.currentTimeMillis() >= updatedCandle.closeTime) {
                            fetchCandles();
                        } else {
                            invalidate();
                        }
                        if (updateListener != null) {
                            updateListener.onPriceUpdate(price, high, low);
                            updateListener.onTickerUpdate(
                                    high, low, volBtc, volUsdt, changePercent
                            );
                        }
                        notifyMa();
                    }
                });
            } catch (Exception e) {
            }
        }).start();
    }

    // ===== CALCULATIONS =====

    private float calculateMovingAverage(int currentIndex, int period) {
        if (currentIndex < period - 1 || data.isEmpty()) {
            return 0f;
        }
        float sum = 0f;
        for (int i = 0; i < period; i++) {
            sum += data.get(currentIndex - i).close;
        }
        return sum / period;
    }

    private float calculateVolumeMaAt(int currentIndex, int period) {
        if (currentIndex < period - 1 || data.isEmpty()) {
            return 0f;
        }
        float sum = 0f;
        for (int i = 0; i < period; i++) {
            sum += data.get(currentIndex - i).volume;
        }
        return sum / period;
    }

    private void calculateVolumeMas() {
        volMa1Values.clear();
        volMa2Values.clear();
        if (data.isEmpty()) {
            return;
        }
        for (int i = 0; i < data.size(); i++) {
            volMa1Values.add(calculateVolumeMaAt(i, volMa1Period));
            volMa2Values.add(calculateVolumeMaAt(i, volMa2Period));
        }
        int lastIdx = data.size() - 1;
        lastVolValue = data.get(lastIdx).volume;
        lastVolMa1Value = volMa1Values.size() > lastIdx ? volMa1Values.get(lastIdx) : 0f;
        lastVolMa2Value = volMa2Values.size() > lastIdx ? volMa2Values.get(lastIdx) : 0f;
    }

    // ===== TOUCH EVENTS =====

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            int priceAxisW = PRICE_AXIS_WIDTH_DP;
            int chartW = getWidth() - priceAxisW;
            if (event.getX() > chartW) {
                if (selectedIndex != -1) {
                    selectedIndex = -1;
                    if (updateListener != null) {
                        updateListener.onNothingSelected();
                    }
                    invalidate();
                }
                return true;
            }
        }
        return true;
    }

    // ===== DRAWING =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int priceAxisWidth = PRICE_AXIS_WIDTH_DP;
        int timeAxisHeight = TIME_AXIS_HEIGHT;
        int volumeHeightPx = VOLUME_CHART_HEIGHT_DP;

        int fullWidth = getWidth();
        int fullHeight = getHeight();
        int chartWidth = fullWidth - priceAxisWidth;

        int priceChartHeight = fullHeight - TOP_PADDING_PX - BOTTOM_PADDING_PX
                - VOLUME_TOP_MARGIN_PX - volumeHeightPx - timeAxisHeight;

        if (priceChartHeight <= 0) {
            return;
        }

        drawGrid(canvas, chartWidth, priceChartHeight, volumeHeightPx);

        if (data.isEmpty()) {
            String loadingText = getResources().getString(R.string.chart_loading);
            canvas.drawText(
                    loadingText,
                    chartWidth / 2f - LOADING_TEXT_OFFSET,
                    fullHeight / 2f,
                    textPaint
            );
            return;
        }

        int count = Math.min(visibleCandleCount, data.size());
        float candleWidth = chartWidth / (float) count;
        int startIndex = calcStartIndex(count, candleWidth);

        DrawInfo info = new DrawInfo();
        info.chartWidth = chartWidth;
        info.fullWidth = fullWidth;
        info.priceChartHeight = priceChartHeight;
        info.volumeHeightPx = volumeHeightPx;
        info.timeAxisHeight = timeAxisHeight;
        info.count = count;
        info.startIndex = startIndex;
        info.candleWidth = candleWidth;
        info.displayMin = minPrice * fiatMultiplier;
        info.displayMax = maxPrice * fiatMultiplier;
        if (info.displayMax - info.displayMin == 0f) {
            info.displayMax = info.displayMin + 1f;
        }

        drawCandles(canvas, info);
        drawMovingAverages(canvas, info);
        drawSelectedLine(canvas, info);
        drawPriceAxis(canvas, info);
        drawLastPriceLine(canvas, info);
        drawVolumeAndTime(canvas, info);
    }

    // ===== DRAWING HELPERS =====

    private static class DrawInfo {
        int chartWidth;
        int fullWidth;
        int priceChartHeight;
        int volumeHeightPx;
        int timeAxisHeight;
        int count;
        int startIndex;
        float candleWidth;
        float displayMin;
        float displayMax;
    }

    private int calcStartIndex(int count, float candleWidth) {
        int startIndex;
        if (translationX >= 0f) {
            startIndex = data.size() - count - (int) (translationX / candleWidth);
        } else {
            startIndex = data.size() - count;
        }
        if (startIndex < 0) {
            startIndex = 0;
        }
        if (startIndex + count > data.size()) {
            startIndex = data.size() - count;
        }
        if (startIndex < 0) {
            startIndex = 0;
        }
        startIndexCache = startIndex;
        return startIndex;
    }

    private void drawGrid(Canvas canvas, int chartWidth, int priceChartHeight, int volumeHeightPx) {
        if (!showGrid) {
            canvas.drawLine(chartWidth, 0f, chartWidth, getHeight(), gridPaint);
            return;
        }
        for (int i = 0; i <= 4; i++) {
            float y = TOP_PADDING_PX + priceChartHeight * i / 4f;
            canvas.drawLine(0f, y, chartWidth, y, gridPaint);
        }
        float volumeSeparatorY = TOP_PADDING_PX + priceChartHeight + VOLUME_TOP_MARGIN_PX;
        canvas.drawLine(0f, volumeSeparatorY, chartWidth, volumeSeparatorY, gridPaint);
        canvas.drawLine(chartWidth, 0f, chartWidth, getHeight(), gridPaint);
    }

    private void drawCandles(Canvas canvas, DrawInfo info) {
        float bodyWidth = info.candleWidth * bodyWidthFraction;
        float minBody = BODY_MIN_WIDTH;
        float maxBody = BODY_MAX_WIDTH;
        if (bodyWidth < minBody) {
            bodyWidth = minBody;
        }
        if (bodyWidth > maxBody) {
            bodyWidth = maxBody;
        }

        float priceRange = info.displayMax - info.displayMin;
        if (priceRange == 0f) {
            priceRange = 1f;
        }

        for (int i = 0; i < info.count; i++) {
            int dataIndex = info.startIndex + i;
            if (dataIndex >= data.size()) {
                break;
            }
            Candle candle = data.get(dataIndex);
            float x = i * info.candleWidth + info.candleWidth / 2f + extraOffsetX;

            float highY = TOP_PADDING_PX + info.priceChartHeight
                    - ((candle.high * fiatMultiplier - info.displayMin)
                    / priceRange * info.priceChartHeight);
            float lowY = TOP_PADDING_PX + info.priceChartHeight
                    - ((candle.low * fiatMultiplier - info.displayMin)
                    / priceRange * info.priceChartHeight);
            float openY = TOP_PADDING_PX + info.priceChartHeight
                    - ((candle.open * fiatMultiplier - info.displayMin)
                    / priceRange * info.priceChartHeight);
            float closeY = TOP_PADDING_PX + info.priceChartHeight
                    - ((candle.close * fiatMultiplier - info.displayMin)
                    / priceRange * info.priceChartHeight);

            boolean isBullish = candle.close >= candle.open;
            Paint currentWickPaint = isBullish ? wickBullishPaint : wickBearishPaint;
            Paint bodyPaint = isBullish ? bullishPaint : bearishPaint;

            canvas.drawLine(x, highY, x, lowY, currentWickPaint);

            float top = Math.min(openY, closeY);
            float bottom = Math.max(openY, closeY);
            float minH = CANDLE_MIN_HEIGHT;
            if (Math.abs(bottom - top) < minH) {
                bottom = top + minH;
            }
            canvas.drawRect(
                    x - bodyWidth / 2f,
                    top,
                    x + bodyWidth / 2f,
                    bottom,
                    bodyPaint
            );
        }
    }

    private void drawMovingAverages(Canvas canvas, DrawInfo info) {
        float priceRange = info.displayMax - info.displayMin;
        if (priceRange == 0f) {
            priceRange = 1f;
        }

        for (int maIndex = 0; maIndex < maLines.size(); maIndex++) {
            MaLine maLine = maLines.get(maIndex);
            int period = maLine.period;
            Paint paint;
            if (maIndex == 0) {
                paint = movingAverage5Paint;
            } else if (maIndex == 1) {
                paint = movingAverage10Paint;
            } else if (maIndex == 2) {
                paint = movingAverage20Paint;
            } else {
                int extraIdx = maIndex - 3;
                paint = (extraIdx < maExtraPaints.size()) ?
                        maExtraPaints.get(extraIdx) : movingAverage20Paint;
            }
            paint.setColor(maLine.color);

            float previousX = 0f;
            float previousY = 0f;
            boolean isFirstPoint = true;
            for (int i = 0; i < info.count; i++) {
                int dataIndex = info.startIndex + i;
                if (dataIndex >= data.size()) {
                    break;
                }
                float movingAverage = calculateMovingAverage(dataIndex, period);
                if (movingAverage == 0f) {
                    continue;
                }
                float x = i * info.candleWidth + info.candleWidth / 2f + extraOffsetX;
                float y = TOP_PADDING_PX + info.priceChartHeight
                        - ((movingAverage * fiatMultiplier - info.displayMin)
                        / priceRange * info.priceChartHeight);
                if (!isFirstPoint) {
                    canvas.drawLine(previousX, previousY, x, y, paint);
                }
                previousX = x;
                previousY = y;
                isFirstPoint = false;
            }
        }
    }

    private void drawSelectedLine(Canvas canvas, DrawInfo info) {
        if (selectedIndex >= info.startIndex &&
                selectedIndex < info.startIndex + info.count) {
            float selectedX = (selectedIndex - info.startIndex) * info.candleWidth
                    + info.candleWidth / 2f + extraOffsetX;
            canvas.drawLine(
                    selectedX,
                    TOP_PADDING_PX,
                    selectedX,
                    TOP_PADDING_PX + info.priceChartHeight,
                    selectedLinePaint
            );
        }
    }

    private void drawLastPriceLine(Canvas canvas, DrawInfo info) {
        if (lastPrice <= 0f || !showLastPriceLine) {
            return;
        }
        float priceRange = info.displayMax - info.displayMin;
        if (priceRange == 0f) {
            priceRange = 1f;
        }

        float lastPriceY = TOP_PADDING_PX + info.priceChartHeight
                - ((lastPrice * fiatMultiplier - info.displayMin)
                / priceRange * info.priceChartHeight);
        canvas.drawLine(
                0f,
                lastPriceY,
                info.chartWidth,
                lastPriceY,
                lastPriceLinePaint
        );

        boolean isBigFiat = fiatMultiplier > BIG_FIAT_THRESHOLD;
        String fmt = isBigFiat ? FMT_PRICE_0 : FMT_PRICE_2;

        float labelH = PRICE_TEXT_OFFSET + TEXT_SIZE;
        float top = lastPriceY - labelH;
        float bottom = lastPriceY + labelH;
        canvas.drawRect(
                info.chartWidth,
                top,
                info.fullWidth,
                bottom,
                lastPriceBgPaint
        );

        String label = String.format(
                Locale.US,
                fmt,
                lastPrice * fiatMultiplier
        );
        float tx = info.chartWidth + PRICE_TEXT_MARGIN / 2f;
        float ty = lastPriceY + TEXT_SIZE / 3f;
        canvas.drawText(label, tx, ty, lastPriceTextPaint);
    }

    private void drawPriceAxis(Canvas canvas, DrawInfo info) {
        boolean isBigFiatAxis = fiatMultiplier > BIG_FIAT_THRESHOLD;
        String axisFmt = isBigFiatAxis ? FMT_PRICE_0 : FMT_PRICE_2;
        for (int i = 0; i <= 4; i++) {
            float price = info.displayMax
                    - (info.displayMax - info.displayMin) * i / 4f;
            float y = TOP_PADDING_PX + info.priceChartHeight * i / 4f + PRICE_TEXT_OFFSET;
            String priceText = String.format(Locale.US, axisFmt, price);
            float x = info.chartWidth + PRICE_TEXT_MARGIN;
            canvas.drawText(priceText, x, y, textPaint);
        }
    }

    private void drawVolumeAndTime(Canvas canvas, DrawInfo info) {
        float volumeTop = TOP_PADDING_PX + info.priceChartHeight + VOLUME_TOP_MARGIN_PX;
        float volumeBottom = volumeTop + info.volumeHeightPx;
        float timeTop = volumeBottom;

        if (maxVolume == 0f) {
            maxVolume = 1f;
        }

        float bodyWidth = info.candleWidth * bodyWidthFraction;
        float minBody = BODY_MIN_WIDTH;
        float maxBody = BODY_MAX_WIDTH;
        if (bodyWidth < minBody) {
            bodyWidth = minBody;
        }
        if (bodyWidth > maxBody) {
            bodyWidth = maxBody;
        }

        if (showVolume) {
            float headerY = volumeTop - 6f;
            if (headerY < TOP_PADDING_PX + info.priceChartHeight + 2f) {
                headerY = volumeTop - 2f;
            }
            drawVolumeHeader(canvas, headerY);
        }

        if (showVolume) {
            for (int i = 0; i < info.count; i++) {
                int dataIndex = info.startIndex + i;
                if (dataIndex >= data.size()) {
                    break;
                }
                Candle candle = data.get(dataIndex);
                float x = i * info.candleWidth + info.candleWidth / 2f + extraOffsetX;
                float volumeBarHeight = info.volumeHeightPx
                        * (candle.volume / maxVolume);
                float barTop = volumeBottom - volumeBarHeight;
                if (barTop < volumeTop) {
                    barTop = volumeTop;
                }
                Paint volumePaint = (candle.close >= candle.open) ?
                        volumeBullishPaint : volumeBearishPaint;
                canvas.drawRect(
                        x - bodyWidth / 2f,
                        barTop,
                        x + bodyWidth / 2f,
                        volumeBottom,
                        volumePaint
                );
            }

            if (showVolMa) {
                drawVolumeMaLines(canvas, info, volumeTop, volumeBottom);
            }
        }

        float timeBaseline = timeTop
                + (info.timeAxisHeight / 2f)
                + (TEXT_SIZE / 3f);
        for (int i = 0; i < info.count; i += Math.max(1, info.count / 4)) {
            int dataIndex = info.startIndex + i;
            if (dataIndex >= data.size()) {
                break;
            }
            float x = i * info.candleWidth + extraOffsetX;
            String timeText = timeFormat.format(
                    new Date(data.get(dataIndex).openTime)
            );
            canvas.drawText(timeText, x, timeBaseline, textPaint);
        }
    }

    private void drawVolumeHeader(Canvas canvas, float y) {
        if (data.isEmpty()) {
            return;
        }
        String volLabel = getContext().getString(R.string.vol_label);
        String maFormat = getContext().getString(R.string.vol_ma_format);

        String volStr = String.format(Locale.US, volLabel, lastVolValue);
        String ma5Str = String.format(Locale.US, maFormat, volMa1Period, lastVolMa1Value);
        String ma10Str = String.format(Locale.US, maFormat, volMa2Period, lastVolMa2Value);

        float x = 4f;
        canvas.drawText(volStr, x, y, volHeaderTextPaint);
        x += volHeaderTextPaint.measureText(volStr);

        if (showVolMa) {
            canvas.drawText(ma5Str, x, y, volHeaderMa5Paint);
            x += volHeaderMa5Paint.measureText(ma5Str);
            canvas.drawText(ma10Str, x, y, volHeaderMa10Paint);
        }
    }

    private void drawVolumeMaLines(Canvas canvas, DrawInfo info, float volumeTop, float volumeBottom) {
        if (volMa1Values.isEmpty() || volMa2Values.isEmpty()) {
            return;
        }
        if (maxVolume == 0f) {
            return;
        }

        float prevX1 = 0f;
        float prevY1 = 0f;
        float prevX2 = 0f;
        float prevY2 = 0f;
        boolean first1 = true;
        boolean first2 = true;

        for (int i = 0; i < info.count; i++) {
            int dataIndex = info.startIndex + i;
            if (dataIndex >= data.size()) {
                break;
            }
            if (dataIndex >= volMa1Values.size() || dataIndex >= volMa2Values.size()) {
                continue;
            }

            float x = i * info.candleWidth + info.candleWidth / 2f + extraOffsetX;

            float ma1 = volMa1Values.get(dataIndex);
            if (ma1 > 0f) {
                float y = volumeBottom - (ma1 / maxVolume) * info.volumeHeightPx;
                if (y < volumeTop) {
                    y = volumeTop;
                }
                if (!first1) {
                    canvas.drawLine(prevX1, prevY1, x, y, volMa5Paint);
                }
                prevX1 = x;
                prevY1 = y;
                first1 = false;
            }

            float ma2 = volMa2Values.get(dataIndex);
            if (ma2 > 0f) {
                float y = volumeBottom - (ma2 / maxVolume) * info.volumeHeightPx;
                if (y < volumeTop) {
                    y = volumeTop;
                }
                if (!first2) {
                    canvas.drawLine(prevX2, prevY2, x, y, volMa10Paint);
                }
                prevX2 = x;
                prevY2 = y;
                first2 = false;
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopLive();
    }
}
