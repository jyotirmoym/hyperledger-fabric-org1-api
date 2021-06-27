package com.explorer.ledger.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;

import com.amazonaws.serverless.proxy.jersey.JerseyLambdaContainerHandler;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;

public class StreamLambdaHandler implements RequestStreamHandler {
	private static final ResourceConfig jerseyApplication = new ResourceConfig()
			.packages("com.explorer.ledger.api.resource").register(JacksonFeature.class)
			.register(MultiPartFeature.class).register(CORSFilter.class);
	private static final JerseyLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> handler = JerseyLambdaContainerHandler
			.getAwsProxyHandler(jerseyApplication);

	@Override
	public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
		handler.proxyStream(inputStream, outputStream, context);

		// just in case it wasn't closed by the mapper
		outputStream.close();
	}
}