package uk.gov.di.lambdas;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.di.entity.ErrorResponse;
import uk.gov.di.entity.Session;
import uk.gov.di.entity.VerifyCodeRequest;
import uk.gov.di.entity.VerifyCodeResponse;
import uk.gov.di.services.CodeStorageService;
import uk.gov.di.services.ConfigurationService;
import uk.gov.di.services.RedisConnectionService;
import uk.gov.di.services.SessionService;

import java.util.Optional;

import static uk.gov.di.entity.SessionState.EMAIL_CODE_NOT_VALID;
import static uk.gov.di.entity.SessionState.EMAIL_CODE_VERIFIED;
import static uk.gov.di.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;

public class VerifyCodeHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SessionService sessionService;
    private final ConfigurationService configService;
    private final CodeStorageService codeStorageService;

    public VerifyCodeHandler(
            SessionService sessionService,
            ConfigurationService configService,
            CodeStorageService codeStorageService) {
        this.sessionService = sessionService;
        this.configService = configService;
        this.codeStorageService = codeStorageService;
    }

    public VerifyCodeHandler() {
        this.configService = new ConfigurationService();
        this.sessionService = new SessionService(configService);
        this.codeStorageService = new CodeStorageService(new RedisConnectionService(configService));
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {

        Optional<Session> session = sessionService.getSessionFromRequestHeaders(input.getHeaders());
        if (session.isEmpty()) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1000);
        }

        try {
            VerifyCodeRequest codeRequest =
                    objectMapper.readValue(input.getBody(), VerifyCodeRequest.class);
            switch (codeRequest.getNotificationType()) {
                case VERIFY_EMAIL:
                    Optional<String> code =
                            codeStorageService.getCodeForEmail(session.get().getEmailAddress());

                    if (code.isEmpty() || !code.get().equals(codeRequest.getCode())) {
                        return generateApiGatewayProxyResponse(
                                200, session.get().setState(EMAIL_CODE_NOT_VALID));
                    }
                    codeStorageService.deleteCodeForEmail(session.get().getEmailAddress());
                    sessionService.save(session.get().setState(EMAIL_CODE_VERIFIED));
                    return generateApiGatewayProxyResponse(
                            200, new VerifyCodeResponse(session.get().getState()));
            }
        } catch (JsonProcessingException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1001);
        }
        return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1002);
    }
}
