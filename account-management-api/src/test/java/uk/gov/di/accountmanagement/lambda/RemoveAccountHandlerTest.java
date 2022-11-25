package uk.gov.di.accountmanagement.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.nimbusds.oauth2.sdk.id.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.accountmanagement.domain.AccountManagementAuditableEvent;
import uk.gov.di.accountmanagement.entity.NotifyRequest;
import uk.gov.di.accountmanagement.exceptions.InvalidPrincipalException;
import uk.gov.di.accountmanagement.services.AwsSqsClient;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.helpers.ClientSubjectHelper;
import uk.gov.di.authentication.shared.helpers.LocaleHelper.SupportedLanguage;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.helpers.SaltHelper;
import uk.gov.di.authentication.shared.serialization.Json;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SerializationService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.di.accountmanagement.entity.NotificationType.DELETE_ACCOUNT;
import static uk.gov.di.authentication.sharedtest.helper.RequestEventHelper.identityWithSourceIp;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasJsonBody;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

class RemoveAccountHandlerTest {

    private static final String EMAIL = "joe.bloggs@digital.cabinet-office.gov.uk";
    private static final Subject PUBLIC_SUBJECT = new Subject();
    private static final String PERSISTENT_ID = "some-persistent-session-id";
    private final Json objectMapper = SerializationService.getInstance();

    private RemoveAccountHandler handler;
    private final Context context = mock(Context.class);
    private final AwsSqsClient sqsClient = mock(AwsSqsClient.class);
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ConfigurationService configurationService = mock(ConfigurationService.class);

    @BeforeEach
    public void setUp() {
        handler =
                new RemoveAccountHandler(
                        authenticationService, sqsClient, auditService, configurationService);
        when(configurationService.getInternalSectorUri()).thenReturn("https://test.account.gov.uk");
    }

    @Test
    void shouldReturn204IfAccountRemovalIsSuccessfulAndPrincipalContainsPublicSubjectId()
            throws Json.JsonException {
        var userProfile = new UserProfile().withPublicSubjectID(PUBLIC_SUBJECT.getValue());
        when(authenticationService.getUserProfileByEmailMaybe(EMAIL))
                .thenReturn(Optional.of(userProfile));

        var event = generateApiGatewayEvent(PUBLIC_SUBJECT.getValue());
        var result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(204));
        verify(authenticationService).removeAccount(eq(EMAIL));
        verify(sqsClient)
                .send(
                        objectMapper.writeValueAsString(
                                new NotifyRequest(EMAIL, DELETE_ACCOUNT, SupportedLanguage.EN)));
        verify(auditService)
                .submitAuditEvent(
                        AccountManagementAuditableEvent.DELETE_ACCOUNT,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        userProfile.getSubjectID(),
                        userProfile.getEmail(),
                        "123.123.123.123",
                        userProfile.getPhoneNumber(),
                        PERSISTENT_ID);
    }

    @Test
    void shouldReturn204IfAccountRemovalIsSuccessfulAndPrincipalContainsInternalPairwiseSubjectId()
            throws Json.JsonException {
        var internalSubject = new Subject();
        var salt = SaltHelper.generateNewSalt();
        var userProfile =
                new UserProfile()
                        .withPublicSubjectID(PUBLIC_SUBJECT.getValue())
                        .withSubjectID(internalSubject.getValue());
        var internalPairwiseIdentifier =
                ClientSubjectHelper.calculatePairwiseIdentifier(
                        internalSubject.getValue(), "test.account.gov.uk", salt);
        when(authenticationService.getUserProfileByEmailMaybe(EMAIL))
                .thenReturn(Optional.of(userProfile));
        when(authenticationService.getOrGenerateSalt(userProfile)).thenReturn(salt);

        var event = generateApiGatewayEvent(internalPairwiseIdentifier);
        var result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(204));
        verify(authenticationService).removeAccount(EMAIL);
        verify(sqsClient)
                .send(
                        objectMapper.writeValueAsString(
                                new NotifyRequest(EMAIL, DELETE_ACCOUNT, SupportedLanguage.EN)));
        verify(auditService)
                .submitAuditEvent(
                        AccountManagementAuditableEvent.DELETE_ACCOUNT,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        userProfile.getSubjectID(),
                        userProfile.getEmail(),
                        "123.123.123.123",
                        userProfile.getPhoneNumber(),
                        PERSISTENT_ID);
    }

    @Test
    void shouldThrowIfPrincipalIdIsInvalid() {
        var userProfile =
                new UserProfile()
                        .withPublicSubjectID(new Subject().getValue())
                        .withSubjectID(new Subject().getValue());
        when(authenticationService.getUserProfileByEmailMaybe(EMAIL))
                .thenReturn(Optional.of(userProfile));
        when(authenticationService.getOrGenerateSalt(userProfile))
                .thenReturn(SaltHelper.generateNewSalt());

        var event = generateApiGatewayEvent(PUBLIC_SUBJECT.getValue());

        var expectedException =
                assertThrows(
                        InvalidPrincipalException.class,
                        () -> handler.handleRequest(event, context),
                        "Expected to throw exception");

        assertThat(expectedException.getMessage(), equalTo("Invalid Principal in request"));
        verifyNoInteractions(sqsClient);
        verifyNoInteractions(auditService);
    }

    @Test
    void shouldReturn400IfUserAccountDoesNotExist() {
        when(authenticationService.getUserProfileByEmailMaybe(EMAIL)).thenReturn(Optional.empty());

        var event = generateApiGatewayEvent(PUBLIC_SUBJECT.getValue());
        var result = handler.handleRequest(event, context);

        verify(authenticationService, never()).removeAccount(EMAIL);
        verifyNoInteractions(auditService);
        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1010));
    }

    private APIGatewayProxyRequestEvent generateApiGatewayEvent(String principalId) {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(format("{\"email\": \"%s\" }", EMAIL));
        APIGatewayProxyRequestEvent.ProxyRequestContext proxyRequestContext =
                new APIGatewayProxyRequestEvent.ProxyRequestContext();
        Map<String, Object> authorizerParams = new HashMap<>();
        authorizerParams.put("principalId", principalId);
        proxyRequestContext.setAuthorizer(authorizerParams);
        proxyRequestContext.setIdentity(identityWithSourceIp("123.123.123.123"));
        event.setRequestContext(proxyRequestContext);
        event.setHeaders(Map.of(PersistentIdHelper.PERSISTENT_ID_HEADER_NAME, PERSISTENT_ID));

        return event;
    }
}
