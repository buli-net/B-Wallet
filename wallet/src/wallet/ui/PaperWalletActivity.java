/*
 * Copyright 2011-2024 Andreas Schildbach and the Bitcoin Wallet contributors
 * Copyright 2024-2026 Buli-Net - v11.04-BASE-BK Fork
 *
 * This file is part of Bitcoin Wallet.
 *
 * Bitcoin Wallet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Bitcoin Wallet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Bitcoin Wallet. If not, see <https://www.gnu.org/licenses/>.
 *
 * Modification Note:
 * - Original file from Schildbach's bitcoin-wallet
 * - Removed Legacy Uncompressed address type (uncompressed ECKey 5... -> 1...)
 * - Reason: sweep scanner fails to detect uncompressed P2PKH
 * - Retained: Legacy Compressed P2PKH (K/L... -> 1...) and Native SegWit P2WPKH (bc1q...)
 * - All generated keys are now forced to compressed = true
 */

package wallet.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.print.PrintHelper;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Network;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.params.TestNet3Params;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import wallet.Constants;
import wallet.R;
import wallet.util.Bip38Helper;
import wallet.util.Qr;

/**
 * PaperWalletActivity
 * Purpose: Generate and display a printable paper wallet with QR codes.
 * Supports only 2 address formats to ensure sweep compatibility:
 * 1. Legacy Compressed P2PKH (starts with 1)
 * 2. Native SegWit P2WPKH (starts with bc1q)
 */
public class PaperWalletActivity extends AbstractWalletActivity {

    // -----------------------------------------------------------------
    // UI CONSTANTS
    // -----------------------------------------------------------------
    /** Fixed size for generated QR bitmaps */
    private static final int QR_SIZE = 512;

    // -----------------------------------------------------------------
    // VIEW REFERENCES
    // -----------------------------------------------------------------
    private View cardView;
    private ImageView qrAddressView;
    private ImageView qrKeyView;
    private TextView addressView;
    private TextView privKeyView;
    private TextView publicLabelView;
    private TextView privKeyLabelView;

    private Button toggleKeyButton;
    private Button privKeyFormatBtn;
    private Button toggleAddressBtn;
    private Button publicFormatBtn;

    // -----------------------------------------------------------------
    // BIP38 ENCRYPTION VIEWS
    // -----------------------------------------------------------------
    private CheckBox encryptToggle;
    private CheckBox showPassToggle;
    private EditText passView;
    private EditText passConfirmView;
    private TextView bip38HintView;

    // -----------------------------------------------------------------
    // STATE FLAGS - Control visibility and format modes
    // -----------------------------------------------------------------
    /** Controls whether private key is shown or hidden */
    private boolean keyVisible = true;
    /** Controls whether public address is shown or hidden */
    private boolean publicVisible = true;
    /** False = WIF format, True = HEX format for private key */
    private boolean privKeyHexMode = false;
    /** False = address string, True = public key hex */
    private boolean publicHexMode = false;
    /** True when displaying BIP38 encrypted key (6P...) */
    private boolean bip38Mode = false;

    // -----------------------------------------------------------------
    // CURRENT WALLET DATA - Cached values for current paper wallet
    // -----------------------------------------------------------------
    private String currentAddress = "";
    private String currentPubKeyHex = "";
    private String currentPrivKeyWif = "";
    private String currentPrivKeyHex = "";
    private String currentPrivKeyBip38 = "";
    private ECKey currentKey = null;

    // -----------------------------------------------------------------
    // ADDRESS TYPE CONFIGURATION
    // Only 2 types now: Legacy Compressed and Bech32
    // Legacy Uncompressed removed permanently
    // -----------------------------------------------------------------
    private int typeIndex = 0;
    private String[] typeNames;

    /** Background thread for heavy BIP38 encryption */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Get FileProvider authority for sharing image files
     */
    private String getFileProviderAuthority() {
        return getPackageName() + ".file_attachment";
    }

    // -----------------------------------------------------------------
    // ACTIVITY LIFECYCLE - onCreate
    // Initializes UI, binds views, sets listeners, generates first wallet
    // -----------------------------------------------------------------
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_paper_wallet);

        if (getActionBar()!= null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        setTitle(R.string.paper_wallet_activity_title);

        // Initialize type names: Legacy Compressed and Bech32 only
        typeNames = new String[]{
                getString(R.string.paper_wallet_public_format_legacy),
                getString(R.string.paper_wallet_public_format_bech32)
        };

        // Bind views from activity_paper_wallet.xml
        cardView = findViewById(R.id.paper_wallet_card);
        qrAddressView = findViewById(R.id.paper_wallet_qr_address);
        qrKeyView = findViewById(R.id.paper_wallet_qr_key);
        addressView = findViewById(R.id.paper_wallet_address);
        privKeyView = findViewById(R.id.paper_wallet_key);
        publicLabelView = findViewById(R.id.paper_wallet_public_label);
        privKeyLabelView = findViewById(R.id.paper_wallet_key_label);

        encryptToggle = findViewById(R.id.paper_wallet_encrypt_toggle);
        showPassToggle = findViewById(R.id.paper_wallet_show_pass);
        passView = findViewById(R.id.paper_wallet_passphrase);
        passConfirmView = findViewById(R.id.paper_wallet_passphrase_confirm);
        bip38HintView = findViewById(R.id.paper_wallet_bip38_hint);

        if (qrAddressView!= null) {
            qrAddressView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        if (qrKeyView!= null) {
            qrKeyView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }

        // Copy address button handler
        findViewById(R.id.paper_wallet_copy_address).setOnClickListener(v -> {
            String key = publicHexMode? currentPubKeyHex : currentAddress;
            copyText(getString(R.string.paper_wallet_public_label), key);
        });

        // Copy private key button handler - supports BIP38/WIF/HEX
        findViewById(R.id.paper_wallet_copy_privkey).setOnClickListener(v -> {
            String key = bip38Mode? currentPrivKeyBip38 : (privKeyHexMode? currentPrivKeyHex : currentPrivKeyWif);
            copyText(getString(R.string.paper_wallet_private_label), key);
        });

        // Toggle format when clicking on text itself
        addressView.setOnClickListener(v -> togglePublicFormat());
        privKeyView.setOnClickListener(v -> togglePrivKeyFormat());

        toggleAddressBtn = findViewById(R.id.paper_wallet_toggle_address);
        toggleAddressBtn.setOnClickListener(v -> togglePublicVisibility());

        publicFormatBtn = findViewById(R.id.paper_wallet_public_format);
        if (publicFormatBtn!= null) {
            publicFormatBtn.setOnClickListener(v -> togglePublicFormat());
        }

        toggleKeyButton = findViewById(R.id.paper_wallet_toggle_key);
        toggleKeyButton.setOnClickListener(v -> toggleKeyVisibility());

        privKeyFormatBtn = findViewById(R.id.paper_wallet_privkey_format);
        if (privKeyFormatBtn!= null) {
            privKeyFormatBtn.setOnClickListener(v -> togglePrivKeyFormat());
        }

        // BIP38 encryption toggle - shows/hides password fields
        encryptToggle.setOnCheckedChangeListener((buttonView, checked) -> {
            int vis = checked? View.VISIBLE : View.GONE;
            showPassToggle.setVisibility(vis);
            passView.setVisibility(vis);
            passView.setEnabled(checked);
            passConfirmView.setVisibility(vis);
            passConfirmView.setEnabled(checked);
            bip38HintView.setVisibility(vis);
            updatePrivKeyView();
        });

        // Show/hide password characters
        showPassToggle.setOnCheckedChangeListener((buttonView, checked) -> {
            if (checked) {
                passView.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                passConfirmView.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
            } else {
                passView.setTransformationMethod(PasswordTransformationMethod.getInstance());
                passConfirmView.setTransformationMethod(PasswordTransformationMethod.getInstance());
            }
            passView.setSelection(passView.getText().length());
            passConfirmView.setSelection(passConfirmView.getText().length());
        });

        // Generate initial wallet
        generateNew();
    }

    // -----------------------------------------------------------------
    // NETWORK DETECTION - Determines Mainnet/Testnet/Regtest
    // -----------------------------------------------------------------
    private Network getNetwork() {
        NetworkParameters params = Constants.NETWORK_PARAMETERS;
        String id = params.getId().toLowerCase();
        if (id.contains("regtest")) {
            return BitcoinNetwork.REGTEST;
        }
        if (id.contains("test") || id.contains("signet")) {
            return BitcoinNetwork.TESTNET;
        }
        return BitcoinNetwork.MAINNET;
    }

    /**
     * Create scaled QR bitmap from text
     */
    private Bitmap makeQr(String text) {
        Bitmap qr = Qr.bitmap(text);
        return Bitmap.createScaledBitmap(qr, QR_SIZE, QR_SIZE, false);
    }

    // -----------------------------------------------------------------
    // ADDRESS GENERATION - FORCED COMPRESSED ONLY
    // This is the core fix: always use compressed ECKey
    // -----------------------------------------------------------------
    /**
     * Generate address for given index.
     * @param key ECKey with private key
     * @param network Bitcoin network
     * @param index 0 = P2PKH compressed, 1 = P2WPKH
     * @return address string
     */
    private String getAddressForType(ECKey key, Network network, int index) {
        try {
            // Always force compressed=true - uncompressed keys are deprecated
            // Uncompressed WIF (5...) produces uncompressed address that sweep cannot scan
            ECKey useKey = ECKey.fromPrivate(key.getPrivKey(), true);
            if (index == 0) {
                // Legacy Compressed P2PKH - compatible with all wallets
                return useKey.toAddress(ScriptType.P2PKH, network).toString();
            } else {
                // Native SegWit P2WPKH - lower fees, bech32 format
                return useKey.toAddress(ScriptType.P2WPKH, network).toString();
            }
        } catch (Exception e) {
            android.util.Log.e("PaperWallet", "getAddress failed idx=" + index + " " + e.getMessage(), e);
            return key.toAddress(ScriptType.P2PKH, network).toString();
        }
    }

    // -----------------------------------------------------------------
    // WALLET GENERATION - Creates new random ECKey and derived data
    // -----------------------------------------------------------------
    private void generateNew() {
        final Network network = getNetwork();
        final boolean doBip38 = encryptToggle!= null && encryptToggle.isChecked();
        String p1 = passView!= null? passView.getText().toString() : "";
        String p2 = passConfirmView!= null? passConfirmView.getText().toString() : "";

        // Validate BIP38 passphrase if encryption enabled
        if (doBip38) {
            if (p1.isEmpty()) {
                Toast.makeText(this, R.string.paper_wallet_passphrase_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!p1.equals(p2)) {
                Toast.makeText(this, R.string.paper_wallet_passphrase_mismatch, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // Generate new random key
        currentKey = new ECKey();
        currentAddress = getAddressForType(currentKey, network, typeIndex);
        currentPubKeyHex = currentKey.getPublicKeyAsHex();

        // Determine WIF network parameters
        NetworkParameters params = Constants.NETWORK_PARAMETERS;
        String id = params.getId().toLowerCase();
        boolean isTestOrSignet = id.contains("test") || id.contains("signet");
        NetworkParameters wifParams = isTestOrSignet? TestNet3Params.get() : params;

        // Force compressed WIF (starts with K or L on mainnet)
        boolean compressed = true;
        ECKey wifKey = ECKey.fromPrivate(currentKey.getPrivKey(), compressed);
        currentPrivKeyWif = wifKey.getPrivateKeyEncoded(wifParams).toBase58();
        currentPrivKeyHex = currentKey.getPrivateKeyAsHex();

        privKeyHexMode = false;
        publicHexMode = false;
        bip38Mode = false;
        currentPrivKeyBip38 = "";

        updatePublicView();
        updatePrivKeyView();

        if (!doBip38) {
            Toast.makeText(this, R.string.paper_wallet_generated, Toast.LENGTH_SHORT).show();
            return;
        }

        // Async BIP38 encryption (CPU intensive)
        final String passphrase = p1;
        final ECKey keyFinal = currentKey;
        Toast.makeText(this, R.string.paper_wallet_encrypting_bip38, Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            try {
                currentPrivKeyBip38 = Bip38Helper.encrypt(keyFinal, passphrase, network);
                bip38Mode = true;
                runOnUiThread(() -> {
                    updatePrivKeyView();
                    Toast.makeText(this, R.string.paper_wallet_bip38_ready, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.paper_wallet_bip38_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /**
     * Regenerate only address when user switches between Legacy and SegWit
     * Private key remains same
     */
    private void regenerateAddressOnly() {
        if (currentKey == null) {
            generateNew();
            return;
        }
        final Network network = getNetwork();
        currentAddress = getAddressForType(currentKey, network, typeIndex);

        NetworkParameters params = Constants.NETWORK_PARAMETERS;
        String id = params.getId().toLowerCase();
        boolean isTestOrSignet = id.contains("test") || id.contains("signet");
        NetworkParameters wifParams = isTestOrSignet? TestNet3Params.get() : params;

        boolean compressed = true;
        ECKey wifKey = ECKey.fromPrivate(currentKey.getPrivKey(), compressed);
        currentPrivKeyWif = wifKey.getPrivateKeyEncoded(wifParams).toBase58();

        updatePublicView();
        updatePrivKeyView();
    }

    // -----------------------------------------------------------------
    // UI UPDATE METHODS
    // -----------------------------------------------------------------
    private void updatePublicView() {
        String base = getString(R.string.paper_wallet_public_label);
        String displayKey;
        String suffix;

        if (publicHexMode) {
            displayKey = currentPubKeyHex;
            suffix = " (" + typeNames[typeIndex] + " / " + getString(R.string.paper_wallet_public_format_hex) + ")";
        } else {
            displayKey = currentAddress;
            suffix = " (" + typeNames[typeIndex] + ")";
        }

        if (publicVisible) {
            addressView.setText(displayKey);
            toggleAddressBtn.setText(R.string.paper_wallet_hide);
            toggleAddressBtn.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_eye_off_24dp, 0, 0);
            if (publicFormatBtn!= null) {
                if (publicHexMode) {
                    publicFormatBtn.setText(typeNames[typeIndex] + " / " + getString(R.string.paper_wallet_public_format_hex));
                } else {
                    publicFormatBtn.setText(typeNames[typeIndex]);
                }
            }
            if (publicLabelView!= null) {
                publicLabelView.setText(base + suffix);
            }
            if (qrAddressView!= null &&!displayKey.isEmpty()) {
                qrAddressView.setImageBitmap(makeQr(displayKey));
            } else if (qrAddressView!= null) {
                qrAddressView.setImageBitmap(null);
            }
        } else {
            addressView.setText(getString(R.string.paper_wallet_hidden));
            toggleAddressBtn.setText(R.string.paper_wallet_show);
            toggleAddressBtn.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_eye_on_24dp, 0, 0);
            if (publicLabelView!= null) {
                publicLabelView.setText(base);
            }
            if (qrAddressView!= null) {
                qrAddressView.setImageBitmap(null);
            }
        }
    }

    private void updatePrivKeyView() {
        String base = getString(R.string.paper_wallet_private_label);
        String displayKey;
        String suffix;

        if (bip38Mode &&!currentPrivKeyBip38.isEmpty()) {
            displayKey = currentPrivKeyBip38;
            suffix = " (" + getString(R.string.paper_wallet_private_format_bip38) + ")";
        } else {
            displayKey = privKeyHexMode? currentPrivKeyHex : currentPrivKeyWif;
            if (privKeyHexMode) {
                suffix = " (" + getString(R.string.paper_wallet_private_format_hex) + ")";
            } else {
                suffix = " (" + getString(R.string.paper_wallet_private_format_wif) + ")";
            }
        }

        if (keyVisible) {
            privKeyView.setText(displayKey);
            toggleKeyButton.setText(R.string.paper_wallet_hide_key);
            toggleKeyButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_eye_off_24dp, 0, 0);
            if (privKeyFormatBtn!= null) {
                privKeyFormatBtn.setEnabled(!bip38Mode);
                if (bip38Mode) {
                    privKeyFormatBtn.setText(R.string.paper_wallet_private_format_bip38);
                } else {
                    if (privKeyHexMode) {
                        privKeyFormatBtn.setText(R.string.paper_wallet_private_format_hex);
                    } else {
                        privKeyFormatBtn.setText(R.string.paper_wallet_private_format_wif);
                    }
                }
                privKeyFormatBtn.setAlpha(bip38Mode? 0.5f : 1.0f);
            }
            if (privKeyLabelView!= null) {
                privKeyLabelView.setText(base + suffix);
            }
            if (qrKeyView!= null &&!displayKey.isEmpty()) {
                qrKeyView.setImageBitmap(makeQr(displayKey));
            } else if (qrKeyView!= null) {
                qrKeyView.setImageBitmap(null);
            }
        } else {
            privKeyView.setText(getString(R.string.paper_wallet_hidden));
            toggleKeyButton.setText(R.string.paper_wallet_show_key);
            toggleKeyButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_eye_on_24dp, 0, 0);
            if (privKeyLabelView!= null) {
                privKeyLabelView.setText(base);
            }
            if (qrKeyView!= null) {
                qrKeyView.setImageBitmap(null);
            }
        }
    }

    // -----------------------------------------------------------------
    // TOGGLE HANDLERS
    // -----------------------------------------------------------------
    private void togglePublicVisibility() {
        publicVisible =!publicVisible;
        updatePublicView();
    }

    private void togglePublicFormat() {
        if (!publicVisible) {
            publicVisible = true;
        }
        if (publicHexMode) {
            publicHexMode = false;
            // Cycle between 0 and 1 only (2 types)
            typeIndex = (typeIndex + 1) % typeNames.length;
            regenerateAddressOnly();
            Toast.makeText(this, getString(R.string.paper_wallet_public_format_toast, typeNames[typeIndex]), Toast.LENGTH_SHORT).show();
            return;
        } else {
            publicHexMode = true;
            updatePublicView();
            Toast.makeText(this, getString(R.string.paper_wallet_public_format_toast, getString(R.string.paper_wallet_public_format_hex)), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleKeyVisibility() {
        keyVisible =!keyVisible;
        updatePrivKeyView();
    }

    private void togglePrivKeyFormat() {
        if (!keyVisible || bip38Mode) {
            return;
        }
        privKeyHexMode =!privKeyHexMode;
        updatePrivKeyView();
        Toast.makeText(this, getString(R.string.paper_wallet_private_format_toast, privKeyHexMode? "HEX" : "WIF"), Toast.LENGTH_SHORT).show();
    }

    // -----------------------------------------------------------------
    // UTILITIES - Clipboard, Print, Save, Share, Export
    // -----------------------------------------------------------------
    private void copyText(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, getString(R.string.paper_wallet_copied, label), Toast.LENGTH_SHORT).show();
    }

    /**
     * Build bitmap for printing / saving / sharing
     * Inflates paper_wallet_print layout and renders to bitmap
     */
    private Bitmap buildPrintBitmap() {
        View printView = getLayoutInflater().inflate(R.layout.paper_wallet_print, null);

        String publicForPrint;
        if (publicHexMode) {
            publicForPrint = currentPubKeyHex;
        } else {
            publicForPrint = currentAddress;
        }

        String privKeyForPrint;
        if (bip38Mode &&!currentPrivKeyBip38.isEmpty()) {
            privKeyForPrint = currentPrivKeyBip38;
        } else {
            privKeyForPrint = privKeyHexMode? currentPrivKeyHex : currentPrivKeyWif;
        }

        if (!publicVisible) {
            publicForPrint = getString(R.string.paper_wallet_hidden);
        }
        if (!keyVisible) {
            privKeyForPrint = getString(R.string.paper_wallet_hidden);
        }

        TextView addrText = printView.findViewById(R.id.print_address);
        TextView privText = printView.findViewById(R.id.print_privkey);

        if (addrText!= null) {
            addrText.setText(publicForPrint);
            addrText.setTextSize(10f);
            addrText.setTypeface(android.graphics.Typeface.MONOSPACE);
            addrText.setSingleLine(false);
            addrText.setMaxLines(10);
            addrText.setHorizontallyScrolling(false);
        }
        if (privText!= null) {
            privText.setText(privKeyForPrint);
            privText.setTextSize(10f);
            privText.setTypeface(android.graphics.Typeface.MONOSPACE);
            privText.setSingleLine(false);
            privText.setMaxLines(10);
            privText.setHorizontallyScrolling(false);
        }

        if (publicVisible &&!publicForPrint.equals(getString(R.string.paper_wallet_hidden))) {
            ((ImageView) printView.findViewById(R.id.print_qr_address)).setImageBitmap(makeQr(publicForPrint));
        }
        if (keyVisible &&!privKeyForPrint.equals(getString(R.string.paper_wallet_hidden))) {
            ((ImageView) printView.findViewById(R.id.print_qr_key)).setImageBitmap(makeQr(privKeyForPrint));
        }

        TextView printPublicLabel = printView.findViewById(R.id.print_public_label);
        if (printPublicLabel!= null) {
            String labelText = getString(R.string.paper_wallet_public_label);
            String fullLabel;
            if (publicHexMode) {
                fullLabel = labelText + " (" + typeNames[typeIndex] + " / " + getString(R.string.paper_wallet_public_format_hex) + ")";
            } else {
                fullLabel = labelText + " (" + typeNames[typeIndex] + ")";
            }
            printPublicLabel.setText(fullLabel);
        }

        TextView printPrivLabel = printView.findViewById(R.id.print_privkey_label);
        if (printPrivLabel!= null) {
            String labelText = getString(R.string.paper_wallet_private_label);
            String fullLabel;
            if (bip38Mode) {
                fullLabel = labelText + " (" + getString(R.string.paper_wallet_private_format_bip38) + ")";
            } else {
                if (privKeyHexMode) {
                    fullLabel = labelText + " (" + getString(R.string.paper_wallet_private_format_hex) + ")";
                } else {
                    fullLabel = labelText + " (" + getString(R.string.paper_wallet_private_format_wif) + ")";
                }
            }
            printPrivLabel.setText(fullLabel);
        }

        int widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        printView.measure(widthSpec, heightSpec);
        printView.layout(0, 0, printView.getMeasuredWidth(), printView.getMeasuredHeight());

        Bitmap bitmap = Bitmap.createBitmap(printView.getMeasuredWidth(), printView.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xFFFFFFFF);
        printView.draw(canvas);
        return bitmap;
    }

    private File getShareFile() throws Exception {
        File dir = new File(getCacheDir(), "paperwallet");
        dir.mkdirs();
        File file = new File(dir, "paperwallet_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            buildPrintBitmap().compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return file;
    }

    private void savePaperWallet() {
        try {
            Bitmap bitmap = buildPrintBitmap();
            String filename = "paperwallet_" + System.currentTimeMillis() + ".png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PaperWallet");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Toast.makeText(this, getString(R.string.paper_wallet_saved, filename), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.paper_wallet_save_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void exportWalletTxt() {
        try {
            String typeName;
            if (publicHexMode) {
                typeName = typeNames[typeIndex] + " / " + getString(R.string.paper_wallet_public_format_hex);
            } else {
                typeName = typeNames[typeIndex];
            }

            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.paper_wallet_activity_title)).append("\n");
            sb.append(getString(R.string.paper_wallet_public_type, typeName)).append("\n");
            sb.append(getString(R.string.paper_wallet_public_label)).append(": ");
            sb.append(publicVisible? addressView.getText().toString() : getString(R.string.paper_wallet_hidden)).append("\n");
            sb.append(getString(R.string.paper_wallet_private_label)).append(": ");
            sb.append(keyVisible? privKeyView.getText().toString() : getString(R.string.paper_wallet_hidden)).append("\n");

            if (bip38Mode &&!currentPrivKeyBip38.isEmpty()) {
                sb.append(getString(R.string.paper_wallet_bip38_hint)).append("\n");
            }

            String filename = "paperwallet_" + System.currentTimeMillis() + ".txt";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/PaperWallet");
            Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, getString(R.string.paper_wallet_exported, filename), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.paper_wallet_export_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void sharePaperWallet() {
        try {
            File file = getShareFile();
            Uri uri = FileProvider.getUriForFile(this, getFileProviderAuthority(), file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.paper_wallet_share)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.paper_wallet_share_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void printPaperWallet() {
        try {
            PrintHelper helper = new PrintHelper(this);
            helper.setScaleMode(PrintHelper.SCALE_MODE_FIT);
            helper.printBitmap(getString(R.string.paper_wallet_activity_title) + " - " + currentAddress, buildPrintBitmap());
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.paper_wallet_print_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    // -----------------------------------------------------------------
    // MENU HANDLING
    // -----------------------------------------------------------------
    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.paper_wallet_options, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        final int networkSignificantColor = getResources().getColor(R.color.fg_on_dark_bg_network_significant);
        final View decor = getWindow().getDecorView();
        decor.post(() -> {
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
    public boolean onOptionsItemSelected(final MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.paper_wallet_options_refresh) {
            generateNew();
            return true;
        } else if (id == R.id.paper_wallet_options_save) {
            savePaperWallet();
            return true;
        } else if (id == R.id.paper_wallet_options_share) {
            sharePaperWallet();
            return true;
        } else if (id == R.id.paper_wallet_options_print) {
            printPaperWallet();
            return true;
        } else if (id == R.id.paper_wallet_options_export) {
            exportWalletTxt();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
