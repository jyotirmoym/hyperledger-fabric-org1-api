package com.explorer.ledger.api.resource;

import java.io.IOException;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.serverless.proxy.RequestReader;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.explorer.ledger.api.exception.AppException;
import com.explorer.ledger.api.exception.ManagedBlockchainServiceException;
import com.explorer.ledger.api.model.Commodity;
import com.explorer.ledger.api.model.User;
import com.explorer.ledger.api.service.AMBConfigService;
import com.explorer.ledger.api.service.ManagedBlockchainService;
import com.explorer.ledger.api.util.CognitoUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.auth.credentials.AwsCredentials;

@Path("/api")
public class ApiController {

	private static final Logger log = LoggerFactory.getLogger(ApiController.class);

	private AMBConfigService ambConfig;

	public ApiController() throws IOException {
		super();
		ambConfig = AMBConfigService.getInstance();
	}

	/**
	 * Enroll a new Fabric user
	 *
	 * @return
	 */
	@Path("/register")
	@POST
	@Produces(MediaType.APPLICATION_JSON)
	public Response enrollUser(@Context HttpServletRequest httpRequest) {
		AwsProxyRequest context = (AwsProxyRequest) httpRequest.getAttribute(RequestReader.API_GATEWAY_EVENT_PROPERTY);

		User user = CognitoUtil.extractAuthenticatedUser(context);
		
		AwsCredentials credentials = CognitoUtil.getCredentials(context);
		ManagedBlockchainService service = new ManagedBlockchainService();
		try {
			service.setupClient();
			service.enrollUser(user.getUserName(), UUID.randomUUID().toString(), ambConfig.getOrg1MemberName(),
					credentials);
			
			return Response.ok(Boolean.TRUE).build();
		} catch (AppException e) {
			log.error("Error while enrolling user - userId: {}", user.getEmail());
			log.error(e.getMessage(), e);
			return Response.status(Status.BAD_REQUEST).entity("Error while enrolling user - " + e.getMessage()).build();
		} catch (ManagedBlockchainServiceException e) {
			log.error("Error while enrolling user, ManagedBlockchainService startup failed - {}", e.getMessage());
			log.error(e.getMessage(), e);
			return Response.status(Status.INTERNAL_SERVER_ERROR)
					.entity("Error while enrolling user, ManagedBlockchainService startup failed - " + e.getMessage())
					.build();
		} catch (Exception e) {
			String email = user.getEmail();
			log.error("Error while enrolling user - userId: {}", email);
			log.error(e.getMessage(), e);
			return Response.status(Status.INTERNAL_SERVER_ERROR).entity("Error while enrolling user. " + e.getMessage())
					.build();
		} finally {
			service.cleanUp();
		}
	}

	/**
	 * Generic endpoint to query any function on any chaincode.
	 *
	 * @param chaincodeName Name of the chaincode
	 * @param functionName  Name of the function to query
	 * @param args          (optional) argument for the function to query
	 * @return
	 */
	@Path("/query")
	@POST
	@Produces(MediaType.APPLICATION_JSON)
	public Response query(String coomodity, @Context HttpServletRequest httpRequest) {
		ManagedBlockchainService service = new ManagedBlockchainService();
		try {
			AwsProxyRequest context = (AwsProxyRequest) httpRequest
					.getAttribute(RequestReader.API_GATEWAY_EVENT_PROPERTY);
			AwsCredentials credentials = CognitoUtil.getCredentials(context);
			
			User user = CognitoUtil.extractAuthenticatedUser(context);
			String member = ambConfig.getOrg1MemberName();

			service.setupClient();
			// First retrieve LambdaUser's credentials and set user context
			service.setUser(user.getUserName(), member, credentials);
			service.initChannel();

			String res = service.queryChaincode(service.getClient(), service.getChannel(),
					ambConfig.getCommodityContract(), ambConfig.getFetchCommodityQuery(), coomodity);
			return Response.status(Status.OK).entity(res).build();
		} catch (AppException e) {
			log.error(e.getMessage(), e);
			return Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build();
		} catch (ManagedBlockchainServiceException e) {
			log.error(e.getMessage(), e);
			return Response.status(Status.INTERNAL_SERVER_ERROR)
					.entity("ManagedBlockchainService startup failed - " + e.getMessage()).build();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return Response.status(Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
		} finally {
			service.cleanUp();
		}
	}

	/**
	 * Generic endpoint to invoke any function on any chaincode
	 *
	 * @param invokeRequest InvokeRequest object containing: - chaincodeName: name
	 *                      of the chaincode - functionName: function to invoke -
	 *                      argsList (optional): list of arguments for the function
	 *                      to invoke
	 * @return
	 */
	@Path("/submit/commodity")
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response invoke(Commodity commodity, @Context HttpServletRequest httpRequest) {
		ManagedBlockchainService service = new ManagedBlockchainService();
		try {
			AwsProxyRequest context = (AwsProxyRequest) httpRequest
					.getAttribute(RequestReader.API_GATEWAY_EVENT_PROPERTY);
			
			AwsCredentials credentials = CognitoUtil.getCredentials(context);
			User user = CognitoUtil.extractAuthenticatedUser(context);
				String member = ambConfig.getOrg1MemberName();
				service.setupClient();
				// First retrieve LambdaUser's credentials and set user context
				service.setUser(user.getUserName(), member, credentials);
				service.initChannel();
				String[] arguments = new String[] { new ObjectMapper().writeValueAsString(commodity) };
				log.info("Invoking chaincode with payload: {}", arguments[0]);

				service.invokeChaincode(ambConfig.getInvokePutCommodity(), arguments);

				return Response.status(Status.OK).entity(Boolean.TRUE).build();
		} catch (AppException e) {
			log.error(e.getMessage(), e);
			return Response.status(Status.FORBIDDEN).entity("Error while invoking chaincode - " + e.getMessage())
					.build();
		} catch (ManagedBlockchainServiceException e) {
			log.error(e.getMessage(), e);
			return Response.status(Status.INTERNAL_SERVER_ERROR).entity(
					"Error while invoking chaincode, ManagedBlockchainService startup failed - " + e.getMessage())
					.build();
		} catch (Exception e) {
			log.error(e.getMessage(), e);
			return Response.status(Status.INTERNAL_SERVER_ERROR).entity("Error while invoking chaincode").build();
		} finally {
			service.cleanUp();
		}
	}
}