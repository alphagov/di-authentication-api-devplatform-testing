package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.frontendapi.entity.AccountRecoveryRequest;
import uk.gov.di.authentication.frontendapi.entity.AccountRecoveryResponse;
import uk.gov.di.authentication.frontendapi.services.DynamoAccountRecoveryBlockService;
import uk.gov.di.authentication.shared.lambda.BaseFrontendHandler;
import uk.gov.di.authentication.shared.serialization.Json.JsonException;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.state.UserContext;

import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;

public class AccountRecoveryHandler extends BaseFrontendHandler<AccountRecoveryRequest> {

    private static final Logger LOG = LogManager.getLogger(AccountRecoveryHandler.class);
    private final DynamoAccountRecoveryBlockService dynamoAccountRecoveryService;

    protected AccountRecoveryHandler(
            ConfigurationService configurationService,
            SessionService sessionService,
            ClientSessionService clientSessionService,
            ClientService clientService,
            AuthenticationService authenticationService,
            DynamoAccountRecoveryBlockService dynamoAccountRecoveryBlockService) {
        super(
                AccountRecoveryRequest.class,
                configurationService,
                sessionService,
                clientSessionService,
                clientService,
                authenticationService);
        this.dynamoAccountRecoveryService = dynamoAccountRecoveryBlockService;
    }

    public AccountRecoveryHandler(ConfigurationService configurationService) {
        super(AccountRecoveryRequest.class, configurationService);
        this.dynamoAccountRecoveryService =
                new DynamoAccountRecoveryBlockService(configurationService);
    }

    public AccountRecoveryHandler() {
        this(ConfigurationService.getInstance());
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequestWithUserContext(
            APIGatewayProxyRequestEvent input,
            Context context,
            AccountRecoveryRequest request,
            UserContext userContext) {
        try {
            LOG.info("Request received to AccountRecoveryHandler");
            LOG.info("Checking if block is present");
            var accountRecoveryPermitted =
                    !dynamoAccountRecoveryService.blockIsPresent(request.getEmail());
            LOG.info("Account recovery is permitted: {}", accountRecoveryPermitted);
            var accountRecoveryResponse = new AccountRecoveryResponse(accountRecoveryPermitted);
            LOG.info("Returning response back to frontend");
            return generateApiGatewayProxyResponse(200, accountRecoveryResponse);
        } catch (JsonException e) {
            LOG.error("Unable to serialize account recovery response", e);
            throw new RuntimeException(e);
        }
    }
}
