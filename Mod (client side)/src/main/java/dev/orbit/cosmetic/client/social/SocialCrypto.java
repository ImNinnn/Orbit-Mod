package dev.orbit.cosmetic.client.social;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * End-to-end encryption of messages between friends. Each computer has its own X25519 key pair; its private key
 * never leaves this computer. A message is encrypted with a random AES-256-GCM key, and that key is wrapped for
 * every computer of the sender and the recipient: an ephemeral X25519 exchange with the computer's public key,
 * stretched with HKDF-SHA256, encrypts it with AES-GCM. Orbit Cloud only ever stores what it cannot read.
 */
public final class SocialCrypto {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int NONCE = 12;
	private static final int TAG_BITS = 128;
	private static final byte[] INFO = "orbit message key v1".getBytes(StandardCharsets.UTF_8);

	/** This computer's key pair and ID. */
	public record DeviceKey(String id, KeyPair pair) {
		public String publicKey() {
			return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
		}
	}

	private SocialCrypto() {
	}

	/** This computer's key for a player, made the first time (the file is private to this computer). */
	public static DeviceKey deviceKey(Path file) throws IOException {
		try {
			KeyFactory factory = KeyFactory.getInstance("X25519");
			if (Files.isRegularFile(file)) {
				JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(json.get("private").getAsString())));
				PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(json.get("public").getAsString())));
				return new DeviceKey(json.get("id").getAsString(), new KeyPair(publicKey, privateKey));
			}
			KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
			byte[] id = new byte[16];
			RANDOM.nextBytes(id);
			JsonObject json = new JsonObject();
			json.addProperty("id", HexFormat.of().formatHex(id));
			json.addProperty("private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
			json.addProperty("public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, json.toString());
			try {
				Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (UnsupportedOperationException ignored) {
				// Windows: the file is in the player's own folder.
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			return new DeviceKey(json.get("id").getAsString(), pair);
		} catch (GeneralSecurityException | RuntimeException e) {
			throw new IOException("Unusable key file " + file.getFileName(), e);
		}
	}

	public static PublicKey publicKey(String base64) throws GeneralSecurityException {
		return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
	}

	public static byte[] randomKey() {
		byte[] key = new byte[32];
		RANDOM.nextBytes(key);
		return key;
	}

	/** AES-256-GCM: the nonce, then the ciphertext and its tag. */
	public static byte[] seal(byte[] plaintext, byte[] key) throws GeneralSecurityException {
		byte[] nonce = new byte[NONCE];
		RANDOM.nextBytes(nonce);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
		byte[] sealed = cipher.doFinal(plaintext);
		byte[] out = new byte[NONCE + sealed.length];
		System.arraycopy(nonce, 0, out, 0, NONCE);
		System.arraycopy(sealed, 0, out, NONCE, sealed.length);
		return out;
	}

	public static byte[] open(byte[] sealed, byte[] key) throws GeneralSecurityException {
		if (sealed.length < NONCE + TAG_BITS / 8) {
			throw new GeneralSecurityException("Too short");
		}
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE));
		return cipher.doFinal(sealed, NONCE, sealed.length - NONCE);
	}

	/** A message key, encrypted for one computer: its ephemeral public key (X.509), then the sealed key. Base64. */
	public static String wrap(byte[] messageKey, PublicKey device) throws GeneralSecurityException {
		KeyPair ephemeral = KeyPairGenerator.getInstance("X25519").generateKeyPair();
		byte[] ephemeralPublic = ephemeral.getPublic().getEncoded();
		byte[] kek = derive(ephemeral.getPrivate(), device, ephemeralPublic, device.getEncoded());
		byte[] sealed = seal(messageKey, kek);
		byte[] out = new byte[ephemeralPublic.length + sealed.length];
		System.arraycopy(ephemeralPublic, 0, out, 0, ephemeralPublic.length);
		System.arraycopy(sealed, 0, out, ephemeralPublic.length, sealed.length);
		return Base64.getEncoder().encodeToString(out);
	}

	public static byte[] unwrap(String wrapped, DeviceKey mine) throws GeneralSecurityException {
		byte[] data = Base64.getDecoder().decode(wrapped);
		int publicLength = 44;
		if (data.length <= publicLength) {
			throw new GeneralSecurityException("Too short");
		}
		byte[] ephemeralPublic = Arrays.copyOf(data, publicLength);
		PublicKey ephemeral = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(ephemeralPublic));
		byte[] kek = derive(mine.pair().getPrivate(), ephemeral, ephemeralPublic, mine.pair().getPublic().getEncoded());
		return open(Arrays.copyOfRange(data, publicLength, data.length), kek);
	}

	/** HKDF-SHA256 of the X25519 shared secret, salted with both public keys. */
	private static byte[] derive(PrivateKey own, PublicKey other, byte[] ephemeralPublic, byte[] devicePublic) throws GeneralSecurityException {
		KeyAgreement agreement = KeyAgreement.getInstance("X25519");
		agreement.init(own);
		agreement.doPhase(other, true);
		byte[] shared = agreement.generateSecret();
		byte[] salt = new byte[ephemeralPublic.length + devicePublic.length];
		System.arraycopy(ephemeralPublic, 0, salt, 0, ephemeralPublic.length);
		System.arraycopy(devicePublic, 0, salt, ephemeralPublic.length, devicePublic.length);
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(salt, "HmacSHA256"));
		byte[] prk = mac.doFinal(shared);
		mac.init(new SecretKeySpec(prk, "HmacSHA256"));
		mac.update(INFO);
		mac.update((byte) 1);
		return mac.doFinal();
	}
}
