package com.explorer.ledger.api.service;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import org.hyperledger.fabric.sdk.BlockEvent;
import org.hyperledger.fabric.sdk.ChaincodeID;
import org.hyperledger.fabric.sdk.ChaincodeResponse;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.Enrollment;
import org.hyperledger.fabric.sdk.HFClient;
import org.hyperledger.fabric.sdk.Orderer;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.QueryByChaincodeRequest;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.ProposalException;
import org.hyperledger.fabric.sdk.exception.TransactionException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric_ca.sdk.HFCAClient;
import org.hyperledger.fabric_ca.sdk.RegistrationRequest;
import org.hyperledger.fabric_ca.sdk.exception.EnrollmentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.explorer.ledger.api.exception.AppException;
import com.explorer.ledger.api.exception.EnrollmentNotFoundException;
import com.explorer.ledger.api.exception.ManagedBlockchainServiceException;
import com.explorer.ledger.api.model.FabricEnrollment;
import com.explorer.ledger.api.model.FabricUser;
import com.explorer.ledger.api.util.Constant;
import com.explorer.ledger.api.util.SecretsManagerUtil;

import software.amazon.awssdk.auth.credentials.AwsCredentials;

/**
 * Managed Blockchain service that interacts with the Fabric SDK to enroll
 * admin, enroll user & query/invoke chaincode
 *
 */
public class ManagedBlockchainService {

	private HFCAClient caClient;
	private HFClient client;
	private Channel channel;
	private String ambTlsCertAsString;

	private AMBConfigService ambConfig = AMBConfigService.getInstance();

	private static final Logger log = LoggerFactory.getLogger(ManagedBlockchainService.class);

	public ManagedBlockchainService() {
		super();
	}

	public void cleanUp() {
		if (channel != null) {
			channel.shutdown(false);
		}
		caClient = null;
		client = null;
	}

	public void setupClient() throws AppException, ManagedBlockchainServiceException {
		try {
			log.debug("Setting up CA Client and Client");
			// Set CA details
			if (this.ambTlsCertAsString == null) {
				this.ambTlsCertAsString = SecretsManagerUtil.readCert(ambConfig.getAmbCertName());
			}
			Properties caProperties = new Properties();
			caProperties.put(Constant.PEM_BYTES, ambTlsCertAsString.getBytes());

			// create HLF CA Client
			this.caClient = createHFCAClient(caProperties, getCAUrl());

			// create HLF Client
			this.client = createHFClient();
		} catch (IOException e) {
			throw new ManagedBlockchainServiceException("Managed Blockchain TLS certificate not found", e);
		}
	}

	/**
	 * Function to enroll admin
	 *
	 */
	private FabricUser enrollAdmin(String memberName, AwsCredentials credentials)
			throws AppException, ManagedBlockchainServiceException {
		if (client == null || caClient == null) {
			log.error("Client/CA Client not initialized. Run ManagedBlockchainService.setupClient() first");
			throw new ManagedBlockchainServiceException("Client/CA Client not initialized!");
		}

		try {
			// Retrieve admin User Context
			FabricUser fabricUser = getAdmin(caClient, getAdminUserName(), getAdminPassword(),
					memberName, getMemberId(), credentials);

			// Set client to act on behalf of adminUser
			client.setUserContext(fabricUser);
			log.debug("Using admin user context");
			return fabricUser;
		} catch (InvalidArgumentException | org.hyperledger.fabric_ca.sdk.exception.InvalidArgumentException
				| EnrollmentException e) {
			throw new AppException("Error enrolling Admin user - " + e.getMessage(), e);
		}
	}

	/**
	 * Set User context by using already enrolled user
	 *
	 * @param userId String: userId
	 * @throws EnrollmentNotFoundException
	 * @throws AppException
	 * @throws ManagedBlockchainServiceException
	 * @throws InvalidArgumentException
	 */
	public void setUser(String userId, String memberName, AwsCredentials credentials)
			throws EnrollmentNotFoundException, ManagedBlockchainServiceException,
			InvalidArgumentException {
		// Check if user is has enrollment credentials on AWS Secrets Manager
		Enrollment enrollment = SecretsManagerUtil.getFabricEnrollment(ambConfig.getBucket(), userId, memberName,
				ambConfig.getRegion(), credentials);

		// create Fabric user context
		FabricUser fabricUser = new FabricUser(userId, memberName, getMemberId(), enrollment);

		// check that the client has been properly setup
		if (client == null) {
			log.error("Client not initialized. Run ManagedBlockchainService.setupClient() first");
			throw new ManagedBlockchainServiceException("Client not initialized!");
		}

		// Set client to act on behalf of userId
		client.setUserContext(fabricUser);
	}

	/**
	 * Enroll a Fabric user, if user is already enrolled it retrieves user context
	 * from AWS Secrets Manager
	 *
	 * @param userId   String: userId
	 * @param password String: password
	 * @throws AppException 
	 */
	public void enrollUser(String userId, String password, String memberName, AwsCredentials credentials) throws AppException {
		try {
			// Check if user has enrollment credentials on AWS Secrets Manager
			SecretsManagerUtil.getFabricEnrollment(ambConfig.getBucket(), userId, memberName, ambConfig.getRegion(),
					credentials);
			log.debug("User is already enrolled!");
		} catch (EnrollmentNotFoundException e) {
			// User is enrolling for the first time
			log.debug("Enrollment not found for user, enrolling user ...");
			// Enroll admin and set admin context, we will need admin context to enroll a
			// new user user
			FabricUser adminUser;
			try {
				adminUser = enrollAdmin(memberName, credentials);
				// Next, enroll user
				enrollUserToCA(caClient, adminUser, userId, password, memberName, credentials);
			} catch (AppException | ManagedBlockchainServiceException e1) {
				throw new AppException(e1.getMessage(), e1);
			}
		}
	}

	/**
	 * Start channel initialization
	 *
	 */
	public void initChannel() throws AppException {
		// Initialize Channel
		log.debug("Initializing channel ...");
		this.channel = initializeChannel(client);
		log.debug("Channel initialized!");
	}

	public Channel getChannel() {
		return channel;
	}

	public HFClient getClient() {
		return client;
	}

	/**
	 * Initialize Fabric channel
	 *
	 * @param client The HF Client
	 * @return Channel
	 */
	private Channel initializeChannel(HFClient client) throws AppException {
		try {
			// Read Managed Blockchain TLS certificate from resources folder
			Properties properties = new Properties();
			if (ambTlsCertAsString.isEmpty()) {
				properties.put(Constant.PEM_BYTES, SecretsManagerUtil.readCert(ambConfig.getAmbCertName()));
			} else {
				properties.put(Constant.PEM_BYTES, ambTlsCertAsString.getBytes());
			}

			properties.setProperty("sslProvider", "openSSL");
			properties.setProperty("negotiationType", "TLS");

			// Configure Peer
			Peer adminPeer = client.newPeer(ambConfig.getOrg1PeerId(), ambConfig.getOrg1PeerUrl(), properties);
			Peer partyPeer = client.newPeer(ambConfig.getOrg2PeerId(), ambConfig.getOrg2PeerUrl(), properties);
			// Configure Orderer
			Orderer orderer = client.newOrderer(ambConfig.getNetworkId(), ambConfig.getAmbOrdererUrl(), properties);
			// Configure Channel
			channel = client.newChannel(ambConfig.getAmbChennel());

			channel.addPeer(adminPeer);
			channel.addPeer(partyPeer);
			channel.addOrderer(orderer);
			channel.initialize();

			return channel;
		} catch (InvalidArgumentException | TransactionException e) {
			throw new AppException("Unable to initialize channel", e);
		} catch (IOException e) {
			throw new AppException("Managed Blockchain TLS certificate not found", e);
		}
	}

	/**
	 * Register and enroll user with provided {@code userId/userPassword} Upon
	 * successful enrollment, user credentials will be saved on AWS Secrets Manager
	 *
	 * @param caClient  HFCAClient: The fabric-ca client.
	 * @param registrar FabricUser: The registrar to be used.
	 * @param userId    String: The user id.
	 * @return Enrollment instance
	 * @throws AppException
	 */
	private Enrollment enrollUserToCA(HFCAClient caClient, FabricUser registrar, String userId, String userPassword,
			String memberName, AwsCredentials credentials) throws AppException {
		try {
			RegistrationRequest registrationRequest = new RegistrationRequest(userId, memberName);
			registrationRequest.setSecret(userPassword);

			// Register and enroll user
			String enrollmentSecret = caClient.register(registrationRequest, registrar);
			Enrollment userEnrollment = caClient.enroll(userId, enrollmentSecret);

			// Save credentials on AWS Secrets Manager
			SecretsManagerUtil.storeEnrollmentCredentials(ambConfig.getBucket(), userId, memberName,
					ambConfig.getRegion(), userEnrollment, credentials);

			return userEnrollment;
		} catch (Exception e) {
			throw new AppException("Error enrolling user to CA", e);
		}
	}

	/**
	 * Enroll admin into Fabric CA using {@code admin/adminpwd} credentials. If
	 * admin's certificates are already present on AWS Secrets Manager, enrollment
	 * will be skipped and Admin user context will be reconstructed using
	 * credentials from Secrets Manager.
	 *
	 * @param hfcaClient HFCAClient: The Fabric CA client
	 * @return FabricUser instance
	 * @throws NoSuchAlgorithmException
	 */
	private FabricUser getAdmin(HFCAClient hfcaClient, String adminUser, String adminPassword, String memberName,
			String memberId, AwsCredentials credentials) throws EnrollmentException,
			org.hyperledger.fabric_ca.sdk.exception.InvalidArgumentException {
		try {
			// Try to build enrollment using AWS Secrets Manager credentials
			FabricEnrollment fabricEnrollment = SecretsManagerUtil.getFabricEnrollment(ambConfig.getBucket(),
					"admin-msp", memberName, ambConfig.getRegion(), credentials);

			// Create Admin user context with existing credentials
			FabricUser adminUserContext = new FabricUser(adminUser, memberName, memberId, fabricEnrollment);
			log.debug("Admin user context reconstructed from Secrets Manager");
			return adminUserContext;
		} catch (EnrollmentNotFoundException e) {
			// If admin has not yet been enrolled, enroll admin once and save credentials
			log.debug("No secret found in Secrets Manager, enrolling admin");

			// Enroll Admin first
			Enrollment adminEnrollment = hfcaClient.enroll(adminUser, adminPassword);
			FabricUser adminUserContext = new FabricUser(adminUser, memberName, memberId, adminEnrollment);
			log.debug("Admin successfully enrolled");

			// Save credentials on AWS Secrets Manager
			SecretsManagerUtil.storeEnrollmentCredentials(ambConfig.getBucket(), adminUser, memberName,
					ambConfig.getRegion(), adminEnrollment, null);

			log.debug("Admin credentials saved on S3");
			return adminUserContext;
		}
	}

	/**
	 * Create HLF client
	 *
	 * @return HFClient instance.
	 */
	private static HFClient createHFClient() throws AppException {
		try {
			CryptoSuite cryptoSuite = CryptoSuite.Factory.getCryptoSuite();
			HFClient client = HFClient.createNewInstance();
			client.setCryptoSuite(cryptoSuite);
			return client;
		} catch (IllegalAccessException | InstantiationException | ClassNotFoundException | CryptoException
				| InvalidArgumentException | NoSuchMethodException | InvocationTargetException e) {
			throw new AppException("Error creating Fabric Client", e);
		}
	}

	/**
	 * Create HLF CA client
	 *
	 * @param caClientProperties String: The Fabric CA client properties.
	 * @return HFCAClient instance
	 */
	private HFCAClient createHFCAClient(Properties caClientProperties, String caURL) throws AppException {
		try {
			CryptoSuite cryptoSuite = CryptoSuite.Factory.getCryptoSuite();
			caClient = HFCAClient.createNewInstance(caURL, caClientProperties);
			caClient.setCryptoSuite(cryptoSuite);
			return caClient;
		} catch (IllegalAccessException | InstantiationException | ClassNotFoundException | CryptoException
				| InvalidArgumentException | NoSuchMethodException | InvocationTargetException
				| MalformedURLException e) {
			throw new AppException("Error creating Fabric CA Client", e);
		}
	}

	/**
	 * Query chaincode by chaincodeName, functionName and arguments provided
	 *
	 * @param hfClient      HFClient: Fabric Client instance
	 * @param channel       Channel: Channel instance
	 * @param chaincodeName String: chaincode to query
	 * @param functionName  String: function to query
	 * @param args          String: argument for the query function
	 * @return String: query response
	 */
	public String queryChaincode(HFClient hfClient, Channel channel, String chaincodeName, String functionName,
			String args) throws ManagedBlockchainServiceException, ProposalException, InvalidArgumentException {

		if (channel == null || hfClient == null) {
			log.error("Channel/Client not initialized. Run ManagedBlockchainService.initChannel() first");
			throw new ManagedBlockchainServiceException("Channel/Client not initialized!");
		}
		QueryByChaincodeRequest qpr = hfClient.newQueryProposalRequest();
		// Chaincode Version is omitted, it can be added if required
		ChaincodeID chaincodeID = ChaincodeID.newBuilder().setName(chaincodeName).build();
		qpr.setChaincodeID(chaincodeID);
		qpr.setFcn(functionName);
		String[] arguments = { args };
		qpr.setArgs(arguments);

		// Query the chaincode
		Collection<ProposalResponse> res = channel.queryByChaincode(qpr);

		String result = "";
		// Retrieve the query response
		for (ProposalResponse pres : res) {
			result = new String(pres.getChaincodeActionResponsePayload());
		}

		return result;
	}
	/**
	 * Invoke chaincode by chaincodeName, functionName and argument list
	 *
	 * @param hfClient      HFClient: HLF client instance
	 * @param channel       Channel: Channel instance
	 * @param chainCodeName String: chaincode to invoke
	 * @param functionName  String: function to invoke
	 * @param arguments     String[]: list of arguments for chaincode invocation
	 */
	public void invokeChaincode(String functionName, String[] arguments)
			throws ManagedBlockchainServiceException, InvalidArgumentException, AppException {

		if (channel == null || client == null) {
			log.error("Channel/Client not initialized. Run ManagedBlockchainService.initChannel() first");
			throw new ManagedBlockchainServiceException("Channel/Client not initialized!");
		}
		// Set chaincdoe name, function and arguments
		ChaincodeID chaincodeID = ChaincodeID.newBuilder().setName(ambConfig.getCommodityContract()).build();
		TransactionProposalRequest invokeRequest = client.newTransactionProposalRequest();
		invokeRequest.setChaincodeID(chaincodeID);
		invokeRequest.setFcn(functionName);
		invokeRequest.setArgs(arguments);
		invokeRequest.setProposalWaitTime(2000);

		Collection<ProposalResponse> successful = new LinkedList<>();
		Collection<ProposalResponse> failed = new LinkedList<>();

		try {
			// Send transaction proposal to all peers
			Collection<ProposalResponse> responses = channel.sendTransactionProposal(invokeRequest);

			// Process responses from transaction proposal
			for (ProposalResponse response : responses) {
				log.debug("Message: {}", response.getMessage());
				String stringResponse = new String(response.getChaincodeActionResponsePayload());
				log.debug("Invoke status: {}, result: {}", response.getStatus(), stringResponse);

				if (response.getStatus() == ChaincodeResponse.Status.SUCCESS) {
					log.debug("Received successful transaction proposal response txId: {} from peer: {}",
							response.getTransactionID(), response.getPeer().getName());
					successful.add(response);
				} else {
					failed.add(response);
					log.error("Received unsuccessful transaction proposal response");
				}
			}

			if (!failed.isEmpty()) {
				throw new AppException("Failed to send Proposal and receive successful proposal responses");
			}
			// Send transaction to Orderer
			CompletableFuture<BlockEvent.TransactionEvent> cf = channel.sendTransaction(responses);
			cf.thenAccept(s -> log.debug("Invoke Completed. Block nb: {}", s.getBlockEvent().getBlockNumber()));

		} catch (ProposalException | InvalidArgumentException ex) {
			throw new AppException("Exaception while executing chaincode", ex);
		}
	}

	private String getAdminUserName() {
		return ambConfig.getOrg1User();	
	}

	private String getAdminPassword() {
		return ambConfig.getOrg1Password();
	}

	private String getCAUrl() {
		return ambConfig.getOrg1CAUrl();
	}

	private String getMemberId() {
		return ambConfig.getOrg1MemberId();
	}
}
