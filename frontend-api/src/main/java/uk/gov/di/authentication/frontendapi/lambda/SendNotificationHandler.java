package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.core.exception.SdkClientException;
import uk.gov.di.authentication.frontendapi.entity.SendNotificationRequest;
import uk.gov.di.authentication.shared.domain.AuditableEvent;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.NotificationType;
import uk.gov.di.authentication.shared.entity.NotifyRequest;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.exceptions.ClientNotFoundException;
import uk.gov.di.authentication.shared.helpers.IpAddressHelper;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.helpers.PhoneNumberHelper;
import uk.gov.di.authentication.shared.lambda.BaseFrontendHandler;
import uk.gov.di.authentication.shared.serialization.Json.JsonException;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.AwsSqsClient;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.CloudwatchMetricsService;
import uk.gov.di.authentication.shared.services.CodeGeneratorService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.state.UserContext;

import java.util.Map;
import java.util.Optional;

import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.ACCOUNT_RECOVERY_EMAIL_CODE_SENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.ACCOUNT_RECOVERY_EMAIL_CODE_SENT_FOR_TEST_CLIENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.ACCOUNT_RECOVERY_EMAIL_INVALID_CODE_REQUEST;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.EMAIL_CODE_SENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.EMAIL_CODE_SENT_FOR_TEST_CLIENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.EMAIL_INVALID_CODE_REQUEST;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.PHONE_CODE_SENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.PHONE_CODE_SENT_FOR_TEST_CLIENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.PHONE_INVALID_CODE_REQUEST;
import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1001;
import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1002;
import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1011;
import static uk.gov.di.authentication.shared.entity.NotificationType.ACCOUNT_CREATED_CONFIRMATION;
import static uk.gov.di.authentication.shared.entity.NotificationType.VERIFY_CHANGE_HOW_GET_SECURITY_CODES;
import static uk.gov.di.authentication.shared.entity.NotificationType.VERIFY_PHONE_NUMBER;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateEmptySuccessApiGatewayResponse;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.attachSessionIdToLogs;
import static uk.gov.di.authentication.shared.helpers.TestClientHelper.isTestClientWithAllowedEmail;
import static uk.gov.di.authentication.shared.services.CodeStorageService.CODE_BLOCKED_KEY_PREFIX;
import static uk.gov.di.authentication.shared.services.CodeStorageService.CODE_REQUEST_BLOCKED_KEY_PREFIX;

public class SendNotificationHandler extends BaseFrontendHandler<SendNotificationRequest>
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOG = LogManager.getLogger(SendNotificationHandler.class);
    private static final CloudwatchMetricsService METRICS = new CloudwatchMetricsService();

    private final AwsSqsClient sqsClient;
    private final CodeGeneratorService codeGeneratorService;
    private final CodeStorageService codeStorageService;
    private final AuditService auditService;

    public SendNotificationHandler(
            ConfigurationService configurationService,
            SessionService sessionService,
            ClientSessionService clientSessionService,
            ClientService clientService,
            AuthenticationService authenticationService,
            AwsSqsClient sqsClient,
            CodeGeneratorService codeGeneratorService,
            CodeStorageService codeStorageService,
            AuditService auditService) {
        super(
                SendNotificationRequest.class,
                configurationService,
                sessionService,
                clientSessionService,
                clientService,
                authenticationService);
        this.sqsClient = sqsClient;
        this.codeGeneratorService = codeGeneratorService;
        this.codeStorageService = codeStorageService;
        this.auditService = auditService;
    }

    public SendNotificationHandler() {
        super(SendNotificationRequest.class, ConfigurationService.getInstance());
        this.sqsClient =
                new AwsSqsClient(
                        configurationService.getAwsRegion(),
                        configurationService.getEmailQueueUri(),
                        configurationService.getSqsEndpointUri());
        this.codeGeneratorService = new CodeGeneratorService();
        this.codeStorageService = new CodeStorageService(configurationService);
        this.auditService = new AuditService(configurationService);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequestWithUserContext(
            APIGatewayProxyRequestEvent input,
            Context context,
            SendNotificationRequest request,
            UserContext userContext) {

        attachSessionIdToLogs(userContext.getSession());

        try {
            if (!userContext.getSession().validateSession(request.getEmail())) {
                return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1000);
            }
            if (request.getNotificationType().equals(ACCOUNT_CREATED_CONFIRMATION)) {
                LOG.info("Placing message on queue for AccountCreatedConfirmation");
                NotifyRequest notifyRequest =
                        new NotifyRequest(
                                request.getEmail(),
                                ACCOUNT_CREATED_CONFIRMATION,
                                userContext.getUserLanguage());
                if (!isTestClientWithAllowedEmail(userContext, configurationService)) {
                    sqsClient.send(objectMapper.writeValueAsString((notifyRequest)));
                    LOG.info("AccountCreatedConfirmation email placed on queue");
                }
                return generateEmptySuccessApiGatewayResponse();
            }
            Optional<ErrorResponse> codeRequestValid =
                    isCodeRequestAttemptValid(
                            request.getEmail(),
                            userContext.getSession(),
                            request.getNotificationType());
            if (codeRequestValid.isPresent()) {
                auditService.submitAuditEvent(
                        getInvalidCodeAuditEventFromNotificationType(request.getNotificationType()),
                        userContext.getClientSessionId(),
                        userContext.getSession().getSessionId(),
                        userContext
                                .getClient()
                                .map(ClientRegistry::getClientID)
                                .orElse(AuditService.UNKNOWN),
                        userContext.getSession().getInternalCommonSubjectIdentifier(),
                        request.getEmail(),
                        IpAddressHelper.extractIpAddress(input),
                        Optional.ofNullable(request.getPhoneNumber()).orElse(AuditService.UNKNOWN),
                        PersistentIdHelper.extractPersistentIdFromHeaders(input.getHeaders()));
                return generateApiGatewayProxyErrorResponse(400, codeRequestValid.get());
            }
            switch (request.getNotificationType()) {
                case VERIFY_EMAIL:
                case VERIFY_CHANGE_HOW_GET_SECURITY_CODES:
                    return handleNotificationRequest(
                            request.getEmail(),
                            request.getNotificationType(),
                            userContext.getSession(),
                            userContext,
                            request.isRequestNewCode(),
                            request,
                            input);
                case VERIFY_PHONE_NUMBER:
                    if (request.getPhoneNumber() == null) {
                        return generateApiGatewayProxyResponse(400, ERROR_1011);
                    }
                    return handleNotificationRequest(
                            PhoneNumberHelper.removeWhitespaceFromPhoneNumber(
                                    request.getPhoneNumber()),
                            request.getNotificationType(),
                            userContext.getSession(),
                            userContext,
                            request.isRequestNewCode(),
                            request,
                            input);
            }
            return generateApiGatewayProxyErrorResponse(400, ERROR_1002);
        } catch (SdkClientException ex) {
            LOG.error("Error sending message to queue");
            return generateApiGatewayProxyResponse(500, "Error sending message to queue");
        } catch (JsonException e) {
            return generateApiGatewayProxyErrorResponse(400, ERROR_1001);
        } catch (ClientNotFoundException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1015);
        }
    }

    private APIGatewayProxyResponseEvent handleNotificationRequest(
            String destination,
            NotificationType notificationType,
            Session session,
            UserContext userContext,
            Boolean requestNewCode,
            SendNotificationRequest request,
            APIGatewayProxyRequestEvent input)
            throws JsonException, ClientNotFoundException {

        String code =
                requestNewCode != null && requestNewCode
                        ? generateAndSaveNewCode(session.getEmailAddress(), notificationType)
                        : codeStorageService
                                .getOtpCode(session.getEmailAddress(), notificationType)
                                .orElseGet(
                                        () ->
                                                generateAndSaveNewCode(
                                                        session.getEmailAddress(),
                                                        notificationType));

        var notifyRequest =
                new NotifyRequest(
                        destination, notificationType, code, userContext.getUserLanguage());

        sessionService.save(session.incrementCodeRequestCount());
        var testClientWithAllowedEmail =
                isTestClientWithAllowedEmail(userContext, configurationService);
        if (!testClientWithAllowedEmail) {

            if (notificationType == VERIFY_PHONE_NUMBER) {
                METRICS.putEmbeddedValue(
                        "SendingSms",
                        1,
                        Map.of(
                                "Environment",
                                configurationService.getEnvironment(),
                                "Country",
                                PhoneNumberHelper.getCountry(destination)));
            }

            sqsClient.send(objectMapper.writeValueAsString((notifyRequest)));
            LOG.info("Successfully processed request");
        }
        auditService.submitAuditEvent(
                getSuccessfulAuditEventFromNotificationType(
                        notificationType, testClientWithAllowedEmail),
                userContext.getClientSessionId(),
                userContext.getSession().getSessionId(),
                userContext
                        .getClient()
                        .map(ClientRegistry::getClientID)
                        .orElse(AuditService.UNKNOWN),
                userContext.getSession().getInternalCommonSubjectIdentifier(),
                request.getEmail(),
                IpAddressHelper.extractIpAddress(input),
                Optional.ofNullable(request.getPhoneNumber()).orElse(AuditService.UNKNOWN),
                PersistentIdHelper.extractPersistentIdFromHeaders(input.getHeaders()));
        return generateEmptySuccessApiGatewayResponse();
    }

    private String generateAndSaveNewCode(String email, NotificationType notificationType) {
        String newCode = codeGeneratorService.sixDigitCode();
        codeStorageService.saveOtpCode(
                email,
                newCode,
                notificationType.equals(VERIFY_PHONE_NUMBER)
                                || notificationType.equals(VERIFY_CHANGE_HOW_GET_SECURITY_CODES)
                        ? configurationService.getDefaultOtpCodeExpiry()
                        : configurationService.getEmailAccountCreationOtpCodeExpiry(),
                notificationType);
        return newCode;
    }

    private Optional<ErrorResponse> isCodeRequestAttemptValid(
            String email, Session session, NotificationType notificationType) {
        if (session.getCodeRequestCount() == configurationService.getCodeMaxRetries()) {
            LOG.info("User has requested too many OTP codes");
            codeStorageService.saveBlockedForEmail(
                    email,
                    CODE_REQUEST_BLOCKED_KEY_PREFIX,
                    configurationService.getBlockedEmailDuration());
            sessionService.save(session.resetCodeRequestCount());
            return Optional.of(getErrorResponseForCodeRequestLimitReached(notificationType));
        }
        if (codeStorageService.isBlockedForEmail(email, CODE_REQUEST_BLOCKED_KEY_PREFIX)) {
            LOG.info("User is blocked from requesting any OTP codes");
            return Optional.of(getErrorResponseForMaxCodeRequests(notificationType));
        }
        if (codeStorageService.isBlockedForEmail(email, CODE_BLOCKED_KEY_PREFIX)) {
            LOG.info("User is blocked from requesting any OTP codes");
            return Optional.of(getErrorResponseForMaxCodeAttempts(notificationType));
        }
        return Optional.empty();
    }

    private ErrorResponse getErrorResponseForCodeRequestLimitReached(
            NotificationType notificationType) {
        switch (notificationType) {
            case VERIFY_EMAIL:
                return ErrorResponse.ERROR_1029;
            case VERIFY_PHONE_NUMBER:
                return ErrorResponse.ERROR_1030;
            case VERIFY_CHANGE_HOW_GET_SECURITY_CODES:
                return ErrorResponse.ERROR_1046;
            default:
                LOG.error("Invalid NotificationType sent");
                throw new RuntimeException("Invalid NotificationType sent");
        }
    }

    private ErrorResponse getErrorResponseForMaxCodeRequests(NotificationType notificationType) {
        switch (notificationType) {
            case VERIFY_EMAIL:
                return ErrorResponse.ERROR_1031;
            case VERIFY_PHONE_NUMBER:
                return ErrorResponse.ERROR_1032;
            case VERIFY_CHANGE_HOW_GET_SECURITY_CODES:
                return ErrorResponse.ERROR_1047;
            default:
                LOG.error("Invalid NotificationType sent");
                throw new RuntimeException("Invalid NotificationType sent");
        }
    }

    private ErrorResponse getErrorResponseForMaxCodeAttempts(NotificationType notificationType) {
        switch (notificationType) {
            case VERIFY_EMAIL:
                return ErrorResponse.ERROR_1033;
            case VERIFY_PHONE_NUMBER:
                return ErrorResponse.ERROR_1034;
            case VERIFY_CHANGE_HOW_GET_SECURITY_CODES:
                return ErrorResponse.ERROR_1048;
            default:
                LOG.error("Invalid NotificationType sent");
                throw new RuntimeException("Invalid NotificationType sent");
        }
    }

    private AuditableEvent getSuccessfulAuditEventFromNotificationType(
            NotificationType notificationType, boolean isTestClient) {
        switch (notificationType) {
            case VERIFY_EMAIL:
                return isTestClient ? EMAIL_CODE_SENT_FOR_TEST_CLIENT : EMAIL_CODE_SENT;
            case VERIFY_PHONE_NUMBER:
                return isTestClient ? PHONE_CODE_SENT_FOR_TEST_CLIENT : PHONE_CODE_SENT;
            case VERIFY_CHANGE_HOW_GET_SECURITY_CODES:
                return isTestClient
                        ? ACCOUNT_RECOVERY_EMAIL_CODE_SENT_FOR_TEST_CLIENT
                        : ACCOUNT_RECOVERY_EMAIL_CODE_SENT;
            default:
                LOG.error(
                        "No successful Audit event configured for NotificationType: {}",
                        notificationType);
                throw new RuntimeException(
                        "No Successful Audit event configured for NotificationType");
        }
    }

    private AuditableEvent getInvalidCodeAuditEventFromNotificationType(
            NotificationType notificationType) {
        switch (notificationType) {
            case VERIFY_EMAIL:
                return EMAIL_INVALID_CODE_REQUEST;
            case VERIFY_PHONE_NUMBER:
                return PHONE_INVALID_CODE_REQUEST;
            case VERIFY_CHANGE_HOW_GET_SECURITY_CODES:
                return ACCOUNT_RECOVERY_EMAIL_INVALID_CODE_REQUEST;
            default:
                LOG.error(
                        "No invalid code request Audit event configured for NotificationType: {}",
                        notificationType);
                throw new RuntimeException(
                        "No Invalid Code Audit event configured for NotificationType");
        }
    }
}
