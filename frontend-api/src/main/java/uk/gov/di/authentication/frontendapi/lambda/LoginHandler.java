package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent;
import uk.gov.di.authentication.frontendapi.entity.LoginRequest;
import uk.gov.di.authentication.frontendapi.entity.LoginResponse;
import uk.gov.di.authentication.frontendapi.helpers.RedactPhoneNumberHelper;
import uk.gov.di.authentication.frontendapi.services.UserMigrationService;
import uk.gov.di.authentication.shared.conditions.ConsentHelper;
import uk.gov.di.authentication.shared.conditions.MfaHelper;
import uk.gov.di.authentication.shared.conditions.TermsAndConditionsHelper;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.MFAMethod;
import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.entity.UserCredentials;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.helpers.ClientSubjectHelper;
import uk.gov.di.authentication.shared.helpers.IpAddressHelper;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.lambda.BaseFrontendHandler;
import uk.gov.di.authentication.shared.serialization.Json.JsonException;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.CloudwatchMetricsService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.CommonPasswordsService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.state.UserContext;

import java.util.Objects;
import java.util.Optional;

import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.LOG_IN_SUCCESS;
import static uk.gov.di.authentication.frontendapi.services.UserMigrationService.userHasBeenPartlyMigrated;
import static uk.gov.di.authentication.shared.conditions.MfaHelper.getPrimaryMFAMethod;
import static uk.gov.di.authentication.shared.entity.Session.AccountState.EXISTING;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;
import static uk.gov.di.authentication.shared.helpers.LogLineHelper.attachSessionIdToLogs;
import static uk.gov.di.authentication.shared.services.AuditService.MetadataPair.pair;

public class LoginHandler extends BaseFrontendHandler<LoginRequest>
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOG = LogManager.getLogger(LoginHandler.class);
    private final CodeStorageService codeStorageService;
    private final UserMigrationService userMigrationService;
    private final AuditService auditService;
    private final CloudwatchMetricsService cloudwatchMetricsService;
    private final CommonPasswordsService commonPasswordsService;

    public LoginHandler(
            ConfigurationService configurationService,
            SessionService sessionService,
            AuthenticationService authenticationService,
            ClientSessionService clientSessionService,
            ClientService clientService,
            CodeStorageService codeStorageService,
            UserMigrationService userMigrationService,
            AuditService auditService,
            CloudwatchMetricsService cloudwatchMetricsService,
            CommonPasswordsService commonPasswordsService) {
        super(
                LoginRequest.class,
                configurationService,
                sessionService,
                clientSessionService,
                clientService,
                authenticationService,
                true);
        this.codeStorageService = codeStorageService;
        this.userMigrationService = userMigrationService;
        this.auditService = auditService;
        this.cloudwatchMetricsService = cloudwatchMetricsService;
        this.commonPasswordsService = commonPasswordsService;
    }

    public LoginHandler(ConfigurationService configurationService) {
        super(LoginRequest.class, configurationService, true);
        this.codeStorageService = new CodeStorageService(configurationService);
        this.userMigrationService =
                new UserMigrationService(
                        new DynamoService(configurationService), configurationService);
        this.auditService = new AuditService(configurationService);
        this.cloudwatchMetricsService = new CloudwatchMetricsService();
        this.commonPasswordsService = new CommonPasswordsService(configurationService);
    }

    public LoginHandler() {
        this(ConfigurationService.getInstance());
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequestWithUserContext(
            APIGatewayProxyRequestEvent input,
            Context context,
            LoginRequest request,
            UserContext userContext) {

        attachSessionIdToLogs(userContext.getSession().getSessionId());

        LOG.info("Request received");
        try {
            var persistentSessionId =
                    PersistentIdHelper.extractPersistentIdFromHeaders(input.getHeaders());
            var clientId = userContext.getClientId();
            Optional<UserProfile> userProfileMaybe =
                    authenticationService.getUserProfileByEmailMaybe(request.getEmail());
            if (userProfileMaybe.isEmpty() || userContext.getUserCredentials().isEmpty()) {

                auditService.submitAuditEvent(
                        FrontendAuditableEvent.NO_ACCOUNT_WITH_EMAIL,
                        userContext.getClientSessionId(),
                        userContext.getSession().getSessionId(),
                        clientId,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        IpAddressHelper.extractIpAddress(input),
                        AuditService.UNKNOWN,
                        persistentSessionId);

                return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1010);
            }

            UserProfile userProfile = userProfileMaybe.get();
            UserCredentials userCredentials = userContext.getUserCredentials().get();

            int incorrectPasswordCount =
                    codeStorageService.getIncorrectPasswordCount(request.getEmail());

            LOG.info("Calculating internal common subject identifier");
            var internalCommonSubjectIdentifier =
                    ClientSubjectHelper.getSubjectWithSectorIdentifier(
                            userProfile,
                            configurationService.getInternalSectorUri(),
                            authenticationService);

            if (incorrectPasswordCount >= configurationService.getMaxPasswordRetries()) {
                LOG.info("User has exceeded max password retries");

                auditService.submitAuditEvent(
                        FrontendAuditableEvent.ACCOUNT_TEMPORARILY_LOCKED,
                        userContext.getClientSessionId(),
                        userContext.getSession().getSessionId(),
                        clientId,
                        internalCommonSubjectIdentifier.getValue(),
                        userProfile.getEmail(),
                        IpAddressHelper.extractIpAddress(input),
                        userProfile.getPhoneNumber(),
                        persistentSessionId,
                        pair("internalSubjectId", userProfile.getSubjectID()));

                return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1028);
            }

            if (!credentialsAreValid(request, userProfile)) {
                codeStorageService.increaseIncorrectPasswordCount(request.getEmail());

                auditService.submitAuditEvent(
                        FrontendAuditableEvent.INVALID_CREDENTIALS,
                        userContext.getClientSessionId(),
                        userContext.getSession().getSessionId(),
                        clientId,
                        internalCommonSubjectIdentifier.getValue(),
                        request.getEmail(),
                        IpAddressHelper.extractIpAddress(input),
                        AuditService.UNKNOWN,
                        persistentSessionId,
                        pair("internalSubjectId", userProfile.getSubjectID()));

                return generateApiGatewayProxyErrorResponse(401, ErrorResponse.ERROR_1008);
            }

            if (incorrectPasswordCount != 0) {
                codeStorageService.deleteIncorrectPasswordCount(request.getEmail());
            }

            LOG.info("Setting internal common subject identifier in user session");
            sessionService.save(
                    userContext
                            .getSession()
                            .setInternalCommonSubjectIdentifier(
                                    internalCommonSubjectIdentifier.getValue()));

            var isPhoneNumberVerified = userProfile.isPhoneNumberVerified();
            String redactedPhoneNumber = null;
            if (isPhoneNumberVerified) {
                redactedPhoneNumber =
                        RedactPhoneNumberHelper.redactPhoneNumber(userProfile.getPhoneNumber());
            }
            boolean termsAndConditionsAccepted = false;
            if (Objects.nonNull(userProfile.getTermsAndConditions())) {
                termsAndConditionsAccepted =
                        TermsAndConditionsHelper.hasTermsAndConditionsBeenAccepted(
                                userProfile.getTermsAndConditions(),
                                configurationService.getTermsAndConditionsVersion());
            }
            sessionService.save(userContext.getSession().setNewAccount(EXISTING));
            var isMfaRequired =
                    MfaHelper.mfaRequired(userContext.getClientSession().getAuthRequestParams());
            var consentRequired = ConsentHelper.userHasNotGivenConsent(userContext);

            var mfaMethod = getPrimaryMFAMethod(userCredentials);
            var mfaMethodType =
                    mfaMethod
                            .map(m -> MFAMethodType.valueOf(m.getMfaMethodType()))
                            .orElse(MFAMethodType.SMS);
            boolean mfaMethodVerified =
                    mfaMethod.map(MFAMethod::isMethodVerified).orElse(isPhoneNumberVerified);

            boolean isPasswordChangeRequired = isPasswordResetRequired(request.getPassword());

            LOG.info("User has successfully logged in with MFAType: {}", mfaMethodType);

            auditService.submitAuditEvent(
                    LOG_IN_SUCCESS,
                    userContext.getClientSessionId(),
                    userContext.getSession().getSessionId(),
                    clientId,
                    internalCommonSubjectIdentifier.getValue(),
                    userProfile.getEmail(),
                    IpAddressHelper.extractIpAddress(input),
                    userProfile.getPhoneNumber(),
                    persistentSessionId,
                    pair("internalSubjectId", userProfile.getSubjectID()));

            if (!isMfaRequired) {
                cloudwatchMetricsService.incrementAuthenticationSuccess(
                        EXISTING,
                        clientId,
                        userContext.getClientName(),
                        "P0",
                        clientService.isTestJourney(clientId, userProfile.getEmail()),
                        false);
            }
            return generateApiGatewayProxyResponse(
                    200,
                    new LoginResponse(
                            redactedPhoneNumber,
                            isMfaRequired,
                            termsAndConditionsAccepted,
                            consentRequired,
                            mfaMethodType,
                            mfaMethodVerified,
                            isPasswordChangeRequired));
        } catch (JsonException e) {
            return generateApiGatewayProxyErrorResponse(400, ErrorResponse.ERROR_1001);
        }
    }

    private boolean credentialsAreValid(LoginRequest request, UserProfile userProfile) {
        var userCredentials = authenticationService.getUserCredentialsFromEmail(request.getEmail());

        var userIsAMigratedUser =
                userHasBeenPartlyMigrated(userProfile.getLegacySubjectID(), userCredentials);

        if (userIsAMigratedUser) {
            LOG.info("Processing migrated user");
            return userMigrationService.processMigratedUser(userCredentials, request.getPassword());
        } else {
            return authenticationService.login(userCredentials, request.getPassword());
        }
    }

    private boolean isPasswordResetRequired(String password) {
        boolean isPasswordChangeRequired = false;
        try {
            isPasswordChangeRequired = commonPasswordsService.isCommonPassword(password);
        } catch (Exception e) {
            LOG.error("Unable to check if password was a common password");
        }
        LOG.info("Password reset required: {}", isPasswordChangeRequired);

        return isPasswordChangeRequired;
    }
}
