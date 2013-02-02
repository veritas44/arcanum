package app.arcanum.crypto.rsa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Base64;
import android.util.Log;
import app.arcanum.AppSettings;
import app.arcanum.crypto.ICrypto;
import app.arcanum.crypto.exceptions.DecryptException;
import app.arcanum.crypto.exceptions.EncryptException;

public class RsaCrypto implements ICrypto {
	final static String ALGORITHM = "RSA";
	final static String ALGORITHM_FULL = "RSA/NONE/PKCS1Padding";
	final static String PREF_RSA_PUBLIC_KEY = "ARCANUM_RSA_PUBLICKEY";
	final static String PREF_RSA_PRIVATE_KEY = "ARCANUM_RSA_PRIVATEKEY";
	final static String PREF_RSA_SERVER_PUBKEY = "ARCANUM_RSA_SERVER_PUBLICKEY";
	final short KEY_SIZE = 4096;
	
	private PublicKey _serverPublicKey;

	private PublicKey _publicKey;
	private PrivateKey _privateKey;
	private final Context _context;

	public RsaCrypto(final Context context) {
		_context = context;
	}

	@Override
	public void init() {
		try {
			load_serverPublicKey();
			load_secretKeys();
		} catch(Exception ex) {
			Log.e("FATAL", "RSA init failed!", ex);
		}		
	}
	
	@Override
	public byte[] encrypt(byte[] plaintext) throws EncryptException {
		return encrypt(plaintext, _publicKey);
	}
	
	public byte[] encrypt_server(byte[] plaintext) throws EncryptException {
		return encrypt(plaintext, _serverPublicKey);
	}
	
	private static byte[] encrypt(byte[] plaintext, PublicKey key) throws EncryptException {
		try {
			Cipher cipher = Cipher.getInstance(ALGORITHM_FULL);
		    cipher.init(Cipher.ENCRYPT_MODE, key);
		    byte [] ciphertext = cipher.doFinal(plaintext);
			return ciphertext;
		} catch (NoSuchAlgorithmException ex) {
			throw new EncryptException("NoSuchAlgorithmException", ex);
		} catch (NoSuchPaddingException ex) {
			throw new EncryptException("NoSuchPaddingException", ex);
		} catch (InvalidKeyException ex) {
			throw new EncryptException("InvalidKeyException", ex);
		} catch (IllegalBlockSizeException ex) {
			throw new EncryptException("IllegalBlockSizeException", ex);
		} catch (BadPaddingException ex) {
			throw new EncryptException("BadPaddingException", ex);
		}
	}

	@Override
	public byte[] decrypt(byte[] ciphertext) throws DecryptException {
		try {
			Cipher cipher = Cipher.getInstance(ALGORITHM_FULL);
			cipher.init(Cipher.DECRYPT_MODE, _privateKey);
			
			byte[] plaintext = cipher.doFinal(ciphertext);
			return plaintext;
		} catch (NoSuchAlgorithmException ex) {
			throw new DecryptException("NoSuchAlgorithmException", ex);
		} catch (NoSuchPaddingException ex) {
			throw new DecryptException("NoSuchPaddingException", ex);
		} catch (InvalidKeyException ex) {
			throw new DecryptException("InvalidKeyException", ex);
		} catch (IllegalBlockSizeException ex) {
			throw new DecryptException("IllegalBlockSizeException", ex);
		} catch (BadPaddingException ex) {
			throw new DecryptException("BadPaddingException", ex);
		}
	}
	
	public void load_serverPublicKey() {
		new LoadServerPublickeyTask().execute();
	}
	
	public void load_secretKeys() {
		new GenerateKeysTask().execute();
	}
	
	public static PublicKey parsePublicKey(String publicKey) {
		// Cleanup
		publicKey = publicKey
				.replace("-----BEGIN PUBLIC KEY-----", "")
				.replace("-----END PUBLIC KEY-----", "")
				.trim();
			
		try {
			byte[] certBuf = Base64.decode(publicKey, Base64.DEFAULT);
			X509EncodedKeySpec spec = new X509EncodedKeySpec(certBuf);
			KeyFactory kf = KeyFactory.getInstance("RSA");
			return kf.generatePublic(spec);
		} catch (GeneralSecurityException ex) {
			Log.e("parsePublicKey", "Parsing public key failed!", ex);
		}
		return null;
	}
	
	private class GenerateKeysTask extends AsyncTask<Void, Void, KeyPair> {
		private final SharedPreferences _pref = _context.getSharedPreferences(AppSettings.APP_NAME + ".security", Context.MODE_PRIVATE);
		
		@Override
		protected KeyPair doInBackground(Void... params) {
			try {
				KeyPair keypair;
				if(_pref.contains(PREF_RSA_PUBLIC_KEY) && _pref.contains(PREF_RSA_PRIVATE_KEY)) {
					keypair = LoadKeyPair();
					if(keypair != null)
						return keypair;
				} 
				
				KeyPairGenerator gen = KeyPairGenerator.getInstance(ALGORITHM);
				gen.initialize(KEY_SIZE);
				keypair = gen.generateKeyPair();
				return keypair;
			} catch (Exception ex) {
				Log.e("GenerateKeysTask", String.format("Error while generating RSA Keypair with params: %s, %s.", params[0], params[1]), ex);
			}
			return null;
		}

		@Override
		protected void onPostExecute(KeyPair result) {
			this.SaveKeyPair(result);
			_publicKey  = result.getPublic();
			_privateKey = result.getPrivate();
		}
		
		private KeyPair LoadKeyPair() {
			try {
				String b64_pubkey = _pref.getString(PREF_RSA_PUBLIC_KEY, null);
				String b64_prvkey = _pref.getString(PREF_RSA_PRIVATE_KEY, null);
				if(b64_pubkey == null || b64_prvkey == null)
					return null;
				
				ModExpResult pub = LoadKey(b64_pubkey);
	        	ModExpResult prv = LoadKey(b64_prvkey);
	        	
				KeyFactory fact = KeyFactory.getInstance(ALGORITHM);
				PublicKey 	pubkey = fact.generatePublic(new RSAPublicKeySpec(pub.Modulus, pub.Exponent));
				PrivateKey 	prvkey = fact.generatePrivate(new RSAPrivateKeySpec(prv.Modulus, prv.Exponent));
				return new KeyPair(pubkey, prvkey);
	        } catch (Exception ex) {
	            Log.e("LoadKeyPair", "Error while loading rsa keypair", ex);
	        }
			return null;	
		}
		
		private ModExpResult LoadKey(String b64_key) throws Exception {
			byte[] byte_pubkey = Base64.decode(b64_key, Base64.DEFAULT);
			
	        ByteArrayInputStream bi = new ByteArrayInputStream(byte_pubkey);
        	ObjectInputStream oi = new ObjectInputStream(bi);
        	
        	BigInteger mod = (BigInteger)oi.readObject();
        	BigInteger exp = (BigInteger)oi.readObject();
			
        	oi.close();
        	bi.close();
        	
        	return new ModExpResult(mod, exp);
		}
		
		private void SaveKeyPair(KeyPair keypair) {
			try {
				KeyFactory fact = KeyFactory.getInstance(ALGORITHM);
				RSAPublicKeySpec pub = fact.getKeySpec(keypair.getPublic(), RSAPublicKeySpec.class);
				RSAPrivateKeySpec priv = fact.getKeySpec(keypair.getPrivate(), RSAPrivateKeySpec.class);
				
				SaveKey(PREF_RSA_PUBLIC_KEY, pub.getModulus(), pub.getPublicExponent());
				SaveKey(PREF_RSA_PRIVATE_KEY, priv.getModulus(), priv.getPrivateExponent());				
	        } catch (Exception ex) {
	        	Log.e("SaveKeyPair", "Error while saving rsa keypair.", ex);
	        }
		}
		
		public void SaveKey(String tag, BigInteger mod, BigInteger exp) throws Exception {
			ByteArrayOutputStream b = new ByteArrayOutputStream();
        	ObjectOutputStream o = new ObjectOutputStream(b);
            
        	o.writeObject(mod);
        	o.writeObject(exp);
            
            byte[] byte_key = b.toByteArray();
	        String b64_key = Base64.encodeToString(byte_key, Base64.DEFAULT);

	        _pref.edit().putString(tag, b64_key).commit();
		}
		

		
		private class ModExpResult {
			public ModExpResult(BigInteger modulus, BigInteger exponent) {
				Modulus = modulus;
				Exponent = exponent;
			}
			public BigInteger Modulus;
			public BigInteger Exponent;
		}
	}
	
	private class LoadServerPublickeyTask extends AsyncTask<Void, Void, PublicKey> {
		private final SharedPreferences _pref = _context.getSharedPreferences(AppSettings.APP_NAME + ".security", Context.MODE_PRIVATE);
		
		@Override
		protected PublicKey doInBackground(Void... params) {
			try {
				if(_pref.contains(PREF_RSA_SERVER_PUBKEY)) {
					PublicKey pubkey = LoadPublicKey();
					if(pubkey != null)
						return pubkey;
				}
				
				String url = AppSettings.SERVER_URL + AppSettings.Methods.SERVER_PUBKEY;
				
				HttpClient 	client 	= new DefaultHttpClient();  
			    HttpGet 	get		= new HttpGet(url);
			    
			    HttpResponse responseGet = client.execute(get);  
			    HttpEntity resEntityGet  = responseGet.getEntity();  
			    
			    if (resEntityGet != null) {  
			        String response = EntityUtils.toString(resEntityGet);
			        return RsaCrypto.parsePublicKey(response);
			    }
			} catch (Exception ex) {
			    Log.e("LoadServerPublickeyTask", "Unknown error while getting the public key", ex);
			}	
			return null;
		}
		
		@Override
		protected void onPostExecute(PublicKey result) {
			_serverPublicKey = result;
			SavePublicKey(result);
		}
		
		private PublicKey LoadPublicKey() {
			String b64_pubkey = _pref.getString(PREF_RSA_SERVER_PUBKEY, null);
			if(b64_pubkey == null)
				return null;
			
			return RsaCrypto.parsePublicKey(b64_pubkey);
		}
		
		private void SavePublicKey(PublicKey pubkey) {
			String b64_pubkey = Base64.encodeToString(pubkey.getEncoded(), Base64.DEFAULT);
			_pref.edit().putString(PREF_RSA_SERVER_PUBKEY, b64_pubkey);
		}
	}
}
