package uk.gov.di.lambdawarmer.lambda;

import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.InvocationType;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.amazonaws.services.lambda.model.ServiceException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.amazonaws.regions.Regions.EU_WEST_2;
import static java.text.MessageFormat.format;

public class LambdaWarmerHandler implements RequestHandler<ScheduledEvent, String> {

    private static final Logger LOG = LoggerFactory.getLogger(LambdaWarmerHandler.class);
    private final ConfigurationService configurationService;
    private final AWSLambda awsLambda;

    public LambdaWarmerHandler(ConfigurationService configurationService, AWSLambda awsLambda) {
        this.configurationService = configurationService;
        this.awsLambda = awsLambda;
    }

    public LambdaWarmerHandler() {
        this.configurationService = new ConfigurationService();
        this.awsLambda = AWSLambdaClientBuilder.standard().withRegion(EU_WEST_2).build();
    }

    @Override
    public String handleRequest(ScheduledEvent input, Context context) {
        LOG.info("Lambda warmer started");

        String lambdaArn = configurationService.getLambdaArn();
        int concurrency = configurationService.getMinConcurrency();
        List<CompletableFuture<InvokeResult>> invocations = new ArrayList<>();
        Executor executor = Executors.newFixedThreadPool(concurrency);
        for (int i = 0; i < concurrency; i++) {
            invocations.add(CompletableFuture.supplyAsync(() -> warmLambda(lambdaArn), executor));
        }

        CompletableFuture.allOf(invocations.toArray(new CompletableFuture[concurrency]))
                .thenRun(() -> {
                    invocations.forEach( i -> LOG.info("Completed Successfully: {}", !i.isCompletedExceptionally()));
                })
                .join();

        LOG.info("Lambda warmer finished");
        return format("Lambda warmup for {0}:{1} complete!", lambdaArn, configurationService.getLambdaQualifier());
    }

    private InvokeResult warmLambda(String functionName) {
        InvokeRequest invokeRequest =
                new InvokeRequest()
                        .withFunctionName(functionName)
                        .withQualifier(configurationService.getLambdaQualifier())
                        .withInvocationType(InvocationType.RequestResponse);

        try {
            InvokeResult invokeResult = awsLambda.invoke(invokeRequest);
            return invokeResult;
        } catch (ServiceException e) {
            LOG.error("Error invoking lambda", e);
            throw new RuntimeException("Error invoking Lambda", e);
        }
    }
}
