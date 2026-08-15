package wallet.util;

import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.Network;
import org.bitcoinj.crypto.ECKey;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.generators.SCrypt;

/**
 * BIP38 helper - Non-EC-multiplied private key encryption
 *
 * Implements BIP38 encryption for Bitcoin private keys.
 * Takes a raw ECKey and a user passphrase, outputs a Base58Check
 * encoded BIP38 key starting with "6P".
 *
 * Compatible with bitcoinj 0.17.1 / Android.
 * Includes a self-contained Base58 encoder, no external dependency.
 */
public class Bip38Helper {

    // Bitcoin Base58 alphabet, excludes 0/O/I/l to avoid visual ambiguity
    private static final char[] BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    /**
     * Encrypt an ECKey with a BIP38 passphrase.
     *
     * @param key ECKey to encrypt. Compressed keys are recommended.
     * @param passphrase User passphrase, UTF-8 encoded.
     * @param network Bitcoin network, used to derive the address hash (P2PKH).
     * @return BIP38 encrypted private key, Base58Check encoded, starts with "6P".
     * @throws Exception on crypto errors.
     */
    public static String encrypt(ECKey key, String passphrase, Network network) throws Exception {
        byte[] privKeyBytes = key.getPrivKeyBytes();
        boolean compressed = key.isCompressed();

        // BIP38 addresshash is always derived from the legacy P2PKH address,
        // regardless of the actual output type used later.
        String address = key.toAddress(ScriptType.P2PKH, network).toString();
        byte[] addressHash = doubleSha256(address.getBytes(StandardCharsets.UTF_8));
        // Use first 4 bytes of the double SHA256 as the address hash / salt
        addressHash = Arrays.copyOf(addressHash, 4);

        // Derive a 64-byte key from passphrase + addressHash using scrypt
        // BIP38 parameters: N=16384, r=8, p=8
        byte[] derived = SCrypt.generate(
                passphrase.getBytes(StandardCharsets.UTF_8),
                addressHash,
                16384, 8, 8, 64
        );
        // Split derived key into two 32-byte halves
        byte[] derivedHalf1 = Arrays.copyOfRange(derived, 0, 32);
        byte[] derivedHalf2 = Arrays.copyOfRange(derived, 32, 64);

        // XOR the private key with derivedHalf1
        byte[] xor = new byte[32];
        for (int i = 0; i < 32; i++) {
            xor[i] = (byte) (privKeyBytes[i] ^ derivedHalf1[i]);
        }

        // Encrypt the XORed private key with AES-256-ECB, using derivedHalf2 as the AES key
        // BIP38 uses two separate 16-byte AES blocks, no padding
        Cipher aes;
        try {
            // Try SpongyCastle provider first (Android "SC")
            aes = Cipher.getInstance("AES/ECB/NoPadding", "SC");
        } catch (Exception e) {
            // Fall back to BouncyCastle ("BC")
            aes = Cipher.getInstance("AES/ECB/NoPadding", "BC");
        }
        SecretKeySpec keySpec = new SecretKeySpec(derivedHalf2, "AES");
        aes.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] encryptedHalf1 = aes.doFinal(Arrays.copyOfRange(xor, 0, 16));
        byte[] encryptedHalf2 = aes.doFinal(Arrays.copyOfRange(xor, 16, 32));

        // Build BIP38 payload:
        // [0x01][0x42][flag][addressHash(4)][encryptedHalf1(16)][encryptedHalf2(16)]
        // flag = 0xC0 | 0x20 if compressed
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0x01);
        baos.write(0x42);
        int flag = 0xC0 | (compressed? 0x20 : 0x00);
        baos.write(flag);
        baos.write(addressHash);
        baos.write(encryptedHalf1);
        baos.write(encryptedHalf2);

        byte[] payload = baos.toByteArray();

        // Append 4-byte checksum = first 4 bytes of double SHA256(payload)
        byte[] checksum = doubleSha256(payload);
        checksum = Arrays.copyOf(checksum, 4);

        byte[] full = new byte[payload.length + 4];
        System.arraycopy(payload, 0, full, 0, payload.length);
        System.arraycopy(checksum, 0, full, payload.length, 4);

        // Base58Check encode the full payload
        return base58Encode(full);
    }

    /**
     * Compute double SHA-256 hash.
     * Used for address hashing and Base58Check checksum.
     */
    private static byte[] doubleSha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(digest.digest(data));
    }

    /**
     * Encode a byte array to Base58.
     * Bitcoin-compatible, preserves leading zero bytes as '1'.
     */
    private static String base58Encode(byte[] input) {
        // Count leading zero bytes
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) zeros++;

        // Make a mutable copy for the divmod operation
        byte[] temp = Arrays.copyOf(input, input.length);
        char[] encoded = new char[temp.length * 2];
        int outputStart = encoded.length;
        int inputStart = zeros;

        // Convert base-256 to base-58
        while (inputStart < temp.length) {
            int mod = divmod58(temp, inputStart);
            if (temp[inputStart] == 0) inputStart++;
            encoded[--outputStart] = BASE58_ALPHABET[mod];
        }

        // Strip extra leading '1's added by the conversion
        while (outputStart < encoded.length && encoded[outputStart] == BASE58_ALPHABET[0]) outputStart++;

        // Add back leading '1's for each leading zero byte in the input
        while (--zeros >= 0) encoded[--outputStart] = BASE58_ALPHABET[0];

        return new String(encoded, outputStart, encoded.length - outputStart);
    }

    /**
     * Divide the number (base-256) by 58 in place.
     * @return remainder
     */
    private static int divmod58(byte[] number, int startAt) {
        int remainder = 0;
        for (int i = startAt; i < number.length; i++) {
            int digit256 = number[i] & 0xFF;
            int temp = remainder * 256 + digit256;
            number[i] = (byte) (temp / 58);
            remainder = temp % 58;
        }
        return remainder;
    }
}
