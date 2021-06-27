package com.explorer.ledger.api.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;

import org.apache.commons.io.IOUtils;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.hyperledger.fabric.sdk.Enrollment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.explorer.ledger.api.exception.EnrollmentNotFoundException;
import com.explorer.ledger.api.exception.SecretNotFoundException;
import com.explorer.ledger.api.model.FabricEnrollment;
import com.explorer.ledger.api.service.AMBConfigService;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Util class to manage Fabric users credentials on AWS Secrets Manager
 *
 */
public class SecretsManagerUtil {

	private static final Logger log = LoggerFactory.getLogger(SecretsManagerUtil.class);

	private SecretsManagerUtil() {
		super();
	}

	public static PrivateKey buildPrivateKeyFromFile(String keyContent)
			throws NoSuchAlgorithmException, InvalidKeySpecException {
		try {
			StringReader keyReader = new StringReader(keyContent);
			PemReader pemReader = new PemReader(keyReader);
			PemObject pemObject = pemReader.readPemObject();
			if (pemObject == null) {
				return buildPrivateKeyFromString(keyContent);
			} else {
				byte[] keyBytes = pemObject.getContent();

				PKCS8EncodedKeySpec priPKCS8 = new PKCS8EncodedKeySpec(keyBytes);
				KeyFactory keyFactory = KeyFactory.getInstance("EC");
				return keyFactory.generatePrivate(priPKCS8);
			}
		} catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException e) {
			log.debug(e.getMessage(), e);
			return buildPrivateKeyFromString(keyContent);
		}
	}

	/**
	 * Converts string representation of a private key to PrivateKey Object required
	 * by FabricEnrolment
	 *
	 * @param pkAsString String: Base64 representation of private key
	 * @return PrivateKey
	 * @throws NoSuchAlgorithmException, InvalidKeySpecException
	 */
	public static PrivateKey buildPrivateKeyFromString(String pkAsString)
			throws NoSuchAlgorithmException, InvalidKeySpecException {
		log.debug("Create pk: {}", pkAsString);
		byte[] keyBytes = null;

		try {
			keyBytes = java.util.Base64.getDecoder().decode(pkAsString);
		} catch (IllegalArgumentException e) {
			keyBytes = pkAsString.getBytes();
		}

		// Fabric 1.2 PrivateKey uses PKCS8EncodedKeySpec with EC algorithm
		PKCS8EncodedKeySpec priPKCS8 = new PKCS8EncodedKeySpec(keyBytes);
		KeyFactory keyFactory = KeyFactory.getInstance("EC");
		return keyFactory.generatePrivate(priPKCS8);
	}

	/**
	 * Retrieves a secret by name from AWS Secrets Manager
	 * 
	 * @param secretName String: secret name
	 * @return String: secret value
	 * @throws SecretNotFoundException
	 */
	public static String getSecret(String bucket, String key, String region, AwsCredentials credentials)
			throws SecretNotFoundException {
		S3Client client = null;
		if (credentials == null) {
			client = S3Client.builder().region(Region.of(region)).build();
		} else {
			client = S3Client.builder().credentialsProvider(StaticCredentialsProvider.create(credentials))
					.region(Region.of(region)).build();
		}
		try {
			return getWithoutEncryption(client, bucket, key);
		} catch (IOException e) {
			throw new SecretNotFoundException(Constant.IO_ERROR, e);
		} catch (SdkException e) {
			throw new SecretNotFoundException("Error while reading the secret.", e);
		}
	}

	/**
	 * Retrieves a secret by name from AWS Secrets Manager
	 * 
	 * @param secretName String: secret name
	 * @return String: secret value
	 * @throws SecretNotFoundException
	 */
	public static String getPrivateKey(String bucket, String key, String region, AwsCredentials credentials)
			throws SecretNotFoundException {
		// Create a Secrets Manager client
		try {
			S3Client client = null;
			if (credentials == null) {
				client = S3Client.builder().region(Region.of(region)).build();
			} else {
				client = S3Client.builder().credentialsProvider(StaticCredentialsProvider.create(credentials))
						.region(Region.of(region)).build();
			}
			ListObjectsV2Request listObjectRequest = ListObjectsV2Request.builder().bucket(bucket).prefix(key).build();
			ListObjectsV2Response listObjectsResponse = client.listObjectsV2(listObjectRequest);
			if (listObjectsResponse.keyCount() != 1) {
				log.debug("Key not found or multiple private keys registered");
				throw new SecretNotFoundException("Key not found or multiple private keys registered");
			}
			return getWithoutEncryption(client, bucket, listObjectsResponse.contents().get(0).key());
		} catch (IOException | SdkException e) {
			throw new SecretNotFoundException(Constant.IO_ERROR, e);
		}
	}

	private static String getWithoutEncryption(S3Client client, String bucket, String key) throws IOException {
		GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
		ResponseInputStream<GetObjectResponse> response = client.getObject(request);

		return IOUtils.toString(response, StandardCharsets.UTF_8.name());
	}

	/**
	 * Creates FabricEnrollment using user credentials (private key and sign
	 * certificate)
	 *
	 * @param userId  String: user id
	 * @param orgName String: organisation name
	 * @return FabricEnrollment
	 * @throws EnrollmentNotFoundException
	 */
	public static FabricEnrollment getFabricEnrollment(String bucket, String userId, String orgName, String region,
			AwsCredentials credentials) throws EnrollmentNotFoundException {

		String userPKSecretName = orgName + "/" + userId + "/keystore/";
		String userCertsSecretName = orgName + "/" + userId + "/signcerts/cert.pem";

		try {

			String pkAsString = SecretsManagerUtil.getPrivateKey(bucket, userPKSecretName, region, credentials);
			String certString = SecretsManagerUtil.getSecret(bucket, userCertsSecretName, region, credentials);

			FabricEnrollment fabricEnrollment = null;

			log.debug("Found users credentials in Secrets Manager");
			// Reconstruct PrivateKey from string
			PrivateKey privKey = SecretsManagerUtil.buildPrivateKeyFromFile(pkAsString);

			// Create FabricEnrollment with Secrets Manager credentials
			fabricEnrollment = new FabricEnrollment(privKey, certString);
			return fabricEnrollment;
		} catch (SecretNotFoundException | NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new EnrollmentNotFoundException("Fabric credentials not found for user " + userId, e);
		}
	}

	/**
	 * Save Enrollment credentials (private Key and sign certificate) to AWS Secrets
	 * Manager
	 *
	 * @param userId     String: user id
	 * @param orgName    String: Organization name
	 * @param enrollment Enrollment: Enrollment details
	 * @return boolean
	 * @throws NoSuchAlgorithmException
	 */
	public static boolean storeEnrollmentCredentials(String bucket, String userId, String orgName, String region,
			Enrollment enrollment, AwsCredentials credentials) {

		String userPKSecretName = orgName + "/" + userId + "/keystore/pk";
		String userCertsSecretName = orgName + "/" + userId + "/signcerts/cert.pem";
		log.debug("store bucket: {}", bucket);
		log.debug("store userPKSecretName: {}", userPKSecretName);
		log.debug("store userCertsSecretName: {}", userCertsSecretName);

		// Save Fabric credentials to Secrets Manager
		SecretsManagerUtil.createSecret(bucket, userCertsSecretName, enrollment.getCert(), region, credentials);
		SecretsManagerUtil.createSecret(bucket, userPKSecretName,
				java.util.Base64.getEncoder().encodeToString(enrollment.getKey().getEncoded()), region, credentials);
		return true;
	}

	/**
	 * Creates a secret on AWS Secrets Manager
	 * 
	 * @param secretName String: secret name
	 * @param value      String: secret value
	 * @throws NoSuchAlgorithmException
	 */
	public static void createSecret(String bucket, String key, String value, String region,
			AwsCredentials credentials) {
		S3Client client = null;
		if (credentials != null) {
			log.info("Using templorary credential");
			client = S3Client.builder().region(Region.of(region))
					.credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
		} else {
			log.info("Using lambda profile");
			client = S3Client.builder().region(Region.of(region)).build();
		}
		log.info("Key: {}", key);
		PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(bucket).key(key)
				.acl(ObjectCannedACL.PRIVATE).build();

		client.putObject(putObjectRequest, RequestBody.fromString(value));
	}

	/**
	 * Reads TLS certificate from resources folder
	 * 
	 * @param certFileName certificate file name
	 * @return String
	 */
	public static String readCert(String certFileName) throws IOException {
		String certificate = null;
		InputStream certIs = null;
		try {
			certIs = SecretsManagerUtil.class.getClassLoader().getResourceAsStream(certFileName);
			certificate = new String(IOUtils.toByteArray(certIs), StandardCharsets.UTF_8);
		} finally {
			if (certIs != null) {
				certIs.close();
			}
		}
		return certificate;
	}

	public static void encryptS3Object(String bucket, String key, AwsCredentials credentials) {
		S3Client client = null;
		if (credentials != null) {
			log.info("Using templorary credential");
			client = S3Client.builder().region(Region.of(AMBConfigService.getInstance().getRegion()))
					.credentialsProvider(StaticCredentialsProvider.create(credentials)).build();
		} else {
			log.info("Using lambda profile");
			client = S3Client.builder().region(Region.of(AMBConfigService.getInstance().getRegion())).build();
		}
		CopyObjectRequest copyObjectRequest = CopyObjectRequest.builder().destinationBucket(bucket).destinationKey(key)
				.copySource(bucket + "/" + key).build();
		client.copyObject(copyObjectRequest);
	}

}
