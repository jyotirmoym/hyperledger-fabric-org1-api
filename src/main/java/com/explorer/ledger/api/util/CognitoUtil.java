package com.explorer.ledger.api.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.collections.CollectionUtils;

import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.explorer.ledger.api.model.User;
import com.explorer.ledger.api.service.AMBConfigService;

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentity.CognitoIdentityClient;
import software.amazon.awssdk.services.cognitoidentity.model.GetCredentialsForIdentityRequest;
import software.amazon.awssdk.services.cognitoidentity.model.GetCredentialsForIdentityResponse;
import software.amazon.awssdk.services.cognitoidentity.model.GetIdRequest;
import software.amazon.awssdk.services.cognitoidentity.model.GetIdResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;

public class CognitoUtil {
	
	private CognitoUtil() {
		super();
	}

	public static User extractAuthenticatedUser(AwsProxyRequest context) {
		User user = new User();
		String uName = context.getRequestContext().getAuthorizer().getClaims().getUsername();
		if (uName.startsWith("google")) {
			user.setUserName(uName);
		} else {
			user.setUserName(context.getRequestContext().getAuthorizer().getClaims().getSubject());
		}
		String groupsStr = context.getRequestContext().getAuthorizer().getClaims().getClaim("cognito:groups");
		if (groupsStr != null) {
			user.setGroups(Arrays.stream(groupsStr.split(",")).map(String::trim).collect(Collectors.toList()));
		}
		user.setFirstName(context.getRequestContext().getAuthorizer().getClaims().getClaim("name"));
		user.setLastName(context.getRequestContext().getAuthorizer().getClaims().getClaim("family_name"));
		user.setEmail(context.getRequestContext().getAuthorizer().getClaims().getEmail());
		user.setPhone(context.getRequestContext().getAuthorizer().getClaims().getClaim("phone_number"));
		return user;
	}

	public static boolean isUserInApprovedInstitutes(User user, List<String> approvedInstitutions) {
		if (user.getGroups() != null) {
			return CollectionUtils.containsAny(user.getGroups(), approvedInstitutions);
		} else {
			return false;
		}

	}

	public static String getUserEmail(String userName) {
		CognitoIdentityProviderClient awsCognitoIdentityProvider = CognitoIdentityProviderClient.builder()
				.region(Region.of(AMBConfigService.getInstance().getRegion())).build();
		AdminGetUserRequest adminGetUserRequest = AdminGetUserRequest.builder().username(userName)
				.userPoolId(AMBConfigService.getInstance().getCognitoUserPoolId()).build();
		AdminGetUserResponse adminGetUserResponse = awsCognitoIdentityProvider.adminGetUser(adminGetUserRequest);
		for (AttributeType attributeType : adminGetUserResponse.userAttributes()) {
			if (attributeType.name().equals("sub")) {
				return attributeType.value();
			}
		}
		return null;
	}

	public static AwsSessionCredentials getCredentials(AwsProxyRequest context) {
		String idToken = context.getHeaders().get("Authorization");
		String identityPoolId = AMBConfigService.getInstance().getCognitoIdentityPoolId();
		String iss = context.getRequestContext().getAuthorizer().getClaims().getIssuer().replace("https://", "");
		Map<String, String> logins = new HashMap<>();
		logins.put(iss, idToken);
		GetIdRequest getIdRequest = GetIdRequest.builder().identityPoolId(identityPoolId).logins(logins).build();
		CognitoIdentityClient cognitoIdentityClient = CognitoIdentityClient.builder()
				.region(Region.of(AMBConfigService.getInstance().getRegion())).build();
		GetIdResponse getIdResponse = cognitoIdentityClient.getId(getIdRequest);

		String identityId = getIdResponse.identityId();

		GetCredentialsForIdentityRequest credentialsForIdentityRequest = GetCredentialsForIdentityRequest.builder()
				.identityId(identityId).logins(logins).build();
		GetCredentialsForIdentityResponse credentialsForIdentityResponse = cognitoIdentityClient
				.getCredentialsForIdentity(credentialsForIdentityRequest);
		return AwsSessionCredentials.create(credentialsForIdentityResponse.credentials().accessKeyId(),
				credentialsForIdentityResponse.credentials().secretKey(),
				credentialsForIdentityResponse.credentials().sessionToken());
	}
}
