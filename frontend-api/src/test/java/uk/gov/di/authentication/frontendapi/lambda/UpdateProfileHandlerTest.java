package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.di.authentication.shared.entity.ClientConsent;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ClientSession;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.entity.ValidScopes;
import uk.gov.di.authentication.shared.entity.VectorOfTrust;
import uk.gov.di.authentication.shared.helpers.ClientSubjectHelper;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.helpers.SaltHelper;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.sharedtest.logging.CaptureLoggingExtension;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_AUTH_APP;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_CONSENT_UPDATED;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_PHONE_NUMBER;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_REQUEST_ERROR;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_REQUEST_RECEIVED;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.UPDATE_PROFILE_TERMS_CONDS_ACCEPTANCE;
import static uk.gov.di.authentication.frontendapi.entity.UpdateProfileType.ADD_PHONE_NUMBER;
import static uk.gov.di.authentication.frontendapi.entity.UpdateProfileType.CAPTURE_CONSENT;
import static uk.gov.di.authentication.frontendapi.entity.UpdateProfileType.REGISTER_AUTH_APP;
import static uk.gov.di.authentication.frontendapi.entity.UpdateProfileType.UPDATE_TERMS_CONDS;
import static uk.gov.di.authentication.frontendapi.lambda.StartHandlerTest.CLIENT_SESSION_ID_HEADER;
import static uk.gov.di.authentication.shared.entity.MFAMethodType.AUTH_APP;
import static uk.gov.di.authentication.shared.helpers.CookieHelper.buildCookieString;
import static uk.gov.di.authentication.sharedtest.logging.LogEventMatcher.withMessageContaining;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasJsonBody;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

class UpdateProfileHandlerTest {

    private static final String TEST_EMAIL_ADDRESS = "joe.bloggs@digital.cabinet-office.gov.uk";
    private static final String PHONE_NUMBER = "07755551084";
    private static final boolean UPDATED_TERMS_AND_CONDITIONS_VALUE = true;
    private static final boolean CONSENT_VALUE = true;
    private static final String SESSION_ID = "a-session-id";
    private static final String CLIENT_SESSION_ID = "client-session-id";
    private static final String PERSISTENT_SESSION_ID = "psid";
    private static final ClientID CLIENT_ID = new ClientID("client-one");
    private static final String CLIENT_NAME = "client-name";
    private static final String INTERNAL_SUBJECT = new Subject().getValue();
    private static final Scope SCOPES =
            new Scope(OIDCScopeValue.OPENID, OIDCScopeValue.EMAIL, OIDCScopeValue.OFFLINE_ACCESS);
    private static final String COOKIE = "Cookie";
    private static final URI REDIRECT_URI = URI.create("http://localhost/redirect");
    private final String expectedCommonSubject =
            ClientSubjectHelper.calculatePairwiseIdentifier(
                    INTERNAL_SUBJECT, "test.account.gov.uk", SaltHelper.generateNewSalt());
    private final Context context = mock(Context.class);
    private UpdateProfileHandler handler;
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final ClientSessionService clientSessionService = mock(ClientSessionService.class);
    private final ConfigurationService configurationService = mock(ConfigurationService.class);
    private final ClientRegistry clientRegistry = mock(ClientRegistry.class);
    private final ClientService clientService = mock(ClientService.class);
    private final AuditService auditService = mock(AuditService.class);

    private final String TERMS_AND_CONDITIONS_VERSION =
            configurationService.getTermsAndConditionsVersion();
    private final Session session =
            new Session(SESSION_ID)
                    .setEmailAddress(TEST_EMAIL_ADDRESS)
                    .setInternalCommonSubjectIdentifier(expectedCommonSubject);

    @RegisterExtension
    private final CaptureLoggingExtension logging =
            new CaptureLoggingExtension(UpdateProfileHandler.class);

    @AfterEach
    void tearDown() {
        assertThat(
                logging.events(),
                not(
                        hasItem(
                                withMessageContaining(
                                        SESSION_ID,
                                        CLIENT_SESSION_ID,
                                        CLIENT_ID.toString(),
                                        TEST_EMAIL_ADDRESS))));
        verifyNoMoreInteractions(auditService);
    }

    @BeforeEach
    void setUp() {
        when(context.getAwsRequestId()).thenReturn("request-id");
        handler =
                new UpdateProfileHandler(
                        authenticationService,
                        sessionService,
                        clientSessionService,
                        configurationService,
                        auditService,
                        clientService);
    }

    private static Stream<Arguments> updateNumberDetailsSuccess() {
        return Stream.of(
                Arguments.of(PHONE_NUMBER, "production", false),
                Arguments.of(PHONE_NUMBER, "production", true),
                Arguments.of("07700900000", "production", true),
                Arguments.of("07700900000", "integration", true),
                Arguments.of("07700900000", "integration", false),
                Arguments.of("+447700900111", "production", true),
                Arguments.of("+447700900111", "integration", true),
                Arguments.of("+447700900111", "integration", false));
    }

    @ParameterizedTest
    @MethodSource("updateNumberDetailsSuccess")
    void shouldReturn204WhenUpdatingPhoneNumber(
            String phoneNumber, String environment, boolean isSmokeTest) {
        usingValidSession();
        usingValidClientSession();
        when(clientService.getClient(CLIENT_ID.getValue())).thenReturn(Optional.of(clientRegistry));
        when(clientRegistry.isSmokeTest()).thenReturn(isSmokeTest);
        when(configurationService.getEnvironment()).thenReturn(environment);

        String persistentId = "some-persistent-id-value";
        Map<String, String> headers = new HashMap<>();
        headers.put(PersistentIdHelper.PERSISTENT_ID_HEADER_NAME, persistentId);
        headers.put("Session-Id", session.getSessionId());
        headers.put(CLIENT_SESSION_ID_HEADER, CLIENT_SESSION_ID);
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(headers);
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"updateProfileType\": \"%s\", \"profileInformation\": \"%s\" }",
                        TEST_EMAIL_ADDRESS, ADD_PHONE_NUMBER, phoneNumber));
        APIGatewayProxyResponseEvent result = makeHandlerRequest(event);

        verify(authenticationService).updatePhoneNumber(TEST_EMAIL_ADDRESS, phoneNumber);

        assertThat(result, hasStatus(204));

        verify(auditService)
                .submitAuditEvent(
                        UPDATE_PROFILE_PHONE_NUMBER,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        "",
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "",
                        phoneNumber,
                        persistentId);
    }

    private static Stream<Arguments> updateNumberDetailsFails() {
        return Stream.of(
                Arguments.of("0123456789A", "production", false),
                Arguments.of("0123456789A", "production", true),
                Arguments.of("07700900000", "production", false),
                Arguments.of("+447700900111", "production", false));
    }

    @ParameterizedTest
    @MethodSource("updateNumberDetailsFails")
    void shouldReturn400WhenPhoneNumberFailsValidation(
            String phoneNumber, String environment, boolean isSmokeTest) {
        usingValidSession();
        usingValidClientSession();
        when(authenticationService.getUserProfileFromEmail(TEST_EMAIL_ADDRESS))
                .thenReturn(Optional.of(generateUserProfileWithConsent()));
        when(clientRegistry.getClientID()).thenReturn(CLIENT_ID.getValue());
        when(clientService.getClient(CLIENT_ID.getValue())).thenReturn(Optional.of(clientRegistry));
        when(clientRegistry.isSmokeTest()).thenReturn(isSmokeTest);
        when(configurationService.getEnvironment()).thenReturn(environment);

        Map<String, String> headers = new HashMap<>();
        headers.put(PersistentIdHelper.PERSISTENT_ID_HEADER_NAME, PERSISTENT_SESSION_ID);
        headers.put("Session-Id", session.getSessionId());
        headers.put(CLIENT_SESSION_ID_HEADER, CLIENT_SESSION_ID);
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(headers);
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"updateProfileType\": \"%s\", \"profileInformation\": \"%s\" }",
                        TEST_EMAIL_ADDRESS, ADD_PHONE_NUMBER, phoneNumber));
        APIGatewayProxyResponseEvent result = makeHandlerRequest(event);

        verify(authenticationService, never()).updatePhoneNumber(TEST_EMAIL_ADDRESS, phoneNumber);

        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1012));

        verify(auditService)
                .submitAuditEvent(
                        UPDATE_PROFILE_REQUEST_ERROR,
                        CLIENT_SESSION_ID,
                        SESSION_ID,
                        CLIENT_ID.getValue(),
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "",
                        "",
                        PERSISTENT_SESSION_ID);
    }

    @Test
    void shouldReturn204WhenUpdatingTermsAndConditions() {
        usingValidSession();
        usingValidClientSession();
        when(authenticationService.getUserProfileFromEmail(TEST_EMAIL_ADDRESS))
                .thenReturn(Optional.of(generateUserProfileWithConsent()));
        when(clientService.getClient(CLIENT_ID.getValue())).thenReturn(Optional.of(clientRegistry));
        when(clientRegistry.getClientID()).thenReturn(CLIENT_ID.getValue());

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(
                Map.of(
                        "Session-Id",
                        session.getSessionId(),
                        CLIENT_SESSION_ID_HEADER,
                        CLIENT_SESSION_ID));
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"updateProfileType\": \"%s\", \"profileInformation\": \"%s\" }",
                        TEST_EMAIL_ADDRESS,
                        UPDATE_TERMS_CONDS,
                        UPDATED_TERMS_AND_CONDITIONS_VALUE));
        APIGatewayProxyResponseEvent result = makeHandlerRequest(event);

        verify(authenticationService)
                .updateTermsAndConditions(eq(TEST_EMAIL_ADDRESS), eq(TERMS_AND_CONDITIONS_VERSION));
        assertThat(result, hasStatus(204));
        verify(auditService)
                .submitAuditEvent(
                        UPDATE_PROFILE_TERMS_CONDS_ACCEPTANCE,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID.getValue(),
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "",
                        PHONE_NUMBER,
                        PersistentIdHelper.PERSISTENT_ID_UNKNOWN_VALUE);
    }

    @Test
    void shouldReturn204WhenUpdatingProfileWithConsent() {
        usingValidSession();
        usingValidClientSession();
        when(authenticationService.getUserProfileFromEmail(TEST_EMAIL_ADDRESS))
                .thenReturn(Optional.of(generateUserProfileWithoutConsent()));

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(
                Map.of(
                        COOKIE,
                        buildCookieString(
                                "gs",
                                SESSION_ID + "." + CLIENT_SESSION_ID,
                                3600,
                                "Secure; HttpOnly;",
                                "domain"),
                        CLIENT_SESSION_ID_HEADER,
                        CLIENT_SESSION_ID));
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"updateProfileType\": \"%s\", \"profileInformation\": \"%s\" }",
                        TEST_EMAIL_ADDRESS, CAPTURE_CONSENT, CONSENT_VALUE));
        APIGatewayProxyResponseEvent result = makeHandlerRequest(event);

        assertThat(result, hasStatus(204));
        verify(authenticationService)
                .updateConsent(eq(TEST_EMAIL_ADDRESS), any(ClientConsent.class));
        verify(auditService)
                .submitAuditEvent(
                        UPDATE_PROFILE_CONSENT_UPDATED,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID.getValue(),
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "",
                        PHONE_NUMBER,
                        PersistentIdHelper.PERSISTENT_ID_UNKNOWN_VALUE);
    }

    @Test
    void shouldReturn400WhenRequestIsMissingParameters() {
        usingValidSession();

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(
                Map.of(
                        "Session-Id",
                        session.getSessionId(),
                        CLIENT_SESSION_ID_HEADER,
                        CLIENT_SESSION_ID));
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"updateProfileType\": \"%s\"}",
                        TEST_EMAIL_ADDRESS, ADD_PHONE_NUMBER));
        APIGatewayProxyResponseEvent result = makeHandlerRequest(event);

        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1001));
        verify(authenticationService, never())
                .updatePhoneNumber(eq(TEST_EMAIL_ADDRESS), eq(PHONE_NUMBER));
        verify(auditService)
                .submitAuditEvent(
                        UPDATE_PROFILE_REQUEST_ERROR,
                        CLIENT_SESSION_ID,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "");
    }

    @Test
    void shouldReturn204WhenUpdatingAuthAppSecret() {
        usingValidSession();
        usingValidClientSession();
        when(authenticationService.getUserProfileFromEmail(TEST_EMAIL_ADDRESS))
                .thenReturn(Optional.of(generateUserProfileWithConsent()));
        when(clientRegistry.getClientID()).thenReturn(CLIENT_ID.getValue());
        when(clientService.getClient(CLIENT_ID.getValue())).thenReturn(Optional.of(clientRegistry));

        var authAppSecret = "JZ5PYIOWNZDAOBA65S5T77FEEKYCCIT2VE4RQDAJD7SO73T3LODA";
        var event = new APIGatewayProxyRequestEvent();
        event.setHeaders(
                Map.of(
                        "Session-Id",
                        session.getSessionId(),
                        CLIENT_SESSION_ID_HEADER,
                        CLIENT_SESSION_ID));
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"updateProfileType\": \"%s\", \"profileInformation\": \"%s\" }",
                        TEST_EMAIL_ADDRESS, REGISTER_AUTH_APP, authAppSecret));
        var result = makeHandlerRequest(event);

        assertThat(result, hasStatus(204));
        verify(authenticationService)
                .updateMFAMethod(TEST_EMAIL_ADDRESS, AUTH_APP, false, true, authAppSecret);
        verify(auditService)
                .submitAuditEvent(
                        UPDATE_PROFILE_AUTH_APP,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID.getValue(),
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "",
                        PHONE_NUMBER,
                        PersistentIdHelper.PERSISTENT_ID_UNKNOWN_VALUE);
    }

    @Test
    void shouldReturn400WhenAuthAppSecretIsNotBase32EncodedString() {
        usingValidSession();
        usingValidClientSession();
        when(authenticationService.getUserProfileFromEmail(TEST_EMAIL_ADDRESS))
                .thenReturn(Optional.of(generateUserProfileWithConsent()));
        when(clientRegistry.getClientID()).thenReturn(CLIENT_ID.getValue());
        when(clientService.getClient(CLIENT_ID.getValue())).thenReturn(Optional.of(clientRegistry));

        var authAppSecret = "not-base-32-encoded-secret";
        var event = new APIGatewayProxyRequestEvent();
        event.setHeaders(
                Map.of(
                        "Session-Id",
                        session.getSessionId(),
                        PersistentIdHelper.PERSISTENT_ID_HEADER_NAME,
                        PERSISTENT_SESSION_ID,
                        CLIENT_SESSION_ID_HEADER,
                        CLIENT_SESSION_ID));
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"updateProfileType\": \"%s\", \"profileInformation\": \"%s\" }",
                        TEST_EMAIL_ADDRESS, REGISTER_AUTH_APP, authAppSecret));

        var result = makeHandlerRequest(event);
        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1041));
        verify(authenticationService, never())
                .updateMFAMethod(TEST_EMAIL_ADDRESS, AUTH_APP, false, true, authAppSecret);
        verify(auditService)
                .submitAuditEvent(
                        UPDATE_PROFILE_REQUEST_ERROR,
                        CLIENT_SESSION_ID,
                        SESSION_ID,
                        CLIENT_ID.getValue(),
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "",
                        "",
                        PERSISTENT_SESSION_ID);
    }

    private void usingValidSession() {
        when(sessionService.getSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(session));
    }

    private void usingValidClientSession() {
        var authRequest =
                new AuthenticationRequest.Builder(
                                new ResponseType(ResponseType.Value.CODE),
                                SCOPES,
                                CLIENT_ID,
                                REDIRECT_URI)
                        .state(new State())
                        .nonce(new Nonce())
                        .build();
        var clientSession =
                new ClientSession(
                        authRequest.toParameters(),
                        LocalDateTime.now(),
                        mock(VectorOfTrust.class),
                        CLIENT_NAME);
        when(clientSessionService.getClientSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(clientSession));
    }

    private APIGatewayProxyResponseEvent makeHandlerRequest(APIGatewayProxyRequestEvent event) {
        var response = handler.handleRequest(event, context);

        verify(auditService)
                .submitAuditEvent(
                        UPDATE_PROFILE_REQUEST_RECEIVED,
                        CLIENT_SESSION_ID,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "");

        return response;
    }

    private UserProfile generateUserProfileWithoutConsent() {
        return new UserProfile()
                .withEmail(TEST_EMAIL_ADDRESS)
                .withEmailVerified(true)
                .withPhoneNumber(PHONE_NUMBER)
                .withEmailVerified(true)
                .withPublicSubjectID(new Subject().getValue())
                .withSubjectID(INTERNAL_SUBJECT);
    }

    private UserProfile generateUserProfileWithConsent() {
        Set<String> claims = ValidScopes.getClaimsForListOfScopes(SCOPES.toStringList());
        return new UserProfile()
                .withEmail(TEST_EMAIL_ADDRESS)
                .withEmailVerified(true)
                .withPhoneNumber(PHONE_NUMBER)
                .withEmailVerified(true)
                .withPublicSubjectID(new Subject().getValue())
                .withSubjectID(INTERNAL_SUBJECT)
                .withClientConsent(
                        new ClientConsent(
                                CLIENT_ID.getValue(),
                                claims,
                                LocalDateTime.now(ZoneId.of("UTC")).toString()));
    }
}
