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
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent;
import uk.gov.di.authentication.frontendapi.entity.SignUpResponse;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ClientSession;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.entity.TermsAndConditions;
import uk.gov.di.authentication.shared.entity.User;
import uk.gov.di.authentication.shared.entity.UserProfile;
import uk.gov.di.authentication.shared.helpers.ClientSubjectHelper;
import uk.gov.di.authentication.shared.helpers.IdGenerator;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.helpers.SaltHelper;
import uk.gov.di.authentication.shared.serialization.Json;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.CommonPasswordsService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SerializationService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.shared.validation.PasswordValidator;
import uk.gov.di.authentication.sharedtest.logging.CaptureLoggingExtension;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.di.authentication.frontendapi.lambda.StartHandlerTest.CLIENT_SESSION_ID_HEADER;
import static uk.gov.di.authentication.shared.services.AuditService.MetadataPair.pair;
import static uk.gov.di.authentication.sharedtest.helper.RequestEventHelper.contextWithSourceIp;
import static uk.gov.di.authentication.sharedtest.logging.LogEventMatcher.withMessageContaining;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasJsonBody;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

class SignUpHandlerTest {

    private final Context context = mock(Context.class);
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final ConfigurationService configurationService = mock(ConfigurationService.class);
    private final ClientSessionService clientSessionService = mock(ClientSessionService.class);
    private final ClientService clientService = mock(ClientService.class);
    private final User user = mock(User.class);
    private final UserProfile userProfile = mock(UserProfile.class);
    private final AuditService auditService = mock(AuditService.class);
    private final CommonPasswordsService commonPasswordsService =
            mock(CommonPasswordsService.class);
    private final PasswordValidator passwordValidator = mock(PasswordValidator.class);
    private static final String CLIENT_SESSION_ID = "a-client-session-id";
    private static final ClientID CLIENT_ID = new ClientID();
    private static final String CLIENT_NAME = "client-name";
    private static final String EMAIL = "joe.bloggs@test.com";
    private static final String PASSWORD = "computer-1";
    private static final String INTERNAL_SECTOR_URI = "https://test.account.gov.uk";
    private static final byte[] SALT = SaltHelper.generateNewSalt();
    private static final URI REDIRECT_URI = URI.create("test-uri");
    private static final Subject INTERNAL_SUBJECT_ID = new Subject();
    private final String expectedCommonSubject =
            ClientSubjectHelper.calculatePairwiseIdentifier(
                    INTERNAL_SUBJECT_ID.getValue(), "test.account.gov.uk", SALT);
    private static final Json objectMapper = SerializationService.getInstance();

    private SignUpHandler handler;

    private final Session session = new Session(IdGenerator.generate());
    private final ClientSession clientSession =
            new ClientSession(generateAuthRequest().toParameters(), null, null, CLIENT_NAME);

    @RegisterExtension
    private final CaptureLoggingExtension logging =
            new CaptureLoggingExtension(SignUpHandler.class);

    @AfterEach
    void tearDown() {
        assertThat(logging.events(), not(hasItem(withMessageContaining(session.getSessionId()))));
    }

    @BeforeEach
    void setUp() {
        when(configurationService.getTermsAndConditionsVersion()).thenReturn("1.0");
        when(configurationService.getInternalSectorUri()).thenReturn(INTERNAL_SECTOR_URI);
        when(user.getUserProfile()).thenReturn(userProfile);
        when(authenticationService.getOrGenerateSalt(any(UserProfile.class))).thenReturn(SALT);
        doReturn(Optional.of(ErrorResponse.ERROR_1006)).when(passwordValidator).validate("pwd");
        handler =
                new SignUpHandler(
                        configurationService,
                        sessionService,
                        clientSessionService,
                        clientService,
                        authenticationService,
                        auditService,
                        commonPasswordsService,
                        passwordValidator);
    }

    private static Stream<Boolean> consentValues() {
        return Stream.of(true, false);
    }

    @ParameterizedTest
    @MethodSource("consentValues")
    void shouldReturn200IfSignUpIsSuccessful(boolean consentRequired) throws Json.JsonException {
        String persistentId = "some-persistent-id-value";
        Map<String, String> headers = new HashMap<>();
        headers.put(PersistentIdHelper.PERSISTENT_ID_HEADER_NAME, persistentId);
        headers.put("Session-Id", session.getSessionId());
        headers.put(CLIENT_SESSION_ID_HEADER, CLIENT_SESSION_ID);
        when(authenticationService.userExists(EMAIL)).thenReturn(false);
        when(clientService.getClient(CLIENT_ID.getValue()))
                .thenReturn(Optional.of(generateClientRegistry(consentRequired)));
        when(clientSessionService.getClientSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(clientSession));
        when(authenticationService.signUp(
                        eq(EMAIL), eq(PASSWORD), any(Subject.class), any(TermsAndConditions.class)))
                .thenReturn(user);
        when(userProfile.getSubjectID()).thenReturn(INTERNAL_SUBJECT_ID.getValue());
        usingValidSession();
        usingValidClientSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setRequestContext(contextWithSourceIp("123.123.123.123"));
        event.setHeaders(headers);
        event.setBody(format("{ \"password\": \"%s\", \"email\": \"%s\" }", PASSWORD, EMAIL));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        verify(authenticationService)
                .signUp(eq(EMAIL), eq(PASSWORD), any(Subject.class), any(TermsAndConditions.class));
        verify(sessionService).save(argThat((session) -> session.getEmailAddress().equals(EMAIL)));

        assertThat(result, hasStatus(200));

        SignUpResponse signUpResponse =
                objectMapper.readValue(result.getBody(), SignUpResponse.class);

        assertThat(signUpResponse.isConsentRequired(), equalTo(consentRequired));
        verify(authenticationService)
                .signUp(
                        eq(EMAIL),
                        eq("computer-1"),
                        any(Subject.class),
                        any(TermsAndConditions.class));

        verify(auditService)
                .submitAuditEvent(
                        FrontendAuditableEvent.CREATE_ACCOUNT,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID.getValue(),
                        expectedCommonSubject,
                        EMAIL,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        persistentId,
                        pair("internalSubjectId", INTERNAL_SUBJECT_ID.getValue()));

        verify(sessionService)
                .save(argThat(session -> session.isNewAccount() == Session.AccountState.NEW));
        verify(sessionService, atLeastOnce())
                .save(
                        argThat(
                                t ->
                                        t.getInternalCommonSubjectIdentifier()
                                                .equals(expectedCommonSubject)));
    }

    @Test
    void shouldReturn400IfSessionIdMissing() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setBody(
                format(
                        "{ \"password\": \"%s\", \"email\": \"%s\" }",
                        PASSWORD, EMAIL.toUpperCase()));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1000));

        verifyNoInteractions(auditService);
    }

    @Test
    void shouldReturn400IfAnyRequestParametersAreMissing() {
        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(format("{ \"email\": \"%s\" }", EMAIL.toUpperCase()));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1001));

        verifyNoInteractions(auditService);
    }

    @Test
    void shouldReturn400IfPasswordInvalid() {
        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(
                format("{ \"password\": \"%s\", \"email\": \"%s\" }", "pwd", EMAIL.toUpperCase()));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1006));

        verifyNoInteractions(auditService);
    }

    @Test
    void shouldReturn400IfUserAlreadyExists() {
        when(authenticationService.userExists(eq("joe.bloggs@test.com"))).thenReturn(true);

        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setRequestContext(contextWithSourceIp("123.123.123.123"));
        event.setHeaders(
                Map.of(
                        "Session-Id",
                        session.getSessionId(),
                        CLIENT_SESSION_ID_HEADER,
                        CLIENT_SESSION_ID));
        event.setBody(
                format(
                        "{ \"password\": \"%s\", \"email\": \"%s\" }",
                        PASSWORD, EMAIL.toUpperCase()));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1009));

        verify(auditService)
                .submitAuditEvent(
                        FrontendAuditableEvent.CREATE_ACCOUNT_EMAIL_ALREADY_EXISTS,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        AuditService.UNKNOWN,
                        AuditService.UNKNOWN,
                        "joe.bloggs@test.com",
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        PersistentIdHelper.PERSISTENT_ID_UNKNOWN_VALUE);
    }

    private void usingValidSession() {
        when(sessionService.getSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(session));
    }

    public static AuthenticationRequest generateAuthRequest() {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        State state = new State();
        Scope scope = new Scope();
        Nonce nonce = new Nonce();
        scope.add(OIDCScopeValue.OPENID);
        scope.add("phone");
        scope.add("email");
        return new AuthenticationRequest.Builder(responseType, scope, CLIENT_ID, REDIRECT_URI)
                .state(state)
                .nonce(nonce)
                .build();
    }

    private void usingValidClientSession() {
        when(clientSessionService.getClientSession(CLIENT_SESSION_ID))
                .thenReturn(Optional.of(clientSession));
    }

    private ClientRegistry generateClientRegistry(boolean consentRequired) {
        return new ClientRegistry()
                .withClientID(CLIENT_ID.getValue())
                .withConsentRequired(consentRequired)
                .withClientName("test-client")
                .withSectorIdentifierUri("https://test.com")
                .withSubjectType("public");
    }
}
