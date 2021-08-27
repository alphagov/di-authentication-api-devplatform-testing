package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.di.authentication.frontendapi.entity.VerifyCodeRequest;
import uk.gov.di.authentication.shared.entity.BaseAPIResponse;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.NotificationType;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.SessionState;
import uk.gov.di.authentication.shared.helpers.StateMachine;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoService;
import uk.gov.di.authentication.shared.services.RedisConnectionService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.services.ValidationService;

import java.util.Optional;

import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;

public class VerifyCodeHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SessionService sessionService;
    private final CodeStorageService codeStorageService;
    private final DynamoService dynamoService;
    private final ConfigurationService configurationService;
    private final ValidationService validationService;

    public VerifyCodeHandler(
            SessionService sessionService,
            CodeStorageService codeStorageService,
            DynamoService dynamoService,
            ConfigurationService configurationService,
            ValidationService validationService) {
        this.sessionService = sessionService;
        this.codeStorageService = codeStorageService;
        this.dynamoService = dynamoService;
        this.configurationService = configurationService;
        this.validationService = validationService;
    }

    public VerifyCodeHandler() {
        this.configurationService = new ConfigurationService();
        this.sessionService = new SessionService(configurationService);
        this.codeStorageService =
                new CodeStorageService(new RedisConnectionService(configurationService));
        this.dynamoService =
                new DynamoService(
                        configurationService.getAwsRegion(),
                        configurationService.getEnvironment(),
                        configurationService.getDynamoEndpointUri());
        this.validationService = new ValidationService();
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
                    if (codeStorageService.isCodeBlockedForSession(
                            session.get().getEmailAddress(), session.get().getSessionId())) {

                        StateMachine.validateStateTransition(
                                session.get(), SessionState.EMAIL_CODE_MAX_RETRIES_REACHED);

                        sessionService.save(
                                session.get()
                                        .setState(SessionState.EMAIL_CODE_MAX_RETRIES_REACHED));
                    } else {
                        Optional<String> emailCode =
                                codeStorageService.getOtpCode(
                                        session.get().getEmailAddress(),
                                        codeRequest.getNotificationType());
                        var newState =
                                validationService.validateEmailVerificationCode(
                                        emailCode,
                                        codeRequest.getCode(),
                                        session.get(),
                                        configurationService.getCodeMaxRetries());

                        StateMachine.validateStateTransition(session.get(), newState);
                        sessionService.save(session.get().setState(newState));
                        processCodeSessionState(session.get(), codeRequest.getNotificationType());
                    }
                    return generateApiGatewayProxyResponse(
                            200, new BaseAPIResponse(session.get().getState()));
                case VERIFY_PHONE_NUMBER:
                    if (codeStorageService.isCodeBlockedForSession(
                            session.get().getEmailAddress(), session.get().getSessionId())) {
                        StateMachine.validateStateTransition(
                                session.get(), SessionState.PHONE_NUMBER_CODE_MAX_RETRIES_REACHED);
                        sessionService.save(
                                session.get()
                                        .setState(
                                                SessionState
                                                        .PHONE_NUMBER_CODE_MAX_RETRIES_REACHED));
                    } else {
                        Optional<String> phoneNumberCode =
                                codeStorageService.getOtpCode(
                                        session.get().getEmailAddress(),
                                        codeRequest.getNotificationType());
                        var newState =
                                validationService.validatePhoneVerificationCode(
                                        phoneNumberCode,
                                        codeRequest.getCode(),
                                        session.get(),
                                        configurationService.getCodeMaxRetries());

                        StateMachine.validateStateTransition(session.get(), newState);
                        sessionService.save(session.get().setState(newState));
                        processCodeSessionState(session.get(), codeRequest.getNotificationType());
                    }
                    return generateApiGatewayProxyResponse(
                            200, new BaseAPIResponse(session.get().getState()));
                case MFA_SMS:
                    if (codeStorageService.isCodeBlockedForSession(
                            session.get().getEmailAddress(), session.get().getSessionId())) {
                        StateMachine.validateStateTransition(
                                session.get(), SessionState.MFA_CODE_MAX_RETRIES_REACHED);
                        sessionService.save(
                                session.get().setState(SessionState.MFA_CODE_MAX_RETRIES_REACHED));
                    } else {
                        Optional<String> mfaCode =
                                codeStorageService.getOtpCode(
                                        session.get().getEmailAddress(),
                                        codeRequest.getNotificationType());
                        var newState =
                                validationService.validateMfaVerificationCode(
                                        mfaCode,
                                        codeRequest.getCode(),
                                        session.get(),
                                        configurationService.getCodeMaxRetries());

                        StateMachine.validateStateTransition(session.get(), newState);
                        sessionService.save(session.get().setState(newState));
                        processCodeSessionState(session.get(), codeRequest.getNotificationType());
                    }
                    return generateApiGatewayProxyResponse(
                            200, new BaseAPIResponse(session.get().getState()));
            }
        } catch (JsonProcessingException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1001);
        } catch (StateMachine.InvalidStateTransitionException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1017);
        }
        return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1002);
    }

    private void blockCodeForSessionAndResetCount(Session session) {
        codeStorageService.saveCodeBlockedForSession(
                session.getEmailAddress(),
                session.getSessionId(),
                configurationService.getCodeExpiry());
        sessionService.save(session.resetRetryCount());
    }

    private void processCodeSessionState(Session session, NotificationType notificationType) {
        if (session.getState().equals(SessionState.PHONE_NUMBER_CODE_VERIFIED)) {
            codeStorageService.deleteOtpCode(session.getEmailAddress(), notificationType);
            dynamoService.updatePhoneNumberVerifiedStatus(session.getEmailAddress(), true);
        } else if (session.getState().equals(SessionState.EMAIL_CODE_VERIFIED)
                || session.getState().equals(SessionState.MFA_CODE_VERIFIED)) {
            codeStorageService.deleteOtpCode(session.getEmailAddress(), notificationType);
        } else if (session.getState().equals(SessionState.PHONE_NUMBER_CODE_MAX_RETRIES_REACHED)
                || session.getState().equals(SessionState.EMAIL_CODE_MAX_RETRIES_REACHED)
                || session.getState().equals(SessionState.MFA_CODE_MAX_RETRIES_REACHED)) {
            blockCodeForSessionAndResetCount(session);
        }
    }
}
