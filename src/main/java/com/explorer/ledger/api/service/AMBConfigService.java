package com.explorer.ledger.api.service;

import java.io.IOException;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Getter;

/**
 * AMB Configuration file, set your Amazon Managed Blockchain network parameters
 * here
 *
 */
@Getter
public final class AMBConfigService {
	private static final Logger log = LoggerFactory.getLogger(AMBConfigService.class);

	private static AMBConfigService ambConfigService;

	static {
		try {
			ambConfigService = new AMBConfigService();
		} catch (IOException e) {
			log.error(e.getMessage(), e);
		}
	}

	public static AMBConfigService getInstance() {
		return ambConfigService;
	}

	private AMBConfigService() throws IOException {
		super();
		Properties prop = new Properties();
		prop.load(AMBConfigService.class.getClassLoader().getResourceAsStream("application.properties"));
		commodityContract = prop.getProperty("commodity.smart.contract");
		invokePutCommodity = prop.getProperty("commodity.smart.contract.invoke.put.contract");
		fetchCommodityQuery = prop.getProperty("commodity.smart.contract.query.get.contract");
		region = prop.getProperty("aws.region");
		networkId = prop.getProperty("aws.amb.network.id");
		ambChennel = prop.getProperty("aws.amb.commodity.channel");
		ambCertName = prop.getProperty("aws.amb.cert.name");
		ambOrdererUrl = prop.getProperty("aws.amb.orderer.url");
		org1MemberName = prop.getProperty("aws.amb.org1.member.name");
		org1MemberId = prop.getProperty("aws.amb.org1.member.id");
		org1PeerId = prop.getProperty("aws.amb.org1.peer.id");
		org1User = prop.getProperty("aws.amb.org1.user");
		org1Password = prop.getProperty("aws.amb.org1.password");
		org1CAUrl = prop.getProperty("aws.amb.org1.ca.url");
		org1PeerUrl = prop.getProperty("aws.amb.org1.peer.url");
		
		org2PeerId = prop.getProperty("aws.amb.org2.peer.id");
		org2PeerUrl = prop.getProperty("aws.amb.org2.peer.url");

		bucket = prop.getProperty("crypto.bucket");
		cognitoUserPoolId = prop.getProperty("cognito.userpoolid");
		cognitoIdentityPoolId = prop.getProperty("cognito.identitypoolid");
	}
	
	private String cognitoUserPoolId;

	private String org2PeerId;
	
	private String org2PeerUrl;

	private String commodityContract;

	private String invokePutCommodity;

	private String fetchCommodityQuery;

	private String region;

	private String networkId;

	private String ambChennel;

	private String ambCertName;

	private String ambOrdererUrl;

	// Organization properties

	private String org1MemberName;

	private String org1MemberId;

	private String org1PeerId;

	private String org1User;

	private String org1Password;

	private String org1CAUrl;

	private String org1PeerUrl;

	private String bucket;
	
	private String cognitoIdentityPoolId;

}
