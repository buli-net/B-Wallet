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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.utils.Fiat;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import wallet.Configuration;
import wallet.R;
import wallet.WalletApplication;
import wallet.exchangerate.ExchangeRateDao;
import wallet.exchangerate.ExchangeRateEntry;
import wallet.exchangerate.ExchangeRatesRepository;

/**
 * MarketChartActivity - Displays interactive cryptocurrency price chart with candlesticks,
 * technical indicators (MA, VOL MA), and real-time price updates from Binance.
 * 
 * Features:
 * - Full-screen candlestick chart with zoom and pan gestures
 * - Multiple timeframes (1m to 1M)
 * - Moving averages with customizable periods
 * - Volume chart with MA overlay
 * - Real-time price updates via WebSocket
 * - Customizable color themes for all chart elements
 * - Fiat currency conversion support
 * - Wallet balance display with BTC/Fiat conversion
 * 
 * UI Components:
 * - Chart view (MarketChartView)
 * - Price ticker (current, high, low, change, volume)
 * - Timeframe selector chips
 * - Chart settings popup with expandable sections
 * - Candle detail popup on tap
 * 
 * All colors and dimensions are loaded from resources (colors.xml, dimens.xml)
 * to support dark/light themes. No hardcoded values in code.
 */
public class MarketChartActivity extends Activity implements ViewModelStoreOwner, LifecycleOwner {

    // ===== CONSTANTS =====
    
    /** Base fraction for candle body width calculation (0.3 = 30% of candle width) */
    private static final float BODY_BASE_FRACTION = 0.3f;

    // ===== UI REFERENCES =====
    private MarketChartView marketChartView;
    private TextView textCurrentPrice;
    private TextView textFiat;
    private TextView textCountdown;
    private TextView textHigh24h;
    private TextView textLow24h;
    private TextView textVolBtc;
    private TextView textVolFiat;
    private TextView textChange24h;
    private TextView textMaLabel;
    private TextView textWalletBalance;
    private LinearLayout chipGroupTimeframe;
    private View popupCandleDetail;
    private TextView popupTime;
    private TextView popupOpen;
    private TextView popupHigh;
    private TextView popupLow;
    private TextView popupClose;
    private TextView popupVolume;
    private View btnChartSettings;

    // ===== PREFERENCES =====
    private static final String PREFS_CHART_STATE = "chart_state_prefs";
    private static final String KEY_INTERVAL = "interval";
    private static final String PREFS_CHART_SETTINGS = "chart_settings";

    // Hardcoded preference keys (from strings.xml)
    private static final String PREF_CHART = "chart_options_prefs";
    private static final String PREF_CANDLE = "candle_prefs";
    private static final String PREF_MA = "ma_prefs";
    private static final String BULLET_SEPARATOR = " • ";
    private static final String PALETTE_PREFIX = "palette_";

    // ===== CHART STATE =====
    private String currentSymbol = "BTCUSDT";
    private String currentInterval = "15m";

    // ===== FORMATTERS =====
    private final SimpleDateFormat fullTimeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    // ===== EXCHANGE RATE =====
    private ExchangeRateDao exchangeRateDao;
    private Configuration config;
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private String currentFiatCode = "USD";
    
    // ===== HANDLERS =====
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private float lastDisplayPrice = 0f;

    // ===== LIFECYCLE =====
    private final ViewModelStore viewModelStore = new ViewModelStore();
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);

    // ===== WALLET DATA =====
    private WalletBalanceViewModel balanceViewModel;
    private Coin currentBalance = null;
    private ExchangeRateEntry currentExchangeRate = null;
    private boolean isBlockchainSynced = false;

    private float currentMarketPriceFiat = 0f;

    // ===== COLOR HELPERS =====
    
    /**
     * Creates a color picker drawable with unified border style.
     * Used for all color selection cells in chart settings.
     */
    private GradientDrawable createColorViewDrawable(int color) {
        return createUnifiedColorDrawable(this, color);
    }

    /**
     * Static utility method to create a unified color picker drawable.
     * Applies consistent border, corner radius, and styling from XML resources.
     */
    public static GradientDrawable createUnifiedColorDrawable(Context ctx, int color) {
        Drawable d = ctx.getResources().getDrawable(R.drawable.color_picker_border, ctx.getTheme());
        if (!(d instanceof GradientDrawable)) {
            throw new IllegalStateException("color_picker_border drawable is not a GradientDrawable");
        }
        GradientDrawable gd = (GradientDrawable) d.mutate();
        gd.setColor(color);
        return gd;
    }

    /**
     * Loads color palette from XML array resource.
     */
    private int[] loadPaletteFromColorsXml() {
        int arrayResId = getResources().getIdentifier("chart_color_palette", "array", getPackageName());
        if (arrayResId == 0) {
            arrayResId = R.array.chart_color_palette;
        }

        TypedArray ta = getResources().obtainTypedArray(arrayResId);
        List<Integer> colors = new ArrayList<>();
        for (int i = 0; i < ta.length(); i++) {
            int color = ta.getColor(i, 0);
            if (color != 0) {
                colors.add(color);
            }
        }
        ta.recycle();

        int[] arr = new int[colors.size()];
        for (int i = 0; i < colors.size(); i++) {
            arr[i] = colors.get(i);
        }
        return arr;
    }

    // ===== CHART SETTINGS STATE =====
    
    /**
     * Internal state holder for chart settings popup.
     * Tracks all user selections before applying them.
     */
    private static class ChartSettingsState {
        int[] candlePalette;
        int[] curBull = new int[1];
        int[] curBear = new int[1];
        int[] bullIdx = new int[1];
        int[] bearIdx = new int[1];
        int[] selectedIdx = new int[1];

        float[] curWick = new float[1];
        float[] curMaW = new float[1];
        float[] curTxtSize = new float[1];
        float[] curLastW = new float[1];
        float[] curLabelSize = new float[1];
        float[] curSelectedW = new float[1];

        int[] curLastColor = new int[1];
        int[] curGridColor = new int[1];
        int[] curPriceTxtColor = new int[1];
        int[] curLabelBg = new int[1];
        int[] curLabelTextColorFinal = new int[1];
        int[] curSelectedColor = new int[1];
        int[] curSelectedAlpha = new int[1];

        boolean[] gridPicked = new boolean[1];
        boolean[] pricePicked = new boolean[1];
        boolean[] labelBgPicked = new boolean[1];
        boolean[] labelTextPicked = new boolean[1];
        boolean[] lastLinePicked = new boolean[1];

        // Selected line user action tracking
        boolean[] selectedColorTouched = new boolean[1];
        boolean[] selectedWidthTouched = new boolean[1];
        boolean[] selectedAlphaTouched = new boolean[1];
        boolean[] selectedDashedTouched = new boolean[1];

        float[] finalTxtSize = new float[1];
        float[] finalLabelSize = new float[1];

        List<MarketChartView.MaLine> tempList;

        // Candle section
        SeekBar sbBody;
        SeekBar sbWick;
        SeekBar sbMaW;
        SeekBar sbVis;
        SeekBar sbTxtSize;
        SeekBar sbLastW;
        SeekBar sbLabelSize;
        SeekBar sbSelectedWidth;
        SeekBar sbSelectedAlpha;
        Switch swGrid;
        Switch swVol;
        Switch swLast;
        Switch swDash;
        Switch swSelectedDash;
        RecyclerView recycler;

        // Volume MA section
        int[] curVolMa1Color = new int[1];
        int[] curVolMa2Color = new int[1];
        float[] curVolMaW = new float[1];
        int[] curVolMa1Period = new int[1];
        int[] curVolMa2Period = new int[1];
        boolean[] curShowVolMa = new boolean[1];

        SeekBar sbVolMaW;
        SeekBar sbVolMa1Period;
        SeekBar sbVolMa2Period;
        TextView tvVolMa1Period;
        TextView tvVolMa2Period;
        TextView tvVolMaW;
        Switch swVolMa;
        View viewVolMa1Color;
        View viewVolMa2Color;
        View viewGridColor;
    }

    // ===== LIFECYCLE METHODS =====

    @Override
    public ViewModelStore getViewModelStore() {
        return viewModelStore;
    }

    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    /**
     * Returns the default interval from string resources.
     */
    private String getDefaultInterval() {
        return getString(R.string.default_interval);
    }

    /**
     * Resets chart interval to default and refreshes the chart.
     */
    private void resetToDefaultInterval() {
        String defaultInterval = getDefaultInterval();
        currentInterval = defaultInterval;

        getSharedPreferences(PREFS_CHART_STATE, MODE_PRIVATE)
                .edit()
                .remove(KEY_INTERVAL)
                .commit();

        if (marketChartView != null) {
            marketChartView.loadChart(currentSymbol, currentInterval);
        }

        setupTimeframeChips();
    }

    /**
     * Extracts color value from a View's tag attribute.
     * Supports: @color/resource, #hex, or integer resource ID.
     */
    private int getColorFromTag(View v) {
        Object tag = v.getTag();

        if (tag instanceof Integer) {
            return getResources().getColor((Integer) tag, getTheme());
        }

        String s = tag.toString().trim();

        if (s.startsWith("#")) {
            return Color.parseColor(s);
        }

        if (s.startsWith("@color/")) {
            String colorName = s.replace("@color/", "");
            int resId = getResources().getIdentifier(colorName, "color", getPackageName());
            return getResources().getColor(resId, getTheme());
        }

        try {
            int resId = Integer.parseInt(s);
            return getResources().getColor(resId, getTheme());
        } catch (NumberFormatException e) {
            return Color.parseColor(s);
        }
    }

    /**
     * Loads default chart settings from layout XML files.
     * This centralizes all default values in XML, eliminating hardcoded values in Java.
     * Values are extracted from chart_settings_*.xml layouts.
     */
    private void loadDefaultsFromLayoutAndApply() {
        LayoutInflater inflater = getLayoutInflater();

        View candleRoot = inflater.inflate(R.layout.chart_settings_candle, null);
        View maRoot = inflater.inflate(R.layout.chart_settings_ma, null);
        View gridRoot = inflater.inflate(R.layout.chart_settings_grid, null);
        View lastPriceRoot = inflater.inflate(R.layout.chart_settings_last_price, null);
        View labelRoot = inflater.inflate(R.layout.chart_settings_label, null);
        View selectedRoot = inflater.inflate(R.layout.chart_settings_selected, null);
        View volMaRoot = inflater.inflate(R.layout.chart_settings_vol_ma, null);

        // Read dimensions from resources (dimens.xml)
        int defTopPadding = (int) getResources().getDimension(R.dimen.default_top_padding);
        int defBottomPadding = (int) getResources().getDimension(R.dimen.default_bottom_padding);
        int defVolumeHeight = (int) getResources().getDimension(R.dimen.default_volume_height);
        int defVolumeTopMargin = (int) getResources().getDimension(R.dimen.default_volume_top_margin);
        int defPriceAxisWidth = (int) getResources().getDimension(R.dimen.default_price_axis_width);
        int defTimeAxisHeight = (int) getResources().getDimension(R.dimen.default_time_axis_height);
        int defPriceTextMargin = (int) getResources().getDimension(R.dimen.default_price_text_margin);
        int defPriceTextOffset = (int) getResources().getDimension(R.dimen.default_price_text_offset);
        int defGridWidth = (int) getResources().getDimension(R.dimen.default_grid_width);
        int defBodyMinWidth = (int) getResources().getDimension(R.dimen.default_body_min_width);
        int defBodyMaxWidth = (int) getResources().getDimension(R.dimen.default_body_max_width);
        int defCandleMinWidth = (int) getResources().getDimension(R.dimen.default_candle_min_width);
        int defCandleMinHeight = (int) getResources().getDimension(R.dimen.default_candle_min_height);
        float defDashOn = getResources().getDimension(R.dimen.dash_on);
        float defDashOff = getResources().getDimension(R.dimen.dash_off);
        float defTimeTextOffset = getResources().getDimension(R.dimen.time_text_offset);
        float defLoadingTextOffset = getResources().getDimension(R.dimen.loading_text_offset);
        float defDefaultTextSize = getResources().getDimension(R.dimen.default_text_size);
        float defSelectedWidth = getResources().getDimension(R.dimen.default_selected_width);
        float defPopupTimeSize = getResources().getDimension(R.dimen.default_popup_time_size);
        float defPopupSize = getResources().getDimension(R.dimen.default_popup_size);

        // Extract views from inflated layouts
        View defBull = candleRoot.findViewById(R.id.viewBull);
        View defBear = candleRoot.findViewById(R.id.viewBear);
        SeekBar defWick = candleRoot.findViewById(R.id.sbWick);
        SeekBar defBody = candleRoot.findViewById(R.id.sbBody);
        SeekBar defVis = candleRoot.findViewById(R.id.sbVis);

        SeekBar defMaW = maRoot.findViewById(R.id.sbMaW);
        Switch defGrid = gridRoot.findViewById(R.id.swGrid);
        Switch defVol = volMaRoot.findViewById(R.id.swVol);
        View defGridColor = gridRoot.findViewById(R.id.viewGridColor);

        SeekBar defTxt = lastPriceRoot.findViewById(R.id.sbTxtSize);
        SeekBar defLastW = lastPriceRoot.findViewById(R.id.sbLastW);
        Switch defLast = lastPriceRoot.findViewById(R.id.swLast);
        Switch defDash = lastPriceRoot.findViewById(R.id.swDash);
        View defLastColor = lastPriceRoot.findViewById(R.id.viewLastColor);
        View defTxtColor = lastPriceRoot.findViewById(R.id.viewTxtColor);

        SeekBar defLabel = labelRoot.findViewById(R.id.sbLabelSize);
        View defLabelBg = labelRoot.findViewById(R.id.viewLabelBg);
        View defLabelTextColor = labelRoot.findViewById(R.id.viewLabelTextColor);

        SeekBar defSelW = selectedRoot.findViewById(R.id.sbSelectedWidth);
        SeekBar defSelAlpha = selectedRoot.findViewById(R.id.sbSelectedAlpha);
        Switch defSelDash = selectedRoot.findViewById(R.id.swSelectedDash);
        View defSelColor = selectedRoot.findViewById(R.id.viewSelectedLine);

        TextView tvPeriods = maRoot.findViewById(R.id.tvDefMaPeriods);
        TextView tvColors = maRoot.findViewById(R.id.tvDefMaColors);

        // Extract values from SeekBars and Switches
        float bodyFrac = BODY_BASE_FRACTION + defBody.getProgress() / 100f;
        float wickW = defWick.getProgress();
        float maW = defMaW.getProgress();
        int visCount = defVis.getProgress();
        boolean showG = defGrid.isChecked();
        boolean showV = defVol.isChecked();
        boolean showLast = defLast.isChecked();
        boolean dashed = defDash.isChecked();
        float txtSize = defTxt.getProgress();
        float lastW = defLastW.getProgress();
        float labelSize = defLabel.getProgress();

        // Extract colors from tags
        int bullColor = getColorFromTag(defBull);
        int bearColor = getColorFromTag(defBear);
        int lastColor = getColorFromTag(defLastColor);
        int gridColor = getColorFromTag(defGridColor);
        int txtColor = getColorFromTag(defTxtColor);
        int labelBg = getColorFromTag(defLabelBg);
        int labelText = getColorFromTag(defLabelTextColor);

        int selColor = getColorFromTag(defSelColor);
        float selW = defSelW.getProgress();
        int selAlpha = defSelAlpha.getProgress();
        boolean selDashed = defSelDash.isChecked();

        // Parse MA periods and colors from TextView
        List<MarketChartView.MaLine> defMa = new ArrayList<>();
        String[] pArr = tvPeriods.getText().toString().split(",");
        String[] cArr = tvColors.getText().toString().split(",");

        for (int i = 0; i < pArr.length; i++) {
            int per = Integer.parseInt(pArr[i].trim());
            int col = Color.parseColor(cArr[i % cArr.length].trim());
            defMa.add(new MarketChartView.MaLine(per, col));
        }

        // Apply all settings to chart view
        marketChartView.setViewDimensionsFromLayout(
                defTopPadding,
                defBottomPadding,
                defVolumeHeight,
                defVolumeTopMargin,
                defPriceAxisWidth,
                defTimeAxisHeight,
                defPriceTextMargin,
                defPriceTextOffset,
                defGridWidth,
                defBodyMinWidth,
                defBodyMaxWidth,
                defCandleMinWidth,
                defCandleMinHeight,
                defDashOn,
                defDashOff,
                defTimeTextOffset,
                defLoadingTextOffset,
                defDefaultTextSize,
                defSelectedWidth,
                defPopupTimeSize,
                defPopupSize
        );

        marketChartView.setDefaultsFromLayout(
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
                labelText,
                defMa,
                selColor,
                selW,
                selAlpha,
                selDashed
        );
    }

    // ===== ACTIVITY LIFECYCLE =====

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        setContentView(R.layout.activity_market_chart);

        // Initialize UI components
        marketChartView = findViewById(R.id.marketChartView);
        textCurrentPrice = findViewById(R.id.textCurrentPrice);
        textFiat = findViewById(R.id.textFiat);
        textCountdown = findViewById(R.id.textCountdown);
        textHigh24h = findViewById(R.id.textHigh24h);
        textLow24h = findViewById(R.id.textLow24h);
        textVolBtc = findViewById(R.id.textVolBtc);
        textVolFiat = findViewById(R.id.textVolFiat);
        textChange24h = findViewById(R.id.textChange24h);
        textMaLabel = findViewById(R.id.textMaLabel);
        textWalletBalance = findViewById(R.id.textWalletBalance);
        chipGroupTimeframe = findViewById(R.id.chipGroupTimeframe);
        popupCandleDetail = findViewById(R.id.popupCandleDetail);
        popupTime = findViewById(R.id.popupTime);
        popupOpen = findViewById(R.id.popupOpen);
        popupHigh = findViewById(R.id.popupHigh);
        popupLow = findViewById(R.id.popupLow);
        popupClose = findViewById(R.id.popupClose);
        popupVolume = findViewById(R.id.popupVolume);
        btnChartSettings = findViewById(R.id.btnChartSettings);

        // Set up chart settings button
        if (btnChartSettings != null) {
            btnChartSettings.setOnClickListener(v -> showChartSettingsPopup());
        }

        // Get symbol from intent if provided
        if (getIntent() != null && getIntent().hasExtra("symbol")) {
            currentSymbol = getIntent().getStringExtra("symbol");
        }

        // Initialize application dependencies
        WalletApplication application = (WalletApplication) getApplication();
        config = application.getConfiguration();
        prefs = application.getSharedPreferences("wallet_preferences", MODE_PRIVATE);
        exchangeRateDao = ExchangeRatesRepository.get(application).exchangeRateDao();

        // Set fiat currency
        currentFiatCode = config.getExchangeCurrencyCode();
        if (currentFiatCode == null) {
            currentFiatCode = "USD";
        }

        // Listen for currency changes
        prefsListener = (sharedPreferences, key) -> {
            if (Configuration.PREFS_KEY_EXCHANGE_CURRENCY.equals(key)) {
                String newCode = config.getExchangeCurrencyCode();
                if (newCode != null) {
                    currentFiatCode = newCode;
                    loadFiatRate();
                }
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);

        // Restore saved interval or use default
        SharedPreferences statePrefs = getSharedPreferences(PREFS_CHART_STATE, MODE_PRIVATE);
        String savedInterval = statePrefs.getString(KEY_INTERVAL, null);
        if (savedInterval != null && !savedInterval.isEmpty()) {
            currentInterval = savedInterval;
        } else {
            currentInterval = getDefaultInterval();
        }

        // Setup UI components
        setupTimeframeChips();
        setupChartListener();
        loadDefaultsFromLayoutAndApply();

        // Load chart data
        marketChartView.loadChart(currentSymbol, currentInterval);

        loadFiatRate();

        // Initialize wallet balance view model
        balanceViewModel = new ViewModelProvider(
                this,
                new ViewModelProvider.AndroidViewModelFactory(application)
        ).get(WalletBalanceViewModel.class);

        balanceViewModel.getBalance().observe(this, balance -> {
            currentBalance = balance;
            updateBalanceDisplay();
        });

        balanceViewModel.getExchangeRate().observe(this, exchangeRate -> {
            currentExchangeRate = exchangeRate;
            updateBalanceDisplay();
        });

        application.blockchainState.observe(this, blockchainState -> {
            if (blockchainState != null) {
                isBlockchainSynced = !blockchainState.replaying;
                updateBalanceDisplay();
            }
        });

        if (textWalletBalance != null) {
            textWalletBalance.setText(getString(R.string.balance_loading_wallet));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        viewModelStore.clear();

        if (prefs != null && prefsListener != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
    }

    // ===== WALLET BALANCE DISPLAY =====

    /**
     * Updates the wallet balance display with current BTC balance and fiat conversion.
     * Shows sync status, loading state, or formatted balance with currency.
     */
    private void updateBalanceDisplay() {
        if (textWalletBalance == null) {
            return;
        }

        if (!isBlockchainSynced) {
            textWalletBalance.setText(getString(R.string.balance_syncing));
            return;
        }

        if (currentBalance == null) {
            textWalletBalance.setText(getString(R.string.balance_loading));
            return;
        }

        try {
            double btcBalance = currentBalance.toBtc().doubleValue();
            String btcStr = String.format(Locale.US, "%.8f", btcBalance);

            if (currentMarketPriceFiat > 0) {
                double fiatVal = btcBalance * currentMarketPriceFiat;
                String symbol = getCurrencySymbol(currentFiatCode);
                textWalletBalance.setText(
                        String.format(Locale.US, "%s BTC ≈ %s%,.2f", btcStr, symbol, fiatVal)
                );
            } else {
                boolean showLocal = getResources().getBoolean(R.bool.show_local_balance)
                        && config.isEnableExchangeRates();

                if (showLocal && currentExchangeRate != null) {
                    Fiat fiatValue = currentExchangeRate.exchangeRate().coinToFiat(currentBalance);
                    String fiatStr = fiatValue.toFriendlyString();
                    textWalletBalance.setText(String.format("%s BTC ≈ %s", btcStr, fiatStr));
                } else {
                    textWalletBalance.setText(String.format("%s BTC", btcStr));
                }
            }

            textWalletBalance.invalidate();
        } catch (Exception e) {
            e.printStackTrace();
            textWalletBalance.setText(getString(R.string.balance_error));
        }
    }

    // ===== CHART SETTINGS POPUP =====

    /**
     * Shows the chart settings popup dialog with expandable sections:
     * - Candle settings (colors, body/width, visible count)
     * - Moving averages (add/remove, periods, colors)
     * - Volume MA (show/hide, periods, colors)
     * - Grid settings (show/hide, color)
     * - Last price line (show/hide, color, width, dashed)
     * - Price label (background, text color, text size)
     * - Selected line (color, width, alpha, dashed)
     */
    private void showChartSettingsPopup() {
        if (marketChartView == null) {
            return;
        }

        final ChartSettingsState state = new ChartSettingsState();

        // Load current state from chart view
        state.candlePalette = loadPaletteFromColorsXml();

        state.curBull[0] = marketChartView.getBullishColor();
        state.curBear[0] = marketChartView.getBearishColor();

        state.bullIdx[0] = -1;
        for (int i = 0; i < state.candlePalette.length; i++) {
            if (state.candlePalette[i] == state.curBull[0]) {
                state.bullIdx[0] = i;
                break;
            }
        }

        state.bearIdx[0] = -1;
        for (int i = 0; i < state.candlePalette.length; i++) {
            if (state.candlePalette[i] == state.curBear[0]) {
                state.bearIdx[0] = i;
                break;
            }
        }

        state.curWick[0] = marketChartView.getWickWidthPx();
        state.curMaW[0] = marketChartView.getMaLineWidthPx();
        state.curTxtSize[0] = marketChartView.getPriceTextSizePx();
        state.curLastW[0] = marketChartView.getLastLineWidthPx();
        state.curLabelSize[0] = marketChartView.getLastPriceLabelTextSizePx();
        state.curSelectedW[0] = marketChartView.getSelectedLineWidthPx();

        state.curLastColor[0] = marketChartView.getLastPriceLineColor();
        state.curGridColor[0] = marketChartView.getGridColor();
        state.curPriceTxtColor[0] = marketChartView.getPriceTextColor();
        state.gridPicked[0] = false;
        state.pricePicked[0] = false;
        state.lastLinePicked[0] = false;
        state.selectedColorTouched[0] = false;
        state.selectedWidthTouched[0] = false;
        state.selectedAlphaTouched[0] = false;
        state.selectedDashedTouched[0] = false;

        SharedPreferences chartPrefs = getSharedPreferences(PREFS_CHART_SETTINGS, MODE_PRIVATE);

        state.labelBgPicked[0] = false;
        state.labelTextPicked[0] = false;

        if (chartPrefs.contains("label_bg")) {
            state.curLabelBg[0] = chartPrefs.getInt("label_bg", 0);
            state.labelBgPicked[0] = true;
        } else if (chartPrefs.contains("current_price_label_bg")) {
            state.curLabelBg[0] = chartPrefs.getInt("current_price_label_bg", 0);
            state.labelBgPicked[0] = true;
        } else {
            state.curLabelBg[0] = marketChartView.getLastPriceBgColor();
        }

        if (chartPrefs.contains("label_text_color")) {
            state.curLabelTextColorFinal[0] = chartPrefs.getInt("label_text_color", 0);
            state.labelTextPicked[0] = true;
        } else if (chartPrefs.contains("current_price_label_text")) {
            state.curLabelTextColorFinal[0] = chartPrefs.getInt("current_price_label_text", 0);
            state.labelTextPicked[0] = true;
        } else {
            state.curLabelTextColorFinal[0] = marketChartView.getLastPriceLabelTextColor();
        }

        state.curSelectedColor[0] = marketChartView.getSelectedLineColor();

        state.selectedIdx[0] = -1;
        for (int i = 0; i < state.candlePalette.length; i++) {
            if (state.candlePalette[i] == state.curSelectedColor[0]) {
                state.selectedIdx[0] = i;
                break;
            }
        }

        state.curSelectedAlpha[0] = marketChartView.getSelectedLineAlpha();

        state.curShowVolMa[0] = marketChartView.isShowVolMa();
        state.curVolMa1Color[0] = marketChartView.getVolMa1Color();
        state.curVolMa2Color[0] = marketChartView.getVolMa2Color();
        state.curVolMaW[0] = marketChartView.getVolMaWidthPx();
        state.curVolMa1Period[0] = marketChartView.getVolMa1Period();
        state.curVolMa2Period[0] = marketChartView.getVolMa2Period();

        state.finalTxtSize[0] = state.curTxtSize[0];
        state.finalLabelSize[0] = state.curLabelSize[0];

        state.tempList = new ArrayList<>();
        List<MarketChartView.MaLine> origLines = marketChartView.getMaLines();
        for (MarketChartView.MaLine o : origLines) {
            state.tempList.add(new MarketChartView.MaLine(o.period, o.color));
        }

        // Build and show dialog
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        View content = getLayoutInflater().inflate(R.layout.chart_settings_popup, null);
        dialog.setContentView(content);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            dialog.getWindow().setGravity(Gravity.CENTER);
        }

        // ===== CANDLE SECTION =====
        View headerCandle = content.findViewById(R.id.headerCandle);
        TextView arrowCandle = content.findViewById(R.id.arrowCandle);
        View containerCandle = content.findViewById(R.id.containerCandle);
        View viewBull = content.findViewById(R.id.viewBull);
        View viewBear = content.findViewById(R.id.viewBear);
        SeekBar sbBodyCandle = null;
        SeekBar sbWickCandle = null;
        SeekBar sbVisCandle = null;
        TextView lbBodyCandle = null;
        TextView lbWickCandle = null;
        TextView lbVisCandle = null;
        if (containerCandle != null) {
            sbBodyCandle = containerCandle.findViewById(R.id.sbBody);
            sbWickCandle = containerCandle.findViewById(R.id.sbWick);
            sbVisCandle = containerCandle.findViewById(R.id.sbVis);
            lbBodyCandle = containerCandle.findViewById(R.id.lbBody);
            lbWickCandle = containerCandle.findViewById(R.id.lbWick);
            lbVisCandle = containerCandle.findViewById(R.id.lbVis);
        }

        // Setup bullish color picker
        if (viewBull != null) {
            viewBull.setBackground(createColorViewDrawable(state.curBull[0]));
            viewBull.setOnClickListener(v -> {
                state.bullIdx[0] = (state.bullIdx[0] + 1) % state.candlePalette.length;
                int next = state.candlePalette[state.bullIdx[0]];
                state.curBull[0] = next;
                v.setBackground(createColorViewDrawable(next));
                if (marketChartView != null) {
                    marketChartView.setCandleColors(state.curBull[0], state.curBear[0]);
                    marketChartView.invalidate();
                }
            });
        }

        // Setup bearish color picker
        if (viewBear != null) {
            viewBear.setBackground(createColorViewDrawable(state.curBear[0]));
            viewBear.setOnClickListener(v -> {
                state.bearIdx[0] = (state.bearIdx[0] + 1) % state.candlePalette.length;
                int next = state.candlePalette[state.bearIdx[0]];
                state.curBear[0] = next;
                v.setBackground(createColorViewDrawable(next));
                if (marketChartView != null) {
                    marketChartView.setCandleColors(state.curBull[0], state.curBear[0]);
                    marketChartView.invalidate();
                }
            });
        }

        // Setup body width seekbar
        if (sbBodyCandle != null) {
            int progBody = (int) ((marketChartView.getBodyWidthFraction() - BODY_BASE_FRACTION) * 100f);
            if (progBody < 0) progBody = 0;
            sbBodyCandle.setProgress(progBody);
            if (lbBodyCandle != null) {
                lbBodyCandle.setText(getString(R.string.chart_body_width, String.format(Locale.US, "%.2f", marketChartView.getBodyWidthFraction())));
            }
            final TextView finalLbBodyCandle = lbBodyCandle;
            sbBodyCandle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float fraction = BODY_BASE_FRACTION + progress / 100f;
                    if (finalLbBodyCandle != null) {
                        finalLbBodyCandle.setText(getString(R.string.chart_body_width, String.format(Locale.US, "%.2f", fraction)));
                    }
                    if (marketChartView != null) {
                        marketChartView.setBodyFraction(fraction);
                        marketChartView.invalidate();
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Setup wick width seekbar
        if (sbWickCandle != null) {
            sbWickCandle.setProgress((int) state.curWick[0]);
            if (lbWickCandle != null) {
                lbWickCandle.setText(getString(R.string.chart_wick_width, (int) state.curWick[0]));
            }
            final TextView finalLbWickCandle = lbWickCandle;
            sbWickCandle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int p = Math.max(1, progress);
                    if (finalLbWickCandle != null) {
                        finalLbWickCandle.setText(getString(R.string.chart_wick_width, p));
                    }
                    if (marketChartView != null) {
                        marketChartView.setWickWidthPx(p);
                        marketChartView.invalidate();
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Setup visible candle count seekbar (min/max from layout XML)
        if (sbVisCandle != null) {
            sbVisCandle.setProgress(marketChartView.getVisibleCandleCountValue());
            if (lbVisCandle != null) {
                lbVisCandle.setText(getString(R.string.chart_visible_candles, marketChartView.getVisibleCandleCountValue()));
            }
            final TextView finalLbVisCandle = lbVisCandle;
            sbVisCandle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (finalLbVisCandle != null) {
                        finalLbVisCandle.setText(getString(R.string.chart_visible_candles, progress));
                    }
                    if (marketChartView != null) {
                        marketChartView.setVisibleCandleCount(progress);
                        marketChartView.invalidate();
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (sbBodyCandle != null) {
            state.sbBody = sbBodyCandle;
        }
        if (sbWickCandle != null) {
            state.sbWick = sbWickCandle;
        }
        if (sbVisCandle != null) {
            state.sbVis = sbVisCandle;
        }

        // Candle section expand/collapse
        if (headerCandle != null && containerCandle != null) {
            final boolean[] candleExpanded = {containerCandle.getVisibility() == View.VISIBLE};
            if (arrowCandle != null) {
                arrowCandle.setText(getString(candleExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
            }
            headerCandle.setOnClickListener(v -> {
                candleExpanded[0] = !candleExpanded[0];
                containerCandle.setVisibility(candleExpanded[0] ? View.VISIBLE : View.GONE);
                if (arrowCandle != null) {
                    arrowCandle.setText(getString(candleExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
                }
            });
        }

        // ===== MA SECTION =====
        View headerMa = content.findViewById(R.id.headerMa);
        TextView arrowMa = content.findViewById(R.id.arrowMa);
        View containerMa = content.findViewById(R.id.containerMa);
        RecyclerView recycler = content.findViewById(R.id.recycler_ma_popup);
        View btnAddMa = content.findViewById(R.id.btn_add_ma);

        if (recycler != null) {
            state.recycler = recycler;
            recycler.setLayoutManager(new LinearLayoutManager(this));
            recycler.setNestedScrollingEnabled(false);
            final MaPopupAdapter adapter = new MaPopupAdapter(state.tempList, state.candlePalette);
            recycler.setAdapter(adapter);
        }

        if (btnAddMa != null) {
            btnAddMa.setOnClickListener(v -> {
                if (state.tempList.size() >= 10) {
                    Toast.makeText(v.getContext(), getString(R.string.max_ma_reached), Toast.LENGTH_SHORT).show();
                    return;
                }
                int[] colors = state.candlePalette;
                int color = colors[state.tempList.size() % colors.length];
                state.tempList.add(new MarketChartView.MaLine(20, color));
                if (state.recycler != null && state.recycler.getAdapter() != null) {
                    state.recycler.getAdapter().notifyDataSetChanged();
                }
            });
        }

        if (containerMa != null) {
            state.sbMaW = containerMa.findViewById(R.id.sbMaW);
            TextView lbMaW = containerMa.findViewById(R.id.lbMaW);
            if (state.sbMaW != null) {
                state.sbMaW.setProgress((int) state.curMaW[0]);
                if (lbMaW != null) {
                    lbMaW.setText(getString(R.string.chart_ma_line_width, (int) state.curMaW[0]));
                }
                state.sbMaW.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int p = Math.max(1, progress);
                        if (lbMaW != null) {
                            lbMaW.setText(getString(R.string.chart_ma_line_width, p));
                        }
                        if (marketChartView != null) {
                            marketChartView.setMaLineWidthPx(p);
                            marketChartView.invalidate();
                        }
                    }
                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }
        }

        // MA section expand/collapse
        if (headerMa != null && containerMa != null) {
            final boolean[] maExpanded = {containerMa.getVisibility() == View.VISIBLE};
            if (arrowMa != null) {
                arrowMa.setText(getString(maExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
            }
            headerMa.setOnClickListener(v -> {
                maExpanded[0] = !maExpanded[0];
                containerMa.setVisibility(maExpanded[0] ? View.VISIBLE : View.GONE);
                if (arrowMa != null) {
                    arrowMa.setText(getString(maExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
                }
            });
        }

        // ===== VOLUME MA SECTION =====
        View headerVolMa = content.findViewById(R.id.headerVolMa);
        TextView arrowVolMa = content.findViewById(R.id.arrowVolMa);
        View containerVolMa = content.findViewById(R.id.containerVolMa);

        if (headerVolMa != null && containerVolMa != null) {
            state.swVol = containerVolMa.findViewById(R.id.swVol);
            state.swVolMa = containerVolMa.findViewById(R.id.swVolMa);
            state.sbVolMaW = containerVolMa.findViewById(R.id.sbVolMaW);
            state.sbVolMa1Period = containerVolMa.findViewById(R.id.sbVolMa1Period);
            state.sbVolMa2Period = containerVolMa.findViewById(R.id.sbVolMa2Period);
            state.tvVolMa1Period = containerVolMa.findViewById(R.id.tvVolMa1Period);
            state.tvVolMa2Period = containerVolMa.findViewById(R.id.tvVolMa2Period);
            state.tvVolMaW = containerVolMa.findViewById(R.id.tvVolMaW);
            state.viewVolMa1Color = containerVolMa.findViewById(R.id.viewVolMa1Color);
            state.viewVolMa2Color = containerVolMa.findViewById(R.id.viewVolMa2Color);
            TextView lbVolMaW = containerVolMa.findViewById(R.id.lbVolMaW);

            if (lbVolMaW != null) {
                lbVolMaW.setText(getString(R.string.label_vol_ma_width));
            }

            // Volume visibility switch
            if (state.swVol != null) {
                state.swVol.setChecked(marketChartView.isShowVolume());
                state.swVol.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (marketChartView != null) {
                        marketChartView.setShowVolume(isChecked);
                        marketChartView.invalidate();
                    }
                });
            }

            // Volume MA visibility switch
            if (state.swVolMa != null) {
                state.swVolMa.setChecked(state.curShowVolMa[0]);
            }

            // Volume MA1 color picker
            if (state.viewVolMa1Color != null) {
                state.viewVolMa1Color.setBackground(createColorViewDrawable(state.curVolMa1Color[0]));
                state.viewVolMa1Color.setOnClickListener(v -> {
                    int idx = -1;
                    for (int i = 0; i < state.candlePalette.length; i++) {
                        if (state.candlePalette[i] == state.curVolMa1Color[0]) {
                            idx = i;
                            break;
                        }
                    }
                    int nextIdx = (idx + 1) % state.candlePalette.length;
                    int next = state.candlePalette[nextIdx];
                    state.curVolMa1Color[0] = next;
                    v.setBackground(createColorViewDrawable(next));
                });
            }

            // Volume MA2 color picker
            if (state.viewVolMa2Color != null) {
                state.viewVolMa2Color.setBackground(createColorViewDrawable(state.curVolMa2Color[0]));
                state.viewVolMa2Color.setOnClickListener(v -> {
                    int idx = -1;
                    for (int i = 0; i < state.candlePalette.length; i++) {
                        if (state.candlePalette[i] == state.curVolMa2Color[0]) {
                            idx = i;
                            break;
                        }
                    }
                    int nextIdx = (idx + 1) % state.candlePalette.length;
                    int next = state.candlePalette[nextIdx];
                    state.curVolMa2Color[0] = next;
                    v.setBackground(createColorViewDrawable(next));
                });
            }

            // Volume MA1 period seekbar
            if (state.sbVolMa1Period != null) {
                state.sbVolMa1Period.setProgress(state.curVolMa1Period[0]);
                if (state.tvVolMa1Period != null) {
                    state.tvVolMa1Period.setText(String.valueOf(state.curVolMa1Period[0]));
                }
                state.sbVolMa1Period.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int p = Math.max(1, progress);
                        state.curVolMa1Period[0] = p;
                        if (state.tvVolMa1Period != null) {
                            state.tvVolMa1Period.setText(String.valueOf(p));
                        }
                    }
                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            // Volume MA2 period seekbar
            if (state.sbVolMa2Period != null) {
                state.sbVolMa2Period.setProgress(state.curVolMa2Period[0]);
                if (state.tvVolMa2Period != null) {
                    state.tvVolMa2Period.setText(String.valueOf(state.curVolMa2Period[0]));
                }
                state.sbVolMa2Period.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int p = Math.max(1, progress);
                        state.curVolMa2Period[0] = p;
                        if (state.tvVolMa2Period != null) {
                            state.tvVolMa2Period.setText(String.valueOf(p));
                        }
                    }
                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            // Volume MA width seekbar
            if (state.sbVolMaW != null) {
                state.sbVolMaW.setProgress((int) state.curVolMaW[0]);
                if (state.tvVolMaW != null) {
                    state.tvVolMaW.setText(String.valueOf((int) state.curVolMaW[0]));
                }
                state.sbVolMaW.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int p = Math.max(1, progress);
                        state.curVolMaW[0] = p;
                        if (state.tvVolMaW != null) {
                            state.tvVolMaW.setText(String.valueOf(p));
                        }
                    }
                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            // Volume MA section expand/collapse
            final boolean[] volMaExpanded = {containerVolMa.getVisibility() == View.VISIBLE};
            if (arrowVolMa != null) {
                arrowVolMa.setText(getString(volMaExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
            }
            headerVolMa.setOnClickListener(v -> {
                volMaExpanded[0] = !volMaExpanded[0];
                containerVolMa.setVisibility(volMaExpanded[0] ? View.VISIBLE : View.GONE);
                if (arrowVolMa != null) {
                    arrowVolMa.setText(getString(volMaExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
                }
            });
        }

        // ===== GRID SECTION =====
        View headerGrid = content.findViewById(R.id.headerGrid);
        TextView arrowGrid = content.findViewById(R.id.arrowGrid);
        View containerGrid = content.findViewById(R.id.containerGrid);

        if (containerGrid != null) {
            state.swGrid = containerGrid.findViewById(R.id.swGrid);
            state.viewGridColor = containerGrid.findViewById(R.id.viewGridColor);

            if (state.swGrid != null) {
                state.swGrid.setChecked(marketChartView.isShowGrid());
                state.swGrid.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (marketChartView != null) {
                        marketChartView.setShowGrid(isChecked);
                        marketChartView.invalidate();
                    }
                });
            }

            if (state.viewGridColor != null) {
                state.viewGridColor.setBackground(createColorViewDrawable(state.curGridColor[0]));
                state.viewGridColor.setOnClickListener(v -> {
                    int idx = -1;
                    for (int i = 0; i < state.candlePalette.length; i++) {
                        if (state.candlePalette[i] == state.curGridColor[0]) {
                            idx = i;
                            break;
                        }
                    }
                    int nextIdx = (idx + 1) % state.candlePalette.length;
                    int next = state.candlePalette[nextIdx];
                    state.curGridColor[0] = next;
                    state.gridPicked[0] = true;
                    v.setBackground(createColorViewDrawable(next));
                    v.invalidate();
                    if (marketChartView != null) {
                        marketChartView.setGridColor(next);
                        marketChartView.invalidate();
                    }
                });
            }
        }

        // Grid section expand/collapse
        if (headerGrid != null && containerGrid != null) {
            final boolean[] gridExpanded = {containerGrid.getVisibility() == View.VISIBLE};
            if (arrowGrid != null) {
                arrowGrid.setText(getString(gridExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
            }
            headerGrid.setOnClickListener(v -> {
                gridExpanded[0] = !gridExpanded[0];
                containerGrid.setVisibility(gridExpanded[0] ? View.VISIBLE : View.GONE);
                if (arrowGrid != null) {
                    arrowGrid.setText(getString(gridExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
                }
            });
        }

        // ===== LAST PRICE LINE SECTION =====
        View headerLastPrice = content.findViewById(R.id.headerLastPrice);
        TextView arrowLastPrice = content.findViewById(R.id.arrowLastPrice);
        View containerLastPrice = content.findViewById(R.id.containerLastPrice);

        state.swLast = content.findViewById(R.id.swLast);
        state.sbTxtSize = content.findViewById(R.id.sbTxtSize);
        state.sbLastW = content.findViewById(R.id.sbLastW);
        state.swDash = content.findViewById(R.id.swDash);

        View viewLastColor = content.findViewById(R.id.viewLastColor);
        View viewTxtColor = content.findViewById(R.id.viewTxtColor);

        TextView lbTxtSize = content.findViewById(R.id.lbTxtSize);
        TextView lbLastW = content.findViewById(R.id.lbLastW);

        if (state.swLast != null) {
            state.swLast.setChecked(marketChartView.isShowLastPriceLine());
        }
        if (state.swDash != null) {
            state.swDash.setChecked(marketChartView.isLastLineDashed());
        }

        if (viewLastColor != null) {
            viewLastColor.setBackground(createColorViewDrawable(state.curLastColor[0]));
        }
        if (viewTxtColor != null) {
            viewTxtColor.setBackground(createColorViewDrawable(state.curPriceTxtColor[0]));
        }

        // Price text size seekbar
        if (state.sbTxtSize != null) {
            state.sbTxtSize.setProgress((int) state.curTxtSize[0]);
            if (lbTxtSize != null) {
                lbTxtSize.setText(getString(R.string.chart_price_text_size, (int) state.curTxtSize[0]));
            }
            state.sbTxtSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int p = Math.max(8, progress);
                    state.finalTxtSize[0] = p;
                    if (lbTxtSize != null) {
                        lbTxtSize.setText(getString(R.string.chart_price_text_size, p));
                    }
                    if (marketChartView != null) {
                        marketChartView.setPriceTextSizePx(p);
                        marketChartView.invalidate();
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Last line width seekbar
        if (state.sbLastW != null) {
            state.sbLastW.setProgress((int) state.curLastW[0]);
            if (lbLastW != null) {
                lbLastW.setText(getString(R.string.chart_last_line_width, (int) state.curLastW[0]));
            }
            state.sbLastW.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int p = Math.max(1, progress);
                    state.curLastW[0] = p;
                    if (lbLastW != null) {
                        lbLastW.setText(getString(R.string.chart_last_line_width, p));
                    }
                    if (marketChartView != null) {
                        marketChartView.setLastLineWidthPx(p);
                        marketChartView.invalidate();
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Last line color picker
        if (viewLastColor != null) {
            viewLastColor.setOnClickListener(v -> {
                int idx = -1;
                for (int i = 0; i < state.candlePalette.length; i++) {
                    if (state.candlePalette[i] == state.curLastColor[0]) {
                        idx = i;
                        break;
                    }
                }
                int nextIdx = (idx + 1) % state.candlePalette.length;
                int next = state.candlePalette[nextIdx];
                state.curLastColor[0] = next;
                state.lastLinePicked[0] = true;
                v.setBackground(createColorViewDrawable(next));
                if (marketChartView != null) {
                    marketChartView.setLastPriceLineColor(next);
                    marketChartView.invalidate();
                }
            });
        }

        // Price text color picker
        if (viewTxtColor != null) {
            viewTxtColor.setOnClickListener(v -> {
                int idx = -1;
                for (int i = 0; i < state.candlePalette.length; i++) {
                    if (state.candlePalette[i] == state.curPriceTxtColor[0]) {
                        idx = i;
                        break;
                    }
                }
                int nextIdx = (idx + 1) % state.candlePalette.length;
                int next = state.candlePalette[nextIdx];
                state.curPriceTxtColor[0] = next;
                state.pricePicked[0] = true;
                v.setBackground(createColorViewDrawable(next));
                if (marketChartView != null) {
                    marketChartView.setPriceTextColor(next);
                    marketChartView.invalidate();
                }
            });
        }

        // Last price section expand/collapse
        if (headerLastPrice != null && containerLastPrice != null) {
            final boolean[] lastPriceExpanded = {containerLastPrice.getVisibility() == View.VISIBLE};
            if (arrowLastPrice != null) {
                arrowLastPrice.setText(getString(lastPriceExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
            }
            headerLastPrice.setOnClickListener(v -> {
                lastPriceExpanded[0] = !lastPriceExpanded[0];
                containerLastPrice.setVisibility(lastPriceExpanded[0] ? View.VISIBLE : View.GONE);
                if (arrowLastPrice != null) {
                    arrowLastPrice.setText(getString(lastPriceExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
                }
            });
        }

        // ===== LABEL SECTION =====
        View headerLabel = content.findViewById(R.id.headerLabel);
        TextView arrowLabel = content.findViewById(R.id.arrowLabel);
        View containerLabel = content.findViewById(R.id.containerLabel);

        state.sbLabelSize = content.findViewById(R.id.sbLabelSize);
        View viewLabelBg = content.findViewById(R.id.viewLabelBg);
        View viewLabelTextColor = content.findViewById(R.id.viewLabelTextColor);

        TextView lbLabelSize = content.findViewById(R.id.lbLabelSize);

        if (viewLabelBg != null) {
            viewLabelBg.setBackground(createColorViewDrawable(state.curLabelBg[0]));
        }
        if (viewLabelTextColor != null) {
            viewLabelTextColor.setBackground(createColorViewDrawable(state.curLabelTextColorFinal[0]));
        }

        // Label text size seekbar
        if (state.sbLabelSize != null) {
            state.sbLabelSize.setProgress((int) state.curLabelSize[0]);
            if (lbLabelSize != null) {
                lbLabelSize.setText(getString(R.string.chart_last_price_label_text_size, (int) state.curLabelSize[0]));
            }
            state.sbLabelSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int p = Math.max(8, progress);
                    state.finalLabelSize[0] = p;
                    if (lbLabelSize != null) {
                        lbLabelSize.setText(getString(R.string.chart_last_price_label_text_size, p));
                    }
                    if (marketChartView != null) {
                        marketChartView.setCurrentPriceLabelTextSizePx(p);
                        marketChartView.invalidate();
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // Label background color picker
        if (viewLabelBg != null) {
            viewLabelBg.setOnClickListener(v -> {
                int idx = -1;
                for (int i = 0; i < state.candlePalette.length; i++) {
                    if (state.candlePalette[i] == state.curLabelBg[0]) {
                        idx = i;
                        break;
                    }
                }
                int nextIdx = (idx + 1) % state.candlePalette.length;
                int next = state.candlePalette[nextIdx];
                state.curLabelBg[0] = next;
                state.labelBgPicked[0] = true;
                v.setBackground(createColorViewDrawable(next));
                getSharedPreferences(PREFS_CHART_SETTINGS, MODE_PRIVATE)
                        .edit()
                        .putInt("label_bg", next)
                        .putInt("current_price_label_bg", next)
                        .commit();
                if (marketChartView != null) {
                    marketChartView.setCurrentPriceLabelBackground(next);
                    marketChartView.invalidate();
                }
            });
        }

        // Label text color picker
        if (viewLabelTextColor != null) {
            viewLabelTextColor.setOnClickListener(v -> {
                int idx = -1;
                for (int i = 0; i < state.candlePalette.length; i++) {
                    if (state.candlePalette[i] == state.curLabelTextColorFinal[0]) {
                        idx = i;
                        break;
                    }
                }
                int nextIdx = (idx + 1) % state.candlePalette.length;
                int next = state.candlePalette[nextIdx];
                state.curLabelTextColorFinal[0] = next;
                state.labelTextPicked[0] = true;
                v.setBackground(createColorViewDrawable(next));
                getSharedPreferences(PREFS_CHART_SETTINGS, MODE_PRIVATE)
                        .edit()
                        .putInt("label_text_color", next)
                        .putInt("current_price_label_text", next)
                        .commit();
                if (marketChartView != null) {
                    marketChartView.setCurrentPriceLabelTextColor(next);
                    marketChartView.invalidate();
                }
            });
        }

        // Label section expand/collapse
        if (headerLabel != null && containerLabel != null) {
            final boolean[] labelExpanded = {containerLabel.getVisibility() == View.VISIBLE};
            if (arrowLabel != null) {
                arrowLabel.setText(getString(labelExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
            }
            headerLabel.setOnClickListener(v -> {
                labelExpanded[0] = !labelExpanded[0];
                containerLabel.setVisibility(labelExpanded[0] ? View.VISIBLE : View.GONE);
                if (arrowLabel != null) {
                    arrowLabel.setText(getString(labelExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
                }
            });
        }

        // ===== SELECTED LINE SECTION =====
        View headerSelected = content.findViewById(R.id.headerSelected);
        TextView arrowSelected = content.findViewById(R.id.arrowSelected);
        View containerSelected = content.findViewById(R.id.containerSelected);

        if (headerSelected != null && containerSelected != null) {
            state.sbSelectedWidth = containerSelected.findViewById(R.id.sbSelectedWidth);
            state.sbSelectedAlpha = containerSelected.findViewById(R.id.sbSelectedAlpha);
            state.swSelectedDash = containerSelected.findViewById(R.id.swSelectedDash);
            View viewSelectedLine = containerSelected.findViewById(R.id.viewSelectedLine);
            TextView lbSelectedW = containerSelected.findViewById(R.id.lbSelectedW);
            TextView lbSelectedAlpha = containerSelected.findViewById(R.id.lbSelectedAlpha);

            if (viewSelectedLine != null) {
                viewSelectedLine.setBackground(createColorViewDrawable(state.curSelectedColor[0]));
            }

            // Selected line width seekbar
            if (state.sbSelectedWidth != null) {
                state.sbSelectedWidth.setProgress((int) state.curSelectedW[0]);
                if (lbSelectedW != null) {
                    lbSelectedW.setText(getString(R.string.chart_selected_line_width, (int) state.curSelectedW[0]));
                }
                state.sbSelectedWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int p = Math.max(1, progress);
                        state.curSelectedW[0] = p;
                        if (fromUser) {
                            state.selectedWidthTouched[0] = true;
                        }
                        if (lbSelectedW != null) {
                            lbSelectedW.setText(getString(R.string.chart_selected_line_width, p));
                        }
                        if (marketChartView != null) {
                            marketChartView.setSelectedLineWidthPx(p);
                            marketChartView.invalidate();
                        }
                    }
                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            // Selected line alpha seekbar
            if (state.sbSelectedAlpha != null) {
                state.sbSelectedAlpha.setMax(255);
                state.sbSelectedAlpha.setProgress(state.curSelectedAlpha[0]);
                if (lbSelectedAlpha != null) {
                    lbSelectedAlpha.setText(getString(R.string.chart_selected_line_alpha, state.curSelectedAlpha[0]));
                }
                state.sbSelectedAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        state.curSelectedAlpha[0] = progress;
                        if (fromUser) {
                            state.selectedAlphaTouched[0] = true;
                        }
                        if (lbSelectedAlpha != null) {
                            lbSelectedAlpha.setText(getString(R.string.chart_selected_line_alpha, progress));
                        }
                        if (marketChartView != null) {
                            marketChartView.setSelectedLineAlpha(progress);
                            marketChartView.invalidate();
                        }
                    }
                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}
                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
            }

            // Selected line dashed switch
            if (state.swSelectedDash != null) {
                state.swSelectedDash.setChecked(marketChartView.isSelectedLineDashed());
                state.swSelectedDash.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    state.selectedDashedTouched[0] = true;
                    if (marketChartView != null) {
                        marketChartView.setSelectedLineDashed(isChecked);
                        marketChartView.invalidate();
                    }
                });
            }

            // Selected line color picker
            if (viewSelectedLine != null) {
                viewSelectedLine.setBackground(createColorViewDrawable(state.curSelectedColor[0]));
                viewSelectedLine.setOnClickListener(v -> {
                    state.selectedIdx[0] = (state.selectedIdx[0] + 1) % state.candlePalette.length;
                    int next = state.candlePalette[state.selectedIdx[0]];
                    state.curSelectedColor[0] = next;
                    state.selectedColorTouched[0] = true;
                    v.setBackground(createColorViewDrawable(next));
                    if (marketChartView != null) {
                        marketChartView.setSelectedLineColor(next);
                        marketChartView.invalidate();
                    }
                });
            }

            // Selected section expand/collapse
            final boolean[] selectedExpanded = {containerSelected.getVisibility() == View.VISIBLE};
            if (arrowSelected != null) {
                arrowSelected.setText(getString(selectedExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
            }
            headerSelected.setOnClickListener(v -> {
                selectedExpanded[0] = !selectedExpanded[0];
                containerSelected.setVisibility(selectedExpanded[0] ? View.VISIBLE : View.GONE);
                if (arrowSelected != null) {
                    arrowSelected.setText(getString(selectedExpanded[0] ? R.string.arrow_expanded : R.string.arrow_collapsed));
                }
            });
        }

        // Apply and Reset buttons
        Button btnApply = content.findViewById(R.id.btnApply);
        Button btnReset = content.findViewById(R.id.btnReset);

        if (btnApply != null) {
            btnApply.setOnClickListener(v -> applyChartSettings(state, dialog));
        }

        if (btnReset != null) {
            btnReset.setOnClickListener(v -> showResetConfirm(dialog));
        }

        dialog.show();
    }

    /**
     * Applies chart settings from the popup state to the chart view.
     * Saves user-selected colors and preferences to SharedPreferences.
     */
    private void applyChartSettings(ChartSettingsState state, Dialog dialog) {
        // Parse MA period values from EditTexts
        if (state.recycler != null) {
            state.recycler.clearFocus();
            for (int i = 0; i < state.recycler.getChildCount(); i++) {
                RecyclerView.ViewHolder vh = state.recycler.getChildViewHolder(
                        state.recycler.getChildAt(i)
                );
                if (vh instanceof MaPopupAdapter.Holder) {
                    MaPopupAdapter.Holder h = (MaPopupAdapter.Holder) vh;
                    int pos = h.getAdapterPosition();
                    if (pos >= 0 && pos < state.tempList.size()) {
                        String txt = h.et.getText().toString().trim();
                        if (!txt.isEmpty()) {
                            int period = Integer.parseInt(txt);
                            if (period > 0) {
                                state.tempList.get(pos).period = period;
                            }
                        }
                    }
                }
            }
        }

        // Ensure all periods are valid
        for (int i = 0; i < state.tempList.size(); i++) {
            if (state.tempList.get(i).period <= 0) {
                state.tempList.get(i).period = 20;
            }
        }

        // Extract values from SeekBars
        float bodyFraction = BODY_BASE_FRACTION + state.sbBody.getProgress() / 100f;
        float wickW = Math.max(1, state.sbWick.getProgress());
        float maW = Math.max(1, state.sbMaW.getProgress());
        int visCount = state.sbVis.getProgress();
        boolean showG = state.swGrid.isChecked();
        boolean showV = state.swVol.isChecked();
        boolean showLast = state.swLast.isChecked();

        // Apply all settings to chart
        marketChartView.setCandleColors(state.curBull[0], state.curBear[0]);
        marketChartView.setBodyFraction(bodyFraction);
        marketChartView.setWickWidthPx(wickW);
        marketChartView.setMaLineWidthPx(maW);
        marketChartView.setVisibleCandleCount(visCount);
        marketChartView.setShowGrid(showG);
        marketChartView.setShowVolume(showV);
        marketChartView.setShowLastPriceLine(showLast);
        marketChartView.setPriceTextSizePx(state.finalTxtSize[0]);
        marketChartView.setLastLineWidthPx(state.curLastW[0]);
        marketChartView.setLastLineDashed(state.swDash.isChecked());
        marketChartView.setCurrentPriceLabelTextSizePx(state.finalLabelSize[0]);

        // Apply user-picked colors
        if (state.gridPicked[0]) {
            marketChartView.setGridColor(state.curGridColor[0]);
        }
        if (state.pricePicked[0]) {
            marketChartView.setPriceTextColor(state.curPriceTxtColor[0]);
        }
        if (state.lastLinePicked[0]) {
            marketChartView.setLastPriceLineColor(state.curLastColor[0]);
        }
        if (state.labelBgPicked[0]) {
            marketChartView.setCurrentPriceLabelBackground(state.curLabelBg[0]);
        }
        if (state.labelTextPicked[0]) {
            marketChartView.setCurrentPriceLabelTextColor(state.curLabelTextColorFinal[0]);
        }

        // Apply selected line appearance (only user-touched parts)
        if (state.selectedColorTouched[0] || state.selectedWidthTouched[0] || state.selectedAlphaTouched[0] || state.selectedDashedTouched[0]) {
            marketChartView.setSelectedLineAppearanceByUser(
                    state.curSelectedColor[0],
                    state.curSelectedW[0],
                    state.curSelectedAlpha[0],
                    state.swSelectedDash.isChecked(),
                    state.selectedColorTouched[0],
                    state.selectedWidthTouched[0],
                    state.selectedAlphaTouched[0],
                    state.selectedDashedTouched[0]
            );
        }

        // Apply Volume MA settings
        if (state.swVolMa != null) {
            marketChartView.setVolMaAppearance(
                    state.swVolMa.isChecked(),
                    state.curVolMa1Color[0],
                    state.curVolMa2Color[0],
                    state.curVolMaW[0],
                    state.curVolMa1Period[0],
                    state.curVolMa2Period[0]
            );
        }

        // Save chart settings to SharedPreferences
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_CHART_SETTINGS, MODE_PRIVATE).edit();
        boolean hasChange = false;

        if (state.gridPicked[0]) {
            editor.putInt("grid_color", state.curGridColor[0]);
            hasChange = true;
        }
        if (state.pricePicked[0]) {
            editor.putInt("price_text_color", state.curPriceTxtColor[0]);
            hasChange = true;
        }
        if (state.labelBgPicked[0]) {
            editor.putInt("label_bg", state.curLabelBg[0]);
            editor.putInt("current_price_label_bg", state.curLabelBg[0]);
            hasChange = true;
        }
        if (state.labelTextPicked[0]) {
            editor.putInt("label_text_color", state.curLabelTextColorFinal[0]);
            editor.putInt("current_price_label_text", state.curLabelTextColorFinal[0]);
            hasChange = true;
        }

        if (hasChange) {
            editor.commit();
        }

        // Apply MA lines
        marketChartView.setMaLines(state.tempList);

        dialog.dismiss();
        Toast.makeText(this, getString(R.string.chart_settings_applied), Toast.LENGTH_SHORT).show();
    }

    /**
     * Shows confirmation dialog before resetting all chart settings to defaults.
     * Clears all saved preferences and reloads defaults from layout.
     */
    private void showResetConfirm(final Dialog settingsDialog) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.chart_reset_confirm_title))
                .setMessage(getString(R.string.chart_reset_confirm_message))
                .setPositiveButton(getString(R.string.chart_reset), (d, which) -> {
                    // Clear all saved preferences
                    getSharedPreferences(PREFS_CHART_SETTINGS, MODE_PRIVATE).edit().clear().commit();
                    getSharedPreferences(PREFS_CHART_STATE, MODE_PRIVATE).edit().clear().commit();
                    getSharedPreferences(PREF_CHART, Context.MODE_PRIVATE).edit().clear().commit();
                    getSharedPreferences(PREF_CANDLE, Context.MODE_PRIVATE).edit().clear().commit();
                    getSharedPreferences(PREF_MA, Context.MODE_PRIVATE).edit().clear().commit();

                    // Reset chart view
                    if (marketChartView != null) {
                        marketChartView.resetToDefaultsFromLayout();
                        loadDefaultsFromLayoutAndApply();
                        marketChartView.refreshTheme();
                    }

                    // Reset interval
                    resetToDefaultInterval();
                    settingsDialog.dismiss();

                    Toast.makeText(
                            MarketChartActivity.this,
                            getString(R.string.chart_settings_reset),
                            Toast.LENGTH_SHORT
                    ).show();

                    if (marketChartView != null) {
                        marketChartView.invalidate();
                    }
                })
                .setNegativeButton(getString(R.string.close), null)
                .show();
    }

    // ===== MA POPUP ADAPTER =====

    /**
     * RecyclerView adapter for managing Moving Average lines in the settings popup.
     * Each item shows: period input field, color picker, and delete button.
     */
    static class MaPopupAdapter extends RecyclerView.Adapter<MaPopupAdapter.Holder> {
        List<MarketChartView.MaLine> list;
        int[] palette;

        MaPopupAdapter(List<MarketChartView.MaLine> list, int[] palette) {
            this.list = list;
            this.palette = palette;
        }

        static class Holder extends RecyclerView.ViewHolder {
            EditText et;
            View color;
            View del;

            Holder(View v) {
                super(v);
                et = v.findViewById(R.id.et_period);
                color = v.findViewById(R.id.view_color);
                del = v.findViewById(R.id.btn_delete);
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_ma_popup, p, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(Holder h, int pos) {
            MarketChartView.MaLine line = list.get(pos);
            h.et.setText(String.valueOf(line.period));
            h.color.setBackground(createUnifiedColorDrawable(h.itemView.getContext(), line.color));

            // Auto-save period when focus lost
            h.et.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    try {
                        String txt = h.et.getText().toString().trim();
                        if (!txt.isEmpty()) {
                            line.period = Integer.parseInt(txt);
                        }
                    } catch (Exception e) {
                        // Ignore invalid input
                    }
                }
            });

            // Cycle through palette colors on tap
            h.color.setOnClickListener(v -> {
                int[] colors = palette;
                if (colors == null || colors.length == 0) {
                    return;
                }
                int idx = -1;
                for (int i = 0; i < colors.length; i++) {
                    if (colors[i] == line.color) {
                        idx = i;
                        break;
                    }
                }
                int nextIdx = (idx + 1) % colors.length;
                int next = colors[nextIdx];
                line.color = next;
                h.color.setBackground(createUnifiedColorDrawable(v.getContext(), next));
            });

            // Delete MA line
            h.del.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p >= 0 && p < list.size()) {
                    list.remove(p);
                    notifyDataSetChanged();
                }
            });
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    // ===== FIAT EXCHANGE RATE =====

    /**
     * Loads fiat exchange rate from database and updates chart fiat multiplier.
     */
    private void loadFiatRate() {
        new Thread(() -> {
            double fiatPerBtc = getFiatPerBtc(currentFiatCode);
            double basePerBtc = getFiatPerBtc("USD");

            if (fiatPerBtc == 0d || basePerBtc == 0d) {
                return;
            }

            double usdToFiat = fiatPerBtc / basePerBtc;

            mainHandler.post(() -> {
                if (textFiat != null) {
                    textFiat.setText(currentFiatCode);
                }
                if (marketChartView != null) {
                    marketChartView.setFiatCode(currentFiatCode);
                    marketChartView.setFiatMultiplier((float) usdToFiat);
                }
                updateBalanceDisplay();
            });
        }).start();
    }

    /**
     * Calculates fiat per BTC for a given currency code using exchange rate data.
     */
    private double getFiatPerBtc(String fiatCode) {
        ExchangeRateEntry entry = exchangeRateDao.findByCurrencyCode(fiatCode);
        if (entry == null) {
            return 0d;
        }

        try {
            long rateFiat = entry.getRateFiat();
            long rateCoin = entry.getRateCoin();

            if (rateCoin == 0) {
                return 0d;
            }

            int fractionDigits;
            try {
                Currency currency = Currency.getInstance(fiatCode);
                fractionDigits = currency.getDefaultFractionDigits();
                if (fractionDigits < 0 || fractionDigits == 0) {
                    fractionDigits = 2;
                }
            } catch (Exception e) {
                fractionDigits = 2;
            }

            double fiatMajor = rateFiat / Math.pow(10, fractionDigits);
            double coinMajor = (double) rateCoin / Coin.COIN.value;

            if (coinMajor == 0d) {
                return 0d;
            }

            return fiatMajor / coinMajor;
        } catch (Exception e) {
            return 0d;
        }
    }

    /**
     * Returns currency symbol for given fiat code, falling back to code if symbol not found.
     */
    private String getCurrencySymbol(String fiatCode) {
        try {
            String[] codes = getResources().getStringArray(R.array.fiat_codes);
            String[] symbols = getResources().getStringArray(R.array.fiat_symbols);
            int len = Math.min(codes.length, symbols.length);
            for (int i = 0; i < len; i++) {
                if (codes[i].equalsIgnoreCase(fiatCode)) {
                    return symbols[i];
                }
            }
            Currency currency = Currency.getInstance(fiatCode);
            String sym = currency.getSymbol(Locale.US);
            if (sym.equals(fiatCode)) {
                sym = currency.getSymbol();
            }
            if (sym.equals(fiatCode) || sym.length() > 6) {
                return fiatCode + " ";
            }
            return sym;
        } catch (Exception e) {
            return fiatCode + " ";
        }
    }

    // ===== THEME HELPERS =====

    /**
     * Resolves a theme attribute to a color value.
     */
    private int getThemeColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        } else {
            try {
                return getResources().getColor(tv.resourceId, getTheme());
            } catch (Exception e) {
                return tv.data;
            }
        }
    }

    // ===== TIMEFRAME CHIPS =====

    /**
     * Returns resource ID for interval label string.
     */
    private int getLabelResForInterval(String interval) {
        if (interval == null) {
            return R.string.more;
        }
        switch (interval) {
            case "1m":
                return R.string.interval_1m;
            case "3m":
                return R.string.interval_3m;
            case "5m":
                return R.string.interval_5m;
            case "15m":
                return R.string.interval_15m;
            case "30m":
                return R.string.interval_30m;
            case "1h":
                return R.string.interval_1h;
            case "2h":
                return R.string.interval_2h;
            case "4h":
                return R.string.interval_4h;
            case "6h":
                return R.string.interval_6h;
            case "12h":
                return R.string.interval_12h;
            case "1d":
            case "1D":
                return R.string.interval_1d;
            case "1w":
            case "1W":
                return R.string.interval_1w;
            case "1M":
                return R.string.interval_1M;
            default:
                return R.string.more;
        }
    }

    /**
     * Shows a dialog with all available intervals in a grid layout.
     */
    private void showMoreIntervalsDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.chart_bg, getTheme()));

        int pad = (int) getResources().getDimension(R.dimen.default_popup_padding);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.intervals_title);
        title.setTextSize(18f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getThemeColor(android.R.attr.textColorPrimary));

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleLp.bottomMargin = pad;
        title.setLayoutParams(titleLp);
        root.addView(title);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setUseDefaultMargins(false);

        android.content.res.Resources res = getResources();
        String[] intervals = res.getStringArray(R.array.interval_values);
        String[] realLoad = new String[intervals.length + 1];
        int[] intervalLabels = new int[intervals.length + 1];

        realLoad[0] = "";
        intervalLabels[0] = R.string.time;

        for (int i = 0; i < intervals.length; i++) {
            realLoad[i + 1] = intervals[i];
            intervalLabels[i + 1] = getLabelResForInterval(intervals[i]);
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .setNegativeButton(R.string.close, (d, w) -> d.dismiss())
                .create();

        for (int i = 0; i < realLoad.length; i++) {
            TextView tv = new TextView(this);
            tv.setText(intervalLabels[i]);
            tv.setTextSize(13f);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);

            int vPad = (int) getResources().getDimension(R.dimen.default_price_text_margin);
            tv.setPadding(0, vPad, 0, vPad);

            boolean isSelected = realLoad[i].equals(currentInterval);
            if (isSelected && !realLoad[i].isEmpty()) {
                tv.setBackgroundResource(R.drawable.bg_time_selected);
                tv.setTextColor(getThemeColor(android.R.attr.colorBackground));
            } else {
                tv.setBackgroundColor(res.getColor(android.R.color.transparent, getTheme()));
                tv.setTextColor(getThemeColor(android.R.attr.textColorSecondary));
            }

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(8, 8, 8, 8);
            tv.setLayoutParams(lp);

            final String load = realLoad[i];

            tv.setOnClickListener(v -> {
                if (load.isEmpty()) {
                    return;
                }
                currentInterval = load;
                getSharedPreferences(PREFS_CHART_STATE, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_INTERVAL, currentInterval)
                        .commit();
                if (marketChartView != null) {
                    marketChartView.loadChart(currentSymbol, currentInterval);
                }
                setupTimeframeChips();
                dialog.dismiss();
            });

            grid.addView(tv);
        }

        root.addView(grid);
        dialog.show();
    }

    /**
     * Sets up the timeframe selector chips at the top of the chart.
     * Shows default intervals plus "More" button for full list.
     */
    private void setupTimeframeChips() {
        if (chipGroupTimeframe == null) {
            return;
        }

        android.content.res.Resources res = getResources();
        chipGroupTimeframe.removeAllViews();

        String[] outerValues = {"15m", "1h", "4h", "1d", "1M"};

        boolean isOuter = false;
        for (String v : outerValues) {
            if (v.equalsIgnoreCase(currentInterval)) {
                if (v.equals("1M") && currentInterval.equals("1m")) {
                    continue;
                }
                isOuter = true;
                break;
            }
        }

        int padH = (int) getResources().getDimension(R.dimen.default_price_text_margin);
        int padV = (int) getResources().getDimension(R.dimen.time_text_offset);

        // "Time" label chip
        TextView tvTime = new TextView(this);
        tvTime.setText(R.string.time);
        tvTime.setTextSize(13f);
        tvTime.setSingleLine(true);
        tvTime.setPadding(padH, padV, padH, padV);
        tvTime.setTextColor(getThemeColor(android.R.attr.textColorSecondary));
        tvTime.setBackgroundColor(res.getColor(android.R.color.transparent, getTheme()));

        LinearLayout.LayoutParams lpTime = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lpTime.setMargins(2, 0, 2, 0);
        tvTime.setLayoutParams(lpTime);
        tvTime.setOnClickListener(v -> showMoreIntervalsDialog());
        chipGroupTimeframe.addView(tvTime);

        // Default interval chips
        for (String realInterval : outerValues) {
            TextView tv = new TextView(this);
            tv.setText(getLabelResForInterval(realInterval));
            tv.setTextSize(13f);
            tv.setSingleLine(true);
            tv.setPadding(padH, padV, padH, padV);

            boolean isSelected = realInterval.equalsIgnoreCase(currentInterval);
            if (realInterval.equals("1M") && currentInterval.equals("1m")) {
                isSelected = false;
            }

            if (isSelected) {
                tv.setTextColor(getThemeColor(android.R.attr.colorBackground));
                tv.setBackgroundResource(R.drawable.bg_time_selected);
            } else {
                tv.setTextColor(getThemeColor(android.R.attr.textColorSecondary));
                tv.setBackgroundColor(res.getColor(android.R.color.transparent, getTheme()));
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(2, 0, 2, 0);
            tv.setLayoutParams(lp);

            final String load = realInterval;

            tv.setOnClickListener(v -> {
                currentInterval = load;
                getSharedPreferences(PREFS_CHART_STATE, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_INTERVAL, currentInterval)
                        .commit();
                if (marketChartView != null) {
                    marketChartView.loadChart(currentSymbol, currentInterval);
                }
                setupTimeframeChips();
            });

            chipGroupTimeframe.addView(tv);
        }

        // "More" chip
        TextView tvMore = new TextView(this);
        tvMore.setTextSize(13f);
        tvMore.setSingleLine(true);
        tvMore.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams lpMore = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lpMore.setMargins(2, 0, 2, 0);
        tvMore.setLayoutParams(lpMore);

        if (isOuter) {
            tvMore.setText(R.string.more);
            tvMore.setTextColor(getThemeColor(android.R.attr.textColorSecondary));
            tvMore.setBackgroundColor(res.getColor(android.R.color.transparent, getTheme()));
        } else {
            tvMore.setText(getLabelResForInterval(currentInterval));
            tvMore.setTextColor(getThemeColor(android.R.attr.colorBackground));
            tvMore.setBackgroundResource(R.drawable.bg_time_selected);
        }

        tvMore.setOnClickListener(v -> showMoreIntervalsDialog());
        chipGroupTimeframe.addView(tvMore);
    }

    // ===== CHART LISTENER =====

    /**
     * Sets up chart event listeners for price updates, ticker data, MA updates,
     * countdown timer, and candle selection.
     */
    private void setupChartListener() {
        if (marketChartView == null) {
            return;
        }

        final android.content.res.Resources res = getResources();

        // Volume click listener - shows candle detail popup
        marketChartView.setOnVolumeClickListener(candle -> runOnUiThread(() -> {
            if (popupCandleDetail == null || candle == null) {
                return;
            }
            popupCandleDetail.setVisibility(View.VISIBLE);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(getResources().getColor(R.color.chart_bg, getTheme()));
            bg.setCornerRadius(0f);
            bg.setStroke(
                    (int) getResources().getDimension(R.dimen.default_grid_width),
                    getResources().getColor(R.color.chart_grid, getTheme())
            );

            popupCandleDetail.setBackground(bg);
            popupCandleDetail.setElevation(
                    getResources().getDimension(R.dimen.default_popup_padding)
            );

            if (popupTime != null) {
                popupTime.setText(fullTimeFormat.format(new Date(candle.openTime)));
            }

            if (popupVolume != null) {
                popupVolume.setText(getString(
                        R.string.chart_volume_label,
                        String.format(Locale.US, "%.2f", candle.volume)
                ));
            }
        }));

        // Chart update listener
        marketChartView.setOnChartUpdateListener(new MarketChartView.OnChartUpdateListener() {

            /**
             * Handles real-time price updates with fiat conversion.
             */
            @Override
            public void onPriceUpdate(final float price, float high24h, float low24h) {
                runOnUiThread(() -> new Thread(() -> {
                    double fiatPerBtc = getFiatPerBtc(currentFiatCode);
                    double basePerBtc = getFiatPerBtc("USD");

                    if (fiatPerBtc == 0d || basePerBtc == 0d) {
                        final double priceInFiat = price;
                        mainHandler.post(() -> updatePriceDisplay(priceInFiat));
                        return;
                    }

                    double usdToFiat = fiatPerBtc / basePerBtc;
                    final double priceInFiat = price * usdToFiat;

                    mainHandler.post(() -> {
                        updatePriceDisplay(priceInFiat);
                        currentMarketPriceFiat = (float) priceInFiat;
                        updateBalanceDisplay();
                    });
                }).start());
            }

            /**
             * Updates price display with color coding based on price movement.
             */
            private void updatePriceDisplay(double priceInFiat) {
                if (textCurrentPrice != null) {
                    String symbol = getCurrencySymbol(currentFiatCode);
                    textCurrentPrice.setText(
                            String.format(Locale.US, "%s%,.2f", symbol, priceInFiat)
                    );

                    int color;
                    if (lastDisplayPrice == 0f) {
                        color = getThemeColor(android.R.attr.textColorPrimary);
                    } else if (priceInFiat > lastDisplayPrice) {
                        color = res.getColor(R.color.palette_green, getTheme());
                    } else if (priceInFiat < lastDisplayPrice) {
                        color = res.getColor(R.color.palette_red, getTheme());
                    } else {
                        color = res.getColor(R.color.chart_last_price_line, getTheme());
                    }

                    textCurrentPrice.setTextColor(color);
                    lastDisplayPrice = (float) priceInFiat;
                }
            }

            /**
             * Updates ticker data (24h high/low, volume, change percentage).
             */
            @Override
            public void onTickerUpdate(final float high24h,
                                       final float low24h,
                                       final float volBtc,
                                       final float volUsdt,
                                       final float changePercent) {

                runOnUiThread(() -> {
                    if (textChange24h != null) {
                        textChange24h.setText(String.format(Locale.US, "%.2f%%", changePercent));
                        int c = changePercent >= 0
                                ? res.getColor(R.color.palette_green, getTheme())
                                : res.getColor(R.color.palette_red, getTheme());
                        textChange24h.setTextColor(c);
                    }

                    new Thread(() -> {
                        double fiatPerBtc = getFiatPerBtc(currentFiatCode);
                        double basePerBtc = getFiatPerBtc("USD");

                        if (fiatPerBtc == 0d || basePerBtc == 0d) {
                            return;
                        }

                        double usdToFiat = fiatPerBtc / basePerBtc;
                        final double highFiat = high24h * usdToFiat;
                        final double lowFiat = low24h * usdToFiat;
                        final double volFiat = volUsdt * usdToFiat;

                        String baseAsset = currentSymbol;

                        if (baseAsset.endsWith("USDT")) {
                            baseAsset = baseAsset.substring(0, baseAsset.length() - 4);
                        } else if (baseAsset.endsWith("BUSD")) {
                            baseAsset = baseAsset.substring(0, baseAsset.length() - 4);
                        } else if (baseAsset.length() > 3) {
                            baseAsset = baseAsset.substring(0, 3);
                        }

                        final String highStr = getString(
                                R.string.chart_high_label,
                                String.format(Locale.US, "%,.2f", highFiat)
                        );

                        final String lowStr = getString(
                                R.string.chart_low_label,
                                String.format(Locale.US, "%,.2f", lowFiat)
                        );

                        final String volBtcStr = getString(
                                R.string.chart_vol_base_format,
                                baseAsset,
                                String.format(Locale.US, "%.2f", volBtc)
                        );

                        final String volFiatStr;

                        if (volFiat >= 1_000_000_000) {
                            volFiatStr = getString(
                                    R.string.chart_vol_quote_format,
                                    currentFiatCode,
                                    String.format(Locale.US, "%.2fB", volFiat / 1_000_000_000)
                            );
                        } else if (volFiat >= 1_000_000) {
                            volFiatStr = getString(
                                    R.string.chart_vol_quote_format,
                                    currentFiatCode,
                                    String.format(Locale.US, "%.2fM", volFiat / 1_000_000)
                            );
                        } else {
                            volFiatStr = getString(
                                    R.string.chart_vol_quote_format,
                                    currentFiatCode,
                                    String.format(Locale.US, "%.2f", volFiat)
                            );
                        }

                        mainHandler.post(() -> {
                            if (textHigh24h != null) {
                                textHigh24h.setText(highStr);
                            }
                            if (textLow24h != null) {
                                textLow24h.setText(lowStr);
                            }
                            if (textVolBtc != null) {
                                textVolBtc.setText(volBtcStr);
                            }
                            if (textVolFiat != null) {
                                textVolFiat.setText(volFiatStr);
                            }
                        });
                    }).start();
                });
            }

            /**
             * Updates Moving Average labels with current values.
             */
            @Override
            public void onMaUpdate(final List<Float> maValues) {
                runOnUiThread(() -> new Thread(() -> {
                    double fiatPerBtc = getFiatPerBtc(currentFiatCode);
                    double basePerBtc = getFiatPerBtc("USD");
                    double usdToFiat = 1d;

                    if (fiatPerBtc != 0d && basePerBtc != 0d) {
                        usdToFiat = fiatPerBtc / basePerBtc;
                    }

                    final double finalUsdToFiat = usdToFiat;

                    mainHandler.post(() -> {
                        if (textMaLabel != null) {
                            if (maValues == null || maValues.isEmpty()) {
                                textMaLabel.setText(getString(R.string.chart_ma_default));
                            } else {
                                List<MarketChartView.MaLine> lines = marketChartView.getMaLines();
                                SpannableStringBuilder sb = new SpannableStringBuilder();

                                for (int i = 0; i < lines.size(); i++) {
                                    if (i >= maValues.size()) {
                                        break;
                                    }
                                    float value = maValues.get(i);
                                    if (value == 0f) {
                                        continue;
                                    }
                                    double fiatVal = value * finalUsdToFiat;
                                    String label = String.format(
                                            Locale.US,
                                            "MA%d: %,.2f",
                                            lines.get(i).period,
                                            fiatVal
                                    );
                                    if (sb.length() > 0) {
                                        sb.append(BULLET_SEPARATOR);
                                    }
                                    int start = sb.length();
                                    sb.append(label);
                                    sb.setSpan(
                                            new ForegroundColorSpan(lines.get(i).color),
                                            start,
                                            start + label.length(),
                                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                    );
                                }
                                textMaLabel.setText(sb);
                            }
                        }
                    });
                }).start());
            }

            /**
             * Updates countdown timer until next candle close.
             */
            @Override
            public void onCountdownUpdate(final String countdown) {
                runOnUiThread(() -> {
                    if (textCountdown != null) {
                        textCountdown.setText(getString(R.string.chart_close_in, countdown));
                    }
                    if (marketChartView != null) {
                        marketChartView.setCountdown(countdown);
                    }
                });
            }

            /**
             * Shows detailed candle data when a candle is selected.
             */
            @Override
            public void onCandleSelected(final MarketChartView.Candle candle) {
                runOnUiThread(() -> {
                    if (popupCandleDetail == null || candle == null) {
                        return;
                    }
                    new Thread(() -> {
                        double fiatPerBtc = getFiatPerBtc(currentFiatCode);
                        double basePerBtc = getFiatPerBtc("USD");
                        double usdToFiat = 1d;

                        if (fiatPerBtc != 0d && basePerBtc != 0d) {
                            usdToFiat = fiatPerBtc / basePerBtc;
                        }

                        final double openFiat = candle.open * usdToFiat;
                        final double highFiat = candle.high * usdToFiat;
                        final double lowFiat = candle.low * usdToFiat;
                        final double closeFiat = candle.close * usdToFiat;

                        mainHandler.post(() -> {
                            popupCandleDetail.setVisibility(View.VISIBLE);

                            GradientDrawable bg = new GradientDrawable();
                            bg.setColor(getResources().getColor(R.color.chart_bg, getTheme()));
                            bg.setCornerRadius(0f);
                            bg.setStroke(
                                    (int) getResources().getDimension(R.dimen.default_grid_width),
                                    getResources().getColor(R.color.chart_grid, getTheme())
                            );

                            popupCandleDetail.setBackground(bg);
                            popupCandleDetail.setElevation(
                                    getResources().getDimension(R.dimen.default_popup_padding)
                            );

                            if (popupTime != null) {
                                popupTime.setText(fullTimeFormat.format(new Date(candle.openTime)));
                            }

                            if (popupOpen != null) {
                                popupOpen.setText(getString(
                                        R.string.chart_open_label,
                                        String.format(Locale.US, "%,.2f", openFiat)
                                ));
                            }

                            if (popupHigh != null) {
                                popupHigh.setText(getString(
                                        R.string.chart_high_detail,
                                        String.format(Locale.US, "%,.2f", highFiat)
                                ));
                            }

                            if (popupLow != null) {
                                popupLow.setText(getString(
                                        R.string.chart_low_detail,
                                        String.format(Locale.US, "%,.2f", lowFiat)
                                ));
                            }

                            if (popupClose != null) {
                                popupClose.setText(getString(
                                        R.string.chart_close_label,
                                        String.format(Locale.US, "%,.2f", closeFiat)
                                ));
                            }

                            if (popupVolume != null) {
                                popupVolume.setText(getString(
                                        R.string.chart_volume_label,
                                        String.format(Locale.US, "%.2f", candle.volume)
                                ));
                            }
                        });
                    }).start();
                });
            }

            /**
             * Hides candle detail popup when nothing is selected.
             */
            @Override
            public void onNothingSelected() {
                runOnUiThread(() -> {
                    if (popupCandleDetail != null) {
                        popupCandleDetail.setVisibility(View.GONE);
                    }
                });
            }
        });
    }
}
