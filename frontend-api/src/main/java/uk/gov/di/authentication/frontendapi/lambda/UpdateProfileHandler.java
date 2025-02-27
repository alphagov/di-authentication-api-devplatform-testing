package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import org.apache.commons.codec.CodecPolicy;
import org.apache.commons.codec.binary.Base32;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.frontendapi.entity.UpdateProfileRequest;
import uk.gov.di.authentication.shared.domain.AuditableEvent;
import uk.gov.di.authentication.shared.entity.ClientConsent;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ClientSession;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.entity.ValidScopes;
import uk.gov.di.authentication.shared.helpers.IpAddressHelper;
import uk.gov.di.authentication.shared.helpers.LogLineHelper;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.helpers.PhoneNumberHelper;
import uk.gov.di.authentication.shared.helpers.ValidationHelper;
import uk.gov.di.authentication.shared.lambda.BaseFrontendHandler;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.state.UserContext;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_AUTH_APP;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_CONSENT_UPDATED;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_PHONE_NUMBER;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_REQUEST_ERROR;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_REQUEST_RECEIVED;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_TERMS_CONDS_ACCEPTANCE;
import static uk.gov.di.authentication.shared.entity.MFAMethodType.AUTH_APP;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateEmptySuccessApiGatewayResponse;

public class UpdateProfileHandler extends BaseFrontendHandler<UpdateProfileRequest>
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOG = LogManager.getLogger(UpdateProfileHandler.class);
    private static final byte PAD_DEFAULT = '=';

    private final AuditService auditService;

    protected UpdateProfileHandler(
            AuthenticationService authenticationService,
            SessionService sessionService,
            ClientSessionService clientSessionService,
            ConfigurationService configurationService,
            AuditService auditService,
            ClientService clientService) {
        super(
                UpdateProfileRequest.class,
                configurationService,
                sessionService,
                clientSessionService,
                clientService,
                authenticationService);
        this.auditService = auditService;
    }

    public UpdateProfileHandler() {
        this(ConfigurationService.getInstance());
    }

    public UpdateProfileHandler(ConfigurationService configurationService) {
        super(UpdateProfileRequest.class, configurationService);
        auditService = new AuditService(configurationService);
    }

    @Override
    public void onRequestReceived(String clientSessionId) {
        auditService.submitAuditEvent(
                UPDATE_PROFILE_REQUEST_RECEIVED,
                clientSessionId,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN);
    }

    @Override
    public void onRequestValidationError(String clientSessionId) {
        auditService.submitAuditEvent(
                UPDATE_PROFILE_REQUEST_ERROR,
                clientSessionId,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequestWithUserContext(
            APIGatewayProxyRequestEvent input,
            Context context,
            UpdateProfileRequest request,
            UserContext userContext) {

        Session session = userContext.getSession();

        String persistentSessionId =
                PersistentIdHelper.extractPersistentIdFromHeaders(input.getHeaders());

        LogLineHelper.attachSessionIdToLogs(session);

        LOG.info("Processing request");

        String ipAddress = IpAddressHelper.extractIpAddress(input);

        if (!session.validateSession(request.getEmail())) {
            LOG.info("Invalid session");
            return generateErrorResponse(
                    ErrorResponse.ERROR_1000,
                    userContext.getClientSessionId(),
                    AuditService.UNKNOWN,
                    AuditService.UNKNOWN,
                    AuditService.UNKNOWN,
                    persistentSessionId,
                    AuditService.UNKNOWN);
        }

        AuditableEvent auditableEvent;
        String auditablePhoneNumber =
                userContext
                        .getUserProfile()
                        .map(UserProfile::getPhoneNumber)
                        .orElse(AuditService.UNKNOWN);
        String auditableClientId =
                userContext
                        .getClient()
                        .map(ClientRegistry::getClientID)
                        .orElse(AuditService.UNKNOWN);
        boolean isSmokeTest =
                userContext.getClient().map(ClientRegistry::isSmokeTest).orElse(false);
        switch (request.getUpdateProfileType()) {
            case ADD_PHONE_NUMBER:
                {
                    String phoneNumber =
                            PhoneNumberHelper.removeWhitespaceFromPhoneNumber(
                                    request.getProfileInformation());
                    Optional<ErrorResponse> errorResponse =
                            ValidationHelper.validatePhoneNumber(
                                    phoneNumber,
                                    configurationService.getEnvironment(),
                                    isSmokeTest);
                    if (errorResponse.isPresent()) {
                        return generateErrorResponse(
                                errorResponse.get(),
                                userContext.getClientSessionId(),
                                session.getSessionId(),
                                auditableClientId,
                                request.getEmail(),
                                persistentSessionId,
                                session.getInternalCommonSubjectIdentifier());
                    }
                    if (isSmokeTest) LOG.info("Accepting test number as valid for smoke test");
                    authenticationService.updatePhoneNumber(
                            request.getEmail(), request.getProfileInformation());
                    auditableEvent = UPDATE_PROFILE_PHONE_NUMBER;
                    auditablePhoneNumber = request.getProfileInformation();

                    LOG.info("Phone number updated");
                    break;
                }
            case CAPTURE_CONSENT:
                {
                    ClientSession clientSession = userContext.getClientSession();

                    if (clientSession == null) {
                        return generateErrorResponse(
                                ErrorResponse.ERROR_1018,
                                userContext.getClientSessionId(),
                                session.getSessionId(),
                                auditableClientId,
                                request.getEmail(),
                                persistentSessionId,
                                session.getInternalCommonSubjectIdentifier());
                    }
                    AuthenticationRequest authorizationRequest;
                    try {
                        authorizationRequest =
                                AuthenticationRequest.parse(clientSession.getAuthRequestParams());
                    } catch (ParseException e) {
                        return generateErrorResponse(
                                ErrorResponse.ERROR_1038,
                                userContext.getClientSessionId(),
                                session.getSessionId(),
                                auditableClientId,
                                request.getEmail(),
                                persistentSessionId,
                                session.getInternalCommonSubjectIdentifier());
                    }
                    String clientId = authorizationRequest.getClientID().getValue();

                    Set<String> claimsConsented;

                    if (!Boolean.parseBoolean(request.getProfileInformation())) {
                        claimsConsented = OIDCScopeValue.OPENID.getClaimNames();
                    } else {
                        claimsConsented =
                                ValidScopes.getClaimsForListOfScopes(
                                        authorizationRequest.getScope().toStringList());
                    }

                    processAndUpdateClientConsent(
                            request.getEmail(), userContext, clientId, claimsConsented);
                    auditableEvent = UPDATE_PROFILE_CONSENT_UPDATED;
                    auditableClientId = clientId;
                    LOG.info("Consent updated");
                    break;
                }
            case UPDATE_TERMS_CONDS:
                {
                    authenticationService.updateTermsAndConditions(
                            request.getEmail(),
                            configurationService.getTermsAndConditionsVersion());
                    auditableEvent = UPDATE_PROFILE_TERMS_CONDS_ACCEPTANCE;
                    LOG.info(
                            "Updated terms and conditions for Version: {}",
                            configurationService.getTermsAndConditionsVersion());
                    break;
                }
            case REGISTER_AUTH_APP:
                {
                    var base32 = new Base32(0, null, false, PAD_DEFAULT, CodecPolicy.STRICT);
                    if (!base32.isInAlphabet(request.getProfileInformation())) {
                        return generateErrorResponse(
                                ErrorResponse.ERROR_1041,
                                userContext.getClientSessionId(),
                                session.getSessionId(),
                                auditableClientId,
                                request.getEmail(),
                                persistentSessionId,
                                session.getInternalCommonSubjectIdentifier());
                    }
                    authenticationService.updateMFAMethod(
                            userContext.getSession().getEmailAddress(),
                            AUTH_APP,
                            false,
                            true,
                            request.getProfileInformation());
                    auditableEvent = UPDATE_PROFILE_AUTH_APP;
                    LOG.info("MFA Method has been updated for type: {}", AUTH_APP);
                    break;
                }
            default:
                LOG.error(
                        "Encountered unexpected error while processing session: {}",
                        session.getSessionId());
                return generateErrorResponse(
                        ErrorResponse.ERROR_1013,
                        userContext.getClientSessionId(),
                        session.getSessionId(),
                        auditableClientId,
                        request.getEmail(),
                        persistentSessionId,
                        AuditService.UNKNOWN);
        }
        auditService.submitAuditEvent(
                auditableEvent,
                userContext.getClientSessionId(),
                session.getSessionId(),
                auditableClientId,
                session.getInternalCommonSubjectIdentifier(),
                session.getEmailAddress(),
                ipAddress,
                auditablePhoneNumber,
                persistentSessionId);
        return generateEmptySuccessApiGatewayResponse();
    }

    private void processAndUpdateClientConsent(
            String email, UserContext userContext, String clientId, Set<String> claimsConsented) {
        Optional<ClientConsent> clientConsentForClientId =
                userContext
                        .getUserProfile()
                        .map(UserProfile::getClientConsent)
                        .flatMap(
                                t ->
                                        t.stream()
                                                .filter(
                                                        consent ->
                                                                consent.getClientId()
                                                                        .equals(clientId))
                                                .findFirst());

        ClientConsent clientConsentToUpdate =
                clientConsentForClientId
                        .map(
                                t ->
                                        t.withClaims(claimsConsented)
                                                .withUpdatedTimestamp(
                                                        LocalDateTime.now(ZoneId.of("UTC"))
                                                                .toString()))
                        .orElse(
                                new ClientConsent(
                                        clientId,
                                        claimsConsented,
                                        LocalDateTime.now(ZoneId.of("UTC")).toString()));

        LOG.info(
                "Consent value successfully added to ClientConsentObject. Attempting to update UserProfile with claims: {}",
                clientConsentToUpdate.getClaims());

        authenticationService.updateConsent(email, clientConsentToUpdate);
    }

    private APIGatewayProxyResponseEvent generateErrorResponse(
            ErrorResponse errorResponse,
            String clientSessionId,
            String sessionId,
            String clientId,
            String email,
            String persistentSessionId,
            String subjectId) {
        auditService.submitAuditEvent(
                UPDATE_PROFILE_REQUEST_ERROR,
                clientSessionId,
                sessionId,
                clientId,
                subjectId,
                email,
                AuditService.UNKNOWN,
                AuditService.UNKNOWN,
                persistentSessionId);
        return generateApiGatewayProxyErrorResponse(400, errorResponse);
    }
}
