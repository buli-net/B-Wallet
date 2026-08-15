/*
 * Copyright the original author or authors.
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package wallet;

import android.os.Build;
import com.google.common.io.BaseEncoding;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.utils.MonetaryFormat;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.SigNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public final class Constants {

    /** Network this wallet is on. */
    public static final NetworkParameters NETWORK_PARAMETERS;
    static {
        if (BuildConfig.FLAVOR.equalsIgnoreCase("testnet"))
            NETWORK_PARAMETERS = TestNet3Params.get();
        else if (BuildConfig.FLAVOR.equalsIgnoreCase("signet"))
            NETWORK_PARAMETERS = SigNetParams.get();
        else if (BuildConfig.FLAVOR.equalsIgnoreCase("mainnet"))
            NETWORK_PARAMETERS = MainNetParams.get();
        else
            throw new IllegalStateException("unknown: " + BuildConfig.FLAVOR);
    }

    /** Bitcoinj global context. */
    public static final Context CONTEXT = new Context(NETWORK_PARAMETERS);

    /**
     * The type of Bitcoin addresses used for the initial wallet: {@link ScriptType#P2PKH} for classic
     * Base58, {@link ScriptType#P2WPKH} for segwit Bech32.
     */
    public static final ScriptType DEFAULT_OUTPUT_SCRIPT_TYPE = ScriptType.P2WPKH;

    /**
     * The type of Bitcoin addresses to upgrade the current wallet to: {@link ScriptType#P2PKH} for classic
     * Base58, {@link ScriptType#P2WPKH} for segwit Bech32.
     */
    public static final ScriptType UPGRADE_OUTPUT_SCRIPT_TYPE = ScriptType.P2WPKH;

    /** Enable switch for synching of the block chain */
    public static final boolean ENABLE_BLOCKCHAIN_SYNC = true;
    /** Enable switch for fetching and showing of exchange rates */
    public static final boolean ENABLE_EXCHANGE_RATES;
    static {
        if (NETWORK_PARAMETERS.getId().equals(MainNetParams.get().getId()))
            ENABLE_EXCHANGE_RATES = true;
        else if (BuildConfig.DEBUG)
            ENABLE_EXCHANGE_RATES = true; // local prices will still be struck through on testnets
        else
            ENABLE_EXCHANGE_RATES = false;
    }
    /** Enable switch for sweeping of paper wallets */
    public static final boolean ENABLE_SWEEP_WALLET = true;
    /** Enable switch for browsing to block explorers */
    public static final boolean ENABLE_BROWSE = true;

    public final static class Files {
        private static final String FILENAME_NETWORK_SUFFIX;
        static {
            if (NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_TESTNET))
                FILENAME_NETWORK_SUFFIX = "-testnet";
            else if (NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_SIGNET))
                FILENAME_NETWORK_SUFFIX = "-signet";
            else if (NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_MAINNET))
                FILENAME_NETWORK_SUFFIX = "";
            else
                throw new IllegalStateException("unknown: " + NETWORK_PARAMETERS.getId());
        }

        /** Filename of the wallet. */
        public static final String WALLET_FILENAME_PROTOBUF = "wallet-protobuf" + FILENAME_NETWORK_SUFFIX;

        /** How often the wallet is autosaved. */
        public static final Duration WALLET_AUTOSAVE_DELAY = Duration.ofSeconds(3);

        /** Filename of the automatic key backup (old format, can only be read). */
        public static final String WALLET_KEY_BACKUP_BASE58 = "key-backup-base58" + FILENAME_NETWORK_SUFFIX;

        /** Filename of the automatic wallet backup. */
        public static final String WALLET_KEY_BACKUP_PROTOBUF = "key-backup-protobuf" + FILENAME_NETWORK_SUFFIX;

        /** Filename of the manual wallet backup. */
        public static final String EXTERNAL_WALLET_BACKUP = "bitcoin-wallet-backup" + FILENAME_NETWORK_SUFFIX;

        /** Filename of the block store for storing the chain. */
        public static final String BLOCKCHAIN_FILENAME = "blockchain" + FILENAME_NETWORK_SUFFIX;

        /** Capacity of the block store. */
        public static final int BLOCKCHAIN_STORE_CAPACITY = 10000;

        /** Name of the asset containing the block checkpoints. */
        public static final String CHECKPOINTS_ASSET = "checkpoints.txt";

        /** Name of the asset containing hardcoded fees. */
        public static final String FEES_ASSET = "fees.txt";

        /** Filename of the dynamic fees file. */
        public static final String FEES_FILENAME = "fees" + FILENAME_NETWORK_SUFFIX + ".txt";

        /** Name of the asset containing Electrum servers. */
        public static final String ELECTRUM_SERVERS_ASSET = "electrum-servers.txt";
    }

    private static final String URL_SUFFIX;
    static {
        if (NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_TESTNET))
            URL_SUFFIX = "-test";
        else if (NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_SIGNET))
            URL_SUFFIX = "-signet";
        else if (NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_MAINNET))
            URL_SUFFIX = "";
        else
            throw new IllegalStateException("unknown: " + NETWORK_PARAMETERS.getId());
    }

    /** URL to fetch version alerts from. */
   // public static final HttpUrl VERSION_URL= HttpUrl.parse("https://wallet.schildbach.de/version" + URL_SUFFIX);
    public static final HttpUrl VERSION_URL= HttpUrl.parse("https://buli-net.github.io/version" + URL_SUFFIX);

    /** URL to fetch dynamic fees from, if enabled. */
   // public static final HttpUrl DYNAMIC_FEES_URL = HttpUrl.parse("https://wallet.schildbach.de/fees" + URL_SUFFIX);
    public static final HttpUrl DYNAMIC_FEES_URL = HttpUrl.parse("https://buli-net.github.io/fees" + URL_SUFFIX);

    /** MIME type used for transmitting single transactions. */
    public static final String MIMETYPE_TRANSACTION = "application/x-btctx";

    /** MIME type used for transmitting wallet backups. */
    public static final String MIMETYPE_WALLET_BACKUP = "application/x-bitcoin-wallet-backup";

    /** Number of confirmations until a transaction is fully confirmed. */
    public static final int MAX_NUM_CONFIRMATIONS = 7;

    /** User-agent to use for the Bitcoin network. */
    public static final String USER_AGENT = "Bitcoin Wallet";

    /** Default currency to use if all default mechanisms fail. */
    public static final String DEFAULT_EXCHANGE_CURRENCY = "USD";

    /** Donation address for tip/donate action. */
   // public static final String DONATION_ADDRESS = NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_MAINNET)
         // ? "bc1q66u53meh4n3a9crmx7fhgphhw975llz00m8jda" : null;
    public static final String DONATION_ADDRESS = NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_MAINNET)
            ? "bc1pmqfmq6v3zk5mtemk7crqgg4twz6t55303nva0dcc9z5kmd834qmskdyad7" : null;

    /** Recipient e-mail address for reports. */
  //  public static final String REPORT_EMAIL = "bitcoin.wallet.developers@gmail.com";
    public static final String REPORT_EMAIL = "hvlrcb@gmail.com";

    /** Subject line for manually reported issues. */
    public static final String REPORT_SUBJECT_ISSUE = "Reported issue";

    /** Subject line for crash reports. */
    public static final String REPORT_SUBJECT_CRASH = "Crash report";

    public static final char CHAR_HAIR_SPACE = '\u200a';
    public static final char CHAR_THIN_SPACE = '\u2009';
    public static final char CHAR_BITCOIN = '\u20bf';
    public static final char CHAR_ALMOST_EQUAL_TO = '\u2248';
    public static final char CHAR_CHECKMARK = '\u2713';
    public static final char CHAR_CROSSMARK = '\u2715';
    public static final char CURRENCY_PLUS_SIGN = '\uff0b';
    public static final char CURRENCY_MINUS_SIGN = '\uff0d';
    public static final String PREFIX_ALMOST_EQUAL_TO = Character.toString(CHAR_ALMOST_EQUAL_TO) + CHAR_THIN_SPACE;
    public static final int ADDRESS_FORMAT_GROUP_SIZE = 4;
    public static final int ADDRESS_FORMAT_LINE_SIZE = 12;

    public static final MonetaryFormat LOCAL_FORMAT = new MonetaryFormat().noCode().minDecimals(2).optionalDecimals();

    public static final BaseEncoding HEX = BaseEncoding.base16().lowerCase();

  //  public static final String BINARY_URL = "https://wallet.schildbach.de/";
    public static final String BINARY_URL = "https://buli-net.github.io/";

    public static final Duration PEER_DISCOVERY_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration PEER_TIMEOUT = Duration.ofSeconds(15);

    public static final Duration LAST_USAGE_THRESHOLD_JUST = Duration.ofHours(1);
    public static final Duration LAST_USAGE_THRESHOLD_TODAY = Duration.ofDays(1);
    public static final Duration LAST_USAGE_THRESHOLD_RECENTLY = Duration.ofDays(7);
    public static final Duration LAST_USAGE_THRESHOLD_INACTIVE = Duration.ofDays(4 * 7);

    public static final Duration SERVICE_STOP_DELAY_AFTER_START =
            NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_MAINNET) ?
                    Duration.ofMinutes(1) :
                    Duration.ofMinutes(2);
    public static final Duration SERVICE_STOP_DELAY_AFTER_TRANSACTION =
            Duration.ofMinutes(5);
    public static final Duration SERVICE_STOP_DELAY_AFTER_EVENT =
            NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_MAINNET) ?
                    Duration.ofSeconds(30) :
                    Duration.ofMinutes(2);

    public static final Duration DELAYED_TRANSACTION_THRESHOLD = Duration.ofHours(2);

    public static final Duration AUTOCLOSE_DELAY = Duration.ofSeconds(1);

    /** A balance above this amount will show a warning */
    public static final Coin TOO_MUCH_BALANCE_THRESHOLD = Coin.COIN.divide(32);
    /** A balance above this amount will cause the donate option to be shown */
    public static final Coin SOME_BALANCE_THRESHOLD = Coin.COIN.divide(1600);
    /** Values less than this are considered not spendable in an economical way */
    public static final Coin MIN_NONDUST = Coin.valueOf(546); // satoshis

    public static final int SDK_DEPRECATED_BELOW = Build.VERSION_CODES.P;
    public static final String SECURITY_PATCH_INSECURE_BELOW = "2025-01-01";

    public static final int NOTIFICATION_ID_CONNECTIVITY = 1;
    public static final int NOTIFICATION_ID_COINS_RECEIVED = 2;
    public static final int NOTIFICATION_ID_BLUETOOTH = 3;
    public static final int NOTIFICATION_ID_INACTIVITY = 4;
    public static final String NOTIFICATION_GROUP_KEY_RECEIVED = "group-received";
    public static final String NOTIFICATION_CHANNEL_ID_RECEIVED = "received";
    public static final String NOTIFICATION_CHANNEL_ID_ONGOING = "ongoing";
    public static final String NOTIFICATION_CHANNEL_ID_IMPORTANT = "important";

    /** Desired number of scrypt iterations for deriving the spending PIN */
    public static final int SCRYPT_ITERATIONS_TARGET = 65536;
    public static final int SCRYPT_ITERATIONS_TARGET_LOWRAM = 32768;

    /** Default ports for Electrum servers */
    private static final int ELECTRUM_SERVER_DEFAULT_PORT_OFFSET;
    static {
        if (NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_TESTNET))
            ELECTRUM_SERVER_DEFAULT_PORT_OFFSET = 1000;
        else if (NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_SIGNET))
            ELECTRUM_SERVER_DEFAULT_PORT_OFFSET = 3000;
        else if (NETWORK_PARAMETERS.getId().equals(BitcoinNetwork.ID_MAINNET))
            ELECTRUM_SERVER_DEFAULT_PORT_OFFSET = 0;
        else
            throw new IllegalStateException("unknown: " + NETWORK_PARAMETERS.getId());
    }

    public static final int ELECTRUM_SERVER_DEFAULT_PORT_TCP = 50001 + ELECTRUM_SERVER_DEFAULT_PORT_OFFSET;
    public static final int ELECTRUM_SERVER_DEFAULT_PORT_TLS = 50002 + ELECTRUM_SERVER_DEFAULT_PORT_OFFSET;

    /** Shared HTTP client, can reuse connections */
    public static final OkHttpClient HTTP_CLIENT;
    public static final OkHttpClient HTTP_CLIENT_WITHOUT_USER_AGENT;
    static {
        final HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                new HttpLoggingInterceptor.Logger() {
                    @Override
                    public void log(final String message) {
                        log.debug(message);
                    }
                });
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);

        final OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder();
        httpClientBuilder.followRedirects(false);
        httpClientBuilder.followSslRedirects(false);
        httpClientBuilder.connectionSpecs(Collections.singletonList(ConnectionSpec.RESTRICTED_TLS));
        httpClientBuilder.connectTimeout(15, TimeUnit.SECONDS);
        httpClientBuilder.writeTimeout(15, TimeUnit.SECONDS);
        httpClientBuilder.readTimeout(15, TimeUnit.SECONDS);
        httpClientBuilder.addInterceptor(loggingInterceptor);
        HTTP_CLIENT = httpClientBuilder.build();
        HTTP_CLIENT_WITHOUT_USER_AGENT = HTTP_CLIENT.newBuilder().addNetworkInterceptor(new Interceptor() {
            @Override
            public Response intercept(Chain chain) throws IOException {
                Request request = chain.request();
                return chain.proceed(request
                        .newBuilder()
                        .removeHeader("User-Agent")
                        .build());
            }
        }).build();
    }

    private static final Logger log = LoggerFactory.getLogger(Constants.class);
}
